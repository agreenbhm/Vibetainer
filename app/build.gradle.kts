plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.portainerapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.portainerapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Use Java 17 toolchain
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by some libraries (e.g., Sora TextMate) on older Android APIs
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        // Target JVM 17 bytecode
        jvmTarget = "17"
    }

    // Ensure Kotlin uses JDK 17 toolchain
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    // AndroidX core and appcompat for AppCompatActivity
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Lifecycle for lifecycleScope
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // Material (optional, common UI widgets/themes)
    implementation("com.google.android.material:material:1.12.0")

    // UI widgets
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.cardview:cardview:1.0.0")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    // Secure storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Charts
    implementation("com.github.PhilJay:MPAndroidChart:3.1.0")

    // YAML editor & validation
    // Sora Editor: modern code editor with auto-indent, line numbers, etc.
    implementation("io.github.Rosemoe.sora-editor:editor:0.22.1")
    implementation("io.github.Rosemoe.sora-editor:language-textmate:0.22.1")

    // SnakeYAML for YAML parsing/validation/formatting
    implementation("org.yaml:snakeyaml:2.2")

    // Core library desugaring for Java 8+ APIs on older Android devices
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
