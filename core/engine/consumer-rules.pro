# JNI: natives are registered by name in liborca_engine.so's JNI_OnLoad, and the
# engine calls NativeProgressListener.onProgress reflectively.
-keep class com.orcamobile.core.engine.jni.NativeEngine { native <methods>; }
-keep interface com.orcamobile.core.engine.jni.NativeProgressListener { *; }
-keep class * implements com.orcamobile.core.engine.jni.NativeProgressListener { void onProgress(int, java.lang.String); }
