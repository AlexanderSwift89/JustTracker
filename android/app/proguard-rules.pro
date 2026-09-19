# --- Logging: strip verbose/debug logs in release (security requirement, see docs/07_security.md)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# --- osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# --- Room: entities are accessed reflectively by generated code; keep-annotations are enough
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# --- Keep line numbers for readable stack traces in Play Console
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
