pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kpal-publishing"

include(":audio")
project(":audio").projectDir = file("../io/audio")

include(":device")
project(":device").projectDir = file("../device")
