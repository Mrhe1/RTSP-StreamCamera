package com.example.supercamera.MyException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// 保证整个工作流只有单个错误在被处理
public class ErrorLock {
    private static volatile AtomicReference<Object> lockObj = new AtomicReference<>(new Object());
    private static volatile Lock errorLock = new ReentrantLock();
    public static boolean getLock(){
        Object lock = lockObj.get();
        if(lock == null) return false;
        synchronized (lock) {
            try {
                // 30ms超时获取锁
                return errorLock.tryLock(30, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    public static void releaseLock(){
        Object lock = lockObj.get();
        if(lock == null) return;
        synchronized (lock) {
            errorLock.unlock();
        }
    }

    public static boolean isOnError(){
        Object lock = lockObj.get();
        if(lock == null) return true;
        synchronized (lock) {
            boolean acquired = errorLock.tryLock();
            if(acquired){
                // 成功获取到锁，说明没有错误在处理中
                errorLock.unlock();
                return false;
            }else{
                return true;
            }
        }
    }
}
