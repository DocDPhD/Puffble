plugins {
    // AGP 9 compiles Kotlin itself; the Kotlin plugin is listed (not applied) only to pin
    // the compiler version, which must be able to read PebbleKit's Kotlin 2.4 metadata.
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
