# Project-specific ProGuard rules go here.
# Networking and Media3 models must survive shrinking:
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class androidx.media3.** { *; }
