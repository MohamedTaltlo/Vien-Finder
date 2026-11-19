package online.vienfinder.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Collections;
import java.util.Locale;

/** Direct camera stream + GL shaders. Does not store photos or transmit data. */
public class MainActivity extends Activity implements VeinRenderer.Listener {
    private static final int CYAN = Color.rgb(38,213,223);
    private static final int DARK = Color.rgb(8,19,35);
    private GLSurfaceView glView;
    private VeinRenderer processor;
    private LinearLayout panel;
    private TextView status, sensitivityLabel, contrastLabel, brightnessLabel, exposureLabel;
    private Button[] modeButtons;
    private Button torchButton, reverseButton, freezeButton, compareButton, controlsToggle, rotationButton;
    private boolean resumed=false, opening=false, flashAvailable=false, torchRequested=false;
    private boolean reversed=false, frozen=false, comparison=false;
    private int mode=1, sensitivity=45, contrast=35, brightness=50;
    private int rotateDegrees=90;
    private CameraManager manager;
    private String cameraId;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private SurfaceTexture cameraTexture;
    private Surface outputSurface;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private int exposure=0;
    private Range<Integer> exposureRange=new Range<Integer>(0,0);
    private float exposureStep=1;
    private Range<Integer> fpsRange;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(4,12,24));
        getWindow().setNavigationBarColor(Color.rgb(4,12,24));
        setContentView(createLayout());
    }
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable background(int color,int corners){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(corners));return g;
    }
    private TextView text(String s,int size,int color,boolean strong){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if(strong)t.setTypeface(null,1);
        return t;
    }
    private Button button(String name){
        Button b=new Button(this);
        b.setText(name);b.setTextColor(Color.WHITE);b.setTextSize(12);
        b.setAllCaps(false);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(dp(6),dp(5),dp(6),dp(5));
        b.setBackground(background(Color.rgb(28,49,72),11));
        return b;
    }
    private LinearLayout row(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;
    }
    private LinearLayout column(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    private void addWeighted(LinearLayout parent,View child,int height){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(height),1);
        lp.setMargins(dp(3),dp(3),dp(3),dp(3));parent.addView(child,lp);
    }
    private void updateButtonColors(){
        if(modeButtons==null)return;
        for(int i=0;i<modeButtons.length;i++)
            modeButtons[i].setBackground(background(i==mode?Color.rgb(0,123,151):Color.rgb(28,49,72),11));
    }
    private SeekBar slider(LinearLayout box,int initial,java.util.function.IntConsumer fn){
        SeekBar sb=new SeekBar(this);sb.setMax(100);sb.setProgress(initial);
        sb.setProgressTintList(ColorStateList.valueOf(CYAN));
        sb.setThumbTintList(ColorStateList.valueOf(CYAN));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int value,boolean user){fn.accept(value);}
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
        box.addView(sb,new LinearLayout.LayoutParams(-1,dp(33)));
        return sb;
    }
    private View createLayout(){
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(DARK);
        glView=new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(false);
        processor=new VeinRenderer(glView,this);
        glView.setRenderer(processor);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        frame.addView(glView,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout top=row();
        top.setPadding(dp(13),dp(8),dp(11),dp(8));
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(background(Color.argb(218,4,15,29),0));
        LinearLayout title=column();
        title.addView(text("Vien Finder",21,Color.WHITE,true));
        title.addView(text("LIVE • GPU • A56",11,CYAN,false));
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        rotationButton=button("↻");
        top.addView(rotationButton,new LinearLayout.LayoutParams(dp(49),dp(40)));
        rotationButton.setOnClickListener(v->{
            rotateDegrees=(rotateDegrees+180)%360;
            processor.setSensorOrientation(rotateDegrees);
        });
        controlsToggle=button("إخفاء");
        LinearLayout.LayoutParams toggleParams=new LinearLayout.LayoutParams(dp(68),dp(40));
        toggleParams.setMargins(dp(6),0,0,0);
        top.addView(controlsToggle,toggleParams);
        controlsToggle.setOnClickListener(v->{
            boolean show=panel.getVisibility()!=View.VISIBLE;
            panel.setVisibility(show?View.VISIBLE:View.GONE);
            controlsToggle.setText(show?"إخفاء":"الأدوات");
        });
        frame.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

        panel=column();
        panel.setPadding(dp(11),dp(10),dp(11),dp(9));
        panel.setBackground(background(Color.argb(244,6,18,34),20));
        TextView modes=text("نمط إبراز الصورة",13,Color.WHITE,true);modes.setGravity(Gravity.RIGHT);
        panel.addView(modes);
        LinearLayout modeRow=row();
        String[] labels={"طبيعي","الأوردة","R−G","أخضر","ملون"};
        modeButtons=new Button[5];
        for(int i=0;i<labels.length;i++){
            final int selection=i;
            modeButtons[i]=button(labels[i]);
            addWeighted(modeRow,modeButtons[i],43);
            modeButtons[i].setOnClickListener(v->{mode=selection;updateButtonColors();applySettings();});
        }
        panel.addView(modeRow);updateButtonColors();

        sensitivityLabel=text("الحساسية 45%",12,Color.WHITE,false);sensitivityLabel.setGravity(Gravity.RIGHT);
        panel.addView(sensitivityLabel);
        slider(panel,sensitivity,v->{sensitivity=v;sensitivityLabel.setText("الحساسية "+v+"%");applySettings();});

        contrastLabel=text("قوة التباين 35%",12,Color.WHITE,false);contrastLabel.setGravity(Gravity.RIGHT);
        panel.addView(contrastLabel);
        slider(panel,contrast,v->{contrast=v;contrastLabel.setText("قوة التباين "+v+"%");applySettings();});

        brightnessLabel=text("سطوع الصورة 50%",12,Color.WHITE,false);brightnessLabel.setGravity(Gravity.RIGHT);
        panel.addView(brightnessLabel);
        slider(panel,brightness,v->{brightness=v;brightnessLabel.setText("سطوع الصورة "+v+"%");applySettings();});

        LinearLayout quick=row();
        torchButton=button("فلاش: إيقاف");
        reverseButton=button("عكس");
        compareButton=button("مقارنة");
        freezeButton=button("تجميد");
        Button focus=button("تركيز");
        addWeighted(quick,torchButton,43);
        addWeighted(quick,reverseButton,43);
        addWeighted(quick,freezeButton,43);
        addWeighted(quick,compareButton,43);
        addWeighted(quick,focus,43);
        panel.addView(quick);
        torchButton.setOnClickListener(v->toggleTorch());
        reverseButton.setOnClickListener(v->{
            reversed=!reversed;
            reverseButton.setBackground(background(reversed?Color.rgb(0,123,151):Color.rgb(28,49,72),11));
            applySettings();
        });
        freezeButton.setOnClickListener(v->{
            frozen=!frozen;freezeButton.setText(frozen?"متابعة":"تجميد");applySettings();
        });
        compareButton.setOnClickListener(v->{
            comparison=!comparison;
            compareButton.setText(comparison?"إلغاء المقارنة":"مقارنة");
            compareButton.setBackground(background(comparison?Color.rgb(0,123,151):Color.rgb(28,49,72),11));
            applySettings();
        });
        focus.setOnClickListener(v->triggerFocus());

        LinearLayout exposureRow=row();exposureRow.setGravity(Gravity.CENTER_VERTICAL);
        Button minus=button("EV −");Button plus=button("EV +");
        exposureLabel=text("EV 0.0",13,CYAN,true);exposureLabel.setGravity(Gravity.CENTER);
        exposureRow.addView(minus,new LinearLayout.LayoutParams(dp(76),dp(38)));
        exposureRow.addView(exposureLabel,new LinearLayout.LayoutParams(0,dp(38),1));
        exposureRow.addView(plus,new LinearLayout.LayoutParams(dp(76),dp(38)));
        minus.setOnClickListener(v->changeExposure(-1));
        plus.setOnClickListener(v->changeExposure(1));
        panel.addView(exposureRow);

        status=text("تهيئة الكاميرا...",11,Color.rgb(177,205,219),false);
        status.setGravity(Gravity.CENTER);
        panel.addView(status);

        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        p.setMargins(dp(8),0,dp(8),dp(9));
        frame.addView(panel,p);
        applySettings();
        return frame;
    }
    private void applySettings(){
        if(processor!=null)processor.setSettings(mode,sensitivity,contrast,brightness,reversed,frozen,comparison);
    }
    private void updateStatus(String text){runOnUiThread(()->{if(status!=null)status.setText(text);});}
    @Override public void onFps(int fps){updateStatus("GPU • "+fps+" FPS • بدون إنترنت");}
    @Override public void onRenderError(String error){updateStatus("معالجة الصورة: "+error);}
    @Override public void onCameraSurface(SurfaceTexture texture){
        runOnUiThread(()->{
            if(!resumed)return;
            if(cameraTexture!=null && cameraTexture!=texture)closeCamera();
            cameraTexture=texture;
            openCamera();
        });
    }

    @Override protected void onResume(){
        super.onResume();
        resumed=true;
        cameraThread=new HandlerThread("vien-camera");
        cameraThread.start();
        cameraHandler=new Handler(cameraThread.getLooper());
        glView.onResume();
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){
            if(cameraTexture!=null)openCamera();
        }else requestPermissions(new String[]{Manifest.permission.CAMERA},27);
    }
    @Override protected void onPause(){
        resumed=false;
        closeCamera();
        glView.onPause();
        if(cameraThread!=null){cameraThread.quitSafely();cameraThread=null;cameraHandler=null;}
        cameraTexture=null;
        super.onPause();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==27 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED){
            if(resumed)openCamera();
        }else if(code==27)updateStatus("اسمح باستخدام الكاميرا من إعدادات Android");
    }
    private void openCamera(){
        if(!resumed || cameraTexture==null || cameraHandler==null || device!=null || opening)return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            manager=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chosen=null;
            cameraId=null;
            int best=-1000;
            for(String id:manager.getCameraIdList()){
                CameraCharacteristics c=manager.getCameraCharacteristics(id);
                Integer facing=c.get(CameraCharacteristics.LENS_FACING);
                if(facing==null || facing!=CameraCharacteristics.LENS_FACING_BACK)continue;
                StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if(map==null || map.getOutputSizes(SurfaceTexture.class)==null)continue;
                Boolean flash=c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                float[] focals=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                float focal=(focals!=null&&focals.length>0)?focals[0]:0;
                int score=Boolean.TRUE.equals(flash)?100:0;
                score+=(focal>=3f && focal<=8f)?35:0;
                if(id.equals("0"))score+=8;
                if(score>best){best=score;chosen=c;cameraId=id;}
            }
            if(chosen==null){updateStatus("لا توجد كاميرا خلفية مناسبة");return;}
            flashAvailable=Boolean.TRUE.equals(chosen.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            final Integer sensor=chosen.get(CameraCharacteristics.SENSOR_ORIENTATION);
            rotateDegrees=sensor==null?90:sensor;
            processor.setSensorOrientation(rotateDegrees);
            StreamConfigurationMap map=chosen.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes=map.getOutputSizes(SurfaceTexture.class);
            Size preferred=sizes[0];long bestDiff=Long.MAX_VALUE;
            for(Size s:sizes){
                if(s.getWidth()<640 || s.getHeight()<360 || s.getWidth()>1920)continue;
                long diff=Math.abs((long)s.getWidth()*s.getHeight()-1280L*720L);
                if(Math.abs(s.getWidth()*9-s.getHeight()*16)>64)diff+=5000000;
                if(diff<bestDiff){bestDiff=diff;preferred=s;}
            }
            cameraTexture.setDefaultBufferSize(preferred.getWidth(),preferred.getHeight());
            processor.setInputSize(preferred.getWidth(),preferred.getHeight());
            outputSurface=new Surface(cameraTexture);
            Range<Integer> ev=chosen.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if(ev!=null)exposureRange=ev;
            Rational step=chosen.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if(step!=null && step.floatValue()>0)exposureStep=step.floatValue();
            Range<Integer>[] fps=chosen.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            fpsRange=null;
            if(fps!=null){
                int bestFps=9999;
                for(Range<Integer> r:fps){
                    if(r.getUpper()<24 || r.getLower()>30)continue;
                    int score=Math.abs(r.getUpper()-30)*20+Math.abs(r.getLower()-15);
                    if(score<bestFps){bestFps=score;fpsRange=r;}
                }
            }
            opening=true;
            manager.openCamera(cameraId,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice cam){
                    opening=false;
                    if(!resumed){cam.close();return;}
                    device=cam;startCameraSession();
                }
                @Override public void onDisconnected(CameraDevice cam){
                    cam.close();if(device==cam)device=null;opening=false;
                    updateStatus("تم فصل الكاميرا");
                }
                @Override public void onError(CameraDevice cam,int error){
                    cam.close();if(device==cam)device=null;opening=false;
                    updateStatus("خطأ تشغيل الكاميرا: "+error);
                }
            },cameraHandler);
        }catch(Exception e){opening=false;updateStatus("تعذر فتح الكاميرا: "+e.getMessage());}
    }
    private void startCameraSession(){
        if(device==null || outputSurface==null)return;
        try{
            request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(outputSurface);
            request.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
            request.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
            request.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if(fpsRange!=null)request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fpsRange);
            device.createCaptureSession(Collections.singletonList(outputSurface),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession configured){
                    if(!resumed){configured.close();return;}
                    session=configured;
                    setRequest();
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured){
                    updateStatus("تعذر تشغيل تدفق الفيديو");
                }
            },cameraHandler);
        }catch(Exception e){updateStatus("تعذر بدء الكاميرا: "+e.getMessage());}
    }
    private final CameraCaptureSession.CaptureCallback feedback=new CameraCaptureSession.CaptureCallback(){
        @Override public void onCaptureFailed(CameraCaptureSession sess,CaptureRequest r,android.hardware.camera2.CaptureFailure failure){
            updateStatus("فشل طلب الكاميرا: "+failure.getReason());
        }
    };
    private void setRequest(){
        if(cameraHandler==null)return;
        cameraHandler.post(()->{
            if(request==null || session==null)return;
            try{
                request.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
                request.set(CaptureRequest.FLASH_MODE,flashAvailable&&torchRequested?
                    CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);
                request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,exposure);
                session.setRepeatingRequest(request.build(),feedback,cameraHandler);
                runOnUiThread(()->{
                    if(torchButton!=null){
                        torchButton.setText(torchRequested?"فلاش: تشغيل":"فلاش: إيقاف");
                        torchButton.setBackground(background(torchRequested?Color.rgb(0,123,151):Color.rgb(28,49,72),11));
                    }
                });
            }catch(Exception e){
                if(torchRequested){
                    torchRequested=false;
                    runOnUiThread(()->{torchButton.setText("فلاش: فشل");});
                }
                updateStatus("فشل التحكم بالفلاش/التعريض: "+e.getMessage());
            }
        });
    }
    private void toggleTorch(){
        if(!flashAvailable){Toast.makeText(this,"الفلاش غير متاح للكاميرا المحددة",Toast.LENGTH_SHORT).show();return;}
        if(session==null){Toast.makeText(this,"الكاميرا لم تبدأ بعد",Toast.LENGTH_SHORT).show();return;}
        torchRequested=!torchRequested;
        setRequest();
    }
    private void triggerFocus(){
        if(cameraHandler==null)return;
        cameraHandler.post(()->{
            if(request==null||session==null)return;
            try{
                request.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(request.build(),null,cameraHandler);
                request.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                setRequest();
            }catch(Exception e){updateStatus("تعذر إعادة التركيز");}
        });
    }
    private void changeExposure(int delta){
        int amount=Math.max(1,Math.round(0.5f/Math.max(0.01f,exposureStep)));
        exposure=Math.max(exposureRange.getLower(),Math.min(exposureRange.getUpper(),exposure+delta*amount));
        exposureLabel.setText(String.format(Locale.US,"EV %+.1f",exposure*exposureStep));
        setRequest();
    }
    private void closeCamera(){
        torchRequested=false;
        try{if(session!=null)session.close();}catch(Exception ignored){}
        session=null;
        try{if(device!=null)device.close();}catch(Exception ignored){}
        device=null;
        try{if(outputSurface!=null)outputSurface.release();}catch(Exception ignored){}
        outputSurface=null;
        request=null;opening=false;
    }
}
