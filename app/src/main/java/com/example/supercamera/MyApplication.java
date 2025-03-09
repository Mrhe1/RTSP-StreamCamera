package com.example.supercamera;

import android.app.Application;
import com.example.supercamera.BuildConfig;
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
    }

    private void initLoggingSystem() {
        if (BuildConfig.DEBUG) {
            Timber.plant(new DebugTree());
        } else {
            Timber.plant(new ReleaseTree());
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