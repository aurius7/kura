# Keep model classes needed for reflection/serialization
-keep class aurius.kura.Item { *; }
-keep class aurius.kura.AppIconManager$IconOption { *; }

-dontwarn androidx.security.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**
