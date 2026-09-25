# Сохраняем ключевые классы безопасности и сериализации
-keepattributes *Annotation*, Signature, InnerClasses
-keepclassmembers class kotlinx.serialization.** { *; }
-keep,includedescriptorclasses class com.svetlana.home.**$$serializer { *; }
-keepclassmembers class com.svetlana.home.** {
    *** Companion;
}
