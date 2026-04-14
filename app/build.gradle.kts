import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

dependencies {
    // Conscrypt
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    kapt("androidx.lifecycle:lifecycle-compiler:2.6.2")

    // KTX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("com.google.truth:truth:1.1.5")
    testImplementation("io.kotest:kotest-property:5.8.0")
    testImplementation("androidx.test:core:1.5.0")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    implementation(project(":contract"))

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Android Auto SDK
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")

    // Dynamic Animation (Spring)
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    // DexMaker for runtime subclassing (Hotspot Fix)
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")
}

// Version scheme: MAJOR * 100000 + MINOR * 10000 + PATCH * 100 + BETA_OFFSET
// - Stable releases: BETA_OFFSET = 99 (always highest within same M.m.p)
// - Beta N releases: BETA_OFFSET = N (1-98)
// Examples:
//   2.2.0-beta3 = 220003
//   2.2.0 stable = 220099
//   2.2.1-beta1 = 220101
//   2.2.1 stable = 220199
//   2.3.0-beta1 = 230001
//   3.0.0 stable = 300099
//
// MIGRATION NOTE: Users on versionCode 58 cannot upgrade in-place to 220003.
// This is a one-time breaking change requiring uninstall/reinstall.
val versionMajor = 2
val versionMinor = 2
val versionPatch = 0
val versionBeta: Int? = 3  // null for stable, 1-98 for beta N

fun computeVersionCode(major: Int, minor: Int, patch: Int, beta: Int?): Int {
    val betaOffset = beta ?: 99
    return major * 100000 + minor * 10000 + patch * 100 + betaOffset
}

fun computeVersionName(major: Int, minor: Int, patch: Int, beta: Int?): String {
    return if (beta != null) {
        "$major.$minor.$patch-beta$beta"
    } else {
        "$major.$minor.$patch"
    }
}

// Git-derived build provenance — baked into BuildConfig so you can always
// tell exactly which commit produced an APK.
fun gitShortSha(): String = try {
    Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short=7", "HEAD"))
        .inputStream.bufferedReader().readText().trim()
} catch (_: Exception) { "unknown" }

fun gitBranch(): String = try {
    Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--abbrev-ref", "HEAD"))
        .inputStream.bufferedReader().readText().trim()
} catch (_: Exception) { "unknown" }

fun buildTimestamp(): String = "${System.currentTimeMillis()}"

// Feature branches get a distinct versionName suffix so APKs are identifiable:
// e.g. "2.2.0-beta3+feature.aap-testing.a1b2c3d"
fun featureVersionName(base: String, branch: String, sha: String): String {
    if (branch == "main" || branch == "develop" || branch == "HEAD") return base
    val shortBranch = branch.removePrefix("feature/").take(20)
    return "$base+$shortBranch.$sha"
}

android {
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    namespace = "com.andrerinas.headunitrevived"

    buildFeatures {
        buildConfig = true
    }

    val copyRootAssets = tasks.register<Copy>("copyRootAssets") {
        from("${project.rootDir}/CHANGELOG.md", "${project.rootDir}/LICENSE")
        into("${project.layout.buildDirectory.get().asFile}/generated/assets/root")
    }

    // Scan available locales at configuration time and store as BuildConfig field
    val resDir = file("src/main/res")
    val availableLocales = resDir.listFiles { file ->
        file.isDirectory && file.name.startsWith("values-") &&
        // Filter out non-language qualifiers (night mode, screen size, etc.)
        !file.name.contains("night") &&
        !file.name.contains("land") &&
        !file.name.contains("port") &&
        !file.name.matches(Regex("values-[whsml]\\d+.*")) &&
        !file.name.matches(Regex("values-v\\d+")) &&
        // Check that it contains strings.xml (actual translation)
        file.resolve("strings.xml").exists()
    }?.map { dir ->
        // Extract locale code from directory name (e.g., "values-es" -> "es", "values-pt-rBR" -> "pt-rBR")
        dir.name.removePrefix("values-")
    }?.sorted() ?: emptyList()

    println("Detected available locales: $availableLocales")

    sourceSets {
        getByName("main") {
            assets.srcDirs("${project.layout.buildDirectory.get().asFile}/generated/assets/root")
        }
    }

    tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
        dependsOn(copyRootAssets)
    }

    tasks.configureEach {
        if (name.contains("lint", ignoreCase = true)) {
            dependsOn(copyRootAssets)
        }
    }

    defaultConfig {
        applicationId = "com.andrerinas.headunitrevived"
        minSdk = 16
        targetSdk = 36

        val baseVersionName = computeVersionName(versionMajor, versionMinor, versionPatch, versionBeta)
        val sha = gitShortSha()
        val branch = gitBranch()

        versionCode = computeVersionCode(versionMajor, versionMinor, versionPatch, versionBeta)
        versionName = featureVersionName(baseVersionName, branch, sha)
        setProperty("archivesBaseName", "${applicationId}_${baseVersionName}")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true

        // Build provenance — visible in Settings > About and in bug reports
        buildConfigField("String", "GIT_SHA", "\"$sha\"")
        buildConfigField("String", "GIT_BRANCH", "\"$branch\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"${buildTimestamp()}\"")

        // Store available locales in BuildConfig for runtime access
        // This is scanned at build time from values-XX directories
        buildConfigField("String", "AVAILABLE_LOCALES", "\"${availableLocales.joinToString(",")}\"")

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("playstore") {
            dimension = "distribution"
            minSdk = 21
        }
        create("github") {
            dimension = "distribution"
            // Default minSdk 16 from defaultConfig is used
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }

    signingConfigs {
        getByName("debug") {
            // storeFile = file("../keystore.jkc")
            // storePassword = property("HEADUNIT_KEYSTORE_PASSWORD") as String
            // keyAlias = property("HEADUNIT_KEY_ALIAS") as String
            // keyPassword = property("HEADUNIT_KEY_PASSWORD") as String
        }

        create("release") {
            storeFile = file("../headunit-release-key.jks") // Use your new keystore file name
            keyAlias = "headunit-revived" // Replace with your key alias
            val signingPropsFile = rootProject.file("secrets.properties")
            if (signingPropsFile.exists()) {
                val props = Properties()
                props.load(FileInputStream(signingPropsFile))

                storePassword = props.getProperty("HEADUNIT_KEYSTORE_PASSWORD")
                keyPassword = props.getProperty("HEADUNIT_KEY_PASSWORD")
            }
            else {
                storePassword = System.getenv("HEADUNIT_KEYSTORE_PASSWORD")
                keyPassword = System.getenv("HEADUNIT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-project.txt")
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            multiDexKeepProguard = file("multidex-config.pro")
        }
        getByName("debug") {
            isDebuggable = true
            isJniDebuggable = true
            multiDexKeepProguard = file("multidex-config.pro")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = false
        disable += "PackagedPrivateKey"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        (this as KotlinJvmOptions).let {
            it.jvmTarget = "1.8"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                var outputFileName = "${variant.applicationId}_${variant.versionName}_debug.apk"
                if(variant.buildType.name == "release") {
                    outputFileName = "${variant.applicationId}_${variant.versionName}.apk"
                    output.outputFileName = outputFileName
                }
                output.outputFileName = outputFileName
            }
    }
}