# kotlinx.serialization keeps its generated serializers through its own rules
# (shipped in the library); keep the API models' names stable for debugging.
-keep class tr.h2open.driver.api.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
