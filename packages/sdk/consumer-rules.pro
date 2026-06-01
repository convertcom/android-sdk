# Consumer ProGuard/R8 rules for Convert Android SDK.
#
# These rules ship inside the AAR (packages/sdk/build.gradle.kts wires them via
# `consumerProguardFiles("consumer-rules.pro")` — Story 1.1) and are applied to
# every consumer app that depends on com.convert:sdk-android. R8 full mode (the
# AGP 8.x+ default) strips aggressively, so the keep directives below cover:
#
#   (a) the public SDK API surface consumers call directly;
#   (b) the core model classes consumers inspect (Variation, Feature, GoalData, …);
#   (c) the kotlinx.serialization generated `$serializer` companions so R8 does
#       not dead-code-strip the auto-generated serializers when reflection-free
#       polymorphism is used.
#
# If a consumer app reports `ClassNotFoundException` for a com.convert.sdk.*
# type in Release, the correct fix is to broaden these rules — NEVER to ask
# consumers to add keep rules in their own proguard-rules.pro. This file is
# the SDK's contract with R8.

# --- Public SDK API (com.convert.sdk.android.*) -----------------------------
-keep public class com.convert.sdk.android.ConvertSDK { public *; }
-keep public class com.convert.sdk.android.ConvertSDK$Builder { public *; }
-keep public class com.convert.sdk.android.ConvertSDK$Companion { public *; }
-keep public class com.convert.sdk.android.ConvertContext { public *; }
-keep public class com.convert.sdk.android.EventCallback { public *; }

# --- Core model classes (com.convert.sdk.core.model.*) ----------------------
# Consumers inspect model fields directly (Variation.id, Feature.key, …) and
# serialization may fall back to reflection for unknown shapes — keep the
# public members of every model class.
-keep public class com.convert.sdk.core.model.** { public *; }
-keepclassmembers class com.convert.sdk.core.model.** { public <fields>; public <methods>; }

# --- kotlinx.serialization generated serializers ----------------------------
# @Serializable data classes generate a companion `$serializer` object. R8 in
# full mode will strip these if nothing references them directly, breaking
# JSON (de)serialization at runtime. Keep both the Companion object and the
# $serializer class for every @Serializable in com.convert.sdk.core.**.
#
# Pattern source — the canonical kotlinx.serialization ProGuard/R8 rules,
# scoped here to the com.convert.sdk.core.** package (the upstream rules use
# `class **` patterns; we narrow the scope to avoid affecting consumer code):
#   https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
# (Verify against the version pinned in gradle/libs.versions.toml on every
# kotlinx.serialization upgrade — the rule shape is stable since 1.4 but
# new directives may be added in major releases.)
-keepclasseswithmembers class com.convert.sdk.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.convert.sdk.core.config.ConvertConfig** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.convert.sdk.core.** { static ** Companion; }
-keepclassmembers class com.convert.sdk.core.** { static **$Companion Companion; }
-keepclasseswithmembernames class com.convert.sdk.core.** { static ** $serializer; }
-keepclassmembers class com.convert.sdk.core.**$$serializer { *; }
