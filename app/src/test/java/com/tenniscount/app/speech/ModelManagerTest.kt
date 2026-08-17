package com.tenniscount.app.speech

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var assetsRoot: File
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        assetsRoot = tmp.newFolder("assets")
        filesDir = tmp.newFolder("files")
        writeAsset("model-ru/conf/model.conf", "config")
        writeAsset("model-ru/am/final.mdl", "acoustic-model")
        writeAsset("model-ru/graph/Gr.fst", "grammar-fst")
    }

    private fun writeAsset(path: String, content: String) {
        val file = File(assetsRoot, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun manager(
        openAsset: (String) -> InputStream = { File(assetsRoot, it).inputStream() },
    ) = ModelManager(
        filesDir,
        listAssets = { path -> File(assetsRoot, path).listFiles()?.map(File::getName).orEmpty() },
        openAsset = openAsset,
    )

    @Test
    fun `успешная установка копирует дерево и ставит маркер версии`() = runBlocking {
        val manager = manager()
        assertFalse(manager.isModelReady())

        manager.ensureModel {}

        assertTrue(manager.isModelReady())
        assertEquals("config", File(manager.modelDir, "conf/model.conf").readText())
        assertEquals("acoustic-model", File(manager.modelDir, "am/final.mdl").readText())
        assertEquals("grammar-fst", File(manager.modelDir, "graph/Gr.fst").readText())
        assertEquals(ModelManager.MODEL_VERSION, File(manager.modelDir, ".ready").readText())
    }

    @Test
    fun `прогресс монотонно растёт и доходит до 100`() = runBlocking {
        val progress = mutableListOf<Int?>()

        manager().ensureModel { progress += it }

        assertEquals(100, progress.last())
        assertEquals(progress, progress.sortedBy { it ?: -1 })
    }

    @Test
    fun `повторный запуск не копирует модель заново`() = runBlocking {
        manager().ensureModel {}
        var opens = 0
        val manager = manager { opens++; File(assetsRoot, it).inputStream() }

        manager.ensureModel {}

        assertEquals(0, opens)
    }

    @Test
    fun `ошибка копирования удаляет staging и модель не готова`() = runBlocking {
        var calls = 0
        val manager = manager {
            calls++
            if (calls == 2) throw java.io.IOException("disk full")
            File(assetsRoot, it).inputStream()
        }

        try {
            manager.ensureModel {}
            fail("ожидалось исключение")
        } catch (expected: java.io.IOException) {
        }

        assertFalse(manager.isModelReady())
        assertFalse(File(filesDir, "model-ru.staging").exists())
        assertFalse(File(filesDir, "model-ru").exists())
    }

    @Test
    fun `повторный запуск после прерванной установки завершает её корректно`() = runBlocking {
        // Имитация прерванной установки: остался staging и каталог без маркера.
        File(filesDir, "model-ru.staging/conf").mkdirs()
        File(filesDir, "model-ru.staging/conf/model.conf").writeText("partial")
        File(filesDir, "model-ru/am").mkdirs()
        File(filesDir, "model-ru/am/final.mdl").writeText("partial")

        val manager = manager()
        assertFalse(manager.isModelReady())
        manager.ensureModel {}

        assertTrue(manager.isModelReady())
        assertFalse(File(filesDir, "model-ru.staging").exists())
        assertEquals("acoustic-model", File(manager.modelDir, "am/final.mdl").readText())
    }

    @Test
    fun `устаревшая версия маркера вызывает переустановку`() = runBlocking {
        val modelDir = File(filesDir, "model-ru")
        modelDir.mkdirs()
        File(modelDir, ".ready").writeText("vosk-model-small-ru-0.15")

        val manager = manager()
        assertFalse(manager.isModelReady())
        manager.ensureModel {}

        assertTrue(manager.isModelReady())
        assertEquals("config", File(manager.modelDir, "conf/model.conf").readText())
    }

    @Test
    fun `каталог модели без маркера не считается готовым`() {
        File(filesDir, "model-ru/conf").mkdirs()
        File(filesDir, "model-ru/conf/model.conf").writeText("config")

        assertFalse(manager().isModelReady())
    }
}
