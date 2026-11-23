import com.android.build.api.artifact.SingleArtifact
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 签名口令放本地 keystore.properties（已 gitignore，不入库）；缺失时用占位符。
val keystoreProps = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 发布证书指纹（小写、无冒号）。release APK 必须是这把 key 签的，否则老用户没法覆盖安装。
val releaseCertSha256 = "963533138ed73e92e9a2859f80fdf18becb4ee5a83132c55634b965d7164ba1c"

private val APK_CERT_SHA256 = Regex("certificate SHA-256 digest:\\s*([0-9a-fA-F:]+)")

/**
 * 用 apksigner verify --print-certs 读出 APK 真实签名证书，与 [releaseCertSha256] 比对。
 * 不一致直接抛异常让任务失败（进而让 assemble 失败），而不是等用户装不上才发现。
 */
fun verifyApkCertificate(apk: File, expectedSha256: String, apksignerExecutable: String) {
    val expected = expectedSha256.replace(":", "").trim().lowercase()
    val process = ProcessBuilder(
        apksignerExecutable,
        "verify",
        "--print-certs",
        apk.absolutePath
    ).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    val exit = process.waitFor()
    if (exit != 0) {
        throw GradleException("apksigner verify 失败（exit=$exit）：${apk.name}\n$output")
    }
    val actual = APK_CERT_SHA256.find(output)?.groupValues?.get(1)
        ?.replace(":", "")?.lowercase()
        ?: throw GradleException("apksigner 输出里找不到证书 SHA-256：${apk.name}\n$output")
    if (actual != expected) {
        throw GradleException(
            "release 证书 SHA-256 不匹配：${apk.name}\n期望：$expected\n实际：$actual"
        )
    }
    logger.lifecycle("APK 证书校验通过：${apk.name} -> $actual")
}

/** 找 apksigner：优先 SDK build-tools 34.0.0（本项目 aapt2 同版本），否则取最高版本。 */
fun resolveApksignerPath(): String {
    val exeName = if (System.getProperty("os.name").lowercase().contains("win")) {
        "apksigner.bat"
    } else {
        "apksigner"
    }
    val buildTools = runCatching { File(android.sdkDirectory, "build-tools") }.getOrNull()
    if (buildTools != null && buildTools.isDirectory) {
        val preferred = File(buildTools, "34.0.0/$exeName")
        if (preferred.isFile) return preferred.absolutePath
        val newest = buildTools.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { File(it, exeName) }
            ?.firstOrNull { it.isFile }
        if (newest != null) return newest.absolutePath
    }
    // 兜底走 PATH，任务运行时会给出明确错误。
    return exeName
}

android {
    namespace = "com.kaoyan.wordhelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kaoyan.wordhelper"
        minSdk = 26
        targetSdk = 34
        versionCode = 32
        versionName = "1.5.3v3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appName"] = "不爱背单词"

        // 1.5.1：默认「公网同步地址」只从本地 keystore.properties（已 gitignore）注入，
        // 仓库里的默认值是空串 —— 私人域名绝不写进任何 Git 追踪文件。
        // 空 = 不启用公网地址，只走局域网 cardBackUrl。
        // 值要当 Java 字符串字面量的内容用，所以反斜杠和双引号都必须转义（空串生成 ""）。
        val defaultRemoteUrl = keystoreProps.getProperty("defaultRemoteUrl", "").trim()
        buildConfigField(
            "String",
            "DEFAULT_REMOTE_URL",
            "\"${defaultRemoteUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
    }

    // 固定签名：后续版本都用这一把 key 签，
    // 手机上可以直接覆盖安装，不会因为签名不同要求先卸载。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("../../_toolchain/signing/wordhelper.jks")
            storePassword = keystoreProps.getProperty("storePassword") ?: "YOUR_KEY_PASSWORD"
            keyAlias = "wordhelper"
            keyPassword = keystoreProps.getProperty("keyPassword") ?: "YOUR_KEY_PASSWORD"
        }
        // release 显式绑定同一把 key（口令仍只从本地 keystore.properties 读，不落库、不打印）。
        create("release") {
            storeFile = rootProject.file("../../_toolchain/signing/wordhelper.jks")
            storePassword = keystoreProps.getProperty("storePassword") ?: "YOUR_KEY_PASSWORD"
            keyAlias = "wordhelper"
            keyPassword = keystoreProps.getProperty("keyPassword") ?: "YOUR_KEY_PASSWORD"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // prod 是正式包（原包名/原应用名）；dev 加 .dev 后缀并改应用名，可与正式包双开。
    flavorDimensions += "channel"
    productFlavors {
        create("prod") {
            dimension = "channel"
            manifestPlaceholders["appName"] = "不爱背单词"
        }
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appName"] = "不爱背单词·实验"
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

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/assets/main"))
        }
    }
}

val syncBuiltinWordbookAssets by tasks.registering(Copy::class) {
    from(rootProject.file("wordbook_full_from_e2c.json"))
    from(rootProject.file("wordbook_full.json"))
    into(layout.buildDirectory.dir("generated/assets/main"))
}

tasks.named("preBuild").configure {
    dependsOn(syncBuiltinWordbookAssets)
}

// release 构建完成后强制校验证书：不一致直接让整个构建失败。
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val variantName = variant.name
        val capitalized = variantName.replaceFirstChar { it.uppercase() }
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        val apksigner = resolveApksignerPath()
        val verifyTask = tasks.register("verify${capitalized}ApkCertificate") {
            group = "verification"
            description = "校验 $variantName APK 的签名证书 SHA-256 是否为发布证书。"
            inputs.files(apkDir.map { it.asFileTree })
            doLast {
                val apks = apkDir.get().asFileTree.files
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                if (apks.isEmpty()) {
                    throw GradleException("证书校验失败：没有找到 $variantName APK 产物")
                }
                apks.forEach { verifyApkCertificate(it, releaseCertSha256, apksigner) }
            }
        }
        tasks.matching { it.name == "assemble$capitalized" }.configureEach {
            finalizedBy(verifyTask)
        }
    }
}

// 加了 productFlavors 后 AGP 不再生成无 flavor 的 testDebugUnitTest / assembleDebug /
// installDebug，注册同名聚合任务保住项目里既有的命令（AGENTS.md 里的固定命令也依赖它）。
afterEvaluate {
    mapOf(
        "testDebugUnitTest" to listOf("testProdDebugUnitTest", "testDevDebugUnitTest"),
        "assembleDebug" to listOf("assembleProdDebug", "assembleDevDebug"),
        "installDebug" to listOf("installProdDebug", "installDevDebug")
    ).forEach { (alias, targets) ->
        if (tasks.findByName(alias) == null) {
            tasks.register(alias) {
                group = "build"
                description = "productFlavors 兼容任务：依次执行 ${targets.joinToString(" ")}"
                dependsOn(targets)
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson.converter)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mpandroidchart)

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
