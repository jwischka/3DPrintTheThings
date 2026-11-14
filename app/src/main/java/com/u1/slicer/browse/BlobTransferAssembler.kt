package com.u1.slicer.browse

import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.UUID

/** Bounded, ordered chunks from one top-level WebView blob transfer. Caller owns a completed file. */
class BlobTransferAssembler(
    private val cacheDir: File,
    private val maxBytes: Long = 32L * 1024 * 1024,
) {
    private var nonce: String? = null
    private var file: File? = null
    private var stream: OutputStream? = null
    private var expectedBytes = 0L
    private var receivedBytes = 0L
    private var nextIndex = 0

    init { require(maxBytes > 0) }

    @Synchronized fun begin(token: String, sizeBytes: Long): Boolean {
        reset()
        if (token.isBlank() || sizeBytes <= 0 || sizeBytes > maxBytes) return false
        if (!cacheDir.mkdirs() && !cacheDir.isDirectory) return false
        return try {
            val partial = File(cacheDir, "browser-blob-${UUID.randomUUID()}.partial")
            stream = partial.outputStream()
            file = partial
            nonce = token
            expectedBytes = sizeBytes
            true
        } catch (_: Exception) {
            reset()
            false
        }
    }

    @Synchronized fun append(token: String, index: Int, encodedChunk: String): Boolean {
        if (token != nonce) return false
        if (index != nextIndex || encodedChunk.length > MAX_ENCODED_CHUNK) {
            reset()
            return false
        }
        return try {
            val bytes = Base64.getDecoder().decode(encodedChunk)
            if (bytes.isEmpty() || receivedBytes + bytes.size > expectedBytes) {
                reset()
                return false
            }
            stream!!.write(bytes)
            receivedBytes += bytes.size
            nextIndex++
            true
        } catch (_: Exception) {
            reset()
            false
        }
    }

    @Synchronized fun complete(token: String): File? {
        if (token != nonce) return null
        if (receivedBytes != expectedBytes) {
            reset()
            return null
        }
        return try {
            stream!!.close()
            val completed = file
            clear()
            completed
        } catch (_: Exception) {
            reset()
            null
        }
    }

    @Synchronized fun reset() {
        runCatching { stream?.close() }
        file?.delete()
        clear()
    }

    private fun clear() {
        nonce = null
        file = null
        stream = null
        expectedBytes = 0L
        receivedBytes = 0L
        nextIndex = 0
    }

    private companion object { const val MAX_ENCODED_CHUNK = 48 * 1024 }
}
