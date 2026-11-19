package online.vienfinder.app;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Camera2 -> external OES texture -> downsampled GPU texture -> GLSL vein enhancement.
 * Camera pixels NEVER go through Java/CPU or leave the phone.
 */
public final class VeinRenderer implements GLSurfaceView.Renderer {
    public interface Listener {
        void onCameraSurface(SurfaceTexture texture);
        void onFps(int frames);
        void onRenderError(String error);
    }
    private final GLSurfaceView view;
    private final Listener listener;
    private SurfaceTexture surfaceTexture;
    private final AtomicBoolean newFrame = new AtomicBoolean(false);
    private final float[] transform = new float[16];
    private int oesTexture, frameTexture, framebuffer, inputProgram, outputProgram;
    private int screenWidth=720,screenHeight=1280;
    private int frameWidth=640,frameHeight=360;
    private volatile int inputWidth=1280, inputHeight=720;
    private volatile int sensorOrientation=90;
    private volatile boolean freeze=false, reverse=false;
    private volatile int mode=1;
    private volatile float sensitivity=0.65f, contrast=0.60f, brightness=0.50f;
    private boolean hasFrame=false;
    private long lastFpsTime=0L;
    private int fpsFrames=0;
    private final FloatBuffer quad;
    private final float[] quadVertices={
        -1f,-1f,0f,0f,
         1f,-1f,1f,0f,
        -1f, 1f,0f,1f,
         1f, 1f,1f,1f
    };
    private static final String VS=
        "attribute vec2 aPosition;attribute vec2 aUv;varying vec2 vUv;"+
        "void main(){gl_Position=vec4(aPosition,0.,1.);vUv=aUv;}";

    private static final String CAMERA_FS=
        "#extension GL_OES_EGL_image_external : require\n"+
        "precision mediump float;varying vec2 vUv;uniform samplerExternalOES uCamera;uniform mat4 uMatrix;"+
        "void main(){vec2 p=(uMatrix*vec4(vUv,0.,1.)).xy;gl_FragColor=texture2D(uCamera,p);}";

    private static final String PROCESS_FS=
        "precision highp float;varying vec2 vUv;uniform sampler2D uFrame;"+
        "uniform vec2 uPixel;uniform vec2 uAspect;uniform float uSensitivity;"+
        "uniform float uContrast;uniform float uBrightness;uniform float uRotate;"+
        "uniform float uReverse;uniform int uMode;"+
        "vec3 sampleAt(vec2 p){return texture2D(uFrame,clamp(p,vec2(0.003),vec2(0.997))).rgb;}"+
        "float chroma(vec3 c){return (c.r-0.82*c.g)/(c.r+c.g+0.20);}"+
        "float lum(vec3 c){return dot(c,vec3(0.30,0.59,0.11));}"+
        "void main(){"+
        // Screen UV -> camera-landscape UV, with center crop respecting aspect ratio.
        "vec2 p=vUv;"+
        "float portraitAspect=uAspect.y/uAspect.x;"+
        "float ratio=uAspect.x/uAspect.y;"+
        "p=vec2(0.5)+(p-vec2(0.5))*vec2(min(1.,ratio/portraitAspect),min(1.,portraitAspect/ratio));"+
        "vec2 uv; if(uRotate<0.5)uv=p;else if(uRotate<1.5)uv=vec2(p.y,1.-p.x);"+
        "else if(uRotate<2.5)uv=vec2(1.-p.x,1.-p.y);else uv=vec2(1.-p.y,p.x);"+
        "vec3 original=sampleAt(uv);"+
        "if(uMode==0){gl_FragColor=vec4(original,1.);return;}"+
        // 3x3 low pass filters remove tiny hairs, sensor noise and pixel-level texture.
        "vec2 d=uPixel*vec2(2.6);"+
        "vec3 small=(sampleAt(uv)*4.0+"+
        "sampleAt(uv+vec2(d.x,0.))+sampleAt(uv-vec2(d.x,0.))+"+
        "sampleAt(uv+vec2(0.,d.y))+sampleAt(uv-vec2(0.,d.y))+"+
        "sampleAt(uv+d)+sampleAt(uv-d)+"+
        "sampleAt(uv+vec2(d.x,-d.y))+sampleAt(uv+vec2(-d.x,d.y)))/12.0;"+
        // Larger surrounding tissue level: smooth, local illumination normalization.
        "vec2 q=uPixel*(11.0+uSensitivity*6.0);"+
        "vec3 broad=(sampleAt(uv+vec2(q.x,0.))+sampleAt(uv-vec2(q.x,0.))+"+
        "sampleAt(uv+vec2(0.,q.y))+sampleAt(uv-vec2(0.,q.y))+"+
        "sampleAt(uv+q)+sampleAt(uv-q)+"+
        "sampleAt(uv+vec2(q.x,-q.y))+sampleAt(uv+vec2(-q.x,q.y)))*0.125;"+
        "float spectral=(chroma(small)-chroma(broad));"+
        "float greenContrast=(broad.g-small.g);"+
        "float signal=(spectral*1.90+greenContrast*0.23);"+
        "signal*=mix(1.,-1.,uReverse);"+
        // Smooth contrast rather than binary cyan thresholds. Avoid clipped cyan speckle.
        "float detail=lum(small)-lum(broad);"+
        "float gain=3.0+uContrast*11.5;"+
        "float shade=0.70+(uBrightness-0.5)*0.50;"+
        "float vein=clamp(signal*gain*(0.9+uSensitivity*1.1),-0.52,0.52);"+
        "vec3 color;"+
        "if(uMode==1){"+
        "float gray=clamp(shade+detail*1.1-vein*1.38,0.,1.);"+
        "color=vec3(gray);"+
        "}else if(uMode==2){"+
        "float gray=clamp(0.53+(uBrightness-.5)*.5+spectral*gain*2.8,0.,1.);"+
        "if(uReverse>0.5)gray=1.-gray;"+
        "color=vec3(gray);"+
        "}else if(uMode==3){"+
        "float gray=clamp(shade+detail*1.5+greenContrast*gain*1.7,0.,1.);"+
        "if(uReverse>0.5)gray=1.-gray;"+
        "color=vec3(gray);"+
        "}else{"+
        "float amount=smoothstep(0.018,0.22,max(0.,vein))*0.85;"+
        "color=mix(original,vec3(original.r*0.3,original.g*0.39,original.b*0.63),amount);"+
        "}"+
        "gl_FragColor=vec4(color,1.);"+
        "}";

