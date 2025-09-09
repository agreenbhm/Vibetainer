import java.util.Properties
import java.io.FileInputStream

val localPropertiesFile = rootProject.file("local.properties")
val keystoreProperties = Properties()

if (localPropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(localPropertiesFile))
}

val releaseKeyName by extra(keystoreProperties.getProperty("releaseKeyName", ""))
val releaseKeystorePath by extra(keystoreProperties.getProperty("releaseKeystorePath", ""))
val releaseKeystorePassword by extra(keystoreProperties.getProperty("releaseKeystorePassword", ""))
val releaseKeyPassword by extra(releaseKeystorePassword)