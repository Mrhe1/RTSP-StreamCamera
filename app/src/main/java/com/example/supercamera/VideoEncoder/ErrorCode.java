package com.example.supercamera.VideoEncoder;

import java.util.HashMap;
import java.util.Map;

public class ErrorCode {
    static final int ERROR_CODEC_CONFIG = 0x1001;
    static final int ERROR_CODEC_START = 0x1002;
    static final int ERROR_CODEC_STOP = 0x1003;
    static final int ERROR_CODEC = 0x1004;
    public static final Map<Integer, String> errorCodeMap = new HashMap<>();

    public ErrorCode () {
        errorCodeMap.put(0x1001, "ERROR_CODEC_CONFIG");
        errorCodeMap.put(0x1002, "ERROR_CODEC_START");
        errorCodeMap.put(0x1003, "ERROR_CODEC_STOP");
        errorCodeMap.put(0x1004, "ERROR_CODEC");
    }

}
