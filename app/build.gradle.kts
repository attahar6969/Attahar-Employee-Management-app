import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

abstract class GenerateAppIconTask : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val appProperties = Properties()
rootProject.file("app.properties").reader(Charsets.UTF_8).use { appProperties.load(it) }

val appIconSource = appProperties.getProperty("app.icon_url", "").trim()
val appIconIsRemote = appIconSource.startsWith("http://") || appIconSource.startsWith("https://")
val localAppIconFile = if (appIconSource.isNotEmpty() && !appIconIsRemote) {
    rootProject.file(appIconSource)
} else {
    null
}
val appIconFallback = appProperties.getProperty("app.icon_fallback", "false").toBoolean()
val generatedAppIconRes = layout.buildDirectory.dir("generated/appIcon/res")

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.reader(Charsets.UTF_8).use { keystoreProperties.load(it) }
}

android {
    namespace = "io.github.webviewtemplate"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    defaultConfig {
        applicationId = appProperties.getProperty("app.package")
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "HOME_URL", "\"${appProperties.getProperty("app.url")}\"")
        resValue("string", "app_name", appProperties.getProperty("app.name"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val generateAppIcon = tasks.register<GenerateAppIconTask>("generateAppIcon") {
    outputDirectory.set(generatedAppIconRes)
    val arguments = mutableListOf(
        rootProject.file("scripts/generate-app-icon.sh").absolutePath,
        "--output",
        generatedAppIconRes.get().asFile.absolutePath,
    )
    if (appIconSource.isNotEmpty()) {
        arguments += if (appIconIsRemote) {
            listOf("--url", appIconSource)
        } else {
            listOf("--path", localAppIconFile!!.absolutePath)
        }
    } else if (appIconFallback) {
        arguments += listOf("--favicon-from", appProperties.getProperty("app.url"))
    }

    commandLine(arguments)
    inputs.file(rootProject.file("scripts/generate-app-icon.sh"))
    inputs.property("appIconSource", appIconSource)
    inputs.property("appIconFallback", appIconFallback)
    inputs.property("appUrl", appProperties.getProperty("app.url"))
    localAppIconFile?.let { inputs.file(it) }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateAppIcon) {
            it.outputDirectory
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
