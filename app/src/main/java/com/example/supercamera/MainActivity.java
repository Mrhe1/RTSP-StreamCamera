package com.example.supercamera;

import static com.example.supercamera.CameraController.CameraConfig.Camera_Facing_BACK;
import static com.example.supercamera.CameraController.CameraConfig.Stab_OFF;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import timber.log.Timber;

import android.view.TextureView;
import android.view.View;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Date;
import android.Manifest;
import android.widget.Button;

import com.example.supercamera.MyException.MyException;
import com.example.supercamera.StreamPusher.PushStats.PushStatsInfo;
import com.example.supercamera.VideoWorkflow.VideoWorkflow;
import com.example.supercamera.VideoWorkflow.VideoWorkflowImpl;
import com.example.supercamera.VideoWorkflow.WorkflowConfig;
import com.example.supercamera.VideoWorkflow.WorkflowListener;

/////////////////////////////////////////////注意要求push与record帧率一致！！！！#####
public class MainActivity extends AppCompatActivity {
    private Button btnStart;
    private Button btnStop;
    private TextureView textureView;
    private final String TAG = "MainActivity";
    private AtomicBoolean isPermitted = new AtomicBoolean(false);
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private VideoWorkflow workflow;
    private final WorkflowListener listener = new WorkflowListener() {
        @Override
        public void onStart(Size previewSize, Size recordSize, int fps, int stabMode) {
            Timber.tag(TAG).i("工作流启动成功");
        }

        @Override
        public void onStop() {
            Timber.tag(TAG).i("工作流停止成功");
            runOnUiThread(() -> {
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
            });
        }

        @Override
        public void onError(MyException e) {
            Timber.tag(TAG).e(e,"工作流出错：%s，%s；package：%s,code:%s",
                                e.getCodeString(), e.getMessage(),
                                e.getErrorPackage(), e.getCode());
            runOnUiThread(() -> {
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
            });
        }

        @Override
        public void onStatistics(PushStatsInfo stats) {
            String msg = String.format("推流统计:码率：%dkbps；丢包率：%.2f%%；RTT：%dms；总延迟：%dms",
                    stats.currentBitrate, stats.pushFailureRate * 100,
                    stats.rtt, stats.totalLatency);
            Timber.tag(TAG).i(msg);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);

        isPermitted.set(checkCameraPermission());//权限检查
        if (!isPermitted.get()) {
            requestCameraPermission();
        }

        // 初始化按钮
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        // 设置按钮点击监听
        btnStart.setOnClickListener(v -> handleStart());
        btnStop.setOnClickListener(v -> handleStop());

        textureView = findViewById(R.id.textureView);
        textureView.setVisibility(View.VISIBLE); // 立即可见

        // 初始化按钮状态
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);

        workflow = new VideoWorkflowImpl(this, textureView);
        workflow.setStreamListener(listener);

        Executors.newSingleThreadExecutor().submit(() -> {
            Timber.tag(TAG).i("初始化完成");
           Stair  stair = new Stair();
           Timber.tag(TAG).i("初始化");
        });
    }

    private void handleStart()
    {
        // 初始化推流参数
        Size pushSize = new Size(1280,720);
        Size recordSize = new Size(2560,1440);
        int fps = 30;
        int pushBitrate = 1500;//单位kbps
        int stabilizationMode = Stab_OFF;//防抖
        int record_bitrate = 5000;//单位kbps
        int record_fps =30;
        String push_Url = "rtsp://47.109.146.122:1521/live/stream";// rtsp://47.109.148.245:1521/live/stream

        if(!isPermitted.get()) {
            requestCameraPermission();
            Timber.tag(TAG).e("权限被拒，工作流无法开始");return;
        }

        try{
            String path = generateUniqueFileName(this);
            workflow.configure(new WorkflowConfig.Builder(pushSize, recordSize, push_Url, path)
                            .setBitrateKbps(pushBitrate, record_bitrate)
                            .setCameraFacing(Camera_Facing_BACK)
                            .setEncoderFormat(MediaFormat.MIMETYPE_VIDEO_AVC)
                            .setFps(fps)
                            .setIFrameInterval(1)
                            .setStabilizationMode(stabilizationMode)
                            .build());
            workflow.start();

            runOnUiThread(() -> {
                btnStart.setEnabled(false);
                btnStop.setEnabled(true);
            });
        } catch (MyException e) {
            Timber.tag(TAG).e(e, "开始工作流出错");
        }
    }

    private void handleStop()
    {
        try {
            workflow.stop();
        } catch (Exception e) {
            Timber.tag(TAG).e("工作流停止出错：%s",e.getMessage());
        }
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        //判断请求码是否匹配
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {

            //检查结果数组是否非空
            if (grantResults.length > 0) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    isPermitted.set(true);// 权限通过
                    Timber.tag(TAG).i("camera权限通过");
                } else {
                    Timber.tag(TAG).i("camera权限被拒");// 权限被拒
                }
            }
        }
    }

    public static String generateUniqueFileName(Context context) {
        // 获取DCIM目录下的自定义文件夹
        File recordsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);

        //获取当前日期字符串
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        String dateStr = dateFormat.format(new Date());
        //查找当天最大序号
        int maxNumber = 0;
        Pattern pattern = Pattern.compile("^supercamera_record-"+dateStr+"_#(\\d{3})\\.mp4$");
        File[] files = recordsDir.listFiles();
        if (files != null) {
            for (File file : files) {
                try {
                    Matcher matcher = pattern.matcher(file.getName());
                    if (matcher.find()) {
                        int num = Integer.parseInt(matcher.group(1));
                        maxNumber = Math.max(maxNumber, num);
                    }
                } catch (NumberFormatException e) {
                    Timber.e(e, "Invalid file number format");
                }
            }
        }
        //生成新序号（三位数格式）
        String newNumber = String.format(Locale.CHINA, "%03d", maxNumber + 1);
        //组合完整路径
        return new File(recordsDir,
                "supercamera_record-" + dateStr + "_#" + newNumber + ".mp4"
        ).getAbsolutePath();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        workflow.destroy();
        super.onDestroy();
    }
}