plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = System.getenv("DT_RELEASE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("DT_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("DT_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("DT_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

val workspaceSettingsSource = rootProject.layout.projectDirectory.file("../config/workspace_settings.json")
val siteProfilesSource = rootProject.layout.projectDirectory.file("../config/site_profiles.json")
val workspaceSettingsDestination = layout.projectDirectory.dir("src/main/assets")

tasks.register<Copy>("syncWorkspaceSettingsAsset") {
    from(workspaceSettingsSource.asFile)
    into(workspaceSettingsDestination)
    rename { "workspace_settings.json" }
    onlyIf { workspaceSettingsSource.asFile.exists() }
}

tasks.register<Copy>("syncSiteProfilesAsset") {
    from(siteProfilesSource.asFile)
    into(workspaceSettingsDestination)
    rename { "site_profiles.json" }
    onlyIf { siteProfilesSource.asFile.exists() }
}

tasks.named("preBuild") {
    dependsOn("syncWorkspaceSettingsAsset")
    dependsOn("syncSiteProfilesAsset")
}

android {
    namespace = "com.realyn.watchdog"
    compileSdk = 34

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.realyn.watchdog"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.android.billingclient:billing-ktx:6.2.1")
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")

    testImplementation("junit:junit:4.13.2")
}
