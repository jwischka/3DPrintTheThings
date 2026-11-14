package com.u1.slicer.library

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryThumbnailCacheTest {
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9QxkgAAAAASUVORK5CYII="
    )

    private fun archive(file: File, path: String, bytes: ByteArray) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    @Test fun `embedded thumbnail is cached and available after store reopen`() = runBlocking {
        val files = Files.createTempDirectory("library-thumb").toFile()
        try {
            val source = File(files, "source.3mf")
            archive(source, "Metadata/thumbnail.png", png)
            val provenance = DownloadProvenance("local", "", "Cube", thumbnailUrl = "https://example.com/remote.png")
            val firstStore = DownloadStore(files, OkHttpClient())
            val entry = firstStore.importFile(source, "cube.3mf", provenance)
            val first = LibraryThumbnailCache(File(files, "cache"), firstStore).thumbnailFile(entry)
            assertTrue(first?.isFile == true)
            assertArrayEquals(png, first!!.readBytes())
            val reopenedStore = DownloadStore(files, OkHttpClient())
            val again = LibraryThumbnailCache(File(files, "cache"), reopenedStore).thumbnailFile(entry)
            assertEquals(first.canonicalPath, again?.canonicalPath)
            assertArrayEquals(png, again!!.readBytes())
        } finally { files.deleteRecursively() }
    }

    @Test fun `STL and untrusted or oversized embedded images yield placeholder`() = runBlocking {
        val files = Files.createTempDirectory("library-thumb-invalid").toFile()
        try {
            val store = DownloadStore(files, OkHttpClient())
            val provenance = DownloadProvenance("local", "", "Model")
            val stl = File(files, "model.stl").apply { writeText("solid model\nendsolid model") }
            val stlEntry = store.importFile(stl, "model.stl", provenance)
            val cache = LibraryThumbnailCache(File(files, "cache"), store)
            assertNull(cache.thumbnailFile(stlEntry))

            val hidden = File(files, "hidden.3mf")
            archive(hidden, "../Metadata/thumbnail.png", png)
            val hiddenEntry = store.importFile(hidden, "hidden.3mf", provenance)
            assertNull(cache.thumbnailFile(hiddenEntry))

            val huge = File(files, "huge.3mf")
            archive(huge, "Metadata/thumbnail.png", ByteArray(2 * 1024 * 1024 + 1) { 0x7f })
            val hugeEntry = store.importFile(huge, "huge.3mf", provenance)
            assertNull(cache.thumbnailFile(hugeEntry))
        } finally { files.deleteRecursively() }
    }

    @Test fun `linked thumbnail cache root is rejected`() = runBlocking {
        val files = Files.createTempDirectory("library-thumb-link").toFile()
        try {
            val source = File(files, "source.3mf")
            archive(source, "Metadata/thumbnail.png", png)
            val store = DownloadStore(files, OkHttpClient())
            val entry = store.importFile(source, "cube.3mf", DownloadProvenance("local", "", "Cube"))
            val cacheDir = File(files, "cache").apply { mkdirs() }
            val outside = File(files, "outside").apply { mkdirs() }
            try {
                Files.createSymbolicLink(File(cacheDir, "library_thumbnails").toPath(), outside.toPath())
            } catch (e: Exception) {
                assumeNoException("Symlink creation is unavailable", e)
            }
            assertNull(LibraryThumbnailCache(cacheDir, store).thumbnailFile(entry))
            assertTrue(outside.listFiles().isNullOrEmpty())
        } finally { files.deleteRecursively() }
    }
}
