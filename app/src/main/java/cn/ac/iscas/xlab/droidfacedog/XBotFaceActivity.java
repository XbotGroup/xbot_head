package cn.ac.iscas.xlab.droidfacedog;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.FaceDetector;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.util.Size;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import com.iflytek.cloud.SpeechConstant;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.SpeechSynthesizer;
import com.iflytek.cloud.SpeechUtility;
import com.iflytek.cloud.SynthesizerListener;
import com.iflytek.cloud.util.ResourceUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import cn.ac.iscas.xlab.droidfacedog.config.Config;
import cn.ac.iscas.xlab.droidfacedog.entity.FaceResult;
import cn.ac.iscas.xlab.droidfacedog.entity.RobotStatus;
import cn.ac.iscas.xlab.droidfacedog.entity.TtsStatus;
import cn.ac.iscas.xlab.droidfacedog.network.YoutuConnection;
import cn.ac.iscas.xlab.droidfacedog.util.ImageUtils;
import cn.ac.iscas.xlab.droidfacedog.util.Util;
import de.greenrobot.event.EventBus;

/**
 * Created by lisongting on 2017/6/16.
 */

public class XBotFaceActivity extends AppCompatActivity{
    public static final String TAG = "XBotFaceActivity";
    public static final int STATE_IDLE = 0;//待机状态
    public static final int STATE_DETECTED = 1;//人脸检测完毕
    public static final int STATE_IDENTIFIED = 2;//人脸识别成功
    public static final int CONN_ROS_SERVER_SUCCESS = 0x11;
    public static final int CONN_ROS_SERVER_ERROR = 0x12;
    public static final int HANDLER_PLAY_TTS = 0x13;
    public static final String TTS_UNREGISTERED_USER = "0000000000";
    public static final int MAX_FACE_COUNT = 3;

    private String mCameraID;//摄像头Id：0代表手机背面的摄像头  1代表朝向用户的摄像头
    private CameraCaptureSession mCameraCaptureSession;
    private TextureView mTextureView;
    private ImageView mStateImageView;
    private SurfaceTexture mSurfaceTexture;
    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private Size mPreviewSize;
    private FaceOverlayView mFaceOverlayView;
    private RecyclerView mRecyclerView;
    private AlertDialog mWaitDialog;
    private ImagePreviewAdapter mImagePreviewAdapter;
    private Handler mMainHandler;

    //用于定时识别人脸的Timer
    private Timer mDetectTimer;
    //用于定时连接至Ros服务器的Timer
    private Timer mRosConnectionTimer;
    //用于定时发布TTS状态的Timer
    private Timer mPublishTopicTimer;
    private TimerTask mDetectFaceTask;

    //用来存放检测到的人脸
    private FaceDetector.Face[] mDetectedFaces;
    private FaceResult[] mFaces;
    private FaceResult[] mPreviousFaces;
    private FaceDetector mFaceDetector;

    //要发送给人脸识别服务器的人脸bitmap
    private Bitmap mFaceBitmap;
    private YoutuConnection youtuConnection;
    //这个boolean表示将人脸发送给服务器后，当前是否正在等待优图服务器返回识别结果
    private boolean isWaitingResult = false;
    private boolean isEnableRos = true;
    private boolean hasGreeted = false;

    //识别到的人脸的id（不是注册在服务端的ID）,初始为0。
    private int mPersonId =0;
    private long mTotalFrameCount = 0;
    //比例因子，将检测到的原始人脸图像按此比例缩小，以此可以加快FaceDetect的检测速度
    private double mScale = 0.2;
    private long mLastChangeTime;
    private int mFaceState;

    //用来标记每个人脸共有几张图像,key是人脸的id，value是当前采集到的图像张数
    private HashMap<Integer,Integer> mFacesCountMap;

    //RecyclerView中的人脸图像的List
    private ArrayList<Bitmap> mRecyclerViewBitmapList;

    private RosConnectionService.ServiceBinder mServiceProxy;
    //mServiceConnection
    private ServiceConnection mServiceConnection;

