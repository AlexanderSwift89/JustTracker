# --- Logging: strip verbose/debug logs in release (security requirement, see docs/07_security.md)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# --- osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# --- Mapsforge (offline regions): render themes and the map reader use reflection-free XML parsing,
# but the library is kept whole like osmdroid — it is small and the build stays predictable.
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**
-dontwarn com.caverock.androidsvg.**

# --- Room: entities are accessed reflectively by generated code; keep-annotations are enough
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# --- Keep line numbers for readable stack traces (crash reports from RuStore / users)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
