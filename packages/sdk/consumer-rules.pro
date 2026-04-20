# Consumer ProGuard/R8 rules for the Convert Android SDK.
#
# These rules ship inside the published AAR (via `consumerProguardFiles(...)`
# in packages/sdk/build.gradle.kts) and are automatically applied to any
# consuming app's R8 pass. They protect the SDK's public API and serialization
# surface from R8 full mode's aggressive stripping/renaming.
#
# Scope notes (Story 1.4 AC-7):
#   - `com.convert.sdk.android.*` — public entry-point classes from packages/sdk
#   - `com.convert.sdk.core.model.*` — data classes consumers receive/inspect
#   - kotlinx.serialization generated `$serializer` classes — required for
#     runtime deserialization of config payloads when R8 full mode renames
#     companion objects.
#
# If future stories add new public classes or models, extend these rules.
# Verification: the AAR published via `./gradlew publishToMavenLocal` contains
# `proguard.txt` with these exact rules; a throwaway consumer app with R8
# enabled in release must resolve + call the public API without crashing.

# Public SDK entry points — keep class + all public members.
-keep public class com.convert.sdk.android.ConvertSDK { public *; }
-keep public class com.convert.sdk.android.ConvertSDK$Builder { public *; }
-keep public class com.convert.sdk.android.ConvertSDK$Companion { public *; }
-keep public class com.convert.sdk.android.ConvertContext { public *; }
-keep public class com.convert.sdk.android.EventCallback { public *; }

# Public core models — consumers receive these, inspect their fields, and
# in some cases serialize them. Keep class + public fields/methods.
-keep public class com.convert.sdk.core.model.** { public *; }
-keepclassmembers class com.convert.sdk.core.model.** {
    public <fields>;
    public <methods>;
}

# kotlinx.serialization — preserve generated `$serializer` static accessors
# for every @Serializable class under core.model and core.config. R8 full
# mode will otherwise rename or remove these, breaking runtime JSON decode.
-keepclasseswithmembers class com.convert.sdk.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.convert.sdk.core.config.ConvertConfig** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the auto-generated companion `$serializer` classes themselves.
# The `-if` rule applies only when the user class is @Serializable, so this
# is a narrow keep that doesn't balloon the final DEX.
-if @kotlinx.serialization.Serializable class com.convert.sdk.core.** { static ** Companion; }
-keepclassmembers class com.convert.sdk.core.** { static **$Companion Companion; }
-keepclasseswithmembernames class com.convert.sdk.core.** { static ** $serializer; }
-keepclassmembers class com.convert.sdk.core.**$$serializer { *; }
