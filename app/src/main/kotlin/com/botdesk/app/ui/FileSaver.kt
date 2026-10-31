package com.botdesk.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.botdesk.app.data.toIndexed
import com.botdesk.app.td.BackupFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Télécharge un document via TDLib puis laisse l'utilisateur choisir où l'enregistrer (aucune permission requise).
 * Le fichier n'est jamais modifié. Un fichier trop gros (réglage) demande une confirmation.
 */
@Composable
fun rememberFileSaver(): (BackupFile) -> Unit {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by app.store.config.collectAsState()
    var pendingPath by remember { mutableStateOf<String?>(null) }
    var bigFile by remember { mutableStateOf<BackupFile?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = pendingPath
        pendingPath = null
        if (uri != null && path != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        ctx.contentResolver.openOutputStream(uri)?.use { out ->
                            File(path).inputStream().use { it.copyTo(out) }
                        }
                    }
                    toast(ctx, "Fichier enregistré")
                } catch (e: Exception) {
                    toast(ctx, "Échec de l'enregistrement : " + e.message)
                }
            }
        }
    }

    fun start(f: BackupFile) {
        scope.launch {
            toast(ctx, "Téléchargement en cours…")
            try {
                val doc = f.doc
                val d = app.tg.download(doc.document.id)
                val p = d.local.path
                if (!d.local.isDownloadingCompleted || p.isNullOrEmpty()) {
                    toast(ctx, "Téléchargement incomplet")
                } else {
                    val title = app.tg.chat(f.chatId)?.title ?: ""
                    app.index.record(listOf(f.toIndexed(title, downloaded = true)))
                    val sniffed = FormatDetector.sniff(p)
                    val guessed = FormatDetector.guess(doc.fileName, doc.mimeType)
                    if (sniffed != null && !sniffed.equals(guessed, ignoreCase = true)) {
                        toast(ctx, "Format détecté : $sniffed (le nom indique : $guessed)")
                    }
                    pendingPath = p
                    app.lock.skipNextLock = true
                    launcher.launch(doc.fileName.ifBlank { "fichier" })
                }
            } catch (e: Exception) {
                toast(ctx, "Erreur : " + e.message)
            }
        }
    }

    bigFile?.let { f ->
        AlertDialog(
            onDismissRequest = { bigFile = null },
            title = { Text("Gros fichier") },
            text = { Text("« ${f.doc.fileName} » fait ${fmtSize(f.doc.document.size)}. Le télécharger consomme des données mobiles. Continuer ?") },
            confirmButton = { TextButton(onClick = { bigFile = null; start(f) }) { Text("Télécharger") } },
            dismissButton = { TextButton(onClick = { bigFile = null }) { Text("Annuler") } },
        )
    }

    return { f ->
        val limit = cfg.confirmDownloadMb * 1_000_000L
        if (limit > 0 && f.doc.document.size > limit) bigFile = f else start(f)
    }
}
