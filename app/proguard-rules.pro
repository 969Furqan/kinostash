# Keep Media3 / Kotlin metadata defaults; nothing reflective in app code except Process.pid.
-keepclassmembers class java.lang.ProcessImpl { int pid; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
