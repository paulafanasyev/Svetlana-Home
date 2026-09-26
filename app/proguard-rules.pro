# Сохраняем ключевые классы безопасности и сериализации
-keepattributes *Annotation*, Signature, InnerClasses
-keepclassmembers class kotlinx.serialization.** { *; }
-keep,includedescriptorclasses class com.svetlana.home.**$$serializer { *; }
-keepclassmembers class com.svetlana.home.** {
    *** Companion;
}

# ── llama.cpp runtime (JNI) ────────────────────────────────────
# Нативный слой обращается к этим классам по имени через JNI —
# обфускация сломала бы загрузку модели и inference.
-keep class dev.ffmpegkit.llama.** { *; }
-keepclassmembers class dev.ffmpegkit.llama.** { *; }

# ── kotlinx.serialization ──────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ── OkHttp (внешние провайдеры, personal server) ───────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Coroutines ──────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ── DataStore ──────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
