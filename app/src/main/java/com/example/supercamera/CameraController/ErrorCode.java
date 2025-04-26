package com.example.supercamera.CameraController;

import java.util.HashMap;
import java.util.Map;

public class ErrorCode {
    public static final int ERROR_CAMERA_IN_USE = 0x1001;
    public static final int ERROR_CAMERA_DEVICE = 0x1002;
    public static final int ERROR_CAMERA_SERVICE = 0x1003;
    public static final int ERROR_CAMERA_DISABLED = 0x1004;
    public static final int ERROR_SURFACE_NotAvailable = 0x1005;
    public static final int ERROR_Param_Illegal = 0x1006;
    public static final int ERROR_CAMERA_Configure = 0x1007;
    public static final int ERROR_OpenCamera = 0x1008;
    public static final int ERROR_StopCamera = 0x1009;
    public static final Map<Integer, String> errorCodeMap = new HashMap<>();

    static {
        errorCodeMap.put(0x1001, "ERROR_CAMERA_IN_USE");
        errorCodeMap.put(0x1002, "ERROR_CAMERA_DEVICE");
        errorCodeMap.put(0x1003, "ERROR_CAMERA_SERVICE");
        errorCodeMap.put(0x1004, "ERROR_CAMERA_DISABLED");
        errorCodeMap.put(0x1005, "ERROR_SURFACE_NotAvailable");
        errorCodeMap.put(0x1006, "ERROR_Param_Illegal");
        errorCodeMap.put(0x1007, "ERROR_CAMERA_Configure");
        errorCodeMap.put(0x1008, "ERROR_OpenCamera");
        errorCodeMap.put(0x1009, "ERROR_StopCamera");
    }
}
