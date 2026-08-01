package com.tenniscount.app.speech

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Управляет офлайн-моделью распознавания Vosk: проверяет наличие,
 * при первом запуске скачивает (~45 МБ) и распаковывает во внутреннее хранилище.
 * После установки модели сеть не используется — распознавание полностью офлайн.
 */
class ModelManager(private val filesDir: File) {

    val modelDir = File(filesDir, MODEL_DIR)
    private val readyMarker = File(modelDir, ".ready")

    fun isModelReady(): Boolean = readyMarker.exists()

    fun modelPath(): String = modelDir.absolutePath

    /**
     * Гарантирует наличие модели. [onProgress] получает процент загрузки 0..100
     * (или null, если сервер не сообщил размер). Бросает исключение при ошибке сети/ФС.
     */
    suspend fun ensureModel(onProgress: (Int?) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (isModelReady()) return@withContext

        val zipFile = File(filesDir, "$MODEL_DIR.zip")
        val stagingDir = File(filesDir, "$MODEL_DIR.staging")
        try {
            download(zipFile, onProgress)
            stagingDir.deleteRecursively()
            unzip(zipFile, stagingDir)
            modelDir.deleteRecursively()
            check(stagingDir.renameTo(modelDir)) { "Не удалось установить модель" }
            readyMarker.writeText("ok")
        } finally {
            zipFile.delete()
            stagingDir.deleteRecursively()
        }
    }

    private fun download(target: File, onProgress: (Int?) -> Unit) {
        val connection = URI(MODEL_URL).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(total?.let { (downloaded * 100 / it).toInt() })
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Распаковывает zip, срезая верхнеуровневую папку архива, в [targetDir]. */
    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relative = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (relative.isEmpty()) continue
                val outFile = File(targetDir, relative)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }

    companion object {
        private const val MODEL_DIR = "model-ru"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    }
}
