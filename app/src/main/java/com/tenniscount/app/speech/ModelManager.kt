package com.tenniscount.app.speech

import android.content.Context
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Управляет офлайн-моделью распознавания Vosk, поставляемой внутри APK
 * (assets/model-ru/, версия [MODEL_VERSION] зафиксирована в app/build.gradle.kts).
 * Vosk работает с обычными файлами, поэтому при первом запуске модель
 * копируется из assets во внутреннее хранилище. Сеть не используется вообще.
 *
 * Установка атомарна: файлы сначала копируются в staging-каталог, и только
 * после полного успешного копирования тот переименовывается в [modelDir],
 * а маркер готовности записывается последним. При любой ошибке staging
 * удаляется целиком, а повторный запуск начинает установку заново.
 */
class ModelManager(
    private val filesDir: File,
    private val listAssets: (String) -> List<String>,
    private val openAsset: (String) -> InputStream,
) {

    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        { path -> context.assets.list(path)?.toList().orEmpty() },
        context.assets::open,
    )

    val modelDir = File(filesDir, MODEL_DIR)
    private val stagingDir = File(filesDir, "$MODEL_DIR.staging")
    private val readyMarker = File(modelDir, READY_MARKER)

    /**
     * true только если модель установлена полностью и её версия актуальна.
     * Незавершённая установка (каталог есть, маркера нет) не считается готовой.
     */
    fun isModelReady(): Boolean =
        readyMarker.isFile && runCatching { readyMarker.readText() }.getOrNull() == MODEL_VERSION

    fun modelPath(): String = modelDir.absolutePath

    /**
     * Гарантирует наличие актуальной модели, при необходимости копируя её
     * из assets. [onProgress] получает процент установки 1..100.
     * Бросает исключение при ошибке ФС или отсутствии модели в APK.
     */
    suspend fun ensureModel(onProgress: (Int?) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (isModelReady()) return@withContext

        // Незавершённая или устаревшая установка и прошлый staging — начинаем заново.
        modelDir.deleteRecursively()
        stagingDir.deleteRecursively()
        try {
            val files = collectAssetFiles(ASSET_MODEL_DIR)
            check(files.isNotEmpty()) { "В APK отсутствует модель ($ASSET_MODEL_DIR)" }
            files.forEachIndexed { index, assetPath ->
                val out = File(stagingDir, assetPath.removePrefix("$ASSET_MODEL_DIR/"))
                out.parentFile?.mkdirs()
                openAsset(assetPath).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                onProgress((index + 1) * 100 / files.size)
            }
            check(stagingDir.renameTo(modelDir)) { "Не удалось установить модель" }
            readyMarker.writeText(MODEL_VERSION)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** Рекурсивный список файлов в дереве assets (list() не спускается в подкаталоги). */
    private fun collectAssetFiles(path: String): List<String> {
        val children = listAssets(path)
        if (children.isEmpty()) return listOf(path)
        return children.flatMap { collectAssetFiles("$path/$it") }
    }

    companion object {
        private const val MODEL_DIR = "model-ru"
        private const val ASSET_MODEL_DIR = "model-ru"
        private const val READY_MARKER = ".ready"

        /** Версия bundled-модели; должна совпадать с voskModelVersion в app/build.gradle.kts. */
        const val MODEL_VERSION = "vosk-model-small-ru-0.22"
    }
}
