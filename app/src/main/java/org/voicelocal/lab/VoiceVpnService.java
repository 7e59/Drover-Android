package org.voicelocal.lab;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import hev.htproxy.TProxyService;

public final class VoiceVpnService extends VpnService {
    private static final ExecutorService engineControl=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean stopping,restarting;
    private volatile LocalSocksProxy proxy;
    private ParcelFileDescriptor tun;
    private volatile TProxyService nativeEngine;
    private File config;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean prelude;
    private volatile long generation;
    private volatile Network previousNetwork;
    private List<InetAddress> previousDns=Collections.emptyList();
    private final Set<Network> physicalNetworks=new HashSet<>();
    static volatile VoiceVpnService active;
    private final Runnable monitor=new Runnable(){public void run(){
        if(stopping||active!=VoiceVpnService.this)return;
        TProxyService engine=nativeEngine;
        LocalSocksProxy relay=proxy;
        if(!restarting&&relay!=null&&relay.tcpProtectFailures.get()>0){
            Journal.add("فشلت حماية مقبس TCP؛ أوقفت VPN لاستعادة اتصال Discord. السبب TCP_PROTECT_FAILED");beginStop();return;
        }
        if(!restarting&&engine!=null&&!engine.TProxyIsRunning()){
            Journal.add("توقف محرك التمرير؛ أزيل VPN لحماية الاتصال");beginStop();return;
        }
        main.postDelayed(this,1000);
    }};
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){beginStop();return START_NOT_STICKY;}
        if(active==this)return START_NOT_STICKY;
        active=this;stopping=false;Journal.running=true;Journal.state="جارٍ التشغيل…";
        prelude=intent.getBooleanExtra("prelude",true);
        try{
            if(prepare(this)!=null)throw new IllegalStateException("لم يُمنح إذن VPN");
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("vpn","Discord المحلي",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getBroadcast(this,0,new Intent(this,StopReceiver.class),PendingIntent.FLAG_IMMUTABLE);
            startForeground(1,new Notification.Builder(this,"vpn").setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Drover يعمل محلياً").setContentText("Discord فقط • افتح المكالمة لاختبار الصوت")
                .setContentIntent(open).addAction(new Notification.Action.Builder(null,"إيقاف",stop).build()).setOngoing(true).build());
            connectivity=getSystemService(ConnectivityManager.class);previousNetwork=connectivity.getActiveNetwork();
            LinkProperties initialProperties=previousNetwork==null?null:connectivity.getLinkProperties(previousNetwork);
            List<InetAddress> initialDns=initialProperties==null?Collections.emptyList():new ArrayList<>(initialProperties.getDnsServers());
            previousDns=new ArrayList<>(initialDns);
            engineControl.execute(()->startEngine(initialDns));
            networkCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){main.post(()->{
                    if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))physicalNetworks.remove(network);else physicalNetworks.add(network);
                });}
                @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){
                    List<InetAddress> dns=new ArrayList<>(properties.getDnsServers());
                    main.post(()->{
                    if(stopping||active!=VoiceVpnService.this)return;
                    if(!physicalNetworks.contains(network))return;
                    if(!network.equals(previousNetwork)||!dns.equals(previousDns)){
                        restarting=true;Journal.add("تغيّرت الشبكة؛ أُعيد إعداد التمرير تلقائياً. قد تحتاج إعادة دخول المكالمة.");
                        Journal.state="إعادة الاتصال بالشبكة…";
                        engineControl.execute(()->{stopEngine();if(!stopping)startEngine(dns);});
                    }
                    previousNetwork=network;previousDns=dns;
                });}
                @Override public void onLost(Network network){main.post(()->{physicalNetworks.remove(network);if(network.equals(previousNetwork))Journal.add("انقطعت الشبكة الحالية؛ بانتظار اتصال الشبكة");});}
            };
            connectivity.registerDefaultNetworkCallback(networkCallback);
        }catch(Exception e){Journal.add("تعذر التشغيل: "+message(e));beginStop();}
        return START_NOT_STICKY;
    }
    private void startEngine(List<InetAddress> dnsServers){
        if(stopping)return;
        final long session=++generation;
        try{
            String user=random(),password=random();
            proxy=new LocalSocksProxy(user,password,prelude,new LocalSocksProxy.Protector(){
                public boolean tcp(Socket s){return protect(s);}public boolean udp(DatagramSocket s){return protect(s);}
            },new LocalSocksProxy.Events(){
                public void log(String s){if(!stopping&&session==generation)Journal.add(s);}
                public void discovery(boolean reply,String endpoint){
                    if(stopping||session!=generation)return;
                    if(reply){Journal.add("رد اكتشاف صوت مطابق من "+endpoint+"؛ اختبر الصوت بالاتجاهين");Journal.state="وصل رد اكتشاف الصوت • جرّب المكالمة";}
                    else{Journal.add("اكتُشف خادم الصوت تلقائياً: "+endpoint);Journal.state="اكتُشف خادم الصوت • بانتظار الرد";}
                }
            });
            Builder b=new Builder().setSession("Drover Discord").setMtu(1500).setBlocking(false)
                .addAddress("198.18.0.1",32).addAddress("fd00:77::1",128)
                .addRoute("0.0.0.0",0).addRoute("::",0).addAllowedApplication("com.discord");
            for(InetAddress dns:dnsServers)b.addDnsServer(dns);
            if(stopping)return;
            tun=b.establish();if(tun==null)throw new IOException("TUN unavailable");
            config=new File(getFilesDir(),"relay.yml");
            String yaml="tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n  ipv6: 'fd00:77::1'\n  icmp: 'off'\nsocks5:\n  address: 127.0.0.1\n  port: "+proxy.port()+
                "\n  udp: 'udp'\n  pipeline: false\n  username: '"+user+"'\n  password: '"+password+"'\nmisc:\n  max-session-count: 48\n  connect-timeout: 8000\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 120000\n  log-level: error\n";
            try(FileOutputStream f=openFileOutput("relay.yml",MODE_PRIVATE)){f.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            if(stopping)return;
            nativeEngine=new TProxyService();
            if(!nativeEngine.TProxyStartService(config.getAbsolutePath(),tun.getFd()))throw new IOException("Native startup failed");
            Journal.add("بدأ التوجيه التلقائي لـDiscord فقط • TCP/UDP وIPv4/IPv6 • "+(prelude?"معالجة الصوت مفعلة":"تمرير عادي للمقارنة"));
            if(!stopping)Journal.state="يعمل محلياً • افتح فويس Discord";
            restarting=false;
            main.post(()->{if(!stopping&&active==this){main.removeCallbacks(monitor);main.postDelayed(monitor,1000);}});
        }catch(Exception|LinkageError e){Journal.add("تعذر بدء المحرك: "+e.getClass().getSimpleName());main.post(()->{if(active==this&&!stopping)beginStop();});}
    }
    private static String random(){byte[] bytes=new byte[18];new SecureRandom().nextBytes(bytes);StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    private static String message(Exception e){return e instanceof IllegalStateException?e.getMessage():e.getClass().getSimpleName();}
    private void stopEngine(){
        generation++;
        if(nativeEngine!=null){nativeEngine.TProxyStopService();nativeEngine=null;}
        if(proxy!=null){proxy.close();proxy=null;}
        try{if(tun!=null)tun.close();}catch(IOException ignored){}tun=null;
        if(config!=null)config.delete();config=null;
    }
    static String snapshot(){
        VoiceVpnService s=active;LocalSocksProxy p=s==null?null:s.proxy;
        if(p==null)return "التوجيه تلقائي؛ لا تحتاج IP أو منفذاً أو حساباً.";
        return "اتصالات TCP: "+p.tcpConnections.get()+" من "+p.tcpAttempts.get()+
            "\nفشل حماية TCP: "+p.tcpProtectFailures.get()+" • فشل الاتصال: "+p.tcpConnectFailures.get()+
            "\nUDP صادر: "+p.udpOut.get()+" • وارد: "+p.udpIn.get()+
            "\nاكتشاف الصوت: "+p.requests.get()+" • ردود مطابقة: "+p.replies.get()+
            "\nآخر خطأ: "+p.lastError+" • إجمالي الأخطاء: "+p.relayFailures.get();
    }
    @Override public void onRevoke(){main.post(()->{if(active==this&&!stopping){Journal.add("سُحب إذن VPN أو بدأ VPN آخر");beginStop();}});}
    static boolean isStopping(){VoiceVpnService service=active;return service!=null&&service.stopping;}
    static void requestStop(Context context){
        VoiceVpnService service=active;
        if(service!=null)service.main.post(service::beginStop);
        else context.stopService(new Intent(context,VoiceVpnService.class));
    }
    private void beginStop(){
        if(stopping)return;
        stopping=true;main.removeCallbacks(monitor);
        Journal.state="جارٍ إيقاف الاتصال…";
        if(connectivity!=null&&networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        networkCallback=null;
        engineControl.execute(()->{
            stopEngine();
            main.post(()->{
                if(active==this){active=null;Journal.running=false;Journal.state="متوقف";}
                Journal.add("أُغلقت المقابس ومحرك الاتصال وواجهة VPN");
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            });
        });
    }
    @Override public void onDestroy(){beginStop();super.onDestroy();}
}
