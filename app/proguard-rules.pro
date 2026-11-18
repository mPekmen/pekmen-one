# Keep Kotlin Serialization route classes for Release builds
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.pekmen.one.**$$serializer { *; }
-keepclassmembers class net.pekmen.one.** {
    *** Companion;
}
-keepclasseswithmembers class net.pekmen.one.** {
    kotlinx.serialization.KSerializer serializer(...);
}
