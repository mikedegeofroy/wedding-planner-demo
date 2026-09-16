pluginManagement {
    // Pin the su.onno.widgets plugin to the same onno version the app depends on, so authored
    // widgets (src/main/widgets/*.tsx) compile against the matching SDK. Resolved from the onno-cloud
    // module registry rather than from a checkout next door: a tenant build has no sibling
    // framework tree, and a local includeBuild is how this app spent months running jars nobody
    // had published.
    val onnoVersion = providers.gradleProperty("onnoVersion").orNull ?: "3.3.0"
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://cloud.onno.su/modules") }
    }
    plugins {
        id("su.onno.widgets") version onnoVersion
    }
}

rootProject.name = "wedding-planner-demo"
