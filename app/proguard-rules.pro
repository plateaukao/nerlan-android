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

# --- Glance -> WorkManager -> Room -------------------------------------------
# Glance drags in work-runtime 2.7.1, which drags in room-runtime 2.2.5 — a Room
# old enough to ship no R8-full-mode consumer rules. Room loads its generated
# WorkDatabase_Impl *by name* and calls newInstance(), so as soon as R8 renames
# that class or drops its no-arg constructor, WorkManager's startup initializer
# throws "Failed to create an instance of androidx.work.impl.WorkDatabase" from
# androidx.startup.InitializationProvider — killing the app before
# Application.onCreate, in release builds only. Reproduced identically on
# Android 10 and Android 16, so it is R8, not a device quirk; debug builds and
# the emulator were both fine, which is exactly why it slipped through.
#
# WorkManager reflects on more than the database, and each miss fails silently
# in a different way. It also instantiates Workers *and* InputMergers by name:
# with only the class kept, R8 drops the unused no-arg constructor and
# WorkerWrapper dies with "Could not create Input Merger
# androidx.work.OverwritingInputMerger" — before any worker body runs. Glance
# composes every widget inside a WorkManager SessionWorker, so the visible
# symptom was widgets stuck forever on Glance's loading spinner while the app
# itself looked perfectly healthy. Keeping the whole (small) library is the
# only rule that doesn't leave another reflective corner exposed.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.** { *; }

# --- Glance widgets ----------------------------------------------------------
# A widget button stores its ActionCallback by class name; the Glance runtime
# instantiates it reflectively when the button is tapped. Glance's own consumer
# rule keeps the classes from being removed or renamed — this pins the no-arg
# constructor as well, without which a tap would silently do nothing in release
# builds only.
-keepclassmembers class * extends androidx.glance.appwidget.action.ActionCallback {
    <init>();
}
