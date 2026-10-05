# --- Logging: no android.util.Log calls survive in release (docs/07_security.md SEC-12, SEC-15).
# Libraries log too: osmdroid's tile downloader writes "/z/x/y" tile indexes of the viewed area with
# Log.w/i/e on network errors. Stripping every level removes those; the app's own release lines (AppLog,
# message + exception class only) go through Log.println, which is not listed here and therefore stays.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
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
