package com.u1.slicer.data

import com.u1.slicer.network.MoonrakerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Private, endpoint-bound credentials. The caller supplies the app's no-backup directory. */
class MoonrakerApiKeyStore(noBackupRoot: File) {
    private val root = File(noBackupRoot, "moonraker-api-keys")
    private val mutableRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revisions: StateFlow<Map<String, Long>> = mutableRevisions.asStateFlow()

    @Synchronized fun get(printerId: String, url: String): String? {
        val endpoint = normalize(url) ?: return null
        val record = read(fileFor(printerId)) ?: return null
        return record.second.takeIf { record.first == endpoint }
    }

    @Synchronized fun put(printerId: String, url: String, key: String?) {
        val endpoint = normalize(url) ?: throw IllegalArgumentException("Invalid Moonraker URL")
        if (key != null && (key.length > MAX_KEY_LENGTH || key.any { it !in ' '..'~' })) {
            throw IllegalArgumentException("Invalid API key")
        }
        val secret = key?.trim().orEmpty()
        if (secret.isEmpty()) {
            remove(printerId)
            return
        }
        val destination = fileFor(printerId)
        if (read(destination) == (endpoint to secret)) return
        val contents = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeUTF(endpoint)
                output.writeUTF(secret)
            }
            bytes.toByteArray()
        }
        if (contents.size > MAX_RECORD_BYTES) throw IllegalArgumentException("Credential record is too large")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create credential directory")
        val temporary = Files.createTempFile(root.toPath(), ".credential-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(contents)
                output.fd.sync()
            }
            Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            bump(printerId)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized fun remove(printerId: String) {
        if (Files.deleteIfExists(fileFor(printerId).toPath())) bump(printerId)
    }

    private fun bump(printerId: String) {
        val old = mutableRevisions.value
        mutableRevisions.value = old + (printerId to ((old[printerId] ?: 0L) + 1L))
    }

    private fun normalize(url: String): String? = MoonrakerClient.normalizeUrl(url)
        .takeIf { it.isNotEmpty() && it.length <= MAX_URL_LENGTH }

    private fun fileFor(printerId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(printerId.toByteArray(Charsets.UTF_8))
        return File(root, digest.joinToString("") { "%02x".format(it) } + ".key")
    }

    private fun read(file: File): Pair<String, String>? {
        return try {
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            if (file.length() > MAX_RECORD_BYTES) return null
            val contents = file.inputStream().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (bytes.size() <= MAX_RECORD_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_RECORD_BYTES + 1 - bytes.size()))
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
            if (contents.size > MAX_RECORD_BYTES) return null
            DataInputStream(ByteArrayInputStream(contents)).use { input ->
                if (input.readInt() != FORMAT_VERSION) return null
                val endpoint = input.readUTF()
                val secret = input.readUTF()
                if (input.available() != 0 || normalize(endpoint) != endpoint || secret.isBlank() ||
                    secret.length > MAX_KEY_LENGTH || secret != secret.trim() || secret.any { it !in ' '..'~' }) return null
                endpoint to secret
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val MAX_KEY_LENGTH = 1024
        const val MAX_URL_LENGTH = 4096
        const val MAX_RECORD_BYTES = 8192
    }
}
