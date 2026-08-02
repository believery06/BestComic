# ProGuard rules

# junrar treats SLF4J as optional; the app intentionally excludes that logging backend.
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory

# Enums: rememberSaveable/Saver and enumValueOf/valueOf rely on unobfuscated class and constant names.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Kotlinx Serialization: keep generated serializers and polymorphic info.
# R8 fullMode may strip synthetic $$serializer classes; keep them explicitly.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions, InnerClasses
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.Required <fields>;
    @kotlinx.serialization.Transient <fields>;
    kotlinx.serialization.KSerializer serializer(...);
    ** INSTANCE;
}
-keepclassmembers class **$$serializer {
    *** INSTANCE;
    *** descriptor;
    *** serialize(...);
    *** deserialize(...);
    *** childSerializers(...);
    *** typeParametersSerializers(...);
}
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if class ** implements kotlinx.serialization.KSerializer
-keep class <1> {
    *;
}
-keep class com.mangareader.data.** { *; }
# WebDAV / sync / metadata: top-level @Serializable classes referenced by DataStore or JSON.
-keep class com.mangareader.utils.WebDavConfig { *; }
-keep class com.mangareader.utils.SyncData { *; }
-keep class com.mangareader.utils.ComicMetadata { *; }
-keep class com.mangareader.utils.WebDavSync$* { *; }

# Comic parsers: implementations are referenced through ParserFactory but R8 fullMode may
# still strip interface methods or rename classes if not explicitly kept.
-keep interface com.mangareader.parser.ComicParser { *; }
-keep class com.mangareader.parser.** implements com.mangareader.parser.ComicParser { *; }
-keep class com.mangareader.parser.ParserFactory { *; }
-keep class com.mangareader.parser.CbtParser$* { *; }
# Broader keep for parser package helpers/inner classes (e.g. private TarEntryInfo,
# CountingInputStream) to prevent R8 fullMode from stripping serialization/reflection paths.
-keep class com.mangareader.parser.** { *; }
-dontwarn kotlinx.serialization.**

# DataStore / Preferences: keys are dynamically constructed from strings, keep the core classes.
-keep class androidx.datastore.** { *; }
-keepclassmembers class androidx.datastore.preferences.core.Preferences$Key { *; }

# AndroidX Navigation / Compose: keep nav argument classes used with SafeArgs if any.
-keep class * implements androidx.navigation.NavArgs { *; }

# Apache Commons Compress / junrar: these libraries may use reflection for archive formats.
-keep class com.github.junrar.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.junrar.**

# Coil: keep model classes (metadata fetchers may use reflection)
-keep public class * implements coil.decode.Decoder
-keep public class * implements coil.fetch.Fetcher

# WorkManager: keep Worker/CoroutineWorker implementations referenced by name in enqueueUniquePeriodicWork.
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# Avoid stripping stack traces from crash handler.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
