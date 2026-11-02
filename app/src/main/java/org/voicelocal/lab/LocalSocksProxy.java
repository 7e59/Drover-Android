package org.voicelocal.lab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Authenticated loopback-only SOCKS5 relay. Outgoing sockets connect directly, never to a VPN server. */
final class LocalSocksProxy implements Closeable {
    interface Protector { boolean tcp(Socket s); boolean udp(DatagramSocket s); }
    interface Events { void log(String s); void discovery(boolean reply,String endpoint); }
    final AtomicLong tcpConnections=new AtomicLong(),udpOut=new AtomicLong(),udpIn=new AtomicLong(),requests=new AtomicLong(),replies=new AtomicLong();
    final AtomicLong tcpAttempts=new AtomicLong(),tcpProtectFailures=new AtomicLong(),tcpConnectFailures=new AtomicLong(),relayFailures=new AtomicLong();
    volatile String lastError="—";
    private final AtomicLong lastFailureLog=new AtomicLong();
    private static final class RelayFailure extends IOException {RelayFailure(String code){super(code);}}
    private final Protector protector;
    private final Events events;
    private final byte[] username,password;
    private final boolean prelude;
    private final ServerSocket listener;
    private final Set<Closeable> resources=ConcurrentHashMap.newKeySet();
    private final Semaphore slots=new Semaphore(48);
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,160,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"local-relay");t.setDaemon(true);return t;});
    private volatile boolean running=true;

    LocalSocksProxy(String user,String pass,boolean prelude,Protector protector,Events events) throws IOException {
        username=user.getBytes(StandardCharsets.UTF_8); password=pass.getBytes(StandardCharsets.UTF_8);
        if(username.length==0||username.length>255||password.length==0||password.length>255) throw new IllegalArgumentException("Invalid local credentials");
        this.prelude=prelude;this.protector=protector;this.events=events;
        listener=new ServerSocket(); listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),48);
        Thread accept=new Thread(this::acceptLoop,"socks-listener"); accept.setDaemon(true); accept.start();
    }
    int port(){return listener.getLocalPort();}
    int trackedResources(){return resources.size();}
    private <T extends Closeable>T track(T resource)throws IOException {
        resources.add(resource);
        if(!running){resources.remove(resource);resource.close();throw new IOException("stopped");}
        return resource;
    }
    private void release(Closeable c){resources.remove(c);try{c.close();}catch(IOException ignored){}}
    private void acceptLoop(){
        while(running) try {
            Socket client=listener.accept();
            if(!client.getInetAddress().isLoopbackAddress()||!slots.tryAcquire()){client.close();continue;}
            try {track(client);workers.execute(()->{try{handle(client);}finally{release(client);slots.release();}});}
            catch(IOException|RejectedExecutionException e){release(client);slots.release();}
        }catch(IOException e){if(running)events.log("تعذر استقبال اتصال محلي");}
    }
    private void handle(Socket client){
        try {
            client.setSoTimeout(5000); client.setTcpNoDelay(true);
            DataInputStream in=new DataInputStream(client.getInputStream()); OutputStream out=client.getOutputStream();
            if(in.readUnsignedByte()!=5)return;
            int count=in.readUnsignedByte();boolean auth=false;
            for(int i=0;i<count;i++)if(in.readUnsignedByte()==2)auth=true;
            out.write(new byte[]{5,(byte)(auth?2:255)});out.flush();if(!auth)return;
            if(in.readUnsignedByte()!=1)return;
            byte[] u=new byte[in.readUnsignedByte()];in.readFully(u);
            byte[] p=new byte[in.readUnsignedByte()];in.readFully(p);
            boolean ok=MessageDigest.isEqual(u,username)&MessageDigest.isEqual(p,password);
            Arrays.fill(p,(byte)0);out.write(new byte[]{1,(byte)(ok?0:1)});out.flush();if(!ok)return;
            if(in.readUnsignedByte()!=5)return;
            int command=in.readUnsignedByte();if(in.readUnsignedByte()!=0)return;
            InetSocketAddress destination=readAddress(in);
            if(command==1)connect(client,in,out,destination);
            else if(command==3)associate(client,in,out);
            else reply(out,7,new InetSocketAddress("127.0.0.1",0));
        }catch(IOException|RuntimeException e){if(running && !(e instanceof EOFException))failure(e instanceof RelayFailure?e.getMessage():e.getClass().getSimpleName());}
    }
    private void failure(String reason){
        lastError=reason;relayFailures.incrementAndGet();long now=System.nanoTime(),previous=lastFailureLog.get();
        if((previous==0||now-previous>TimeUnit.SECONDS.toNanos(2))&&lastFailureLog.compareAndSet(previous,now))events.log("خطأ التمرير: "+reason+" • إجمالي الأخطاء: "+relayFailures.get());
    }
    private void connect(Socket client,InputStream in,OutputStream out,InetSocketAddress destination)throws IOException {
        if(destination.getPort()==0){reply(out,8,new InetSocketAddress("127.0.0.1",0));return;}
        Socket remote=track(new Socket());
        try {
            tcpAttempts.incrementAndGet();
            // Android creates the OS descriptor lazily. bind creates it without contacting a peer;
            // VpnService.protect(Socket) needs this real descriptor before connect sends packets.
            try{remote.bind(new InetSocketAddress(0));}catch(IOException e){throw new RelayFailure("TCP_BIND_FAILED");}
            boolean protectedSocket;
            try{protectedSocket=protector.tcp(remote);}catch(RuntimeException e){protectedSocket=false;}
            if(!protectedSocket){tcpProtectFailures.incrementAndGet();reply(out,1,new InetSocketAddress("127.0.0.1",0));throw new RelayFailure("TCP_PROTECT_FAILED");}
            try{remote.connect(destination,8000);}catch(IOException e){tcpConnectFailures.incrementAndGet();reply(out,5,new InetSocketAddress("127.0.0.1",0));throw new RelayFailure(e instanceof SocketTimeoutException?"TCP_CONNECT_TIMEOUT":"TCP_CONNECT_FAILED");}
            remote.setTcpNoDelay(true);remote.setSoTimeout(300000);client.setSoTimeout(300000);
            reply(out,0,(InetSocketAddress)remote.getLocalSocketAddress());tcpConnections.incrementAndGet();
            Future<?> reverse=workers.submit(()->{try{copy(remote.getInputStream(),out);client.shutdownOutput();}catch(IOException ignored){try{client.close();}catch(IOException ignored2){}}});
            try {copy(in,remote.getOutputStream());remote.shutdownOutput();reverse.get(60,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(ExecutionException|TimeoutException e){reverse.cancel(true);}
        }finally{release(remote);}
    }
    private static void copy(InputStream in,OutputStream out)throws IOException {byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)if(n>0)out.write(b,0,n);}
    private void associate(Socket client,DataInputStream in,OutputStream out)throws IOException {
        DatagramSocket local=track(new DatagramSocket(new InetSocketAddress("127.0.0.1",0)));
        DatagramSocket direct=null;
        try {
            direct=track(new DatagramSocket(null));
            if(!protector.udp(direct))throw new IOException("protect failed");
            direct.bind(new InetSocketAddress(0));direct.setSoTimeout(1000);local.setSoTimeout(1000);
            Association a=new Association(local,direct);
            reply(out,0,(InetSocketAddress)local.getLocalSocketAddress());client.setSoTimeout(1000);
            Future<?> outgoing=workers.submit(a::outgoing),incoming=workers.submit(a::incoming);
            try {
                while(running&&!local.isClosed()) {
                    try{if(in.read()==-1)break;}catch(SocketTimeoutException timeout){if(System.nanoTime()-a.last>TimeUnit.SECONDS.toNanos(120))break;}
                }
            }finally{release(local);release(direct);outgoing.cancel(true);incoming.cancel(true);}
        }finally{release(local);if(direct!=null)release(direct);}
    }
    private final class Peer {
        volatile byte[] ssrc;
        volatile long awaited;
        volatile long last=System.nanoTime();
        boolean preludeApplied;
    }
    private final class Association {
        final DatagramSocket local,direct;
        final ConcurrentHashMap<InetSocketAddress,Peer> peers=new ConcurrentHashMap<>();
        volatile InetSocketAddress source;
        volatile long last=System.nanoTime();
        Association(DatagramSocket local,DatagramSocket direct){this.local=local;this.direct=direct;}
        void checkDeadlines(){
            long now=System.nanoTime();
            for(Peer p:peers.values())if(p.awaited!=0&&now-p.awaited>TimeUnit.SECONDS.toNanos(5)){
                p.awaited=0;p.ssrc=null;events.log("مهلة اكتشاف الصوت: لم يصل رد مطابق خلال5 ثوانٍ؛ لا تثبت سبب التعليق");
            }
        }
        void outgoing(){
            byte[] buffer=new byte[65535];
            try {
                while(running&&!local.isClosed()) {
                    checkDeadlines();
                    try {
                        DatagramPacket packet=new DatagramPacket(buffer,buffer.length);local.receive(packet);
                        InetSocketAddress from=(InetSocketAddress)packet.getSocketAddress();
                        if(!from.getAddress().isLoopbackAddress()||(source!=null&&!source.equals(from)))continue;
                        UdpMessage m;
                        try{m=decodeUdp(Arrays.copyOf(buffer,packet.getLength()));}catch(IOException malformed){continue;}
                        if(m==null||m.destination.getPort()==0)continue;
                        if(source==null)source=from;
                        long now=System.nanoTime();
                        peers.entrySet().removeIf(e->now-e.getValue().last>TimeUnit.SECONDS.toNanos(120));
                        Peer peer=peers.get(m.destination);
                        if(peer==null){if(peers.size()>=64)continue;peer=new Peer();peers.put(m.destination,peer);}
                        peer.last=now;last=now;
                        boolean discovery=Packet.discovery(m.payload,1);
                        if(discovery){peer.ssrc=Arrays.copyOfRange(m.payload,4,8);peer.awaited=now;requests.incrementAndGet();events.discovery(false,m.destination.toString());}
                        if(!peer.preludeApplied&&discovery&&prelude){
                            direct.send(new DatagramPacket(new byte[]{0},1,m.destination));direct.send(new DatagramPacket(new byte[]{1},1,m.destination));
                            Thread.sleep(50);events.log("طُبقت مقدمة UDP التجريبية تلقائياً على اكتشاف الصوت");
                            peer.preludeApplied=true;
                        }
                        direct.send(new DatagramPacket(m.payload,m.payload.length,m.destination));udpOut.incrementAndGet();
                    }catch(SocketTimeoutException timeout){checkDeadlines();}
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(IOException e){if(running&&!local.isClosed())events.log("إرسال UDP: "+e.getClass().getSimpleName());}
            finally{local.close();direct.close();}
        }
        void incoming(){
            byte[] buffer=new byte[65507];
            try {
                while(running&&!direct.isClosed()) {
                    try {
                        DatagramPacket p=new DatagramPacket(buffer,buffer.length);direct.receive(p);
                        InetSocketAddress remote=(InetSocketAddress)p.getSocketAddress();Peer peer=peers.get(remote);
                        InetSocketAddress client=source;if(peer==null||client==null)continue;
                        byte[] payload=Arrays.copyOf(buffer,p.getLength()),expected=peer.ssrc;
                        if(expected!=null&&Packet.matchesSsrc(expected,payload)){
                            peer.ssrc=null;peer.awaited=0;replies.incrementAndGet();events.discovery(true,remote.toString());
                        }
                        byte[] wrapped=encodeUdp(remote,payload);if(wrapped.length>65507)continue;
                        local.send(new DatagramPacket(wrapped,wrapped.length,client));udpIn.incrementAndGet();
                        last=System.nanoTime();peer.last=last;
                    }catch(SocketTimeoutException timeout){/* poll close */}
                }
            }catch(IOException e){if(running&&!direct.isClosed())events.log("استقبال UDP: "+e.getClass().getSimpleName());}
            finally{local.close();direct.close();}
        }
    }
    static final class UdpMessage {
        final InetSocketAddress destination;final byte[] payload;
        UdpMessage(InetSocketAddress d,byte[] p){destination=d;payload=p;}
    }
    static InetSocketAddress readAddress(DataInputStream in)throws IOException {
        int type=in.readUnsignedByte();InetAddress address;
        if(type==1||type==4){byte[] a=new byte[type==1?4:16];in.readFully(a);address=InetAddress.getByAddress(a);}
        else if(type==3){int n=in.readUnsignedByte();if(n==0)throw new IOException("Empty domain");byte[] a=new byte[n];in.readFully(a);address=InetAddress.getByName(new String(a,StandardCharsets.US_ASCII));}
        else throw new IOException("Unsupported address type");
        return new InetSocketAddress(address,in.readUnsignedShort());
    }
    private static void address(OutputStream out,InetSocketAddress endpoint)throws IOException {
        byte[] a=endpoint.getAddress().getAddress();out.write(a.length==4?1:4);out.write(a);out.write(endpoint.getPort()>>>8);out.write(endpoint.getPort()&255);
    }
    private static void reply(OutputStream out,int code,InetSocketAddress endpoint)throws IOException {out.write(new byte[]{5,(byte)code,0});address(out,endpoint);out.flush();}
    static UdpMessage decodeUdp(byte[] bytes)throws IOException {
        if(bytes.length<4||bytes[0]!=0||bytes[1]!=0||bytes[2]!=0)return null;
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes,3,bytes.length-3));
        InetSocketAddress target=readAddress(in);byte[] payload=new byte[in.available()];in.readFully(payload);return new UdpMessage(target,payload);
    }
    static byte[] encodeUdp(InetSocketAddress target,byte[] payload)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(payload.length+22);out.write(new byte[3]);address(out,target);out.write(payload);return out.toByteArray();
    }
    @Override public void close(){
        running=false;try{listener.close();}catch(IOException ignored){}
        for(Closeable c:resources)release(c);workers.shutdownNow();
        Arrays.fill(username,(byte)0);Arrays.fill(password,(byte)0);
    }
}
