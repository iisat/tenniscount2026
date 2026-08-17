import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// --- Bundled Vosk-модель ---
// Модель офлайн-распознавания поставляется внутри APK (assets/model-ru/),
// версия и SHA-256 архива зафиксированы здесь. При сборке архив скачивается
// один раз в build/vosk-model/ (нужна сеть), приложение к сети не обращается.
// Лицензия модели — Apache 2.0, attribution: app/src/main/assets/vosk-model-NOTICE.txt.
val voskModelVersion = "vosk-model-small-ru-0.22"
val voskModelSha256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
val voskModelUrl = "https://alphacephei.com/vosk/models/$voskModelVersion.zip"
val voskModelZip = layout.buildDirectory.file("vosk-model/$voskModelVersion.zip")
val voskModelAssetsDir = layout.buildDirectory.dir("vosk-model/assets")

val downloadVoskModel = tasks.register("downloadVoskModel") {
    description = "Скачивает bundled Vosk-модель ($voskModelVersion) и проверяет SHA-256."
    inputs.property("url", voskModelUrl)
    inputs.property("sha256", voskModelSha256)
    outputs.file(voskModelZip)
    // Пересчитываем хэш, а не доверяем самому факту наличия файла.
    outputs.upToDateWhen { voskModelZip.get().asFile.let { it.isFile && sha256Hex(it) == voskModelSha256 } }
    doLast {
        val file = voskModelZip.get().asFile
        file.parentFile.mkdirs()
        val tmp = file.resolveSibling(file.name + ".part")
        tmp.delete()
        try {
            URI(voskModelUrl).toURL().openStream().use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val actual = sha256Hex(tmp)
            check(actual == voskModelSha256) {
                "SHA-256 модели не совпал: $actual (ожидался $voskModelSha256)"
            }
            check(tmp.renameTo(file)) { "Не удалось переименовать $tmp в $file" }
        } finally {
            tmp.delete()
        }
    }
}

val unpackVoskModel = tasks.register("unpackVoskModel") {
    description = "Распаковывает Vosk-модель в assets/model-ru."
    dependsOn(downloadVoskModel)
    // Целостность архива гарантирует downloadVoskModel, поэтому достаточно имени файла.
    inputs.file(voskModelZip).withPropertyName("modelZip")
    outputs.dir(voskModelAssetsDir)
    doLast {
        val modelRoot = voskModelAssetsDir.get().asFile.resolve("model-ru")
        modelRoot.deleteRecursively()
        modelRoot.mkdirs()
        ZipInputStream(voskModelZip.get().asFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // Срезаем верхнеуровневую папку архива (vosk-model-small-ru-0.22/).
                val relative = entry.name.substringAfter('/', "")
                if (relative.isNotEmpty()) {
                    val target = modelRoot.resolve(relative)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}

android {
    namespace = "com.tenniscount.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tenniscount.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        named("main") {
            assets.srcDir("build/vosk-model/assets")
        }
    }
}

// Распакованная модель должна лежать в assets до слияния ресурсов.
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(unpackVoskModel)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.vosk.android)
    implementation(libs.androidx.graphics.path)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
