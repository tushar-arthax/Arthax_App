# Minification is currently off for release builds. These rules are here so that turning
# it on later is a one-line change rather than a debugging session — the failure mode with
# reflective libraries is a runtime crash in the field, not a build error.

# --- Retrofit -----------------------------------------------------------------------
# Retrofit reads generic signatures and annotations off the service interface at runtime.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

# --- OkHttp -------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Moshi --------------------------------------------------------------------------
# Generated adapters are looked up by name, so the DTOs and their adapters must survive.
-keep class com.example.arthax.data.remote.dto.** { *; }
-keep class **JsonAdapter { *; }
-keepnames @com.squareup.moshi.JsonClass class *
-keepclassmembers @com.squareup.moshi.JsonClass class * { synthetic <init>(...); }
-dontwarn okio.**

# --- Room ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Hilt / Dagger ------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembernames class * { @javax.inject.Inject <init>(...); }

# --- WorkManager --------------------------------------------------------------------
# Workers are instantiated by class name from the WorkManager database, so their names
# must not be obfuscated even though nothing references them statically.
-keep class * extends androidx.work.ListenableWorker { *; }

# --- App enums ----------------------------------------------------------------------
# Moshi serialises enums by reflecting over their constant FIELDS to read the names and any
# @Json annotations. Keeping only values()/valueOf() is not enough: R8 renames the constants
# and Moshi then throws "AssertionError: Missing field" the first time it builds an adapter,
# which crashes the app on launch. Covers every package - SyncState lives outside
# domain.model, and that omission is exactly what broke the first minified build.
-keepclassmembers enum com.example.arthax.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Moshi models -------------------------------------------------------------------
# Generated adapters are found by appending "JsonAdapter" to the model's class name, so the
# names of @JsonClass types must survive obfuscation. This covers the file-backed stores
# (LogEntry, PendingCall) as well as the network DTOs.
-keep,allowobfuscation @interface com.squareup.moshi.JsonClass
-keep class com.example.arthax.data.local.store.** { *; }

# --- Compile-only annotations ---------------------------------------------------------
# Tink (pulled in by security-crypto) references ErrorProne annotations that are
# compileOnly and never packaged. They carry no runtime behaviour, so R8 can ignore them.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.annotations.**
