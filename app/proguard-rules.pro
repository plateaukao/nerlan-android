# R8 / ProGuard rules for the release build.
#
# Most dependencies (media3, okhttp, coil, play-services, Compose, navigation3)
# ship their own consumer rules, so they need nothing here. The one library that
# needs explicit keep rules under R8 full mode is kotlinx.serialization, which
# the app uses for the Channel+ API models and the on-disk JSON stores
# (favorites.json, downloads.json, Drive sync payloads).

# --- kotlinx.serialization ---------------------------------------------------
# Keep the @Serializable annotations and the generated serializer() entry points
# that R8 full mode would otherwise strip. Field names may still be obfuscated;
# the generated serializers reference them via the descriptor, not reflection.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- okhttp ------------------------------------------------------------------
# Optional, platform-specific deps okhttp references reflectively but doesn't ship.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- media3 (AudioTranscoder / Transformer) ----------------------------------
# The transcribe path crashes on API < 31 devices (e.g. Hisense A7 / Android 10)
# with NoClassDefFoundError: android.media.metrics.LogSessionId — but only in
# R8-minified release builds. media3 guards that API-31 reference behind an
# SDK_INT >= 31 check, but R8 full-mode optimization (horizontal class merging /
# code relocation) moves it into an eagerly-verified path, so ART aborts when the
# class is absent below API 31. A keep rule on LogSessionId itself is useless (the
# class doesn't exist on the device), and pinning individual media3 classes only
# makes R8 relocate the reference elsewhere — we hit it first in the Transformer
# asset-loader factory, then again deeper on the internal ExoPlayer:Playback
# thread (PlayerId/renderer init). The reliable fix per androidx/media#2535 is to
# stop R8 optimizing media3 at all, which preserves every SDK_INT guard. Costs a
# little APK size (media3 stays un-optimized); revisit if/when the upstream R8 bug
# is fixed. disableHorizontalClassMerging alone does NOT fix it.
-keep class androidx.media3.** { *; }
-dontwarn android.media.metrics.**
