package com.matiasnl.hakiosk.data.config

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Most a backup may weigh. A real dashboard's JSON is a few tens of KB; the cap is there so picking
 * the wrong file (a video, a database dump) fails with a message instead of filling the heap of a
 * 2 GB tablet before the format is even checked.
 */
const val MAX_CONFIG_BACKUP_BYTES: Int = 1024 * 1024

sealed interface ConfigBackupReadResult {
    data class Success(val text: String) : ConfigBackupReadResult

    /** Over [MAX_CONFIG_BACKUP_BYTES]: whatever it is, it isn't one of our backups. */
    data object TooLarge : ConfigBackupReadResult

    /** Gone, not readable, or not text at all. */
    data object Unreadable : ConfigBackupReadResult
}

/**
 * Reads and writes the file the user picked with the system file picker (Storage Access Framework,
 * so no storage permission is involved).
 *
 * Addresses travel as plain strings rather than [Uri] so the view model driving the screen stays
 * free of Android types and testable on the plain JVM.
 */
interface ConfigBackupFiles {
    suspend fun read(uri: String): ConfigBackupReadResult

    /** True when the whole content reached the file. */
    suspend fun write(uri: String, content: String): Boolean
}

/** [ConfigBackupFiles] on top of the system's content resolver. */
class AndroidConfigBackupFiles(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConfigBackupFiles {

    private val appContext = context.applicationContext

    override suspend fun read(uri: String): ConfigBackupReadResult = withContext(ioDispatcher) {
        try {
            appContext.contentResolver.openInputStream(Uri.parse(uri)).use { input ->
                input ?: return@withContext ConfigBackupReadResult.Unreadable
                val bytes = input.readAtMost(MAX_CONFIG_BACKUP_BYTES)
                    ?: return@withContext ConfigBackupReadResult.TooLarge
                ConfigBackupReadResult.Success(bytes.toString(Charsets.UTF_8))
            }
        } catch (_: IOException) {
            ConfigBackupReadResult.Unreadable
        } catch (_: SecurityException) {
            ConfigBackupReadResult.Unreadable
        } catch (_: IllegalArgumentException) {
            ConfigBackupReadResult.Unreadable
        }
    }

    override suspend fun write(uri: String, content: String): Boolean = withContext(ioDispatcher) {
        try {
            // "wt" truncates first. With a plain "w", overwriting an existing longer backup would
            // leave its tail behind and produce a file that is no longer valid JSON.
            appContext.contentResolver.openOutputStream(Uri.parse(uri), "wt").use { output ->
                output ?: return@withContext false
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** All the bytes, or null as soon as it's clear there are more than [limit] of them. */
    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val collected = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_CHUNK_BYTES)
        while (true) {
            val read = read(chunk)
            if (read == -1) break
            collected.write(chunk, 0, read)
            if (collected.size() > limit) return null
        }
        return collected.toByteArray()
    }

    private companion object {
        const val DEFAULT_CHUNK_BYTES = 8 * 1024
    }
}
