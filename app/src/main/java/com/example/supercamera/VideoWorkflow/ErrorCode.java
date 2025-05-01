package com.example.supercamera.VideoWorkflow;

import java.util.HashMap;
import java.util.Map;

public class ErrorCode {
    public static final int ERROR_Workflow_CONFIG = 0x1001;
    public static final int ERROR_Workflow_START = 0x1002;
    public static final int ERROR_Workflow_STOP = 0x1003;
    public static final int ERROR_Workflow = 0x1004;
    public static final int ERROR_Recorder = 0x1005;
    public static final int ERROR_Camera = 0x1006;
    public static final int ERROR_Streamer = 0x1007;
    public static final int Start_TimeOUT = 0x1008;
    public static final Map<Integer, String> errorCodeMap = new HashMap<>();

    static {
        errorCodeMap.put(0x1001, "ERROR_Workflow_CONFIG");
        errorCodeMap.put(0x1002, "ERROR_Workflow_START");
        errorCodeMap.put(0x1003, "ERROR_Workflow_STOP");
        errorCodeMap.put(0x1004, "ERROR_Workflow");
        errorCodeMap.put(0x1005, "ERROR_Recorder");
        errorCodeMap.put(0x1006, "ERROR_Camera");
        errorCodeMap.put(0x1007, "ERROR_Streamer");
        errorCodeMap.put(0x1008, "Start_TimeOUT");
    }
}
