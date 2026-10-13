plugins {
    id("com.android.application")
}

android {
    namespace = "com.johndoe6345789.motorwaysim"
    // Android 17 (API level 37)
    compileSdk = 37

    defaultConfig {
        applicationId = "com.johndoe6345789.motorwaysim"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
