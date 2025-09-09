import org.gradle.kotlin.dsl.extra
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application") version "8.13.0" // Or your desired AGP version
    id("org.jetbrains.kotlin.android") version "2.2.10" // Or your desired Kotlin plugin version
}

val localPropertiesFile = rootProject.file("local.properties")
val keystoreProperties = Properties()

if (localPropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(localPropertiesFile))
}

android {
    signingConfigs {
        create("release") {
            val releaseKeystorePath: String by rootProject.extra
            val releaseKeyName: String by rootProject.extra
            val releaseKeystorePassword: String by rootProject.extra
            val releaseKeyPassword: String by rootProject.extra

            storeFile = file(releaseKeystorePath)
            keyAlias = releaseKeyName
            storePassword = releaseKeystorePassword
            keyPassword = releaseKeyPassword
        }
    }
    namespace = "com.agreenbhm.vibetainer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agreenbhm.vibetainer"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.$versionCode"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            //proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    // Lifecycle for lifecycleScope
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.3")
    implementation("androidx.activity:activity-ktx:1.10.1")

    // Material (optional, common UI widgets/themes)
    implementation("com.google.android.material:material:1.12.0")

    // UI widgets
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.cardview:cardview:1.0.0")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.1.0")

    // Secure storage
    implementation("androidx.security:security-crypto:1.1.0")

    // Charts
    implementation("com.github.PhilJay:MPAndroidChart:3.1.0")

    // YAML editor & validation
    // Sora Editor: modern code editor with auto-indent, line numbers, etc.
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
    implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.6")

    // SnakeYAML for YAML parsing/validation/formatting
    implementation("org.yaml:snakeyaml:2.4")

    // Core library desugaring for Java 8+ APIs on older Android devices
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
