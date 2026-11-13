# Add project specific ProGuard rules here.
-keep class com.satulin.geniuslyrics.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
