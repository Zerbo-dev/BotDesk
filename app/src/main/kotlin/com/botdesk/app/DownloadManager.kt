package com.botdesk.app

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.botdesk.app.data.toIndexed
import com.botdesk.app.td.BackupFile
import com.botdesk.app.ui.DownloadDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class DlTask(val id: Long, val fileId: Int, val name: String, val state: String, val message: String = "")

/**
 * Téléchargements vers le dossier choisi, exécutés au niveau de l'app : ils continuent quand on change d'écran
 * ou qu'on quitte la conversation, et se suivent/annulent depuis Réglages > Stockage.
 */
class DownloadManager(private val app: AppContainer) {
    val tasks = MutableStateFlow<List<DlTask>>(emptyList())
    private val jobs = ConcurrentHashMap<Long, Job>()
    private var seq = 0L
    private val main = Handler(Looper.getMainLooper())

    private fun say(msg: String) { main.post { Toast.makeText(app.app, msg, Toast.LENGTH_LONG).show() } }

    private fun update(id: Long, f: (DlTask) -> DlTask) {
        tasks.update { l -> l.map { if (it.id == id) f(it) else it } }
    }

    fun saveToFolder(f: BackupFile, folder: String, sub: String?) {
        val doc = f.doc
        val name = doc.fileName.ifBlank { "fichier" }
        val id = ++seq
        tasks.update { listOf(DlTask(id, doc.document.id, name, "running")) + it }
        val job = app.scope.launch {
            try {
                val d = app.tg.download(doc.document.id)
                val p = d.local.path
                if (!d.local.isDownloadingCompleted || p.isNullOrEmpty()) {
                    update(id) { it.copy(state = "failed", message = "Téléchargement incomplet") }
                    say("Téléchargement incomplet : $name")
                    return@launch
                }
                val title = app.tg.chat(f.chatId)?.title ?: ""
                app.index.record(listOf(f.toIndexed(title, downloaded = true)))
                val saved = withContext(Dispatchers.IO) {
                    try { DownloadDir.save(app.app, folder, sub, name, doc.mimeType, File(p)) } catch (e: Exception) { null }
                }
                if (saved != null) {
                    update(id) { it.copy(state = "done", message = DownloadDir.label(folder) + (sub?.let { s -> " › $s" } ?: "")) }
                    say("Enregistré : $name")
                } else {
                    update(id) { it.copy(state = "failed", message = "Dossier inaccessible") }
                    say("Dossier inaccessible : re-sélectionnez-le dans Réglages > Stockage")
                }
            } catch (e: CancellationException) {
                update(id) { it.copy(state = "cancelled") }
                throw e
            } catch (e: Exception) {
                update(id) { it.copy(state = "failed", message = e.message ?: "Erreur") }
                say("Échec : $name")
            } finally {
                jobs.remove(id)
            }
        }
        jobs[id] = job
    }

    fun cancel(task: DlTask) {
        jobs[task.id]?.cancel()
        app.scope.launch { try { app.tg.cancelDownload(task.fileId) } catch (e: Exception) { } }
        update(task.id) { it.copy(state = "cancelled") }
    }

    fun clearFinished() {
        tasks.update { l -> l.filter { it.state == "running" } }
    }
}
