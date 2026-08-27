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
# R8-minified release builds. LogSessionId is an API-31 platform class. In
# media3-exoplayer every use of it is isolated in @RequiresApi(31) helper classes
# (PlayerId$LogSessionIdApi31, MediaCodecRenderer$Api31, ...), the pattern R8's
# API-level modeling handles. media3-transformer is different: it passes a
# LogSessionId as a plain parameter through its core signatures
# (Codec.DecoderFactory, TransformerInternal, ExoPlayerAssetLoader,
# AudioSampleExporter, ~20 classes), with no guard for R8 to respect, so any
# optimization touching those methods (inlining / horizontal class merging) can
# land the reference in an eagerly-verified path and ART aborts below API 31.
# A keep rule on LogSessionId itself is useless (the class doesn't exist on the
# device), and pinning individual classes only makes R8 relocate the reference —
# we hit it first in the Transformer asset-loader factory, then on the internal
# ExoPlayer:Playback thread (PlayerId/renderer init). Upstream: androidx/media#2535.
#
# The rule below disables *optimization* on the transformer module only; the
# allowshrinking/allowobfuscation modifiers let R8 still remove unused code and
# rename what's left. It used to be a plain `-keep class androidx.media3.** { *; }`,
# which pinned all of media3 (25k methods, ~900 KB of APK) unshrunk. Revisit once
# transformer guards LogSessionId behind Api31 helpers like exoplayer does, or
# when AudioTranscoder moves off Transformer.
-keep,allowshrinking,allowobfuscation class androidx.media3.transformer.** { *; }
-dontwarn android.media.metrics.**
