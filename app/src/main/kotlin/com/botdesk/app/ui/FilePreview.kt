package com.botdesk.app.ui

import java.io.File

/** Détection du format (nom, type MIME, puis octets magiques une fois téléchargé). */
object FormatDetector {
    fun guess(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || mime == "application/pdf" -> "PDF"
            ext == "zip" || mime == "application/zip" -> "ZIP"
            ext == "gz" || ext == "tgz" || mime.contains("gzip") -> "GZ"
            ext == "sql" -> "SQL"
            ext.isNotEmpty() -> ext.uppercase()
            else -> "Inconnu"
        }
    }

    fun sniff(path: String): String? = try {
        File(path).inputStream().use { s ->
            val b = ByteArray(8)
            val n = s.read(b)
            when {
                n >= 4 && b[0] == '%'.code.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'D'.code.toByte() -> "PDF"
                n >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() -> "ZIP"
                n >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte() -> "GZ"
                else -> null
            }
        }
    } catch (e: Exception) { null }
}

/** Point d'extension : ajouter un handler pour prévisualiser d'autres formats. */
interface PreviewHandler {
    fun canHandle(name: String, mime: String, size: Long): Boolean
    fun preview(path: String): String
}

object TextPreview : PreviewHandler {
    private val exts = setOf("txt", "log", "json", "md", "csv", "yml", "yaml", "conf", "ini", "xml")
    override fun canHandle(name: String, mime: String, size: Long): Boolean =
        size <= 500_000 && (mime.startsWith("text/") || name.substringAfterLast('.', "").lowercase() in exts)

    override fun preview(path: String): String =
        File(path).inputStream().use { s -> val buf = ByteArray(20_000); val n = s.read(buf); buf.copyOf(maxOf(n, 0)) }.toString(Charsets.UTF_8)
}

object PreviewRegistry {
    val handlers = mutableListOf<PreviewHandler>(TextPreview)
    fun find(name: String, mime: String, size: Long): PreviewHandler? =
        handlers.firstOrNull { it.canHandle(name, mime, size) }
}
