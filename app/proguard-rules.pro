# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep line numbers and original source file attributes for readable stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Gson models and serialization
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers enum * { *; }
-keep class com.google.gson.** { *; }
-keep class com.danieldk.splatevpn.data.** { *; }
-keep class com.danieldk.splatevpn.vpn.XrayConfigGenerator** { *; }

# Keep Room entities and DAOs
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep JNI classes and native methods for hev-socks5-tunnel
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.v2ray.ang.service.TProxyService { *; }
-keepclassmembers class com.v2ray.ang.service.TProxyService {
    private static native <methods>;
    public static native <methods>;
}

# Keep Hilt / Dagger components
-keep class dagger.hilt.** { *; }
-keep class com.danieldk.splatevpn.di.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel