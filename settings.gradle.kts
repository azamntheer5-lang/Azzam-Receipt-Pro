pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // مطلوبة لمكتبة MPAndroidChart (الرسوم البيانية)
    }
}

rootProject.name = "WhatsAppReceiptScanner"
include(":app")
