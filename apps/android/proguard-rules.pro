# Release builds are shrunk and optimised by R8; every library the app uses ships its own
# consumer rules (kotlinx.serialization, OkHttp, Coil, Room, Navigation 3), so what is left
# is what R8 cannot see through on its own.

# The back stack is restored from saved state by class name (Navigation 3's Android
# rememberNavBackStack looks the serializer up by reflection), so the screens' keys must keep
# the names they were saved under — across an update as well.
-keepnames class * implements androidx.navigation3.runtime.NavKey

# Ktor's JVM engine and the logging façade reference optional classes that are not on the classpath.
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
