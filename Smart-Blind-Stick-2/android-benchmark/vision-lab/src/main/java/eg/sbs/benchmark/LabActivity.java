package eg.sbs.benchmark;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.google.ar.core.ArCoreApk;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** A separate, offline comparison app. Heavy engines share one worker and never overlap. */
public final class LabActivity extends Activity {
    private static final String[] MODES={"YOLO26n · مقارنة بالنسخة الحالية", "YOLOE · 140 نوعًا", "Depth Anything · تقدير عمق للصورة", "ARCore · عمق حي مع الثقة", "Qwen3-VL · وصف عربي للصورة"};
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Spinner modes;
    private TextView status, result, modelStatus, speechStatus;
    private Button start, capture;
    private FrameLayout preview;
    private DetectionView detection;
    private GLSurfaceView gl;
    private ModelStore models;
    private LabSpeech speech;
    private ArabicAudio clips;
    private final List<String> arabic=new ArrayList<>();
    private volatile Run active;
    private LabReport lastReport;
    private String exportJson;
    private boolean destroyed, installingAr;
    private int pendingMode;
    private static final class Run {
        final int mode;
        final LabReport report;
        final AtomicBoolean busy=new AtomicBoolean();
        final DepthMath.Attention attention=new DepthMath.Attention();
        volatile boolean running=true, snapshot, requested;
        volatile byte[] latest;
        volatile long received, resultReceived, nativeId;
        volatile String spoken="";
        volatile List<String> assets=List.of();
        long lastNotice=-1, lastTelemetry, lastDepthTimestamp;
        Future<?> task;
        RearJpegCamera camera;
        ArDepthCamera ar;
        YoloEngine baseline;
        ExpandedDetector expanded;
        MetricDepthEngine metric;
        Run(int mode) { this.mode=mode; report=new LabReport(MODES[mode]); }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        var root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(16,12,16,8);
        var scroll=new ScrollView(this); var controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); scroll.addView(controls);
        modes=new Spinner(this); modes.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,MODES)); controls.addView(modes);
        var row=new LinearLayout(this);
        start=button(row,"ابدأ",v-> { if(active==null) beginCamera(); else stop("user_stop"); });
        capture=button(row,"التقط وصف / عمق",v->requestSnapshot());
        controls.addView(row);
        var row2=new LinearLayout(this);
        button(row2,"اختيار صورة",v->chooseImage()); button(row2,"اقرأ النتيجة",v->readNow()); controls.addView(row2);
        status=label(controls,"اختار تجربة. النسخة القديمة موجودة كتطبيق منفصل.");
        result=label(controls,"النتائج هتظهر هنا"); result.setTextIsSelectable(true);
        speechStatus=label(controls,"");
        var row3=new LinearLayout(this);
        button(row3,"حالة النماذج",v->verifyModels()); button(row3,"استيراد نموذج",v->{stop("model_import"); startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),12);}); controls.addView(row3);
        modelStatus=label(controls,"");
        var row4=new LinearLayout(this);
        button(row4,"حفظ تقرير",v->exportReport()); button(row4,"إعدادات النطق",v->{stop("tts_settings"); try {startActivity(new Intent("com.android.settings.TTS_SETTINGS"));} catch(Exception e) {status.setText("افتح إعدادات اللغة والنطق وثبّت صوتًا عربيًا أوفلاين");}}); controls.addView(row4);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        preview=new FrameLayout(this); detection=new DetectionView(this); preview.addView(detection,new FrameLayout.LayoutParams(-1,-1)); root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        try {
            models=new ModelStore(this);
            try(var input=getAssets().open("yoloe-labels.json")) { var data=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("labels"); for(int i=0;i<data.length();i++) arabic.add(data.getJSONObject(i).getString("ar")); }
            modelStatus.setText(models.status());
        } catch(Exception e) { status.setText("تعذر تجهيز النماذج: "+e.getMessage()); start.setEnabled(false); }
        clips=new ArabicAudio(this,text->speechStatus.setText(text));
        speech=new LabSpeech(this,text->speechStatus.setText(text));
    }
    private Button button(LinearLayout row,String title,View.OnClickListener listener) {
        var b=new Button(this); b.setText(title); b.setOnClickListener(listener); row.addView(b,new LinearLayout.LayoutParams(0,-2,1)); return b;
    }
    private TextView label(LinearLayout root,String text) { var v=new TextView(this); v.setText(text); v.setTextSize(16); v.setPadding(0,5,0,5); root.addView(v); return v; }
    private boolean current(Run run) { return !destroyed && active==run && run.running; }
    private void ui(Run run,Runnable action) { main.post(()->{if(current(run)) action.run();}); }
    private void beginCamera() {
        if(models==null) return;
        pendingMode=modes.getSelectedItemPosition();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {requestPermissions(new String[]{Manifest.permission.CAMERA},20);return;}
        if(pendingMode==3) try {
            if(ArCoreApk.getInstance().requestInstall(this,!installingAr)==ArCoreApk.InstallStatus.INSTALL_REQUESTED) {installingAr=true;status.setText("ثبّت خدمات Google Play للواقع المعزز ثم اضغط ابدأ");return;}
            installingAr=false;
        } catch(Exception e) {status.setText("ARCore غير متاح: "+e.getMessage());return;}
        Run run=newRun(pendingMode);
        run.task=worker.submit(()->{
            try { load(run); ui(run,()->openCamera(run)); }
            catch(Exception | LinkageError | OutOfMemoryError e) {fail(run,e);}
        });
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==20 && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED) beginCamera();
        else if(code==20) status.setText("صلاحية الكاميرا مطلوبة للتجربة الحية؛ تقدر تختار صورة");
    }
    private Run newRun(int mode) {
        stop("new_run"); Run run=new Run(mode); active=run; lastReport=run.report;
        modes.setEnabled(false); start.setText("إيقاف"); capture.setEnabled(mode==2||mode==4);
        result.setText(""); status.setText("جارٍ التحقق من النماذج وتحميلها…"); detection.show(null);
        return run;
    }
    private void load(Run run) throws Exception {
        if(!current(run)) return;
        if(run.mode==1) run.expanded=new ExpandedDetector(this,models);
        else if(run.mode!=4) run.baseline=new YoloEngine(getAssets(),320);
        if(run.mode==2 && current(run)) run.metric=new MetricDepthEngine(models);
        if(run.mode==4 && current(run)) {models.require("qwen"); models.require("qwen_vision");}
        if(!current(run)) closeEngines(run);
    }
    private void openCamera(Run run) {
        if(!current(run)) return;
        status.setText(run.mode==2||run.mode==4 ? "وجّه الكاميرا ثم اضغط التقط؛ المعالجة للصورة المختارة فقط" : "الكاميرا تعمل؛ اضغط اقرأ النتيجة للوصف");
        if(run.mode==3) {
            try {
                gl=new GLSurfaceView(this); preview.addView(gl,0,new FrameLayout.LayoutParams(-1,-1));
                run.ar=new ArDepthCamera(this,gl,new ArDepthCamera.Listener() {
                    public boolean wantsFrame() {return current(run)&&run.busy.compareAndSet(false,true);}
                    public void onFrame(byte[] jpeg,long received,ArDepthCamera.Depth depth) {accept(run,jpeg,received,depth);}
                    public void onError(String text) {fail(run,new IllegalStateException(text));}
                });
            } catch(Exception e) {fail(run,e);}
        } else {
            int degrees=switch(getDisplay().getRotation()){case Surface.ROTATION_90->90;case Surface.ROTATION_180->180;case Surface.ROTATION_270->270;default->0;};
            run.camera=new RearJpegCamera(this,degrees,new RearJpegCamera.Listener(){
                public void onReady(String input) {run.report.event("camera",input);}
                public boolean wantsFrame() {return current(run)&&!run.snapshot&&run.busy.compareAndSet(false,true);}
                public void onJpeg(byte[] jpeg,long received,long captured) {accept(run,jpeg,received,null);}
                public void onError(String message) {fail(run,new IllegalStateException(message));}
            });
        }
    }
    private void accept(Run run,byte[] jpeg,long received,ArDepthCamera.Depth depth) {
        if(!current(run)||jpeg==null) {run.busy.set(false);return;}
        run.latest=jpeg; run.received=received;
        boolean snapshot=(run.mode==2||run.mode==4)&&run.requested;
        if(snapshot) { run.snapshot=true; run.requested=false; }
        run.task=worker.submit(()->{
            try {
                if(!current(run)) return;
                if(snapshot) {ui(run,()->{closeCamera(run);status.setText(run.mode==4?"جارٍ إنشاء الوصف العربي على الجهاز…":"جارٍ تقدير عمق الصورة…");}); processSnapshot(run,jpeg);}
                else if(run.mode==2||run.mode==4) {
                    Bitmap image=LabImage.decode(jpeg);
                    present(run,new YoloEngine.Prediction(image,List.of(),0,0,0),"معاينة؛ اضغط التقط", "",List.of(),received);
                } else processLive(run,jpeg,received,depth);
            } catch(Exception | LinkageError | OutOfMemoryError e) {fail(run,e);}
            finally {run.busy.set(false);}
        });
    }
    private void requestSnapshot() {
        Run run=active;
        if(run==null || (run.mode!=2&&run.mode!=4)) {status.setText("ابدأ تجربة العمق أو الوصف أولًا");return;}
        if(run.snapshot) {status.setText("اضغط إيقاف ثم ابدأ لالتقاط صورة جديدة");return;}
        run.requested=true; status.setText("جارٍ التقاط الصورة التالية…");
    }
    private String objectText(YoloEngine.Detection box) {
        String name=box.classId()<arabic.size()?arabic.get(box.classId()):box.label();
        float center=(box.x1()+box.x2())*.5f;
        return name+(center<.35f?" على اليسار":center>.65f?" على اليمين":" في المنتصف");
    }
    private List<YoloEngine.Detection> scene(YoloEngine.Prediction p) {return p.boxes().stream().sorted(Comparator.comparingDouble(YoloEngine.Detection::score).reversed()).limit(4).collect(java.util.stream.Collectors.toList());}
    private List<String> clipsFor(List<YoloEngine.Detection> boxes) {
        var list=new ArrayList<String>();
        for(var b:boxes) if(b.classId()<80) {float c=(b.x1()+b.x2())*.5f;list.add(b.classId()+"_"+(c<.35?0:c>.65?2:1));}
        return List.copyOf(list);
    }
    private void processLive(Run run,byte[] jpeg,long received,ArDepthCamera.Depth depth) throws Exception {
        long start=YoloEngine.now();
        var p=run.mode==1?run.expanded.predict(jpeg,.45f):run.baseline.predict(jpeg,.45f);
        boolean handed=false;
        try {
            var boxes=scene(p); var text=new StringBuilder(); var spoken=new StringJoiner("، ");
            for(var b:boxes) {
                String phrase=objectText(b); text.append(phrase); spoken.add(phrase);
                if(run.mode==3) {
                    DepthMath.Estimate estimate=sampleAr(depth,b.x1(),b.y1(),b.x2(),b.y2()); run.report.depth(estimate,objectText(b));
                    text.append(estimate.available()?String.format(Locale.ROOT," · حوالي %.1f م (ARCore)",estimate.meters()):" · المسافة غير متاحة");
                }
                text.append('\n');
            }
            boolean notice=false;
            if(run.mode==3) {
                var center=sampleAr(depth,.30f,.35f,.70f,.9f); run.report.depth(center,"center_foreground");
                if(depth==null || depth.sensorTimestampNs()==0 || !center.available()) {
                    run.attention.update(center,YoloEngine.now()/1_000_000,true);
                } else if(depth.sensorTimestampNs()!=run.lastDepthTimestamp) {
                    run.lastDepthTimestamp=depth.sensorTimestampNs();
                    notice=run.attention.update(center,YoloEngine.now()/1_000_000,true);
                }
                text.append("العمق: ").append(depth==null?"غير متاح":depth.state());
            }
            if(boxes.isEmpty()) text.insert(0,"لا توجد أجسام مؤكدة في هذه اللقطة\n");
            run.report.processing(YoloEngine.now()-start);
            if(YoloEngine.now()-run.lastTelemetry>5_000_000_000L) {run.report.sample(this);run.lastTelemetry=YoloEngine.now();}
            present(run,p,text.toString(),spoken.toString(),clipsFor(boxes),received);handed=true;
            if(notice) ui(run,()->{
                long now=YoloEngine.now()/1_000_000;
                if((YoloEngine.now()-received)/1_000_000<=500 && (run.lastNotice<0||now-run.lastNotice>=10_000) && !clips.isBusy()) {
                    speech.stop(); clips.play("notice",()->current(run)&&(YoloEngine.now()-received)/1_000_000<=500,()->{run.lastNotice=now;run.report.event("attention","fresh_confident_arcore_center_under_0.9m");});
                }
            });
        } finally {if(!handed)p.image().recycle();}
    }
    private DepthMath.Estimate sampleAr(ArDepthCamera.Depth depth,float x1,float y1,float x2,float y2) {
        if(depth==null) return DepthMath.Estimate.unavailable(DepthMath.Source.ARCORE_RAW,"no_depth_frame",0,0);
        return DepthMath.sample(depth.meters(),depth.confidence(),depth.width(),depth.height(),x1,y1,x2,y2,depth.imageToDepth(),DepthMath.Source.ARCORE_RAW,(YoloEngine.now()-depth.capturedAtNs())/1_000_000);
    }
    private void processSnapshot(Run run,byte[] jpeg) throws Exception {
        long started=YoloEngine.now();
        if(run.mode==4) {
            var memory=new ActivityManager.MemoryInfo();getSystemService(ActivityManager.class).getMemoryInfo(memory);
            if(memory.lowMemory||memory.availMem<2_200_000_000L) throw new IllegalStateException("ذاكرة غير كافية للوصف؛ اقفل التطبيقات الأخرى وجرّب تاني");
            Bitmap image=LabImage.decode(jpeg); boolean handed=false;
            try {
                float scale=Math.min(1,448f/Math.max(image.getWidth(),image.getHeight()));
                Bitmap small=Bitmap.createScaledBitmap(image,Math.max(1,Math.round(image.getWidth()*scale)),Math.max(1,Math.round(image.getHeight()*scale)),true);
                byte[] rgb;int width=small.getWidth(),height=small.getHeight();
                try {rgb=LabImage.rgb(small);} finally {if(small!=image)small.recycle();}
                if(!current(run)||Thread.currentThread().isInterrupted()) return;
                synchronized(run) {if(!current(run))return;run.nativeId=QwenNative.begin();}
                String caption=new String(QwenNative.describe(run.nativeId,models.require("qwen").getAbsolutePath(),models.require("qwen_vision").getAbsolutePath(),rgb,width,height,CaptionGuard.PROMPT),StandardCharsets.UTF_8);
                var checked=CaptionGuard.inspect(caption); run.report.event("caption_guard",checked.reason());
                if(!checked.accepted()) throw new IllegalStateException("النموذج لم ينتج وصفًا عربيًا مناسبًا؛ أعد التقاط صورة أوضح");
                run.report.processing(YoloEngine.now()-started);run.report.sample(this);
                present(run,new YoloEngine.Prediction(image,List.of(),0,YoloEngine.now()-started,0),checked.text()+"\nوصف مولّد للصورة؛ المسافة غير مقاسة",checked.text(),List.of(),run.received);handed=true;
            } finally {if(!handed)image.recycle();}
        } else {
            var p=run.baseline.predict(jpeg,.45f);boolean handed=false;
            try {
                var depth=run.metric.predict(p.image());var text=new StringBuilder("تقدير عمق للصورة (Depth Anything)؛ يحتاج مقارنة بمسافة حقيقية\n");
                for(var b:scene(p)) {
                    var e=DepthMath.sample(depth.meters(),null,depth.width(),depth.height(),b.x1(),b.y1(),b.x2(),b.y2(),DepthMath.Transform.identity(),DepthMath.Source.DEPTH_ANYTHING_METRIC,0);
                    run.report.depth(e,objectText(b));text.append(objectText(b)).append(e.available()?String.format(Locale.ROOT," · حوالي %.1f متر (تقدير نموذج)",e.meters()):" · المسافة غير متاحة").append('\n');
                }
                if(p.boxes().isEmpty()) text.append("لا توجد أجسام مؤكدة لربطها بالعمق");
                run.report.processing(YoloEngine.now()-started);run.report.sample(this);
                present(run,p,text.toString(),text.toString(),List.of(),run.received);handed=true;
            } finally {if(!handed)p.image().recycle();}
        }
        ui(run,()->status.setText("اكتملت معالجة الصورة؛ اقرأ النتيجة أو احفظ التقرير"));
    }
    private void present(Run run,YoloEngine.Prediction prediction,String text,String spoken,List<String> assets,long sourceReceived) {
        main.post(()->{
            if(!current(run)) {prediction.image().recycle();return;}
            detection.show(prediction);result.setText(text);run.spoken=spoken;run.assets=assets;run.resultReceived=sourceReceived;
        });
    }
    private void readNow() {
        Run run=active;
        if(run==null||run.spoken.isBlank()||(!run.snapshot&&(YoloEngine.now()-run.resultReceived)>500_000_000L)) {speechStatus.setText("انتظر نتيجة حديثة أو التقط صورة");return;}
        clips.stop();speech.stop();
        if(!speech.speak(run.spoken)) {
            if(!run.assets.isEmpty()) clips.play(run.assets,()->current(run)&&(run.snapshot||YoloEngine.now()-run.resultReceived<=500_000_000L),()->current(run),()->{},()->{});
            else speechStatus.setText("الوصف مكتوب؛ ثبّت صوتًا عربيًا أوفلاين لقراءته");
        }
    }
    private void closeCamera(Run run) {
        if(run.camera!=null){run.camera.close();run.camera=null;}
        if(run.ar!=null){run.ar.close();run.ar=null;}
        if(gl!=null){preview.removeView(gl);gl=null;}
    }
    private void stop(String reason) {
        Run run=active;if(run==null)return;
        synchronized(run) {run.running=false;active=null;if(run.nativeId!=0)QwenNative.cancel();}
        if(run.task!=null)run.task.cancel(true);
        closeCamera(run);speech.stop();clips.stop();run.report.finish(reason);lastReport=run.report;
        worker.submit(()->closeEngines(run));
        modes.setEnabled(true);start.setText("ابدأ");capture.setEnabled(false);
        status.setText("التجربة متوقفة؛ التقرير متاح للحفظ");
    }
    private static void closeEngines(Run run) {
        try {if(run.expanded!=null){run.expanded.close();run.expanded=null;}}catch(Exception ignored){}
        try {if(run.metric!=null){run.metric.close();run.metric=null;}}catch(Exception ignored){}
        try {if(run.baseline!=null){run.baseline.close();run.baseline=null;}}catch(Exception ignored){}
    }
    private void fail(Run run,Throwable error) {
        run.report.event("error",String.valueOf(error.getMessage()));
        main.post(()->{if(current(run)){stop("error");status.setText("تعذرت التجربة: "+error.getMessage());}});
    }
    private void chooseImage() {
        if(modes.getSelectedItemPosition()==3) {status.setText("ARCore يحتاج كاميرا حية ولا يقيس صورة محفوظة");return;}
        stop("choose_image");pendingMode=modes.getSelectedItemPosition();
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/jpeg").addCategory(Intent.CATEGORY_OPENABLE),11);
    }
    private void verifyModels() {
        if(models==null)return;stop("verify_models");status.setText("جارٍ التحقق من بصمات الملفات…");
        worker.submit(()->{try{for(String role:List.of("yoloe","metric_depth","qwen","qwen_vision")) models.require(role);main.post(()->{if(!destroyed){modelStatus.setText(models.status());status.setText("كل النماذج موجودة وبصماتها صحيحة");}});}catch(Exception e){main.post(()->{if(!destroyed){modelStatus.setText(models.status());status.setText(e.getMessage());}});}});
    }
    private void exportReport() {
        if(lastReport==null){status.setText("شغّل تجربة أولًا");return;}
        stop("export_report");
        try {exportJson=lastReport.json().toString(2);startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,"SBS-Vision-Lab-"+System.currentTimeMillis()+".json").addCategory(Intent.CATEGORY_OPENABLE),13);}
        catch(Exception e){status.setText("تعذر تجهيز التقرير: "+e.getMessage());}
    }
    @Override protected void onActivityResult(int request,int code,Intent data) {
        super.onActivityResult(request,code,data);if(code!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(request==11) {
            Run run=newRun(pendingMode);run.snapshot=true;
            run.task=worker.submit(()->{
                try(var input=getContentResolver().openInputStream(uri)) {
                    if(input==null)throw new java.io.IOException("الصورة غير متاحة");
                    byte[] bytes=input.readNBytes(YoloEngine.JPEG_LIMIT+1);if(bytes.length>YoloEngine.JPEG_LIMIT)throw new java.io.IOException("الصورة أكبر من 32 MiB");
                    load(run);if(!current(run))return;run.received=YoloEngine.now();
                    if(run.mode==2||run.mode==4)processSnapshot(run,bytes);else processLive(run,bytes,run.received,null);
                }catch(Exception|LinkageError|OutOfMemoryError e){fail(run,e);}
            });
        } else if(request==12) {
            status.setText("جارٍ استيراد النموذج والتحقق منه…");
            worker.submit(()->{try{models.importFile(uri);main.post(()->{if(!destroyed){modelStatus.setText(models.status());status.setText("تم استيراد النموذج والتحقق من بصمته");}});}catch(Exception e){main.post(()->{if(!destroyed)status.setText(e.getMessage());});}});
        } else if(request==13 && exportJson!=null) {
            String json=exportJson;exportJson=null;
            worker.submit(()->{try(var stream=getContentResolver().openOutputStream(uri)){if(stream==null)throw new java.io.IOException("Cannot create report");stream.write(json.getBytes(StandardCharsets.UTF_8));main.post(()->{if(!destroyed)status.setText("تم حفظ التقرير بدون صور");});}catch(Exception e){main.post(()->{if(!destroyed)status.setText(e.getMessage());});}});
        }
    }
    @Override protected void onPause(){stop("activity_paused");super.onPause();}
    @Override protected void onDestroy(){stop("destroyed");destroyed=true;speech.close();clips.close();worker.shutdown();super.onDestroy();}
}
