import java.util.Properties

plugins {
    id("com.android.application")
}

val releasePropertiesFile = rootProject.file("release.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.isFile) {
        releasePropertiesFile.inputStream().use(::load)
    }
}

fun releaseProperty(name: String): String {
    return (System.getenv("BBSFUSION_$name") ?: releaseProperties.getProperty(name) ?: "").trim()
}

val releaseStoreFilePath = releaseProperty("STORE_FILE")
val hasReleaseSigning = releaseStoreFilePath.isNotEmpty()

android {
    namespace = "dev.bbsfusion"
    compileSdk = 36
    compileSdkMinor = 1
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "dev.bbsfusion"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.1"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    signingConfigs {
        create("releaseLocal") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFilePath)
                storePassword = releaseProperty("STORE_PASSWORD")
                keyAlias = releaseProperty("KEY_ALIAS")
                keyPassword = releaseProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseLocal")
            }
        }
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.21.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
