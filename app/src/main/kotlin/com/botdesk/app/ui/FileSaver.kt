package com.botdesk.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/** Télécharge un document via TDLib puis laisse l'utilisateur choisir où l'enregistrer (aucune permission requise). */
@Composable
fun rememberFileSaver(): (TdApi.Document) -> Unit {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPath by remember { mutableStateOf<String?>(null) }

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

    return { doc ->
        scope.launch {
            toast(ctx, "Téléchargement en cours…")
            try {
                val f = app.tg.download(doc.document.id)
                val p = f.local.path
                if (!f.local.isDownloadingCompleted || p.isNullOrEmpty()) {
                    toast(ctx, "Téléchargement incomplet")
                } else {
                    pendingPath = p
                    app.lock.skipNextLock = true
                    launcher.launch(doc.fileName.ifBlank { "fichier" })
                }
            } catch (e: Exception) {
                toast(ctx, "Erreur : " + e.message)
            }
        }
    }
}
