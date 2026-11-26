import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Configuration resolution order:
 *   android/local.properties  ->  gradle.properties / -P property  ->  fallback
 * Never put real keys in source control.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun configValue(key: String, fallback: String): String {
    val fromLocal = localProperties.getProperty(key)
    if (!fromLocal.isNullOrBlank()) return fromLocal.trim()
    val fromGradle = providers.gradleProperty(key).getOrNull()
    if (!fromGradle.isNullOrBlank()) return fromGradle.trim()
    return fallback
}

fun String.asBuildConfigLiteral(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val amapKey = configValue("AMAP_KEY", "")
val apiBaseUrl = configValue("API_BASE_URL", "http://10.0.2.2:3000")

android {
    namespace = "com.whereareu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whereareu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "AMAP_KEY", amapKey.asBuildConfigLiteral())
        buildConfigField("String", "API_BASE_URL", apiBaseUrl.asBuildConfigLiteral())
        manifestPlaceholders["AMAP_KEY"] = amapKey
    }

    buildTypes {
        release {
            // v1.0.0 侧载版：沿用 debug 证书，以匹配高德 Android Key 当前登记的
            // 调试 SHA1。正式上架前必须换成 release keystore 并新增高德 Key。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // NOTE: com.amap.api:3dmap:10.0.600 already bundles the
    // com.amap.api.location classes. Adding com.amap.api:location
    // (6.4.3) on top produces duplicate-class build failures, so we keep
    // only the map artifact. If you switch to a map artifact that does not
    // bundle location, re-add libs.amap.location here.
    implementation(libs.amap.map)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.compose.ui.tooling)
}
