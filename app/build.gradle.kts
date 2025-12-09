plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.scenic_navigation"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.scenic_navigation"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.osmdroid)
    implementation(libs.androidx.recyclerview)

    // Lifecycle and ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)


    // Fragment
    implementation(libs.androidx.fragment.ktx)

    // Preference support for Settings screen
    implementation("androidx.preference:preference-ktx:1.2.0")

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // OkHttp for network requests
    implementation(libs.okhttp)

    // Google Play Services Location for GPS
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // TensorFlow Lite for on-device inference (ensure version matches your trainer runtime)
    implementation("org.tensorflow:tensorflow-lite:2.12.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}