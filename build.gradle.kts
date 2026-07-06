plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
    // KSP مطلوب لـ Room (بديل kapt الأسرع، متوافق مع Kotlin 1.9.22)
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
