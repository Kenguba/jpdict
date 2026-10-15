package com.yuen.jpdict;

import android.Manifest;
import android.app.*;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.widget.RemoteViews;

import java.nio.ByteBuffer;
import java.util.*;

public class MainActivity extends Activity {

    private boolean pendingProjectionSetup=false;
    private TextView groqStatus;
    private Button groqKeyButton;
    private final Runnable groqKeyChanged = this::refreshGroqStatus;

    static final Map<String,String[]> WORDS = new LinkedHashMap<>();
    static {
        WORDS.put("守る", new String[]{"まもる","mamoru","vt","守护；遵守；保护"});
        WORDS.put("守り", new String[]{"まもり","mamori","n","守护；保护；防守"});
        WORDS.put("漏る", new String[]{"もる","moru","vi","漏出；渗漏；从缝隙滴下"});
        WORDS.put("漏れる", new String[]{"もれる","moreru","vi","漏；泄漏；遗漏"});
        WORDS.put("曇る", new String[]{"くもる","kumoru","vi","阴天；变模糊；起雾"});
        WORDS.put("見る", new String[]{"みる","miru","vt","看；观看；观察"});
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        resetCrashDiagnosticsOnUpgrade();
        LinearLayout root=base();
        TextView title=title("日语悬浮搜索");
        root.addView(title);

        TextView buildInfo=text("当前版本：" + BuildConfig.VERSION_NAME
                + "  ·  versionCode " + BuildConfig.VERSION_CODE);
        buildInfo.setTextSize(14);
        buildInfo.setTextColor(0xff6f7780);
        root.addView(buildInfo);

        groqStatus=text("");
        groqStatus.setTextSize(14);
        root.addView(groqStatus);
        groqKeyButton=button("", v -> GroqKeyStore.showEditor(this, this::refreshGroqStatus));
        root.addView(groqKeyButton);
        refreshGroqStatus();

        String lastCrash=getSharedPreferences("crash_log",MODE_PRIVATE)
                .getString("last","");
        if(lastCrash!=null && !lastCrash.trim().isEmpty()){
            TextView crash=text("上次 Java 崩溃：\n"+lastCrash);
            crash.setTextSize(12);
            crash.setTextColor(0xffb00020);
            crash.setTextIsSelectable(true);
            root.addView(crash);
        }

        String exitInfo=systemExitSummary();
        if(exitInfo!=null && !exitInfo.isEmpty()){
            TextView exit=text("系统上次退出记录：\n"+exitInfo);
            exit.setTextSize(12);
            exit.setTextColor(0xff8a3a00);
            exit.setTextIsSelectable(true);
            root.addView(exit);
        }

        String lastAction=getSharedPreferences("crash_log",MODE_PRIVATE)
                .getString("last_action","");
        if(lastAction!=null && !lastAction.isEmpty()){
            TextView action=text("崩溃前最后动作：\n"+lastAction);
            action.setTextSize(12);
            action.setTextColor(0xff5d2e8c);
            action.setTextIsSelectable(true);
            root.addView(action);
        }

        String handled=getSharedPreferences("crash_log",MODE_PRIVATE)
                .getString("last_handled","");
        if(handled!=null && !handled.isEmpty()){
            TextView hv=text("已捕获异常：\n"+handled);
            hv.setTextSize(12);
            hv.setTextColor(0xff9c1b1b);
            hv.setTextIsSelectable(true);
            root.addView(hv);
        }
        root.addView(button("🟠 开启浮动取词（悬浮 + OCR）", v->enableFloatingBubble()));
        root.addView(button("⛔ 关闭桌面悬浮球", v->stopService(new Intent(this,FloatingService.class))));
        root.addView(button("🔍 打开悬浮搜索页", v->openFloatingSearch()));
        root.addView(button("📷 屏幕 OCR", v->startActivity(new Intent(this,OcrActivity.class))));
        TextView note=text("这是自己的浮动取词功能，不调用欧路。\n"
                +"轻点浮动按钮：打开原生悬浮搜索。\n"
                    +"拖动浮动按钮：对准文字停留 150ms 后识别，高亮后松手查词；来回划过文字可选定范围，屏幕底部指针自动移到按钮下方。原地长按 700ms 打开方向摇杆：上方句子、右侧日语、下方英语，所选语言与主页共用并保存。\n"
                +"屏幕共享只请求“共享整个屏幕”。");
        root.addView(note);
        setContentView(root);
        if(Settings.canDrawOverlays(this)) startFloatingBubble();
    }

    void enableFloatingBubble(){
        if(Settings.canDrawOverlays(this)){
            startFloatingBubble();
            Toast.makeText(this,
                    ScreenCaptureService.READY
                            ? "浮动取词已开启：拖动停留取词，长按选择模式"
                            : "浮动取词已开启：拖动可移动，停留取词时申请屏幕 OCR 授权",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pendingProjectionSetup=true;
        Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName()));
        startActivity(i);
        Toast.makeText(this,"请允许“显示在其他应用上层”；返回后即可拖动浮标取词",Toast.LENGTH_LONG).show();
    }

