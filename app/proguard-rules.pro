# IdleGamer release shrinking rules.
#
# The app's own code needs no rules: it uses no reflection. The rules below exist for libraries that look
# classes up at runtime. They were validated by installing the minified APK and running the QR sign-in step
# against Steam (docs/TEST_PLAN.md, "Release smoke").

# Readable stack traces in local diagnostics without shipping a mapping file.
-keepattributes SourceFile,LineNumberTable,Signature,Exceptions,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# --- JavaSteam -----------------------------------------------------------------------------------------
# Handlers, callbacks and unified-service stubs are resolved by class (getHandler(Class), subscribe(Class),
# createService(Class)) and service method names are derived reflectively for RPC routing.
-keep class in.dragonbra.javasteam.** { *; }
-dontwarn in.dragonbra.javasteam.**

# --- Protocol Buffers (full runtime) -------------------------------------------------------------------
# Generated messages use reflection on field accessors (GeneratedMessage.FieldAccessorTable).
-keep class * extends com.google.protobuf.GeneratedMessage { *; }
-keep class * extends com.google.protobuf.GeneratedMessage$Builder { *; }
-keep class * implements com.google.protobuf.ProtocolMessageEnum { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --- Bouncy Castle -------------------------------------------------------------------------------------
# JCE provider: algorithms are registered by class name strings and instantiated reflectively.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Transitive JVM-only references from JavaSteam's dependencies -----------------------------------
-dontwarn org.apache.commons.lang3.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
