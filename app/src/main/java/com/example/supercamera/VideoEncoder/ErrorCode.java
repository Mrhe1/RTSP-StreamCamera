package com.example.supercamera.VideoEncoder;

import java.util.HashMap;
import java.util.Map;

public class ErrorCode {
    public static final int ERROR_CODEC_CONFIG = 0x1001;
    public static final int ERROR_CODEC_START = 0x1002;
    public static final int ERROR_CODEC_STOP = 0x1003;
    public static final int ERROR_CODEC = 0x1004;
    public static final Map<Integer, String> errorCodeMap = new HashMap<>();

    static {
        errorCodeMap.put(0x1001, "ERROR_CODEC_CONFIG");
        errorCodeMap.put(0x1002, "ERROR_CODEC_START");
        errorCodeMap.put(0x1003, "ERROR_CODEC_STOP");
        errorCodeMap.put(0x1004, "ERROR_CODEC");
    }
}
