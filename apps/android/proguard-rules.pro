# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in 'proguard-rules.pro' files of dependency libraries.

# Keep standard Android component classes to ensure functionality.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep JNA classes (Essential for Vosk)
# Vosk relies heavily on JNA for native interop. Shrinking these crashes the app.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# Keep Vosk classes
-keep class org.vosk.** { *; }

# Keep TensorFlow Lite (if used explicitly, though it has consumer rules)
-keep class org.tensorflow.lite.** { *; }

# Keep Kotlin Coroutines (Debug tooling)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Attributes for Reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Prevent warnings (JNA touches java.awt which Android doesn't have)
-dontwarn java.awt.**
-dontwarn javax.swing.**

# @ysig_audit(ticket_id="TASK-304", risk="ERR_JNI_STRIPPED")
# Prevent JNI methods in translator from being stripped out during R8 optimization
-keep class com.viitor.translate.LocalTranslator {
    private native <methods>;
    *;
}
