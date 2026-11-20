plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.artemiy.glasstest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vivo.widget.widgetsdk_inner_demo" // v0.7: демо-имя SDK vivo из белых списков «живых» виджетов (лаунчер f6.a.a1, Jovi ca0.z.a). До v0.7: com.vivo.artemiy.glasstest
        minSdk = 26
        targetSdk = 34
        versionCode = 34
        versionName = "0.34"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