    public VeinRenderer(GLSurfaceView view, Listener listener){
        this.view=view;
        this.listener=listener;
        quad=ByteBuffer.allocateDirect(quadVertices.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(quadVertices).position(0);
    }
    public void setSettings(int newMode,int strength,int newContrast,int newBrightness,boolean invert,boolean frozen){
        mode=newMode;sensitivity=strength/100f;contrast=newContrast/100f;
        brightness=newBrightness/100f;reverse=invert;freeze=frozen;
        view.requestRender();
    }
    public void setSensorOrientation(int degree){sensorOrientation=degree;view.requestRender();}
    public void setInputSize(int width,int height){
        inputWidth=width;inputHeight=height;
        if(surfaceTexture!=null) surfaceTexture.setDefaultBufferSize(width,height);
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            int[] tex=new int[1];
            GLES20.glGenTextures(1,tex,0);oesTexture=tex[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oesTexture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            inputProgram=program(VS,CAMERA_FS);
            outputProgram=program(VS,PROCESS_FS);
            createFramebuffer();
            hasFrame=false;
            surfaceTexture=new SurfaceTexture(oesTexture);
            surfaceTexture.setDefaultBufferSize(inputWidth,inputHeight);
            surfaceTexture.setOnFrameAvailableListener(t -> {
                newFrame.set(true);
                if (!freeze) view.requestRender();
            });
            listener.onCameraSurface(surfaceTexture);
        } catch (Exception e){
            listener.onRenderError(e.toString());
        }
    }
    private int shader(int type,String source){
        int shader=GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader,source);
        GLES20.glCompileShader(shader);
        int[] result=new int[1];
        GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,result,0);
        if(result[0]==0) throw new RuntimeException("Shader: "+GLES20.glGetShaderInfoLog(shader));
        return shader;
    }
    private int program(String vertex,String fragment){
        int p=GLES20.glCreateProgram();
        int v=shader(GLES20.GL_VERTEX_SHADER,vertex);
        int f=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
        GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);
        GLES20.glLinkProgram(p);
        int[] result=new int[1];
        GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,result,0);
        if(result[0]==0)throw new RuntimeException("Link: "+GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);
        return p;
    }
    private void createFramebuffer(){
        frameWidth=640;frameHeight=360;
        int[] ids=new int[1];
        GLES20.glGenTextures(1,ids,0);frameTexture=ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,frameTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,frameWidth,frameHeight,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
        GLES20.glGenFramebuffers(1,ids,0);framebuffer=ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,frameTexture,0);
        if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new RuntimeException("OpenGL framebuffer incomplete");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
    }
    @Override public void onSurfaceChanged(GL10 gl,int width,int height){
        screenWidth=width;screenHeight=height;
        GLES20.glViewport(0,0,width,height);
    }
    private void setupQuad(int p) {
        int aPosition=GLES20.glGetAttribLocation(p,"aPosition");
        int aUv=GLES20.glGetAttribLocation(p,"aUv");
        quad.position(0);GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition,2,GLES20.GL_FLOAT,false,16,quad);
        quad.position(2);GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glVertexAttribPointer(aUv,2,GLES20.GL_FLOAT,false,16,quad);
    }
    @Override public void onDrawFrame(GL10 gl){
        try{
            if(surfaceTexture!=null && newFrame.getAndSet(false) && !freeze){
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(transform);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);
                GLES20.glViewport(0,0,frameWidth,frameHeight);
                GLES20.glUseProgram(inputProgram);
                setupQuad(inputProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oesTexture);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(inputProgram,"uCamera"),0);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(inputProgram,"uMatrix"),1,false,transform,0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                hasFrame=true;
                fpsFrames++;
                long now=SystemClock.elapsedRealtime();
                if(now-lastFpsTime>=1200){
                    if(lastFpsTime!=0)listener.onFps(Math.round(1000f*fpsFrames/(now-lastFpsTime)));
                    lastFpsTime=now;fpsFrames=0;
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
            GLES20.glViewport(0,0,screenWidth,screenHeight);
            GLES20.glClearColor(0.02f,0.05f,0.09f,1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if(!hasFrame)return;
            GLES20.glUseProgram(outputProgram);
            setupQuad(outputProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,frameTexture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(outputProgram,"uFrame"),0);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(outputProgram,"uPixel"),1f/frameWidth,1f/frameHeight);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(outputProgram,"uAspect"),screenWidth,screenHeight);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(outputProgram,"uSensitivity"),sensitivity);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(outputProgram,"uContrast"),contrast);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(outputProgram,"uBrightness"),brightness);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(outputProgram,"uReverse"),reverse?1f:0f);
            int rotation=((sensorOrientation%360)+360)%360;
            GLES20.glUniform1f(GLES20.glGetUniformLocation(outputProgram,"uRotate"),rotation/90f);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(outputProgram,"uMode"),mode);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        }catch(Exception e){
            listener.onRenderError(e.toString());
        }
    }
}
