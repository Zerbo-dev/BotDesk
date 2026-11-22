package com.botdesk.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Télécharge un document via TDLib puis l'enregistre tel quel :
 * - dans le dossier choisi par l'utilisateur (Réglages > Stockage), sans autre question ;
 * - sinon, à l'emplacement choisi pour ce fichier.
 * Un fichier trop gros (réglage) demande une confirmation.
 */
@Composable
fun rememberFileSaver(): (BackupFile) -> Unit {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by app.store.config.collectAsState()
    var pendingPath by remember { mutableStateOf<String?>(null) }
    var bigFile by remember { mutableStateOf<BackupFile?>(null) }
    var promptFor by remember { mutableStateOf<BackupFile?>(null) }

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
                    return@launch
                }
                val title = app.tg.chat(f.chatId)?.title ?: ""
                app.index.record(listOf(f.toIndexed(title, downloaded = true)))
                val sniffed = FormatDetector.sniff(p)
                val guessed = FormatDetector.guess(doc.fileName, doc.mimeType)
                if (sniffed != null && !sniffed.equals(guessed, ignoreCase = true)) {
                    toast(ctx, "Format détecté : $sniffed (le nom indique : $guessed)")
                }
                val c = app.store.config.value
                val name = doc.fileName.ifBlank { "fichier" }
                if (c.downloadDir.isNotBlank() && !c.askEachTime) {
                    val sub = if (c.subfolderPerSource && title.isNotBlank()) title else null
                    val saved = withContext(Dispatchers.IO) {
                        try { DownloadDir.save(ctx, c.downloadDir, sub, name, doc.mimeType, File(p)) } catch (e: Exception) { null }
                    }
                    if (saved != null) {
                        toast(ctx, "Enregistré dans " + DownloadDir.label(c.downloadDir) + (sub?.let { " › $it" } ?: ""))
                        return@launch
                    }
                    toast(ctx, "Le dossier choisi n'est plus accessible : choisissez un emplacement (ou re-sélectionnez le dossier dans les Réglages)")
                }
                pendingPath = p
                app.lock.skipNextLock = true
                launcher.launch(name)
            } catch (e: Exception) {
                toast(ctx, "Erreur : " + e.message)
            }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val f = promptFor
        promptFor = null
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) { }
            app.store.update { it.copy(downloadDir = uri.toString()) }
        }
        if (f != null) start(f)
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

    promptFor?.let { f ->
        AlertDialog(
            onDismissRequest = { promptFor = null },
            title = { Text("Où enregistrer vos fichiers ?") },
            text = { Text("Choisissez un dossier de votre téléphone : les prochains fichiers y seront enregistrés directement, sans question. Vous pourrez le changer dans Réglages > Stockage.") },
            confirmButton = {
                TextButton(onClick = {
                    ctx.getSharedPreferences("botdesk_flags", Context.MODE_PRIVATE).edit().putBoolean("folder_prompted", true).apply()
                    app.lock.skipNextLock = true
                    treeLauncher.launch(null)
                }) { Text("Choisir un dossier") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ctx.getSharedPreferences("botdesk_flags", Context.MODE_PRIVATE).edit().putBoolean("folder_prompted", true).apply()
                    promptFor = null
                    start(f)
                }) { Text("Cette fois seulement") }
            },
        )
    }

    return { f ->
        val limit = cfg.confirmDownloadMb * 1_000_000L
        val prompted = ctx.getSharedPreferences("botdesk_flags", Context.MODE_PRIVATE).getBoolean("folder_prompted", false)
        when {
            limit > 0 && f.doc.document.size > limit -> bigFile = f
            cfg.downloadDir.isBlank() && !prompted -> promptFor = f
            else -> start(f)
        }
    }
}
