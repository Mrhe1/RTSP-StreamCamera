package com.example.supercamera;

import android.app.Application;
import com.example.supercamera.BuildConfig;

import com.alivc.live.pusher.AlivcLiveBase;
import com.alivc.live.pusher.AlivcLiveBaseListener;
import com.alivc.live.pusher.AlivcLivePushLogLevel;
import com.alivc.live.pusher.AlivcLivePushConstants;
import timber.log.Timber;
import timber.log.Timber.DebugTree;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // 优先初始化日志系统
        initLoggingSystem();

        // 初始化阿里云推流 SDK
        initAliyunLivePusherSDK();
    }

    private void initLoggingSystem() {
        if (BuildConfig.DEBUG) {
            Timber.plant(new timber.log.Timber.DebugTree());
        } else {
            Timber.plant(new ReleaseTree());
        }
    }

    private void initAliyunLivePusherSDK() {

        AlivcLiveBase.setListener(new AlivcLiveBaseListener() {
            //@Override
            public void onLicenceCheck(AlivcLivePushConstants.AlivcLiveLicenseCheckResultCode result, String reason)
            {
                if(result != AlivcLivePushConstants.AlivcLiveLicenseCheckResultCode.AlivcLiveLicenseCheckResultCodeSuccess)
                {
                    Timber.e("阿里云SDK初始化失败:" + result + "," + reason);
                    System.exit(0);
                }
                else{
                    Timber.i("阿里云sdk注册成功");
                }
            }
        });

        try {
            // 先配置日志，再初始化 SDK
            AlivcLiveBase.setLogLevel(AlivcLivePushLogLevel.AlivcLivePushLogLevelDebug);
            AlivcLiveBase.setLogDirPath(getExternalFilesDir(null).getAbsolutePath(), 65536);

            AlivcLiveBase.registerSDK();

        } catch (Exception e) {
            Timber.e(e, "阿里云SDK初始化失败");
        }
    }


    private class ReleaseTree extends Timber.Tree {
        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            try {
                String logEntry = String.format("[%s] %s: %s\n",
                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()),
                        tag != null ? tag : "SuperCamera",
                        message);

                File logFile = new File(getExternalFilesDir(null), "logs/sdk.log");
                // 确保日志目录存在
                if (!logFile.getParentFile().exists()) {
                    logFile.getParentFile().mkdirs();
                }

                try (FileWriter writer = new FileWriter(logFile, true)) {
                    writer.write(logEntry);
                    if (t != null) {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        t.printStackTrace(pw);
                        pw.flush();
                        writer.write(sw.toString());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } catch (Exception e) {
                // 静默处理日志错误
            }
        }
    }
}