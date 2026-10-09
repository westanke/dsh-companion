import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val signingFile = providers.gradleProperty("dshSigningProperties").orNull
    ?.let(::file)
    ?: rootProject.file("../.secrets/deepseek-harness-android/signing.properties")
val signingValues = Properties().apply {
    signingFile.takeIf { it.isFile }?.inputStream()?.use(::load)
}
val hasDedicatedSigning = signingValues.isNotEmpty()

android {
    namespace = "io.github.hakunm.deepseekharness"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.hakunm.deepseekharness"
        minSdk = 26
        targetSdk = 36
        // 版本号必须高于上游（1.0.0 / 10000），否则无法覆盖安装，用户在手机上也无法区分装的是哪个。
        // 1.1.0 = 多地址连接层 + 配置导入；1.2.0 = 插件清单页；
        // 1.4.0 = 会话文件 + 发送行为可配置 + 发图片/文件 + 消息可复制 + 链接可点 + 会话图片可见；
        // 1.5.0 = 会话里的 dsh-ui 交互卡片（此前只有网页版能渲染，手机上退化成一坨 JSON）；
        // 1.6.0 = 会话产生的文件直接列在消息流末尾（此前只有顶栏一个图标按钮，功能在但入口藏得深）；
        // 1.7.0 = dsh-ui 卡片诊断开关：卡片没渲染时用户自己就能给出证据，不必等我们出新包；
        // 1.8.0 = 发送失败不再吞字（原文自动放回输入框）+ 失败原因说人话 + 图片取不到时说明真实原因；
        // 1.9.0 = 长按让给「选择片段」（此前被「复制整条」吃掉），整条复制改用双击。
        // 本仓库相对上游的功能变更见 docs/project/CHANGELOG-DEV.md。
        versionCode = 10009
        versionName = "1.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (signingValues.isNotEmpty()) {
            create("dedicated") {
                storeFile = file(signingValues.getProperty("storeFile"))
                storePassword = signingValues.getProperty("storePassword")
                keyAlias = signingValues.getProperty("keyAlias")
                keyPassword = signingValues.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("dedicated")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("dedicated")
        }
    }

    buildFeatures.compose = true
    buildFeatures.buildConfig = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    testOptions.unitTests.isIncludeAndroidResources = true
}

kotlin {
    jvmToolchain(17)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

val verifyDedicatedReleaseSigning = tasks.register("verifyDedicatedReleaseSigning") {
    group = "verification"
    description = "Requires the project-specific signing key before packaging a release."
    inputs.property("dedicatedSigningConfigured", hasDedicatedSigning)
    doLast {
        check(inputs.properties["dedicatedSigningConfigured"] == true) {
            "Dedicated signing config is required. Set dshSigningProperties."
        }
    }
}

tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        dependsOn(verifyDedicatedReleaseSigning)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.markdown.renderer.m3)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
