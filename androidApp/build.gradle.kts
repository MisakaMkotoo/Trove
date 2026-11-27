import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import javax.inject.Inject

abstract class SubsetMaterialSymbolsFontTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:InputFile
    abstract val symbolsKt: RegularFileProperty

    @get:InputFile
    abstract val sourceFont: RegularFileProperty

    @get:InputFile
    abstract val subsetScript: RegularFileProperty

    @get:OutputFile
    abstract val outputFont: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val python3Executable: Property<String>

    @TaskAction
    fun run() {
        val out = outputFont.get().asFile
        out.parentFile.mkdirs()
        execOperations.exec {
            commandLine(
                python3(),
                subsetScript.get().asFile.absolutePath,
                symbolsKt.get().asFile.absolutePath,
                sourceFont.get().asFile.absolutePath,
                out.absolutePath,
            )
        }
    }

    private fun python3(): String =
        python3Executable.orNull?.takeIf { it.isNotBlank() } ?: "python3"
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val releaseKeystoreFile = providers.environmentVariable("MOBILE_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("MOBILE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MOBILE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MOBILE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val subsetMaterialSymbolsFont =
    tasks.register<SubsetMaterialSymbolsFontTask>("subsetMaterialSymbolsFont") {
        symbolsKt = file("src/main/kotlin/com/exodidio/tune/ui/components/MaterialSymbols.kt")
        sourceFont = rootProject.file("tools/fonts/material_symbols_rounded.ttf")
        subsetScript = rootProject.file("tools/font-subset/subset_font.py")
        outputFont = file("src/main/res/font/material_symbols_rounded.ttf")

        // Honour an explicit path set in local.properties:  python3=/path/to/python3
        localProperties.getProperty("python3")?.let { python3Executable = it }
    }

tasks.named("preBuild") { dependsOn(subsetMaterialSymbolsFont) }

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}
val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
val availableJniAbis = file("src/main/jniLibs")
    .listFiles()
    ?.filter { it.isDirectory && it.name in supportedAbis }
    ?.map { it.name }
    ?.toSet()
    .orEmpty()

val trimmedOrtDir = file("build/onnxruntime")
val trimmedOrtJar = file("build/onnxruntime/libs/onnxruntime-android.jar")
val trimmedOrtJniDir = file("build/onnxruntime/jniLibs")
val targetOrtAbis = availableJniAbis.ifEmpty { setOf("arm64-v8a") }
val hasTrimmedOrt = if (!trimmedOrtDir.exists()) {
    false
} else {
    require(trimmedOrtJar.isFile) {
        "Partial ONNX Runtime build at $trimmedOrtDir: missing $trimmedOrtJar. Run: bash scripts/build-onnxruntime-android.sh"
    }
    targetOrtAbis.forEach { abi ->
        val ortSo = File(trimmedOrtJniDir, "$abi/libonnxruntime.so")
        val jniSo = File(trimmedOrtJniDir, "$abi/libonnxruntime4j_jni.so")
        require(ortSo.isFile && jniSo.isFile) {
            "Missing trimmed ONNX Runtime .so for $abi under $trimmedOrtJniDir. Run: bash scripts/build-onnxruntime-android.sh $abi"
        }
    }
    true
}

dependencies {
    implementation(project(":sharedLogic"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewmodelKtx)

    implementation(libs.compose.material3)
    implementation(libs.compose.lucideIcons)
    debugImplementation(libs.compose.uiToolingPreview)
    implementation(libs.haze.core)
    implementation(libs.haze.blur)
    implementation(libs.kyant0.backdrop)
    implementation(libs.kyant0.shapes)
    implementation(libs.kyant0.capsule)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.hyper.focus.api)
    implementation(libs.lyricon.provider)
    if (hasTrimmedOrt) {
        implementation(files(trimmedOrtJar))
    } else {
        implementation(libs.onnxruntime.android)
    }
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.reorderable)
    implementation(libs.vico.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.compose.uiTestJunit4)
    debugImplementation(libs.compose.uiTooling)
    debugImplementation(libs.compose.uiTestManifest)
}

val appVersionCode = 10
val appVersionName = "1.2.0-beta"
val betaVersionCode = 8
val buildingRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
val releaseAbiSplitsRequested = gradle.startParameter.projectProperties["enableReleaseAbiSplits"] != "false"
val enableAbiSplits = buildingRelease && releaseAbiSplitsRequested

android {
    namespace = "com.exodidio.tune"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "30.0.16248370"


    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fexceptions", "-frtti")
            }
        }
        if (!enableAbiSplits) {
            ndk {
                abiFilters += availableJniAbis.ifEmpty { setOf("arm64-v8a") }
            }
        }
    }
    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationId = "com.exodidio.tune.dev"
        }
        create("prod") {
            dimension = "environment"
            applicationId = "com.mashiro.favmusic"
        }
        create("beta") {
            dimension = "environment"
            applicationId = "com.exodidio.tune.beta"
            versionCode = betaVersionCode
            versionName = appVersionName
        }
    }
    splits {
        abi {
            // ABI splits only for release builds: keep debug as a single fast APK.
            isEnable = enableAbiSplits
            reset()
            val splitTargets = availableJniAbis.ifEmpty { supportedAbis }
            include(*splitTargets.toTypedArray())
            isUniversalApk = true
        }
    }
    sourceSets {
        getByName("main") {
            if (hasTrimmedOrt) {
                jniLibs.directories.add(trimmedOrtJniDir.path)
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Per-ABI versionCode + APK naming for ABI splits (AGP 9 androidComponents API).
// Scheme: base versionCode x 1000 + offset — universal +0,
// armeabi-v7a +1, x86_64 +2, arm64-v8a +3 (highest so capable devices prefer it).
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val offset = when (abi) {
                null -> 0
                "armeabi-v7a" -> 1
                "x86_64" -> 2
                "arm64-v8a" -> 3
                else -> error("unknown ABI \"$abi\"")
            }
            val baseVersionCode = when (variant.flavorName) {
                "beta" -> betaVersionCode
                else -> appVersionCode
            }
            output.versionCode.set(baseVersionCode * 1000 + offset)
            val suffix = if (abi != null) "_$abi" else ""
            val flavorSuffix = when (variant.flavorName) {
                "beta" -> "-beta"
                else -> ""
            }
            output.outputFileName.set("FavFoldMusic-v$appVersionName$flavorSuffix$suffix.apk")
        }
    }
}
