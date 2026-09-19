plugins {
    id("com.android.library") version "8.13.2" apply false
}

val coreVersion = providers.gradleProperty("version").orNull ?: "0.1.0-SNAPSHOT"

allprojects {
    group = "com.heycyan.core"
    version = coreVersion
}
