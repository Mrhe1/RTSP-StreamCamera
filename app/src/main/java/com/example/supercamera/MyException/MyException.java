package com.example.supercamera.MyException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MyException extends RuntimeException {
    public static final int ILLEGAL_ARGUMENT = 0x1001;
    public static final int ILLEGAL_STATE = 0x1002;
    public static final int RUNTIME_ERROR = 0x1003;

    private final String errorPackage;
    private final int exceptionType;
    private final List<Integer> code;

    public MyException(String errorPackage, int exceptionType,
                       List<Integer> code, String message, Throwable cause) {
        super(message, cause);

        this.errorPackage = errorPackage;
        this.exceptionType = exceptionType;
        this.code = code;
    }

    public MyException(String errorPackage, int exceptionType,
                       List<Integer> code, String message) {
        super(message);

        this.errorPackage = errorPackage;
        this.exceptionType = exceptionType;
        this.code = code;
    }

    public MyException(String errorPackage, int exceptionType,
                       List<Integer> code, Throwable cause) {
        super(cause);

        this.errorPackage = errorPackage;
        this.exceptionType = exceptionType;
        this.code = code;
    }

    public MyException(String errorPackage, int exceptionType,
                       int newCode, String message, Throwable cause) {
        super(message, cause);

        code = new ArrayList<Integer>();
        code.add(newCode);

        this.errorPackage = errorPackage;
        this.exceptionType = exceptionType;
    }

    public MyException(String errorPackage, int exceptionType,
                       int newCode, String message) {
        super(message);

        code = new ArrayList<Integer>();
        code.add(newCode);

        this.errorPackage = errorPackage;
        this.exceptionType = exceptionType;
    }

    public MyException(String errorPackage, int exceptionType,
                       int newCode, Throwable cause) {
        super(cause);

        code = new ArrayList<Integer>();
        code.add(newCode);

        this.errorPackage = errorPackage;
        this.exceptionType = exceptionType;
    }

    public String getErrorPackage() {
        return errorPackage;
    }

    public int getExceptionType() {
        return exceptionType;
    }

    public List<Integer> getCode() {
        return code;
    }

    public String getCodeString() {
        return getCodeString(errorPackage, code.get(0));
    }

    private static String getCodeString(String errorPackage, int code) {
        try {
            // 加载类
            Class<?> clazz = Class.forName(errorPackage + ".ErrorCode");
            Field field = clazz.getField("errorCodeMap");
            Map<Integer, String> codeMap = (Map<Integer, String>) field.get(null);

            // 查询错误码
            return codeMap.getOrDefault(code,
                    "Unknown error code: 0x" + Integer.toHexString(code).toUpperCase());

        } catch (Exception e) {
            return "Error package not found: " + errorPackage;
        }
    }

    public void addCode(int newCode) {
        code.add(newCode);
    }
}