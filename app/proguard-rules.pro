# Room generates these implementations at build time.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Keep the app's own database entities readable by Room's generated code.
-keep class com.locky.app.data.** { *; }

# Accessibility service and device admin receiver are resolved by name from
# the manifest, so the framework must be able to instantiate them.
-keep class com.locky.app.service.AppWatcherService { *; }
-keep class com.locky.app.admin.LockyAdminReceiver { *; }
