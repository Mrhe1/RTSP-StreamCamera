package com.example.supercamera.VideoStreamer;

import java.util.HashMap;
import java.util.Map;

public class ErrorCode {
    public static final int ERROR_Stream_CONFIG = 0x1001;
    public static final int ERROR_Stream_START = 0x1002;
    public static final int ERROR_Stream_STOP = 0x1003;
    public static final int ERROR_Pusher = 0x1004;
    public static final int ERROR_Codec = 0x1005;
    public static final int ERROR_Pusher_ReconnectFail = 0x1006;  // 重连失败
    public static final int ERROR_Pusher_START = 0x1007;
    public static final Map<Integer, String> errorCodeMap = new HashMap<>();

    static {
        errorCodeMap.put(0x1001, "ERROR_Stream_CONFIG");
        errorCodeMap.put(0x1002, "ERROR_Stream_START");
        errorCodeMap.put(0x1003, "ERROR_Stream_STOP");
        errorCodeMap.put(0x1004, "ERROR_Pusher");
        errorCodeMap.put(0x1005, "ERROR_Codec");
        errorCodeMap.put(0x1006, "ERROR_Pusher_ReconnectFail");
        errorCodeMap.put(0x1007, "ERROR_Pusher_START");
    }
}
