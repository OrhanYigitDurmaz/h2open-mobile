import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tr.h2open.driver"
    compileSdk = 37

    defaultConfig {
        applicationId = "tr.h2open.driver"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Push (FCM): drop the Firebase project's google-services.json into app/ (it is not committed).
        // Without it the app still builds and calls work, but nothing wakes it for incoming calls.
        val fcm = firebaseConfig(file("google-services.json"), "tr.h2open.driver")
        buildConfigField("String", "FCM_APP_ID", "\"${fcm["appId"].orEmpty()}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${fcm["apiKey"].orEmpty()}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${fcm["projectId"].orEmpty()}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${fcm["senderId"].orEmpty()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Any lint warning fails the build.
        warningsAsErrors = true
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":sip"))
    implementation(libs.webrtc)
    implementation(libs.firebase.messaging)
    // Firebase (via Play services) pulls in fragment 1.1, too old for the ActivityResult APIs.
    implementation(libs.androidx.fragment)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

/** The fields of google-services.json the app needs, or nothing when the file is absent. */
fun firebaseConfig(file: File, packageName: String): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val json = JsonSlurper().parse(file) as Map<*, *>
    val project = json["project_info"] as Map<*, *>
    val client = (json["client"] as List<*>).map { it as Map<*, *> }.firstOrNull {
        val info = it["client_info"] as Map<*, *>
        (info["android_client_info"] as Map<*, *>)["package_name"] == packageName
    } ?: error("google-services.json has no Android app $packageName")
    return mapOf(
        "appId" to (client["client_info"] as Map<*, *>)["mobilesdk_app_id"].toString(),
        "apiKey" to ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"].toString(),
        "projectId" to project["project_id"].toString(),
        "senderId" to project["project_number"].toString(),
    )
}
