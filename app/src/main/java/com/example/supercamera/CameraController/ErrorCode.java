package com.example.supercamera.CameraController;

import java.util.HashMap;
import java.util.Map;

public class ErrorCode {
    public static final int ERROR_FFmpeg_CONFIG = 0x1001;
    public static final int ERROR_FFmpeg_START = 0x1002;
    public static final int ERROR_FFmpeg_STOP = 0x1003;
    public static final int ERROR_FFmpeg = 0x1004;
    public static final int ERROR_FFmpeg_Reconnect = 0x1005;  // 重连流发生致命错误
    public static final int ERROR_FFmpeg_ReconnectFail = 0x1006;  // 重连失败
    public static final Map<Integer, String> errorCodeMap = new HashMap<>();

    static {
        errorCodeMap.put(0x1001, "ERROR_FFmpeg_CONFIG");
        errorCodeMap.put(0x1002, "ERROR_FFmpeg_START");
        errorCodeMap.put(0x1003, "ERROR_FFmpeg_STOP");
        errorCodeMap.put(0x1004, "ERROR_FFmpeg");
        errorCodeMap.put(0x1005, "ERROR_FFmpeg_Reconnect");
        errorCodeMap.put(0x1006, "ERROR_FFmpeg_ReconnectFail");
    }
}
