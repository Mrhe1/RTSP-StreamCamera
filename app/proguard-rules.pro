# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFi
-keep class org.webrtc.** { *; }
#-keep class com.alivc.** { *; }
#-keep class com.aliyun.** { *; }
-keep class com.cicada.** { *; }
-keep class com.example.supercamera.MyApplication { *; }
-keep class com.example.supercamera.MainActivity { *; }
-keep class com.example.supercamera.VideoPusher { *; }
-keep class com.example.supercamera.VideoRecorder { *; }
-keep class org.bytedeco.** { *; }
-dontwarn org.bytedeco.**
-keepclassmembers class * {
    public void yourMethod();
}
-keep class com.example.supercamera.StreamPusher.PushStats.PushStatistics$FrameInfo { *; }
