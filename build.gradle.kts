import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application") version "8.12.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
val localPropertiesFile = rootProject.file("local.properties")
val keystoreProperties = Properties()

if (localPropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(localPropertiesFile))
}

val releaseKeyName by extra(keystoreProperties.getProperty("releaseKeyName", ""))
val releaseKeystorePath by extra(keystoreProperties.getProperty("releaseKeystorePath", ""))