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
