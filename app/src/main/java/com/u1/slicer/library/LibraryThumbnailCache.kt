package com.u1.slicer.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Extracts a small embedded 3MF preview into disposable app cache; never fetches thumbnailUrl. */
class LibraryThumbnailCache(cacheDir: File, private val store: DownloadStore) {
    private val root = File(cacheDir, "library_thumbnails")
    private val mutex = Mutex()

    suspend fun thumbnailFile(entry: DownloadEntry): File? = withContext(Dispatchers.IO) {
        if (!entry.fileName.endsWith(".3mf", ignoreCase = true)) return@withContext null
        mutex.withLock {
            try {
                val model = store.fileFor(entry)
                if (!model.isFile) return@withLock null
                if (!root.isDirectory && !root.mkdirs()) return@withLock null
                if (root.canonicalFile != root.absoluteFile) return@withLock null
                val cached = File(root, "${entry.sha256}.thumb")
                if (cached.isFile && cached.canonicalFile == cached.absoluteFile &&
                    cached.length() > 0 && cached.length() <= MAX_THUMBNAIL_BYTES && hasImageSignature(cached)) return@withLock cached
                if (cached.exists()) cached.delete()
                val partial = File.createTempFile(".thumbnail-", ".tmp", root)
                try {
                    val extracted = ZipFile(model).use { zip -> extractSelected(zip, partial) }
                    if (!extracted) return@withLock null
                    if (!partial.renameTo(cached)) return@withLock null
                    cached
                } finally { partial.delete() }
            } catch (_: IOException) { null }
        }
    }

    private suspend fun extractSelected(zip: ZipFile, destination: File): Boolean {
        val candidates = listOf("metadata/thumbnail.png", "3d/thumbnail.png", "metadata/thumbnail.jpg",
            "metadata/thumbnail.jpeg", "3d/thumbnail.jpg", "3d/thumbnail.jpeg")
        var chosen: ZipEntry? = null
        var chosenRank = Int.MAX_VALUE
        val all = zip.entries()
        var count = 0
        while (all.hasMoreElements()) {
            currentCoroutineContext().ensureActive()
            if (++count > MAX_ARCHIVE_ENTRIES) return false
            val item = all.nextElement()
            val rank = candidates.indexOf(item.name.lowercase(Locale.ROOT))
            if (rank < 0 || rank >= chosenRank || item.isDirectory) continue
            if (item.size <= 0 || item.size > MAX_THUMBNAIL_BYTES || item.compressedSize <= 0 ||
                (item.size - 1) / item.compressedSize >= MAX_COMPRESSION_RATIO || item.crc < 0) continue
            chosen = item
            chosenRank = rank
        }
        val image = chosen ?: return false
        var copied = 0L
        val crc = CRC32()
        zip.getInputStream(image).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    copied += n
                    if (copied > image.size || copied > MAX_THUMBNAIL_BYTES) return false
                    crc.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        return copied == image.size && crc.value == image.crc && hasImageSignature(destination)
    }

    private fun hasImageSignature(file: File): Boolean {
        val header = ByteArray(8)
        val count = file.inputStream().use { it.read(header) }
        if (count < 3) return false
        val jpg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
        val png = count >= 8 && header.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A))
        return jpg || png
    }

    private companion object {
        const val MAX_THUMBNAIL_BYTES = 2L * 1024 * 1024
        const val MAX_ARCHIVE_ENTRIES = 1000
        const val MAX_COMPRESSION_RATIO = 200L
    }
}
