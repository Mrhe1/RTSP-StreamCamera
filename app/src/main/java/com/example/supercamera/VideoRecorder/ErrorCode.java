package com.example.supercamera.VideoRecorder;

import java.util.HashMap;
import java.util.Map;

public class ErrorCode {
    public static final int ERROR_Recorder_CONFIG = 0x1001;
    public static final int ERROR_Recorder_START = 0x1002;
    public static final int ERROR_Recorder_STOP = 0x1003;
    public static final int ERROR_Codec = 0x1004;
    public static final int ERROR_Codec_Start = 0x1005;
    public static final Map<Integer, String> errorCodeMap = new HashMap<>();

    static {
        errorCodeMap.put(0x1001, "ERROR_Recorder_CONFIG");
        errorCodeMap.put(0x1002, "ERROR_Recorder_START");
        errorCodeMap.put(0x1003, "ERROR_Recorder_STOP");
        errorCodeMap.put(0x1004, "ERROR_Codec");
        errorCodeMap.put(0x1005, "ERROR_Codec_Start");
    }
}