    void startFloatingBubble(){
        Intent i=new Intent(this,FloatingService.class);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
    }

    void openFloatingSearch(){
        if(!Settings.canDrawOverlays(this)){
            pendingProjectionSetup=true;
            Intent p=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:"+getPackageName()));
            startActivity(p);
            Toast.makeText(this,"请先允许“显示在其他应用上层”",Toast.LENGTH_LONG).show();
            return;
        }

        Intent i=new Intent(this,FloatingService.class)
                .setAction(FloatingService.ACTION_SHOW_SEARCH);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
    }

    @Override protected void onResume(){
        super.onResume();
        refreshGroqStatus();
        if(Settings.canDrawOverlays(this)){
            startFloatingBubble();
            if(pendingProjectionSetup){
                pendingProjectionSetup=false;
                Toast.makeText(this,
                        "悬浮球已开启；拖动可移动按钮；点“屏幕 OCR”开启识别",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override protected void onStart(){
        super.onStart();
        GroqKeyStore.addChangeListener(groqKeyChanged);
    }

    @Override protected void onStop(){
        GroqKeyStore.removeChangeListener(groqKeyChanged);
        super.onStop();
    }

    private void refreshGroqStatus(){
        if(groqStatus==null || groqKeyButton==null) return;
        boolean configured=GroqKeyStore.hasKey(this);
        groqStatus.setText("Groq AI：" + (configured ? "Key 已全局配置" : "未配置"));
        groqStatus.setTextColor(configured ? 0xff118844 : 0xffb00020);
        groqKeyButton.setText(configured ? "🤖 更换 Groq Key" : "🤖 配置 Groq Key");
    }

    void resetCrashDiagnosticsOnUpgrade(){
        SharedPreferences p=getSharedPreferences("crash_log",MODE_PRIVATE);
        int saved=p.getInt("diagnostic_build_code",-1);
        if(saved==BuildConfig.VERSION_CODE) return;

        p.edit()
                .remove("last")
                .remove("time")
                .remove("last_action")
                .remove("last_action_time")
                .remove("last_handled")
                .putInt("diagnostic_build_code",BuildConfig.VERSION_CODE)
                .putLong("diagnostic_build_start",System.currentTimeMillis())
                .commit();
    }

    String systemExitSummary(){
        if(Build.VERSION.SDK_INT<30) return "";

        try{
            ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ApplicationExitInfo> list=
                    am.getHistoricalProcessExitReasons(getPackageName(),0,5);

            if(list==null || list.isEmpty()) return "";

            long buildStart=getSharedPreferences("crash_log",MODE_PRIVATE)
                    .getLong("diagnostic_build_start",0L);

            android.app.ApplicationExitInfo e=null;
            for(android.app.ApplicationExitInfo candidate:list){
                if(candidate.getTimestamp()>=buildStart){
                    e=candidate;
                    break;
                }
            }

            if(e==null) return "";

            return "reason="+exitReasonName(e.getReason())
                    +" ("+e.getReason()+")"
                    +"\nstatus="+e.getStatus()
                    +"\nimportance="+e.getImportance()
                    +"\ndescription="+String.valueOf(e.getDescription())
                    +"\ntimestamp="+new java.util.Date(e.getTimestamp());
        }catch(Throwable t){
            return "读取退出原因失败："+t.getClass().getSimpleName()+": "+t.getMessage();
        }
    }

    String exitReasonName(int reason){
        if(Build.VERSION.SDK_INT<30) return String.valueOf(reason);
        switch(reason){
            case android.app.ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case android.app.ApplicationExitInfo.REASON_ANR: return "ANR";
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case android.app.ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case android.app.ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case android.app.ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "REASON_"+reason;
        }
    }

    static LinearLayout base(){
        LinearLayout l=new LinearLayout(App.get());
        l.setOrientation(LinearLayout.VERTICAL);
        int p=dp(20);
        l.setPadding(p,p,p,p);
        l.setBackgroundColor(0xfff7f7f7);
        return l;
    }
    static TextView title(String s){ TextView v=text(s); v.setTextSize(28); v.setPadding(0,0,0,dp(18)); return v; }
    static TextView text(String s){ TextView v=new TextView(App.get()); v.setText(s); v.setTextColor(0xff222222); v.setTextSize(17); v.setPadding(0,dp(8),0,dp(8)); return v; }
    static Button button(String s, View.OnClickListener l){ Button b=new Button(App.get()); b.setText(s); b.setAllCaps(false); b.setOnClickListener(l); return b; }
    static int dp(int v){ return Math.round(v*App.get().getResources().getDisplayMetrics().density); }

    public static class SearchActivity extends Activity {
        @Override public void onCreate(Bundle b){
            super.onCreate(b);
            route(getIntent());
        }

        @Override protected void onNewIntent(Intent i){
            super.onNewIntent(i);
            setIntent(i);
            route(i);
        }

        void route(Intent i){
            String q=readIncoming(i);
            String value=q==null?"":q.trim();
            if(Settings.canDrawOverlays(this)){
                // Desktop/widget search needs the same mask and Back host as
                // Intent lookup; a direct float Service request has no host.
                Intent host=new Intent(this,LookupLinkActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                                Intent.FLAG_ACTIVITY_NO_ANIMATION);
                if(value.isEmpty()){
                    host.putExtra(FloatingService.EXTRA_HOST_SEARCH,true);
                }else{
                    host.putExtra(FloatingService.EXTRA_LOOKUP_QUERY,value)
                            .putExtra(FloatingService.EXTRA_RETURN_TO_SEARCH,false);
                }
                startActivity(host);
            }else if(!value.isEmpty()){
                startActivity(new Intent(this,QuickLookupActivity.class)
                        .putExtra("query",value));
            }else{
                startActivity(new Intent(this,MainActivity.class));
            }
            finish();
            overridePendingTransition(0,0);
        }

        String readIncoming(Intent i){
            if(i==null)return null;
            if(Intent.ACTION_PROCESS_TEXT.equals(i.getAction())){
                CharSequence p=i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                return p==null?null:p.toString();
            }
            if(Intent.ACTION_SEND.equals(i.getAction())) return i.getStringExtra(Intent.EXTRA_TEXT);
            if(Intent.ACTION_SEARCH.equals(i.getAction())) return i.getStringExtra("query");
            return i.getStringExtra("query");
        }
    }

    public static class OcrActivity extends Activity {
        static final String ACTION_FINISHED="com.yuen.jpdict.OCR_PERMISSION_FINISHED";
        static final int REQ_CAPTURE=7001, REQ_NOTIFY=7002;
        private boolean granted;
        @Override public void onCreate(Bundle b){ super.onCreate(b); ask(); }
        void ask(){
            try{
                getSharedPreferences("crash_log",MODE_PRIVATE)
                        .edit().putString("last_action","OCR_PERMISSION_ASK").commit();

                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFY);
                    return;
                }

                MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
                Intent captureIntent;
                if(Build.VERSION.SDK_INT>=34){
                    MediaProjectionConfig config=MediaProjectionConfig.createConfigForDefaultDisplay();
                    captureIntent=m.createScreenCaptureIntent(config);
                }else{
                    captureIntent=m.createScreenCaptureIntent();
                }
                startActivityForResult(captureIntent,REQ_CAPTURE);
            }catch(Throwable t){
                String msg=t.getClass().getSimpleName()
                        +(t.getMessage()==null?"":" · "+t.getMessage());
                getSharedPreferences("crash_log",MODE_PRIVATE)
                        .edit().putString("last_handled","OcrActivity.ask\n"+msg).commit();
                Toast.makeText(this,"屏幕共享授权异常："+msg,Toast.LENGTH_LONG).show();
                finish();
            }
        }
        @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
            super.onRequestPermissionsResult(r,p,g);
            if(r==REQ_NOTIFY){
                if(g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) ask();
                else { Toast.makeText(this,"需要通知权限，才能在通知栏显示“识别当前屏幕”按钮",Toast.LENGTH_LONG).show(); finish(); }
            }
        }
        @Override protected void onActivityResult(int r,int c,Intent data){
            super.onActivityResult(r,c,data);
            if(r==REQ_CAPTURE && c==RESULT_OK && data!=null){
                granted=true;
                Intent s=new Intent(this,ScreenCaptureService.class).putExtra("code",c).putExtra("data",data);
                if(Build.VERSION.SDK_INT>=26) startForegroundService(s); else startService(s);
                Toast.makeText(this,"录屏 OCR 已启动；可从通知栏点“识别当前屏幕”",Toast.LENGTH_LONG).show();
            }
            finish();
        }
        @Override protected void onDestroy(){
            if(isFinishing() && !isChangingConfigurations())sendBroadcast(new Intent(ACTION_FINISHED).setPackage(getPackageName()).putExtra("granted",granted));
            super.onDestroy();
        }
    }

    public static class ScreenCaptureService extends Service {
        static final String CH="ocr_capture", ACT_OCR="ocr_now", ACT_STOP="ocr_stop";
        public static final String ACT_OCR_REGION="ocr_region";
        public static final String ACT_OCR_PREVIEW="ocr_preview";
        public static final String ACT_OCR_REFINE="ocr_refine", ACT_OCR_CANCEL="ocr_cancel";
        public static final String ACTION_PREVIEW="com.yuen.jpdict.OCR_PREVIEW";
        public static final String ACTION_PREVIEW_FRAME="com.yuen.jpdict.OCR_PREVIEW_FRAME";
        public static final String ACTION_RESULT="com.yuen.jpdict.OCR_RESULT";
        public static final String ACTION_STOPPED="com.yuen.jpdict.OCR_STOPPED";
        public static final String ACTION_READY="com.yuen.jpdict.OCR_READY";
        public static volatile boolean READY=false;
        MediaProjection projection; ScreenCaptureFrames captureFrames; VirtualDisplay vd; int w,h,dpi;
        MultilingualOcrRecognizer ocrRecognizer;
        boolean previewInFlight;
        long previewSession, previewOperation;
        Intent pendingPreview;
        OcrPreviewFrame previewFrame;
        OcrLanguageSettings.Language previewLanguage;
        OcrPickupMode previewMode=OcrPickupMode.WORD;
        final Handler captureMain=new Handler(Looper.getMainLooper());
        @Override public void onCreate(){
            super.onCreate();
            ocrRecognizer=new MultilingualOcrRecognizer();
            ocrRecognizer.prepareModels(this);
            ensureChannel();
        }
        @Override public int onStartCommand(Intent in,int flags,int id){
            if(in==null)return START_NOT_STICKY;
            String a=in.getAction();
            if(ACT_STOP.equals(a)){ stopCapture(); stopSelf(); return START_NOT_STICKY; }
            if(ACT_OCR.equals(a)){ captureOnce(null); return START_STICKY; }
            if(ACT_OCR_REGION.equals(a)){ captureOnce(in); return START_STICKY; }
            if(ACT_OCR_PREVIEW.equals(a)){ captureOnce(in); return START_STICKY; }
            if(ACT_OCR_REFINE.equals(a)){
                if(captureAlive())refinePreview(in);else requireProjection(in);
                return START_NOT_STICKY;
            }
            if(ACT_OCR_CANCEL.equals(a)){
                long canceled=in.getLongExtra("session",-1);
                if(previewSession==canceled)previewSession=0;
                if(pendingPreview!=null && pendingPreview.getLongExtra("session",-1)==canceled)pendingPreview=null;
                if(previewFrame!=null && previewFrame.session==canceled){previewFrame.close();previewFrame=null;}
                return START_NOT_STICKY;
            }
            Intent data=(Intent)in.getParcelableExtra("data"); int code=in.getIntExtra("code",Activity.RESULT_CANCELED);
            if(data!=null){
                Notification n=notification("录屏 OCR 正在启动","准备屏幕捕获…");
                if(Build.VERSION.SDK_INT>=29) startForeground(42,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                else startForeground(42,n);
                startProjection(code,data);
            }
            return START_STICKY;
        }
        void startProjection(int code,Intent data){
            stopCapture();
            try{
                MediaProjectionManager mm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
                final MediaProjection current=mm.getMediaProjection(code,data);
                if(current==null)throw new IllegalStateException("没有取得屏幕共享会话");
                projection=current;
                current.registerCallback(new MediaProjection.Callback(){
                    @Override public void onStop(){ if(projection==current){stopCapture();stopSelf();} }
                    @Override public void onCapturedContentResize(int width,int height){ if(projection==current)resizeCapture(width,height); }
                },captureMain);
                DisplayMetrics dm=getResources().getDisplayMetrics(); dpi=dm.densityDpi;
                if(Build.VERSION.SDK_INT>=30){
                    android.view.WindowMetrics m=((WindowManager)getSystemService(WINDOW_SERVICE)).getMaximumWindowMetrics();
                    w=m.getBounds().width(); h=m.getBounds().height();
                } else { w=dm.widthPixels; h=dm.heightPixels; }
                captureFrames=new ScreenCaptureFrames(w,h,captureMain);
                vd=current.createVirtualDisplay("jp-ocr",w,h,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,captureFrames.surface(),null,null);
                if(vd==null)throw new IllegalStateException("屏幕捕获窗口创建失败");
                READY=true;
                Notification n=notification("录屏 OCR 已开启","拖动停留 150ms 取词；长按选择并保存识别语言");
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(42,n);
                sendBroadcast(new Intent(ACTION_READY).setPackage(getPackageName()));
            }catch(RuntimeException error){
                stopCapture();stopSelf();
                Toast.makeText(this,"屏幕共享授权已失效，请重新授权："+error.getMessage(),Toast.LENGTH_LONG).show();
            }
        }
        boolean captureAlive(){
            return READY && projection!=null && captureFrames!=null && vd!=null && vd.getDisplay()!=null && vd.getDisplay().isValid();
        }
        void requireProjection(Intent request){
            // A static READY flag alone cannot prove an actual live projection.
            // Dispose the revoked session before launching the system grant UI.
            if(pendingPreview!=null)request=pendingPreview;
            READY=false;stopCapture();
            if(request!=null && (ACT_OCR_PREVIEW.equals(request.getAction()) || ACT_OCR_REFINE.equals(request.getAction()))){
                sendBroadcast(new Intent(ACTION_PREVIEW).setPackage(getPackageName())
                        .putExtra("session",request.getLongExtra("session",-1)).putExtra("permission_required",true));
            }else{
                startActivity(new Intent(this,OcrActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION));
                Toast.makeText(this,"屏幕共享已结束，请重新授权后识别",Toast.LENGTH_LONG).show();
            }
        }
        void resizeCapture(int width,int height){
            if(vd==null || width<=0 || height<=0 || (width==w && height==h))return;
            ScreenCaptureFrames next=new ScreenCaptureFrames(width,height,captureMain);
            ScreenCaptureFrames old=captureFrames;
            try{vd.resize(width,height,dpi);vd.setSurface(next.surface());}
            catch(RuntimeException error){next.close();throw error;}
            captureFrames=next;w=width;h=height;
            if(old!=null)old.close();
        }
        @Override public void onConfigurationChanged(android.content.res.Configuration config){
            super.onConfigurationChanged(config);
            android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();
            ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
            resizeCapture(metrics.widthPixels,metrics.heightPixels);
        }
        Notification notification(String title,String body){
            PendingIntent o=PendingIntent.getService(this,1,new Intent(this,ScreenCaptureService.class).setAction(ACT_OCR),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent s=PendingIntent.getService(this,2,new Intent(this,ScreenCaptureService.class).setAction(ACT_STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder=Build.VERSION.SDK_INT>=26
                    ? new Notification.Builder(this,CH) : new Notification.Builder(this);
            return builder.setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle(title).setContentText(body).setOngoing(true)
                    .addAction(new Notification.Action.Builder(null,"识别当前屏幕",o).build())
                    .addAction(new Notification.Action.Builder(null,"停止",s).build()).build();
        }
        void ensureChannel(){
            if(Build.VERSION.SDK_INT>=26) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CH,"屏幕 OCR",NotificationManager.IMPORTANCE_DEFAULT));
        }
        void captureOnce(Intent request){
            final boolean preview=request!=null && ACT_OCR_PREVIEW.equals(request.getAction());
            final long session=request==null?0:request.getLongExtra("session",0);
            if(preview && (previewInFlight || pendingPreview!=null)){
                // Keep at most one physical capture/model operation and one
                // latest request. Retired SDK tasks still own their bitmap.
                pendingPreview=new Intent(request);
                if(session!=previewSession){
                    previewSession=0;
                    if(previewFrame!=null){previewFrame.close();previewFrame=null;}
                }
                if(!previewInFlight)captureMain.post(this::runPendingPreview);
                return;
            }
            if(!captureAlive()){requireProjection(request);return;}
            final boolean region=request!=null && ACT_OCR_REGION.equals(request.getAction());
            final int rx=region?request.getIntExtra("x",0):0;
            final int ry=region?request.getIntExtra("y",0):0;
            final int rw=region?request.getIntExtra("w",w):w;
            final int rh=region?request.getIntExtra("h",h):h;

            if(preview){
                previewInFlight=true;previewSession=previewOperation=session;
                previewMode=OcrPickupMode.fromValue(request.getStringExtra("pickup_mode"));
                previewLanguage=previewMode==OcrPickupMode.ENGLISH?OcrLanguageSettings.Language.ENGLISH
                        :previewMode==OcrPickupMode.JAPANESE?OcrLanguageSettings.Language.JAPANESE
                        :previewMode==OcrPickupMode.SENTENCE?OcrLanguageSettings.Language.MIXED:OcrLanguageSettings.get(this).language();
            }
            captureMain.postDelayed(new Runnable(){
                int attempts;
                @Override public void run(){
                if(preview && session!=previewSession){finishPreviewOperation(session);return;}
                if(!captureAlive()){if(preview)finishPreviewOperation(session);requireProjection(request);return;}
                final Image image;
                try{image=captureFrames.latest();}
                catch(RuntimeException error){if(preview)finishPreviewOperation(session);requireProjection(request);return;}
                if(image==null){
                    if(++attempts<25){captureMain.postDelayed(this,80);return;}
                    captureFailed(preview,region,session,"尚未收到屏幕画面，请稍后重试");return;
                }
                Bitmap bmp=null;
                try{
                    Image.Plane p=image.getPlanes()[0];
                    // The latest native frame can serve repeated requests on
                    // a static screen, so never advance its shared buffer.
                    ByteBuffer buf=p.getBuffer().duplicate();buf.rewind();
                    int imageWidth=image.getWidth(), imageHeight=image.getHeight();
                    int px=p.getPixelStride(), row=p.getRowStride(), pad=row-px*imageWidth;
                    Bitmap tmp=Bitmap.createBitmap(imageWidth+pad/px,imageHeight,Bitmap.Config.ARGB_8888);
                    tmp.copyPixelsFromBuffer(buf);
                    Bitmap full=Bitmap.createBitmap(tmp,0,0,imageWidth,imageHeight);
                    if(tmp!=full) tmp.recycle();

                    if(region){
                        int left=Math.max(0,Math.min(rx,imageWidth-1));
                        int top=Math.max(0,Math.min(ry,imageHeight-1));
                        int cw=Math.max(1,Math.min(rw,imageWidth-left));
                        int ch=Math.max(1,Math.min(rh,imageHeight-top));
                        bmp=Bitmap.createBitmap(full,left,top,cw,ch);
                        if(bmp!=full) full.recycle();
                    }else{
                        bmp=full;
                    }
                } catch(Exception e){
                    captureFailed(preview,region,session,"截图失败："+e.getMessage());
                    return;
                }

                if(preview){
                    sendBroadcast(new Intent(ACTION_PREVIEW_FRAME).setPackage(getPackageName()).putExtra("session",session));
                    recognizePreview(bmp,session);
                }else recognize(bmp,region);
                }
            },preview?100:280);
        }
        void captureFailed(boolean preview,boolean region,long session,String error){
            if(preview)sendPreview(session,null,error);
            else if(region)sendOcrResult(null,error);
            else Toast.makeText(this,error,Toast.LENGTH_LONG).show();
        }
        void runPendingPreview(){
            if(previewInFlight || pendingPreview==null)return;
            Intent next=pendingPreview;pendingPreview=null;captureOnce(next);
        }
        void finishPreviewOperation(long session){
            if(!previewInFlight || session!=previewOperation)return;
            previewInFlight=false;previewOperation=0;
            if(pendingPreview!=null)captureMain.post(this::runPendingPreview);
        }
        void recognizePreview(Bitmap bitmap,long session){
            if(ocrRecognizer==null){bitmap.recycle();sendPreview(session,null,"OCR 识别器尚未就绪");return;}
            if(previewFrame!=null)previewFrame.close();
            OcrPreviewFrame frame=new OcrPreviewFrame(session,bitmap);previewFrame=frame;
            frame.borrow(session);
            // Keep the preview and its local refinement on the same selected model.
            final OcrPickupMode requestMode=previewMode;
            final OcrLanguageSettings.Language requestLanguage=previewLanguage;
            ocrRecognizer.recognizeRegions(bitmap,requestLanguage,requestMode==OcrPickupMode.SENTENCE,new MultilingualOcrRecognizer.RegionsCallback(){
                public void onSuccess(java.util.List<MultilingualOcrRecognizer.Region> regions){
                    if(requestLanguage==OcrLanguageSettings.Language.ENGLISH){
                        java.util.List<MultilingualOcrRecognizer.Region> cleaned=new java.util.ArrayList<>();
                        for(MultilingualOcrRecognizer.Region region:regions){
                            MultilingualOcrRecognizer.Region word=region.clean(OcrPickupMode.ENGLISH);
                            if(!word.text.isEmpty())cleaned.add(word);
                        }
                        regions=cleaned;
                    }
                    try{sendPreview(session,regions,null);}finally{frame.release();}
                }
                public void onFailure(Exception error){
                    // A missed full-screen detection can still succeed on the enlarged local crop.
                    try{
                        if(error.getCause() instanceof com.google.mlkit.common.MlKitException)sendPreview(session,null,error.getMessage());
                        else sendPreview(session,java.util.Collections.emptyList(),null);
                    }finally{frame.release();}
                }
            });
        }
        void refinePreview(Intent request){
            long session=request.getLongExtra("session",-1);
            android.graphics.Rect target=request.getParcelableExtra("target");
            if(previewInFlight || session!=previewSession || target==null || target.isEmpty()){
                sendBroadcast(new Intent(ACTION_PREVIEW).setPackage(getPackageName()).putExtra("session",session).putExtra("refined",true).putExtra("error","取词会话已失效，请重新拖动"));return;
            }
            OcrPreviewFrame frame=previewFrame;
            Bitmap bitmap=frame==null?null:frame.borrow(session);
            if(bitmap==null || ocrRecognizer==null){
                if(bitmap!=null)frame.release();
                sendRefined(session,null,"屏幕共享或截图已失效，请重新拖动取词");return;
            }
            previewInFlight=true;previewOperation=session;
            String seedText=request.getStringExtra("seed");
            MultilingualOcrRecognizer.Region seed=seedText==null?null:new MultilingualOcrRecognizer.Region(seedText,target);
            if(seed!=null){
                seed.quality=request.getFloatExtra("quality",0);seed.boundedRange=request.getBooleanExtra("bounded_range",false);
                int[] selectionBoxes=request.getIntArrayExtra("selection_boxes");
                if(selectionBoxes!=null && selectionBoxes.length%4==0 && selectionBoxes.length<=64*4)
                    for(int i=0;i<selectionBoxes.length;i+=4){
                        Rect bounds=new Rect(selectionBoxes[i],selectionBoxes[i+1],selectionBoxes[i+2],selectionBoxes[i+3]);
                        if(!bounds.isEmpty() && target.contains(bounds))seed.selectedBounds.add(bounds);
                    }
            }
            final OcrPickupMode requestMode=previewMode;
            final OcrLanguageSettings.Language requestLanguage=previewLanguage;
            ocrRecognizer.recognizeSelection(bitmap,target,seed,request.getFloatExtra("aim_x",0),request.getFloatExtra("aim_y",0),request.getFloatExtra("tolerance",0),requestLanguage,requestMode==OcrPickupMode.SENTENCE,new MultilingualOcrRecognizer.Callback(){
                public void onSuccess(String text){try{
                    String cleaned=requestLanguage==OcrLanguageSettings.Language.ENGLISH?OcrPickupMode.ENGLISH.clean(text):requestMode.clean(text);
                    sendRefined(session,cleaned,cleaned.isEmpty()?"准星附近没有拉丁字母单词，请重新对准":null);
                }finally{frame.close();frame.release();}}
                public void onFailure(Exception error){try{sendRefined(session,null,error.getMessage());}finally{frame.close();frame.release();}}
            });
        }
        void sendRefined(long session,String text,String error){
            finishPreviewOperation(session);
            if(session!=previewSession)return;
            Intent out=new Intent(ACTION_PREVIEW).setPackage(getPackageName()).putExtra("session",session).putExtra("refined",true);
            if(text!=null)out.putExtra("text",text);if(error!=null)out.putExtra("error",error);
            sendBroadcast(out);
        }
        void sendPreview(long session,java.util.List<MultilingualOcrRecognizer.Region> regions,String error){
            finishPreviewOperation(session);
            if(session!=previewSession)return;
            Intent out=new Intent(ACTION_PREVIEW).setPackage(getPackageName()).putExtra("session",session);
            if(regions!=null){
                int chars=0;for(MultilingualOcrRecognizer.Region region:regions)chars+=region.text.length();
                if(regions.size()>4096 || chars>65536){error="屏幕文字过多，请缩放页面后重试";}
                else{
                    String[] words=new String[regions.size()];int[] boxes=new int[regions.size()*4];float[] quality=new float[regions.size()];
                    int symbolCount=0;for(MultilingualOcrRecognizer.Region region:regions)symbolCount+=region.symbols.size();
                    // Bound optional glyph geometry well below Binder's transaction limit.
                    symbolCount=Math.min(symbolCount,8192);
                    int[] symbolOwners=new int[symbolCount],symbolOffsets=new int[symbolCount*2],symbolBoxes=new int[symbolCount*4];
                    int symbolIndex=0;
                    for(int i=0;i<regions.size();i++){
                        MultilingualOcrRecognizer.Region region=regions.get(i);words[i]=region.text;
                        quality[i]=region.quality;
                        boxes[i*4]=region.bounds.left;boxes[i*4+1]=region.bounds.top;boxes[i*4+2]=region.bounds.right;boxes[i*4+3]=region.bounds.bottom;
                        for(MultilingualOcrRecognizer.Symbol symbol:region.symbols){
                            if(symbolIndex>=symbolCount)break;
                            symbolOwners[symbolIndex]=i;symbolOffsets[symbolIndex*2]=symbol.start;symbolOffsets[symbolIndex*2+1]=symbol.end;
                            symbolBoxes[symbolIndex*4]=symbol.bounds.left;symbolBoxes[symbolIndex*4+1]=symbol.bounds.top;
                            symbolBoxes[symbolIndex*4+2]=symbol.bounds.right;symbolBoxes[symbolIndex*4+3]=symbol.bounds.bottom;symbolIndex++;
                        }
                    }
                    out.putExtra("words",words).putExtra("boxes",boxes).putExtra("quality",quality)
                            .putExtra("symbol_owners",symbolOwners).putExtra("symbol_offsets",symbolOffsets).putExtra("symbol_boxes",symbolBoxes);
                }
            }
            if(error!=null)out.putExtra("error",error);
            sendBroadcast(out);
        }

        void recognize(Bitmap bmp, boolean region){
            MultilingualOcrRecognizer recognizer=ocrRecognizer;
            if(recognizer==null){
                try{
                    if(region) sendOcrResult(null,"OCR 识别器尚未就绪");
                    else Toast.makeText(this,"OCR 识别器尚未就绪",Toast.LENGTH_LONG).show();
                } finally {
                    try{ bmp.recycle(); }catch(Exception ignored){}
                }
                return;
            }

            recognizer.recognize(
                    bmp,
                    new MultilingualOcrRecognizer.Callback() {
                        @Override public void onSuccess(String q) {
                            try {
                                if(region){
                                    sendOcrResult(q,null);
                                }else{
                                    Intent i=new Intent(ScreenCaptureService.this,SearchActivity.class)
                                            .putExtra("query",q)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                    try{ startActivity(i); }catch(Exception e){ postResult(q); }
                                }
                            } finally {
                                try{ bmp.recycle(); }catch(Exception ignored){}
                            }
                        }

                        @Override public void onFailure(Exception e) {
                            try {
                                String message=e==null || e.getMessage()==null
                                        ? "没有识别到文字"
                                        : e.getMessage();
                                if(region) sendOcrResult(null,"OCR 失败："+message);
                                else Toast.makeText(ScreenCaptureService.this,
                                        "OCR 失败："+message,Toast.LENGTH_LONG).show();
                            } finally {
                                try{ bmp.recycle(); }catch(Exception ignored){}
                            }
                        }
                    });
        }

        void sendOcrResult(String text,String error){
            if(text!=null && error==null){
                Intent show=new Intent(this,FloatingService.class)
                        .setAction(FloatingService.ACTION_SHOW_LOOKUP_OVERLAY)
                        .putExtra(FloatingService.EXTRA_LOOKUP_QUERY,text)
                        .putExtra(FloatingService.EXTRA_RETURN_TO_SEARCH,false);
                try{
                    if(Build.VERSION.SDK_INT>=26) startForegroundService(show);
                    else startService(show);
                    return;
                }catch(Throwable ignored){}
            }

            Intent out=new Intent(ACTION_RESULT).setPackage(getPackageName());
            if(text!=null) out.putExtra("text",text);
            if(error!=null) out.putExtra("error",error);
            sendBroadcast(out);
        }
        void postResult(String q){
            Intent i=new Intent(this,SearchActivity.class).putExtra("query",q);
            PendingIntent p=PendingIntent.getActivity(this,3,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder=Build.VERSION.SDK_INT>=26
                    ? new Notification.Builder(this,CH) : new Notification.Builder(this);
            Notification n=builder.setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("OCR 识别结果").setContentText(q).setContentIntent(p).setAutoCancel(true).build();
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(43,n);
        }
        void stopCapture(){
            boolean wasReady=READY;
            previewSession=0;pendingPreview=null;
            if(previewFrame!=null){previewFrame.close();previewFrame=null;}
            VirtualDisplay v=vd; vd=null;
            ScreenCaptureFrames r=captureFrames; captureFrames=null;
            MediaProjection p=projection; projection=null;
            READY=false;
            if(wasReady)sendBroadcast(new Intent(ACTION_STOPPED).setPackage(getPackageName()));
            if(v!=null) v.release();
            if(r!=null) r.close();
            if(p!=null) try{ p.stop(); }catch(Exception ignored){}
        }
        @Override public void onDestroy(){
            captureMain.removeCallbacksAndMessages(null);
            MultilingualOcrRecognizer recognizer=ocrRecognizer;
            ocrRecognizer=null;
            if(recognizer!=null) recognizer.close();
            stopCapture();
            super.onDestroy();
        }
        @Override public android.os.IBinder onBind(Intent i){ return null; }
    }

    public static class SearchWidgetProvider extends AppWidgetProvider {
        @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){
            for(int id:ids){
                RemoteViews rv=new RemoteViews(c.getPackageName(),R.layout.widget_search);
                PendingIntent p=PendingIntent.getActivity(c,10,new Intent(c,SearchActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                rv.setOnClickPendingIntent(R.id.widget_root,p); m.updateAppWidget(id,rv);
            }
        }
    }

    public static final class App extends Application {
        static Context c;

        @Override public void onCreate(){
            super.onCreate();
            c=this;

            Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();

            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    StringBuilder out=new StringBuilder();
                    out.append(throwable.getClass().getName())
                            .append(": ")
                            .append(throwable.getMessage()==null?"":throwable.getMessage())
                            .append("\n");

                    StackTraceElement[] trace=throwable.getStackTrace();
                    int max=Math.min(trace.length,24);
                    for(int i=0;i<max;i++){
                        out.append("  at ").append(trace[i].toString()).append("\n");
                    }

                    getSharedPreferences("crash_log",MODE_PRIVATE)
                            .edit()
                            .putString("last",out.toString())
                            .putLong("time",System.currentTimeMillis())
                            .commit();
                }catch(Throwable ignored){}

                if(previous!=null) previous.uncaughtException(thread,throwable);
            });
        }

        static Context get(){
            if(c!=null)return c;
            throw new IllegalStateException("App context not initialized");
        }
    }
}
