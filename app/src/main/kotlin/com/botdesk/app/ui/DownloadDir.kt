package com.botdesk.app.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Enregistrement des fichiers dans un dossier choisi par l'utilisateur (accès par dossier Android, sans permission de stockage). */
object DownloadDir {
    fun label(tree: String): String {
        if (tree.isBlank()) return "Aucun dossier choisi"
        val id = try { DocumentsContract.getTreeDocumentId(Uri.parse(tree)) } catch (e: Exception) { return "Dossier choisi" }
        val parts = id.split(":", limit = 2)
        val vol = if (parts[0] == "primary") "Stockage interne" else "Carte SD / " + parts[0]
        val path = parts.getOrElse(1) { "" }
        return if (path.isBlank()) vol else "$vol › $path"
    }

    private fun clean(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "BotDesk" }.take(80)

    /** Copie [source] dans le dossier choisi (et un sous-dossier optionnel). Retourne l'URI créée, ou null en cas d'échec. */
    fun save(ctx: Context, tree: String, sub: String?, name: String, mime: String, source: File): Uri? {
        val cr = ctx.contentResolver
        val treeUri = Uri.parse(tree)
        var parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        if (!sub.isNullOrBlank()) parent = findOrCreateDir(cr, treeUri, parent, clean(sub)) ?: parent
        val doc = DocumentsContract.createDocument(cr, parent, mime.ifBlank { "application/octet-stream" }, name) ?: return null
        val out = cr.openOutputStream(doc) ?: return null
        out.use { o -> source.inputStream().use { it.copyTo(o) } }
        return doc
    }

    private fun findOrCreateDir(cr: ContentResolver, treeUri: Uri, parentUri: Uri, name: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        cr.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
            }
        }
        return DocumentsContract.createDocument(cr, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }
}
