package com.nyora.hasan72341.shared.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Reads and writes Mihon `.tachibk` files: a protobuf-encoded [MihonBackup],
 * gzipped.
 *
 * Decoding mirrors Mihon's own `BackupDecoder` byte sniffing so we accept every
 * file Mihon does — gzipped or bare protobuf — and reject legacy JSON backups
 * with a message that says what actually went wrong.
 */
@OptIn(ExperimentalSerializationApi::class)
object MihonBackupCodec {

    // Protobuf has no representation for an explicit null, so defaults stay
    // unencoded — which is also what Mihon writes.
    private val proto = ProtoBuf

    private const val GZIP_MAGIC = 0x1f8b
    // '{}' , '{"' , '{\n' — a JSON backup from Tachiyomi 0.x or Nyora's old v2 format.
    private val JSON_MAGIC = setOf(0x7b7d, 0x7b22, 0x7b0a)

    class InvalidBackupException(message: String) : Exception(message)

    fun encode(backup: MihonBackup): ByteArray {
        val raw = proto.encodeToByteArray(MihonBackup.serializer(), backup)
        if (raw.isEmpty()) throw InvalidBackupException("Refusing to write an empty backup")
        val out = ByteArrayOutputStream(raw.size / 2)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): MihonBackup {
        if (bytes.size < 2) throw InvalidBackupException("Backup file is empty or truncated")

        val magic = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
        if (magic in JSON_MAGIC) {
            throw InvalidBackupException(
                "This is a JSON backup. Nyora now uses the Mihon .tachibk format — " +
                    "re-export from the app that produced this file.",
            )
        }

        val raw = if (magic == GZIP_MAGIC) {
            runCatching {
                GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            }.getOrElse { throw InvalidBackupException("Backup file is corrupt (bad gzip data)") }
        } else {
            // Mihon tolerates a bare, ungzipped protobuf payload; so do we.
            bytes
        }

        return runCatching {
            proto.decodeFromByteArray(MihonBackup.serializer(), raw)
        }.getOrElse {
            throw InvalidBackupException("Not a valid Mihon backup file")
        }
    }

    /**
     * Re-decodes freshly written bytes to prove the file we are about to hand the
     * user is readable. Mihon does the same before reporting a backup as complete.
     */
    fun verify(bytes: ByteArray): MihonBackup = decode(bytes)
}
