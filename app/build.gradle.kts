import groovy.json.JsonSlurper
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.licensee)
}

val cloudEnabled = providers.gradleProperty("pekmen.cloudEnabled")
    .map { it.toBooleanStrictOrNull() ?: false }
    .getOrElse(true)
val supabaseUrl = providers.gradleProperty("pekmen.supabaseUrl").getOrElse("")
val supabaseAnonKey = providers.gradleProperty("pekmen.supabasePublishableKey")
    .orElse(providers.gradleProperty("pekmen.supabaseAnonKey")).getOrElse("")
val redirectHost = providers.gradleProperty("pekmen.authRedirectHost").getOrElse("")

val hasLegacyClientKey = runCatching {
    val claims = Base64.getUrlDecoder().decode(supabaseAnonKey.split('.')[1]).decodeToString()
    (JsonSlurper().parseText(claims) as? Map<*, *>)?.get("role") == "anon"
}.getOrDefault(false)
require(
    supabaseAnonKey.isEmpty() || supabaseAnonKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]+")) || hasLegacyClientKey
) { "Use the Supabase publishable key for Android." }
require(redirectHost.isEmpty() || redirectHost.matches(Regex("[A-Za-z0-9.-]+"))) {
    "pekmen.authRedirectHost must contain only a hostname."
}

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\""

android {
    namespace = "net.pekmen.one"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.pekmen.one"
        minSdk = 29
        targetSdk = 35
        versionName = "0.2.0-v2-dev"
        versionCode = 2

        buildConfigField("boolean", "CLOUD_ENABLED", cloudEnabled.toString())
        buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseUrl))
        buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(supabaseAnonKey))
        buildConfigField("String", "AUTH_REDIRECT_HOST", buildConfigString(redirectHost.lowercase()))
        manifestPlaceholders["authRedirectHost"] = redirectHost.lowercase().ifBlank { "auth.invalid" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        androidResources {
            localeFilters += listOf("en", "tr")
        }
    }

    if (cloudEnabled) {
        sourceSets.named("debug") {
            manifest.srcFile("src/cloud/AndroidManifest.xml")
        }
        sourceSets.named("release") {
            manifest.srcFile("src/cloud/AndroidManifest.xml")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        lintConfig = rootProject.file("config/lint/lint.xml")
        abortOnError = true
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allow("ISC")
    allow("EPL-2.0")
    allow("Unicode-3.0")
    allow("Unicode-DFS-2016")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:platform"))
    implementation(project(":core:data"))
    implementation(project(":core:portability"))
    implementation(project(":core:cloud"))
    implementation(project(":feature:focus"))
    implementation(project(":feature:learning"))
    implementation(project(":feature:planner"))
    implementation(project(":feature:insights"))
    implementation(project(":feature:circles"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