    private AudioManager mAudioManager;
    //科大讯飞的语音合成器[需要联网才能使用]
    private SpeechSynthesizer ttsSynthesizer;
    private SynthesizerListener synthesizerListener;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_xbot_face);
        getSupportActionBar().hide();

        mTextureView = (TextureView) findViewById(R.id.id_small_texture_view);
        mStateImageView = (ImageView) findViewById(R.id.id_iv_face_state);
        mRecyclerView = (RecyclerView) findViewById(R.id.id_face_recyclerview);

        initView();

        mFaces = new FaceResult[MAX_FACE_COUNT];
        mPreviousFaces = new FaceResult[MAX_FACE_COUNT];
        mDetectedFaces = new FaceDetector.Face[MAX_FACE_COUNT];
        for (int i = 0; i < MAX_FACE_COUNT; i++) {
            mFaces[i] = new FaceResult();
            mPreviousFaces[i] = new FaceResult();
        }

        mRecyclerViewBitmapList = new ArrayList<>();
        mFacesCountMap = new HashMap<>();
        mAudioManager = new AudioManager(this);
        mAudioManager.loadTts();

        //双用途Handler，一用来接收TimerTask中发回来的Ros连接状态，二用来接收优图的识别结果
        mMainHandler = new Handler(){
            public void handleMessage(Message msg) {
                //如果连接成功
                if (msg.what == CONN_ROS_SERVER_SUCCESS) {
                    if (mWaitDialog.isShowing()) {
                        mWaitDialog.dismiss();
                        mRosConnectionTimer.cancel();
                        Toast.makeText(XBotFaceActivity.this, "连接成功", Toast.LENGTH_SHORT).show();
                        Toast.makeText(XBotFaceActivity.this, "正在进行人脸识别，请稍等", Toast.LENGTH_LONG).show();
                        startPreview();
                        mDetectTimer.schedule(mDetectFaceTask, 0, 200);
                    }
                }else if(msg.what == CONN_ROS_SERVER_ERROR){
                    //Toast.makeText(XBotFace.this, "连接失败，正在重试", Toast.LENGTH_SHORT).show();
                    Log.i(TAG, "Ros连接失败，正在重试");
                } else if (msg.what == HANDLER_PLAY_TTS) {
                    if (msg.arg1 == 0) {
                        updateFaceState(STATE_IDENTIFIED);
                    }
                    Bundle data = msg.getData();
                    String userName = (String) data.get("userId");
                    if (!hasGreeted) {
                        speakOutUser(userName);
                    }
                    hasGreeted = true;
                }
            }
        };
        youtuConnection = new YoutuConnection(XBotFaceActivity.this,mMainHandler);

        initSynthesizer();

        EventBus.getDefault().register(this);

        //创建ServiceConnection对象
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "mServiceConnection--onServiceConnected()");
                mServiceProxy = (RosConnectionService.ServiceBinder) service;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "mServiceConnection--onServiceDisconnected()");
            }
        };
        //绑定RosConnectionService
        Intent intent = new Intent(this, RosConnectionService.class);
        bindService(intent, mServiceConnection, BIND_AUTO_CREATE);
    }

    private void showWaitingDialog() {
        //启动一个对话框提示用户等待，然后连接至Ros服务器
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("请稍等")
                .setMessage("正在连接至Ros服务端")
                .setCancelable(false)
                .setPositiveButton("直接识别人脸", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        isEnableRos = false;
                        mRosConnectionTimer.cancel();
                        Toast.makeText(XBotFaceActivity.this, "正在进行人脸识别，请稍等", Toast.LENGTH_LONG).show();

                        if (mServiceConnection != null) {
                            unbindService(mServiceConnection);
                        }
                        startPreview();
                        mDetectTimer.schedule(mDetectFaceTask, 0, 200);
                    }
                })
                .setNegativeButton("取消连接", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        XBotFaceActivity.this.onBackPressed();
                        mRosConnectionTimer.cancel();
                    }
                });
        mWaitDialog = builder.create();
        mWaitDialog.show();
    }


    @Override
    public void onResume() {
        super.onResume();

        //为SurfaceView设置监听器
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.i(TAG, "TextureView.SurfaceTextureListener -- onSurfaceTextureAvailable()");
                mSurfaceTexture = surface;
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                Log.i(TAG, "TextureView.SurfaceTextureListener -- onSurfaceTextureSizeChanged()");
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                Log.i(TAG, "TextureView.SurfaceTextureListener -- onSurfaceTextureDestroyed()");
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                mTotalFrameCount++;
                //每过400帧数，将isWaitingResult置为false，这样可以避免频繁发送人脸给服务器
                if (mTotalFrameCount % 400 == 0) {
                    isWaitingResult = false;
                }

                Log.i(TAG, "totalFrameCount:" + mTotalFrameCount);
            }
        });

        initCamera();
        //开启三个定时任务
        startTimerTask();

    }

    private CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.i(TAG, "CameraDevice.StateCallback -- onOpened()");
            mCameraDevice = camera;

        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.i(TAG, "CameraDevice.StateCallback -- onDisconnected()");
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.i(TAG, "CameraDevice.StateCallback -- onError()");
        }
    };


    private void initView() {
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(layoutManager);

        updateFaceState(STATE_IDLE);
        mFaceOverlayView = new FaceOverlayView(this);
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.MATCH_PARENT);
        addContentView(mFaceOverlayView,params );
        mFaceOverlayView.setFront(true);
        mFaceOverlayView.setDisplayOrientation(getWindowManager().getDefaultDisplay().getRotation());

        showWaitingDialog();
    }

    private void startTimerTask() {
        //启动定时任务，每3秒种发起一次连接
        //然后将结果发送给Handler，
        mRosConnectionTimer = new Timer();
        mRosConnectionTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (mServiceProxy != null) {
                    if (mServiceProxy.connect()) {
                        mMainHandler.sendEmptyMessage(CONN_ROS_SERVER_SUCCESS);
                    } else {
                        mMainHandler.sendEmptyMessage(CONN_ROS_SERVER_ERROR);
                    }
                }

            }
        },0,3000);

        mPublishTopicTimer = new Timer();
        mPublishTopicTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (mServiceProxy != null && mAudioManager !=null) {
                    int id = mAudioManager.getCurrentId();
                    boolean isPlaying = mAudioManager.isPlaying();
                    TtsStatus status = new TtsStatus(id,isPlaying);
                    mServiceProxy.publishTtsStatus(status);
                }
            }
        },1000,200);

        mDetectTimer = new Timer();
        //在这个定时任务中，不断的检测界面中的人脸
        mDetectFaceTask = new TimerTask() {
            @Override
            public void run() {
                int rotate = getWindowManager().getDefaultDisplay().getRotation();
                Bitmap face = mTextureView.getBitmap();
                if (face != null) {
                    Log.i(TAG, "Bitmap in mTextureView :" + face.getWidth() + "x" + face.getHeight()+",Config:"+face.getConfig());

                    //原先的bitmap格式是ARGB_8888，以下的步骤是把格式转换为RGB_565
                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    face.compress(Bitmap.CompressFormat.JPEG, 100, bout);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap RGBFace = BitmapFactory.decodeStream(new ByteArrayInputStream(bout.toByteArray()), null, options);

                    Bitmap smallRGBFace = Bitmap.createScaledBitmap(RGBFace, (int)(RGBFace.getWidth()*mScale)
                            , (int)(RGBFace.getHeight()*mScale), false);

                    //创建一个人脸检测器，MAX_FACE参数表示最多识别MAX_FACE张人脸
                    mFaceDetector = new FaceDetector(smallRGBFace.getWidth(), smallRGBFace.getHeight(), MAX_FACE_COUNT);
                    //findFaces中传入的bitmap格式必须为RGB_565
                    int found = mFaceDetector.findFaces(smallRGBFace, mDetectedFaces);

                    Log.i(TAG, "RGBFace:" + RGBFace.getWidth() + "x" + RGBFace.getHeight() + "," + RGBFace.getConfig());
                    Log.i(TAG, "smallRGBFace:" + smallRGBFace.getWidth() + "x" + smallRGBFace.getHeight() + ","
                            + smallRGBFace.getConfig());
                    Log.i(TAG, "found:" + found+" face(s)");

                    for(int i=0;i<MAX_FACE_COUNT;i++) {
                        if (mDetectedFaces[i] == null) {
                            mFaces[i].clear();
                        } else {
                            PointF mid = new PointF();
                            mDetectedFaces[i].getMidPoint(mid);

                            //前面为了方便检测人脸将图片缩小，现在按比例还原
                            mid.x *= 1.0/mScale;
                            mid.y *= 1.0/mScale;
                            Log.i(TAG, mTextureView.getWidth() + "x" + mTextureView.getHeight());
                            Log.i(TAG, "mid pointF:" + mid.x + "," + mid.y);
                            float eyeDistance = mDetectedFaces[i].eyesDistance()*(float)(1.0/mScale);
                            float confidence = mDetectedFaces[i].confidence();
                            float pose = mDetectedFaces[i].pose(FaceDetector.Face.EULER_Y);
                            //mPersonId一开始是0
                            int personId = mPersonId;

                            //预先创建一个人脸矩形区域
                            RectF rectF = ImageUtils.getPreviewFaceRectF(mid, eyeDistance);

                            //如果人脸矩形区域大于一定面积，才采集图像
                            if (rectF.width() * rectF.height() > 90 * 60) {
                                for(int j=0;j<MAX_FACE_COUNT;j++) {
                                    //获取之前的Faces数据
                                    float eyesDisPre = mPreviousFaces[j].eyesDistance();
                                    PointF midPre = new PointF();
                                    mPreviousFaces[j].getMidPoint(midPre);

                                    //在一定区域内检查人脸是否移动过大，超出这个区域。
                                    RectF rectCheck = ImageUtils.getCheckFaceRectF(midPre, eyesDisPre);

                                    //如果没有当前人脸没有超过这个检查区域，说明该ID对应的人脸晃动程度小，则适合采集
                                    if (rectCheck.contains(mid.x, mid.y) && (System.currentTimeMillis() - mPreviousFaces[j].getTime()) < 1000) {
                                        personId = mPreviousFaces[j].getId();
                                        break;
                                    }
                                }

                                if (mPersonId == personId) {
                                    mPersonId++;
                                }
                                mFaces[i].setFace(personId, mid, eyeDistance, confidence, pose, System.currentTimeMillis());
                                mPreviousFaces[i].set(mFaces[i].getId(), mFaces[i].getMidEye(), mFaces[i].eyesDistance(), mFaces[i].getConfidence(), mFaces[i].getPose(), mFaces[i].getTime());
                                if (mFacesCountMap.get(personId) == null) {
                                    mFacesCountMap.put(personId, 0);
                                }else {
                                    int tmpFrameCount = mFacesCountMap.get(personId) + 1;
                                    if (tmpFrameCount < 5) {
                                        mFacesCountMap.put(personId, tmpFrameCount);
                                    }
                                    if (tmpFrameCount == 5) {
                                        mFaceBitmap = ImageUtils.cropFace(mFaces[i], RGBFace,rotate);
                                        if (mFaceBitmap != null) {
                                            mMainHandler.post(new Runnable() {
                                                public void run() {
                                                    mImagePreviewAdapter = new ImagePreviewAdapter(XBotFaceActivity.this, mRecyclerViewBitmapList, new ImagePreviewAdapter.ViewHolder.OnItemClickListener() {
                                                        @Override
                                                        public void onClick(View v, int position) {
                                                            mImagePreviewAdapter.setCheck(position);
                                                            mRecyclerView.setAdapter(mImagePreviewAdapter);
                                                            mImagePreviewAdapter.notifyDataSetChanged();
                                                        }
                                                    });
                                                    if (mImagePreviewAdapter.getItemCount() < 5) {
                                                        mImagePreviewAdapter.add(mFaceBitmap);
                                                        mRecyclerView.setAdapter(mImagePreviewAdapter);
                                                    }
                                                    if (!hasGreeted) {
                                                        updateFaceState(STATE_DETECTED);
                                                    }

                                                }
                                            });
                                            if (!isWaitingResult) {
                                                youtuConnection.sendBitmap(mFaceBitmap);
                                                isWaitingResult = true;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (System.currentTimeMillis() - mLastChangeTime > 10000) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateFaceState(STATE_IDLE);
                        }
                    });
                }
            }
        };
    }
    private void initCamera() {
        mCameraID = "" + CameraCharacteristics.LENS_FACING_BACK;
        mCameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "没有权限", Toast.LENGTH_SHORT).show();
                return;
            }
            mCameraManager.openCamera(mCameraID, cameraStateCallback, null);
        }catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startPreview() {
        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraID);
            StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            int width = mTextureView.getWidth();
            int height = mTextureView.getHeight();

            //设置一个合适的预览尺寸，防止图像拉伸
//            mPreviewSize = getPreferredPreviewSize(configMap.getOutputSizes(SurfaceTexture.class), width, height);
            mPreviewSize = getPreferredPreviewSize(configMap.getOutputSizes(ImageFormat.JPEG), width, height);
            mSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
            Log.i(TAG, "mPreviewSize info:" + mPreviewSize.getWidth() + "x" + mPreviewSize.getHeight());

            Surface surface = new Surface(mSurfaceTexture);

            final CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            if (surface.isValid()) {
                builder.addTarget(surface);
            }
            Log.i(TAG, "mTextureView info:" + mTextureView.getWidth() + "x" + mTextureView.getHeight());

            mFaceOverlayView.setPreviewWidth(mTextureView.getWidth());
            mFaceOverlayView.setPreviewHeight(mTextureView.getHeight());

            mCameraDevice.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.i(TAG, "CameraCaptureSession.StateCallback -- onConfigured()");
                            if (mCameraDevice == null) {
                                return;
                            }
                            mCameraCaptureSession = session;
                            //设置自动对焦
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            //设置自动曝光
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                            //显示预览
                            CaptureRequest captureRequest = builder.build();
                            try {
                                mCameraCaptureSession.setRepeatingRequest(captureRequest, new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                                        super.onCaptureStarted(session, request, timestamp, frameNumber);
                                    }

                                    @Override
                                    public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                                        super.onCaptureProgressed(session, request, partialResult);
                                    }

                                    @Override
                                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                        super.onCaptureCompleted(session, request, result);
                                    }
                                }, null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.i(TAG, "CameraCaptureSession.StateCallback -- onConfigureFailed()");
                        }
                    },null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void speakOutUser(String userId){
        if (mAudioManager.isPlaying())
            return;

        Log.i(TAG, "speakOutUser()");
        StringBuilder text = new StringBuilder();
        text.append("你好，");

        if (userId.equals(TTS_UNREGISTERED_USER)) {
            text.append("游客。");
        } else {
            String name = Util.hexStringToString(userId);
            text.append(name+"。");
        }

        //创建一个监听器
        synthesizerListener = new SynthesizerListener() {
            @Override
            public void onSpeakBegin() {
                Log.i(TAG, "--TTS--onSpeakBegin()--");
            }

            @Override
            public void onBufferProgress(int progress, int beginPos, int endPos,String info) {
                Log.i(TAG, "--TTS--onBufferProgress()--");
            }

            @Override
            public void onSpeakPaused() {
                Log.i(TAG, "--TTS--onSpeakPaused()--");
            }

            @Override
            public void onSpeakResumed() {
                Log.i(TAG, "--TTS--onSpeakResumed()--");
            }

            @Override
            public void onSpeakProgress(int progress,int beginPos,int endPos) {
                Log.i(TAG, "--TTS--onSpeakProgress()--");
            }

            @Override
            public void onCompleted(SpeechError speechError) {
                Log.i(TAG, "--TTS--onCompleted()--");

                //首先播放第0个音频
                mAudioManager.play(0);
            }

            //扩展用接口，由具体业务进行约定。
            @Override
            public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {
                Log.i(TAG, "--TTS--onEvent()--");
            }
        };

        //设置声音文件的缓存。仅支持保存为 pcm 和 wav 格式
        String cacheFileName = getExternalCacheDir() + "/" + userId + ".pcm";
        //如果本地已经有离线缓存，则直接播放离线缓存文件
        if (isCacheExist(cacheFileName)) {
            ttsSynthesizer.setParameter(ResourceUtil.TTS_RES_PATH, cacheFileName);
            ttsSynthesizer.startSpeaking(text.toString(),synthesizerListener);
            Log.i(TAG, "播放离线缓存文件---");
        } else {
            //如果本地没有缓存，则播放在线数据的同时缓存到本地
            ttsSynthesizer.setParameter(SpeechConstant.TTS_AUDIO_PATH, cacheFileName);
            //开始播放
            ttsSynthesizer.startSpeaking(text.toString(),synthesizerListener );
            Log.i(TAG, "离线文件不存在,在线播放---");
        }

    }

    public boolean isCacheExist(String cacheFileName) {
        File f = new File(cacheFileName);
        if (f.exists()) {
            return true;
        } else {
            return false;
        }
    }
    //初始化语音合成器
    public void initSynthesizer() {
        //初始化讯飞TTS引擎
        SpeechUtility.createUtility(this, SpeechConstant.APPID +"="+ Config.APPID);

        //创建 SpeechSynthesizer 对象, 第二个参数：本地合成时传 InitListener，可以为Null
        ttsSynthesizer = SpeechSynthesizer.createSynthesizer(XBotFaceActivity.this, null);

        ttsSynthesizer.setParameter(SpeechConstant.VOICE_NAME, "xiaoyan"); //设置发音人
        ttsSynthesizer.setParameter(SpeechConstant.SPEED, "50");//设置语速
        ttsSynthesizer.setParameter(SpeechConstant.VOLUME, "80");//设置音量，范围 0~100
        ttsSynthesizer.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_CLOUD); //设置云端

    }

    //EventBus的回调，用来接收从Service中发回来的机器人状态
    public void onEvent(RobotStatus status) {
        int locationId = status.getLocationId();
//        boolean isMoving = status.isMoving();
        Log.i(TAG, "RobotStatus:"+status.toString());
        //如果到达了新的位置，并且mAudioManager并没有在播放音频，则开始播放指定id的音频
        if (locationId != mAudioManager.getCurrentId() && !mAudioManager.isPlaying()) {
            mAudioManager.play(locationId);
        }
    }

    private void updateFaceState(int state) {
        mFaceState = state;
        mLastChangeTime = System.currentTimeMillis();
        if (state == STATE_IDLE)
            mStateImageView.setImageResource(R.drawable.idleface);
        else if (state == STATE_DETECTED)
            mStateImageView.setImageResource(R.drawable.detectedface);
        else if (state == STATE_IDENTIFIED)
            mStateImageView.setImageResource(R.drawable.identifiedface);
        else
            Log.i(TAG, "updateFace: STATE ERROR");

    }

    private Size getPreferredPreviewSize(Size[] sizes, int width, int height) {
        List<Size> collectorSizes = new ArrayList<>();
        for (Size option : sizes) {
            //找到长宽都大于指定宽高的size，把这些size放在List中
            if (width > height) {
                if (option.getWidth() > width && option.getHeight() > height) {
                    collectorSizes.add(option);
                }
            } else {
                if (option.getHeight() > width && option.getWidth() > height) {
                    collectorSizes.add(option);
                }
            }
        }
        if (collectorSizes.size() > 0) {
            return Collections.min(collectorSizes, new Comparator<Size>() {
                @Override
                public int compare(Size s1, Size s2) {
                    return Long.signum(s1.getWidth() * s1.getHeight() - s2.getWidth() * s2.getHeight());
                }
            });
        }
        return sizes[0];
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mCameraCaptureSession != null) {
            mCameraCaptureSession.close();
        }
        //取消所有运行中的Timer
        mDetectTimer.cancel();
        mRosConnectionTimer.cancel();
        mPublishTopicTimer.cancel();
    }

    @Override
    public void onDestroy(){
        if (mCameraDevice != null) {
            mCameraDevice.close();
        }

        if (mSurfaceTexture != null) {
            mSurfaceTexture.release();
        }
        if (mImagePreviewAdapter != null) {
            mImagePreviewAdapter.clearAll();
        }
        mAudioManager.releaseMemory();

        ttsSynthesizer.stopSpeaking();
        ttsSynthesizer.destroy();
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
