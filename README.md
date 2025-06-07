# SuperCamera

## 项目简介
`SuperCamera` 是一个基于 Android 的高性能视频采集与流媒体推送框架，支持实时视频录制、RTSP 流媒体推送、硬件编码优化等功能。核心功能基于 **Camera2**、**MediaCodec** 和 **FFmpeg** 实现，适用于需要低延迟视频传输的场景（如安防监控、远程协作等）。

### 核心特性
- **多协议支持**：通过 FFmpeg 实现 RTSP 流媒体推送
- **硬件加速编码**：使用 MediaCodec 实现 H.264/H.265 硬件编码
- **动态码率控制**：支持 VBR/CBR 模式切换
- **异常恢复机制**：自动重连、错误状态管理
- **性能监控**：帧率统计、网络延迟追踪

## 项目结构
```
com.example.supercamera
├── CameraController    # 相机控制核心逻辑
├── StreamPusher        # 流媒体推送模块（基于 FFmpeg）
├── VideoStreamer       # 直播管理模块（调用StreamPusher与VideoEncoder）
├── VideoEncoder        # 视频编码器抽象层
├── VideoRecorder       # 本地视频录制功能
├── VideoWorkflow       # 整体视频处理工作流管理
├── MyException         # 自定义异常体系
└── MainActivity.java   # 主界面控制器
```

### 核心组件交互图
```
CameraController → VideoEncoder → VideoStreamer → StreamPusher
          ↑ VideoRecorder (可选本地存储)
```


## 依赖说明
### 必须依赖
1. **FFmpeg 本地库**（arm64的ffmpeg库已在jniLibs中，无需进行下面的工作）
   - 所有 `.so` 文件需放入 `app/src/main/jniLibs/` 目录
   - 需包含：`libavcodec.so`, `libavformat.so`, `libavutil.so` 等
   - 版本要求：v7.1+（与 `org.bytedeco:ffmpeg:7.1-1.5.11` 匹配）

2. Gradle 依赖（详见 `app/build.gradle`）
```gradle
implementation 'org.bytedeco:javacpp:1.5.11'
implementation 'org.bytedeco:ffmpeg:7.1-1.5.11'
implementation "androidx.camera:camera-core:1.4.1"
implementation "androidx.camera:camera-view:1.4.1"
```


### ABI 兼容性
- 推荐包含所有架构：`armeabi-v7a`, `arm64-v8a`, `x86_64`
- 可通过以下配置限制目标架构：
```gradle
android {
    ndk {
        abiFilters 'armeabi-v7a' // 根据设备类型调整
    }
}
```


## 关键代码模块解析

### 1. 流媒体推送 (StreamPusher)
- **FFmpegPusherImpl.java**
  - RTSP 推流核心实现
  - 支持自动重连机制（最大重试次数可配置）
  - 关键配置参数：
    ```java
    public class PushConfig {
        String url;           // RTSP 地址
        int width/height;     // 分辨率
        int fps;              // 帧率
        int bitrateKbps;      // 码率
        int codecID;          // 编码格式（H.264/H.265）
    }
    ```


### 2. 视频编码 (VideoEncoder)
- **MediaCodecImpl.java**
  - 硬件编码器封装
  - 支持特性：
    - 动态码率调节(支持VBR和CBR)
    - I 帧间隔配置
    - 编码模式选择（Baseline/Main/High Profile）

### 3. 异常处理体系
- 统一异常基类：[MyException]
- 错误码分类：（每个package下会有自己的错误码）
  ```java
  // 示例错误码
  ERROR_FFmpeg_START = 0x1001, // FFmpeg 初始化失败
  ERROR_FFmpeg_Reconnect = 0x1002, // 重连失败
  ERROR_Stream_STOP = 0x2001 // 流停止错误
  ```

- 自动恢复机制：
    1. 本模块出错，根据错误类型自行处理
    2. 层次上报，每层进行处理
    3. 上报到顶层，通过MyException获取出错的package和错误码的内容

## 构建注意事项
1. **NDK 配置**
   - 必须指定 NDK 版本（推荐 28.0.x）：
   ```gradle
   android {
       ndkVersion '28.0.12916984'
   }
   ```


2. **ProGuard 规则**
   ```proguard
   -keep class com.example.supercamera.** { *; }
   -keep class org.bytedeco.** { *; }
   -dontwarn org.bytedeco.**
   ```


3. **运行时权限**
   需在运行时请求以下权限：
   ```xml
   <uses-permission android:name="android.permission.CAMERA"/>
   <uses-permission android:name="android.permission.RECORD_AUDIO"/>
   <uses-permission android:name="android.permission.INTERNET"/>
   ```


## 开发建议
1. **调试 FFmpeg**
   - 启用日志输出：
   ```java
   FFmpegLogCallback.setLevel(LogLevel.AV_LOG_DEBUG);
   FFmpegLogCallback.set();
   ```


2. **性能优化点**
   - 在 `StreamConfig.Builder` 中调整 `iFrameInterval = 1` 可提高随机访问性能
   - 使用 `setBitrateMode(BITRATE_MODE_CBR)` 可获得更稳定的带宽占用

3. **常见问题排查**
   - 黑屏：检查 `CameraController` 的预览 Surface 配置
   - 推流失败：验证 RTSP URL 格式（必须以 `rtsp://` 开头）
   - 卡顿：尝试降低分辨率或帧率

## 许可证
本项目采用 MIT 许可证，详情请参阅 `LICENSE` 文件

---

**注意**：实际部署时请根据设备性能调整编码参数（建议真机测试不同分辨率下的 CPU 占用率），并确保 `jniLibs` 目录包含完整的 FFmpeg 二进制库。