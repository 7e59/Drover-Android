package org.voicelocal.lab;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.*;
import android.view.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private TextView status,counters,logs;
    private Boolean lastRunning;
    private Button toggle;
    private Switch processing;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean selectedProcessing=true;
    private final Runnable refresh=new Runnable(){public void run(){
        status.setText(Journal.state);counters.setText(VoiceVpnService.snapshot());logs.setText(Journal.text());
        toggle.setEnabled(!VoiceVpnService.isStopping());
        toggle.setText(VoiceVpnService.isStopping()?"جارٍ الإيقاف…":Journal.running?"إيقاف الاتصال":"تشغيل Drover");
        if(lastRunning==null||lastRunning!=Journal.running){lastRunning=Journal.running;toggle.setBackground(shape(Journal.running?0xff26384a:0xff9ce8cf,28));toggle.setTextColor(Journal.running?0xffe6eef5:0xff10231f);}
        processing.setEnabled(!Journal.running);handler.postDelayed(this,1000);
    }};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        var prefs=getPreferences(MODE_PRIVATE);selectedProcessing=prefs.getBoolean("process",true);
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        int dp=(int)(24*getResources().getDisplayMetrics().density);root.setPadding(dp,dp,dp,dp);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.setBackgroundColor(0xff0c1420);scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,i)->{if(Build.VERSION.SDK_INT>=30){var bars=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        TextView brand=label(root,"D R O V E R",16);brand.setTextColor(0xff9ce8cf);
        brand.setGravity(Gravity.CENTER);brand.setPadding(0,dp(20),0,dp(30));
        ImageView mark=new ImageView(this);mark.setImageResource(org.voicelocal.lab.R.drawable.app_icon);
        LinearLayout.LayoutParams emblem=new LinearLayout.LayoutParams(dp(88),dp(88));emblem.gravity=Gravity.CENTER;root.addView(mark,emblem);
        TextView title=label(root,"قريب من صوتك.",32);title.setGravity(Gravity.CENTER);title.setTypeface(null,android.graphics.Typeface.BOLD);
        TextView intro=label(root,"مساحة أبسط لمكالمات Discord",16);intro.setGravity(Gravity.CENTER);
        intro.setTextColor(0xff99aabe);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(22),dp(16),dp(22),dp(22));card.setBackground(shape(0xff162333,28));
        LinearLayout.LayoutParams cardSize=new LinearLayout.LayoutParams(-1,-2);cardSize.topMargin=dp(28);cardSize.bottomMargin=dp(20);root.addView(card,cardSize);
        TextView caption=label(card,"حالة الخدمة",13);caption.setTextColor(0xff99aabe);
        status=label(card,"جاهز للبدء",18);status.setTextColor(0xff9ce8cf);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        toggle=new Button(this);toggle.setText("تشغيل Drover");toggle.setTextSize(20);toggle.setAllCaps(false);toggle.setTypeface(null,android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(-1,dp(68));action.topMargin=dp(18);card.addView(toggle,action);
        TextView hint=label(root,"اضغط تشغيل، ثم افتح مكالمتك في Discord.",15);hint.setGravity(Gravity.CENTER);
        TextView privacy=label(root,"محلي على جهازك  ·  دون تسجيل دخول",13);privacy.setTextColor(0xff99aabe);privacy.setGravity(Gravity.CENTER);
        Button advanced=new Button(this);advanced.setText("الإعدادات والتشخيص  +");root.addView(advanced);styleButton(advanced);
        LinearLayout details=new LinearLayout(this);details.setOrientation(LinearLayout.VERTICAL);root.addView(details);details.setVisibility(View.GONE);
        advanced.setOnClickListener(v->{boolean show=details.getVisibility()!=View.VISIBLE;details.setVisibility(show?View.VISIBLE:View.GONE);advanced.setText(show?"إخفاء التفاصيل  −":"الإعدادات والتشخيص  +");});
        details.setPadding(dp(18),dp(12),dp(18),dp(18));details.setBackground(shape(0xff162333,22));
        label(details,"حالة الخدمة لا تؤكد اكتمال المكالمة. تحقق من الصوت بالاتجاهين.",14);
        processing=new Switch(this);processing.setText("معالجة الصوت التجريبية");processing.setChecked(selectedProcessing);details.addView(processing);
        processing.setOnCheckedChangeListener((button,value)->{selectedProcessing=value;prefs.edit().putBoolean("process",value).apply();});
        label(details,"مفعلة افتراضياً. عطّلها فقط لمقارنة التمرير العادي بمحاولة مكالمة جديدة. لا فك تشفير أو بيانات حساب. السجل مؤقت وقد يتضمن عنوان خادم الصوت.",14);
        counters=label(details,"",15);
        Button copy=new Button(this);copy.setText("نسخ سجل التشخيص");details.addView(copy);styleButton(copy);
        copy.setOnClickListener(v->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Drover",VoiceVpnService.snapshot()+"\n"+Journal.text()));Toast.makeText(this,"نُسخ السجل",Toast.LENGTH_SHORT).show();});
        Button clear=new Button(this);clear.setText("مسح السجل");details.addView(clear);styleButton(clear);clear.setOnClickListener(v->Journal.clear());
        Button licenses=new Button(this);licenses.setText("التراخيص");details.addView(licenses);styleButton(licenses);licenses.setOnClickListener(v->{try(var in=getAssets().open("NOTICES.txt")){byte[] b=new byte[in.available()];int n=in.read(b);new AlertDialog.Builder(this).setMessage(new String(b,0,n,java.nio.charset.StandardCharsets.UTF_8)).setPositiveButton("إغلاق",null).show();}catch(Exception e){error("التراخيص مرفقة مع الكود");}});
        logs=label(details,"",13);logs.setTextIsSelectable(true);logs.setTextDirection(View.TEXT_DIRECTION_LTR);
        toggle.setOnClickListener(v->{
            if(Journal.running){VoiceVpnService.requestStop(this);toggle.setEnabled(false);toggle.setText("جارٍ الإيقاف…");return;}
            try{getPackageManager().getPackageInfo("com.discord",0);}catch(PackageManager.NameNotFoundException e){error("ثبّت Discord الرسمي أولاً");return;}
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},9);
            else consent();
        });
    }
    private TextView label(LinearLayout l,String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(0xffe6eef5);t.setLineSpacing(dp(3),1);t.setPadding(0,dp(8),0,dp(8));l.addView(t);return t;}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private android.graphics.drawable.GradientDrawable shape(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void styleButton(Button b){b.setAllCaps(false);b.setTextColor(0xffbfcedd);b.setTextSize(15);b.setMinHeight(dp(52));b.setBackground(shape(0xff1e3043,16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.topMargin=dp(12);b.setLayoutParams(p);}
    private void error(String text){new AlertDialog.Builder(this).setMessage(text).setPositiveButton("حسناً",null).show();}
    private void consent(){Intent i=VpnService.prepare(this);if(i!=null)startActivityForResult(i,7);else start();}
    private void start(){try{startForegroundService(new Intent(this,VoiceVpnService.class).putExtra("prelude",selectedProcessing));}catch(Exception e){error("تعذر التشغيل: "+e.getClass().getSimpleName());}}
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] grants){super.onRequestPermissionsResult(request,p,grants);if(request==9)consent();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==7){if(result==RESULT_OK)start();else Journal.add("لم يُمنح إذن VPN");}}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
