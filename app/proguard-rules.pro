# UniFFI and JNA ProGuard rules
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# JNA references AWT classes which are not available on Android
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Keep UniFFI generated classes and their native callbacks
-keep class org.ratatosk.core.** { *; }

# Keep all classes that are part of the UniFFI runtime
-keep class uniffi.** { *; }

# ML Kit Barcode Scanning - avoid stripping detection logic
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }

# CameraX - avoid stripping camera control and analysis logic
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }
-keep class androidx.camera.core.impl.MetadataHolderService { *; }
