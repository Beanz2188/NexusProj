plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional local release key (never committed). Without it the build falls
// back to the machine's debug key. Because the app is installed as the
// tablet's device owner, an update must be signed with the SAME key as the
// installed copy -- otherwise use scripts/reinstall.sh (see android/README.md).
val panelKeystore = rootProject.file("signing/nexuspanel.jks")

android {
    namespace = "com.nexuslights.panel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexuslights.panel"
        // The OTEETO K7 is an MT6737M on Android 8.1 (API 27), whatever its
        // About screen claims. 26 gives adaptive icons + CompletableFuture.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (panelKeystore.exists()) {
            create("panel") {
                storeFile = panelKeystore
                storePassword = System.getenv("NEXUS_KEYSTORE_PASSWORD") ?: "nexuspanel"
                keyAlias = "nexuspanel"
                keyPassword = System.getenv("NEXUS_KEYSTORE_PASSWORD") ?: "nexuspanel"
            }
        }
    }

    buildTypes {
        release {
            // Netty/HAP-Java lean on reflection; R8 isn't worth the risk for a
            // single-device app. APK size is irrelevant here.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("panel") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
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
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // HomeKit Accessory Protocol server (pure Java) -> Apple Home
    implementation("io.github.hap-java:hap:2.0.7") {
        // Both ship the same public-domain djb.Curve25519; keep net.vrallev's copy.
        exclude(group = "org.zeromq", module = "curve25519-java")
        // org.glassfish:javax.json already contains the javax.json API classes.
        exclude(group = "javax.json", module = "javax.json-api")
    }
    implementation("org.slf4j:slf4j-android:1.7.36")
    // QR code for HomeKit pairing
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation("junit:junit:4.13.2")
}
