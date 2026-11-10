# CoolMax R8 / ProGuard rules (release builds only: debug is not minified).

# Keep generic signatures and annotations: libsu and kotlinx.serialization both rely on them.
-keepattributes *Annotation*,AnnotationDefault,InnerClasses,Signature,RuntimeVisibleAnnotations

# libsu (root shell) uses reflection against its own classes and nested callbacks.
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Serialized models: the JSON profile import/export and DataStore payloads are decoded through
# generated serializers. Keeping the model package means a renamed field cannot silently change the
# on-disk schema between releases.
-keep class id.coolmax.app.domain.model.** { *; }

# kotlinx.serialization: `serializer()` resolves the generated KSerializer companion reflectively,
# so R8 must not strip the Companion or the serializer factory methods.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# Keep enum names: enum values travel through JSON payloads and event logs by name.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
