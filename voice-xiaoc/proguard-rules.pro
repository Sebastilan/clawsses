# Keep Gson model fields (reflection-based serialization).
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.voicexiaoc.phone.** { *; }
