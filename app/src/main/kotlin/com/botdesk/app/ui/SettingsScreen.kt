@file:OptIn(ExperimentalMaterial3Api::class)

package com.botdesk.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.botdesk.app.core.ConfigBackup
import com.botdesk.app.data.AppConfig
import com.botdesk.app.data.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

private enum class Dlg { None, SetPin, ClearPin, Logout, Export, Import, NewCategory }

@Composable
fun SettingsScreen(activity: FragmentActivity) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by app.store.config.collectAsState()
    var dlg by remember { mutableStateOf(Dlg.None) }
    var pinSet by remember { mutableStateOf(app.lock.isPinSet) }
    var bio by remember { mutableStateOf(app.lock.biometricEnabled) }
    var renaming by remember { mutableStateOf<Category?>(null) }
    var exportPwd by remember { mutableStateOf<String?>(null) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var storageTick by remember { mutableStateOf(0) }
    var voiceDialog by remember { mutableStateOf(false) }
    val voices by Speaker.voices.collectAsState()
    LaunchedEffect(Unit) { Speaker.prepare(ctx) }
    val indexed by app.index.items.collectAsState()
    val storage by produceState<Long?>(null, storageTick) { value = try { app.tg.storageBytes() } catch (e: Exception) { null } }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast(ctx, "Notifications refusées : activez-les dans les réglages Android de BotDesk.")
    }
    fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val pwd = exportPwd
        exportPwd = null
        if (uri != null && pwd != null) scope.launch {
            try {
                val data = ConfigBackup.encrypt(app.store.config.value.toJson().toString(), pwd.toCharArray())
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) } }
                toast(ctx, "Configuration exportée (chiffrée)")
            } catch (e: Exception) { toast(ctx, "Export impossible : " + e.message) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes != null) { importBytes = bytes; dlg = Dlg.Import }
            } catch (e: Exception) { toast(ctx, "Lecture impossible : " + e.message) }
        }
    }

    val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("Paramètres", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }

        item { SectionTitle("Sécurité") }
        item { AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (pinSet) "PIN activé" else "PIN désactivé", Modifier.weight(1f))
                TextButton(onClick = { dlg = Dlg.SetPin }) { Text(if (pinSet) "Changer" else "Définir") }
                if (pinSet) TextButton(onClick = { dlg = Dlg.ClearPin }) { Text("Désactiver") }
            }
            if (pinSet && Biometric.available(ctx)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Déverrouillage biométrique", Modifier.weight(1f))
                    Switch(bio, { bio = it; app.lock.biometricEnabled = it })
                }
            }
            Text("Le verrouillage s'applique dès que l'app passe en arrière-plan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }

        item { SectionTitle("Catégories de bots") }
        items(cfg.categories.size) { i ->
            val c = cfg.categories[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, Modifier.weight(1f))
                IconButton(onClick = { renaming = c }) { Icon(Icons.Default.Edit, "Renommer") }
                IconButton(onClick = {
                    app.store.update { it.copy(
                        categories = it.categories.filter { x -> x.id != c.id },
                        bots = it.bots.mapValues { (_, m) -> if (m.categoryId == c.id) m.copy(categoryId = null) else m },
                    ) }
                }) { Icon(Icons.Default.Delete, "Supprimer") }
            }
        }
        item { TextButton(onClick = { dlg = Dlg.NewCategory }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Nouvelle catégorie") } }

        item { SectionTitle("Alertes et actions sensibles") }
        item { AppCard(Modifier.fillMaxWidth()) {
            var alerts by remember(cfg.alertKeywords) { mutableStateOf(cfg.alertKeywords) }
            var sens by remember(cfg.sensitiveKeywords) { mutableStateOf(cfg.sensitiveKeywords) }
            OutlinedTextField(alerts, { alerts = it }, label = { Text("Mots-clés d'alerte (séparés par des virgules)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(sens, { sens = it }, label = { Text("Mots-clés d'actions à confirmer") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { app.store.update { it.copy(alertKeywords = alerts, sensitiveKeywords = sens) }; toast(ctx, "Enregistré") }) { Text("Enregistrer les mots-clés") }
        } }

        item { SectionTitle("Apparence") }
        item { AppCard(Modifier.fillMaxWidth()) {
            ChipRow(listOf("system" to "Système", "dark" to "Sombre", "light" to "Clair"), cfg.theme) { v -> app.store.update { it.copy(theme = v) } }
        } }

        item { SectionTitle("Voix") }
        item { AppCard(Modifier.fillMaxWidth()) {
            Text("Langue de dictée et de lecture", style = MaterialTheme.typography.bodyMedium)
            ChipRow(listOf("auto" to "Auto", "fr" to "Français", "en" to "English"), cfg.voiceLang) { v -> app.store.update { it.copy(voiceLang = v) } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Envoyer automatiquement après la dictée", Modifier.weight(1f))
                Switch(cfg.voiceAutoSend, { on -> app.store.update { it.copy(voiceAutoSend = on) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lire à voix haute les nouvelles réponses (conversation ouverte)", Modifier.weight(1f))
                Switch(cfg.voiceAutoRead, { on -> app.store.update { it.copy(voiceAutoRead = on) } })
            }

            Text("Voix de lecture", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            ChipRow(listOf("system" to "Voix du téléphone", "eleven" to "ElevenLabs"), cfg.ttsEngine) { v -> app.store.update { it.copy(ttsEngine = v) } }

            var rate by remember(cfg.ttsRate) { mutableStateOf(cfg.ttsRate) }
            Text("Vitesse : " + String.format("%.1f×", rate), modifier = Modifier.padding(top = 8.dp))
            Slider(rate, { rate = it }, valueRange = 0.5f..2f, onValueChangeFinished = { app.store.update { it.copy(ttsRate = rate) } })

            if (cfg.ttsEngine == "system") {
                var pitch by remember(cfg.ttsPitch) { mutableStateOf(cfg.ttsPitch) }
                Text("Hauteur : " + String.format("%.1f", pitch))
                Slider(pitch, { pitch = it }, valueRange = 0.5f..1.5f, onValueChangeFinished = { app.store.update { it.copy(ttsPitch = pitch) } })
                OutlinedButton(onClick = { voiceDialog = true }) { Text("Voix : " + cfg.ttsVoice.ifBlank { "automatique" }, maxLines = 1) }
                Text(
                    "Pour de meilleures voix : Réglages Android > Synthèse vocale > moteur Google > installer les voix de haute qualité de votre langue, puis les choisir ici.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                var key by remember { mutableStateOf("") }
                var hasKey by remember { mutableStateOf(app.secure.getString("eleven_key") != null) }
                var vid by remember(cfg.elevenVoiceId) { mutableStateOf(cfg.elevenVoiceId) }
                OutlinedTextField(
                    key, { key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (hasKey) "Clé API (enregistrée — saisir pour remplacer)" else "Clé API ElevenLabs") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    vid, { vid = it.trim() }, singleLine = true, label = { Text("Identifiant de la voix (Voice ID)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                TextButton(onClick = {
                    if (key.isNotBlank()) { app.secure.putString("eleven_key", key.trim()); hasKey = true; key = "" }
                    app.store.update { it.copy(elevenVoiceId = vid) }
                    toast(ctx, "Enregistré")
                }) { Text("Enregistrer") }
                Text(
                    "Le texte des messages lus est envoyé à ElevenLabs (compte et quota à votre charge). La clé reste chiffrée sur ce téléphone et n'est jamais exportée. " +
                        "Les audios générés sont gardés en cache pour ne pas être facturés deux fois.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { Speaker.speak(ctx, "Bonjour, ceci est un test de la voix de BotDesk.", -1L, cfg.voiceLang) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("🔊 Tester la voix")
            }
            Text(
                "La dictée utilise la reconnaissance vocale du téléphone (selon le fabricant, l'audio peut être traité par le service vocal de Google). " +
                    "Une commande sensible dictée demande toujours confirmation avant l'envoi.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        } }

        item { SectionTitle("Notifications") }
        item { AppCard(Modifier.fillMaxWidth()) {
            Text("Messages des bots", style = MaterialTheme.typography.bodyMedium)
            ChipRow(listOf("off" to "Aucune", "alerts" to "Alertes", "all" to "Tous"), cfg.notifyBots) { v ->
                app.store.update { it.copy(notifyBots = v) }
                if (v != "off") ensureNotifPermission()
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Nouvelle sauvegarde dans un dépôt", Modifier.weight(1f))
                Switch(cfg.notifyBackups, { on -> app.store.update { it.copy(notifyBackups = on) }; if (on) ensureNotifPermission() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rester connecté en arrière-plan", Modifier.weight(1f))
                Switch(cfg.backgroundService, { on ->
                    app.store.update { it.copy(backgroundService = on) }
                    if (on) ensureNotifPermission()
                    app.notifier.syncService()
                })
            }
            Text(
                "Sans l'arrière-plan, les notifications n'arrivent que lorsque l'app est ouverte ou récemment utilisée. " +
                    "L'arrière-plan affiche une notification permanente et consomme de la batterie ; sur certains téléphones, " +
                    "désactivez l'optimisation de batterie pour BotDesk. Avec un PIN actif, le contenu des messages est masqué. " +
                    "Le mode silencieux se règle bot par bot (onglet Bots, bouton Modifier).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } }

        item { SectionTitle("Stockage et téléchargements") }
        item { AppCard(Modifier.fillMaxWidth()) {
            Text("Cache de fichiers : " + (storage?.let { fmtSize(it) } ?: "—"))
            Text("Confirmer avant de télécharger un fichier de plus de :", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            ChipRow(listOf("0" to "Jamais", "50" to "50 Mo", "100" to "100 Mo", "500" to "500 Mo"), cfg.confirmDownloadMb.toString()) { v ->
                app.store.update { it.copy(confirmDownloadMb = v.toInt()) }
            }
            TextButton(onClick = {
                scope.launch {
                    try { app.tg.clearDownloads(); toast(ctx, "Cache vidé") } catch (e: Exception) { toast(ctx, "Impossible : " + e.message) }
                    storageTick++
                }
            }) { Text("Vider le cache de téléchargement") }
        } }

        item { SectionTitle("Historique des fichiers") }
        item { AppCard(Modifier.fillMaxWidth()) {
            Text("${indexed.size} fichier(s) indexé(s) (métadonnées uniquement)")
            Text("Conserver :", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            ChipRow(listOf("30" to "30 jours", "90" to "90 jours", "365" to "1 an", "0" to "Illimité"), cfg.indexRetentionDays.toString()) { v ->
                app.store.update { it.copy(indexRetentionDays = v.toInt()) }
                app.index.applyRetention()
            }
            TextButton(onClick = { app.index.clear(); toast(ctx, "Historique vidé") }) { Text("Vider l'historique") }
        } }

        item { SectionTitle("Configuration") }
        item { AppCard(Modifier.fillMaxWidth()) {
            Text("Catégories, favoris, noms, canaux de sauvegarde et mots-clés. Ni session Telegram, ni PIN, ni clés API.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { dlg = Dlg.Export }) { Text("Exporter (chiffré)") }
                TextButton(onClick = { app.lock.skipNextLock = true; importLauncher.launch(arrayOf("*/*")) }) { Text("Importer") }
            }
        } }

        item { SectionTitle("Compte") }
        item { OutlinedButton(onClick = { dlg = Dlg.Logout }) { Text("Se déconnecter de Telegram") } }

        item {
            SectionTitle("À propos")
            Text("BotDesk $version · Client Telegram (TDLib) pour piloter vos bots. Aucun serveur BotDesk : tout reste sur ce téléphone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
        }
    }

    // ----- dialogues -----
    when (dlg) {
        Dlg.SetPin -> PinDialog(
            title = if (pinSet) "Nouveau PIN" else "Définir un PIN",
            askConfirm = true,
            onDismiss = { dlg = Dlg.None },
            onOk = { pin -> app.lock.setPin(pin); pinSet = true; dlg = Dlg.None; toast(ctx, "PIN enregistré") },
        )
        Dlg.ClearPin -> PinDialog(
            title = "Saisissez le PIN actuel",
            askConfirm = false,
            onDismiss = { dlg = Dlg.None },
            onOk = { pin ->
                if (app.lock.verify(pin)) { app.lock.clearPin(); pinSet = false; bio = false; dlg = Dlg.None }
                else toast(ctx, "PIN incorrect")
            },
        )
        Dlg.Logout -> AlertDialog(
            onDismissRequest = { dlg = Dlg.None },
            title = { Text("Se déconnecter ?") },
            text = { Text("La session Telegram et la base locale seront supprimées de ce téléphone.") },
            confirmButton = { TextButton(onClick = { dlg = Dlg.None; scope.launch { try { app.tg.logout() } catch (e: Exception) { toast(ctx, e.message ?: "Erreur") } } }) { Text("Se déconnecter") } },
            dismissButton = { TextButton(onClick = { dlg = Dlg.None }) { Text("Annuler") } },
        )
        Dlg.Export -> PasswordDialog(
            title = "Mot de passe d'export (8 caractères min.)",
            confirmField = true,
            onDismiss = { dlg = Dlg.None },
            onOk = { pwd -> exportPwd = pwd; dlg = Dlg.None; app.lock.skipNextLock = true; exportLauncher.launch("botdesk-config.bdcfg") },
        )
        Dlg.Import -> PasswordDialog(
            title = "Mot de passe du fichier — remplace la configuration actuelle",
            confirmField = false,
            onDismiss = { dlg = Dlg.None; importBytes = null },
            onOk = { pwd ->
                try {
                    val json = ConfigBackup.decrypt(importBytes ?: ByteArray(0), pwd.toCharArray())
                    app.store.replace(AppConfig.fromJson(JSONObject(json)))
                    dlg = Dlg.None; importBytes = null
                    toast(ctx, "Configuration importée")
                } catch (e: Exception) { toast(ctx, "Mot de passe ou fichier invalide") }
            },
        )
        Dlg.NewCategory -> NameDialog("Nouvelle catégorie", "", { dlg = Dlg.None }) { n ->
            app.store.update { it.copy(categories = it.categories + Category(UUID.randomUUID().toString(), n)) }; dlg = Dlg.None
        }
        Dlg.None -> Unit
    }
    if (voiceDialog) {
        val lang = when (cfg.voiceLang) { "fr" -> "fr"; "en" -> "en"; else -> Locale.getDefault().language }
        val list = voices.filter { it.language == lang }
        AlertDialog(
            onDismissRequest = { voiceDialog = false },
            title = { Text("Choisir la voix") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        TextButton(onClick = { app.store.update { it.copy(ttsVoice = "") }; voiceDialog = false }) { Text("Automatique (voix par défaut)") }
                    }
                    if (list.isEmpty()) item { Text("Aucune voix trouvée pour cette langue. Installez-en une dans les réglages Android (Synthèse vocale).", style = MaterialTheme.typography.bodySmall) }
                    items(list.size) { i ->
                        val v = list[i]
                        val q = when { v.quality >= 400 -> "haute qualité"; v.quality >= 300 -> "standard"; else -> "basique" }
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                app.store.update { it.copy(ttsVoice = v.name) }
                                voiceDialog = false
                                Speaker.speak(ctx, "Bonjour, ceci est un test de cette voix.", -1L, cfg.voiceLang)
                            }.padding(vertical = 8.dp),
                        ) {
                            Text(v.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(q + if (v.network) " · en ligne" else " · hors ligne", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { voiceDialog = false }) { Text("Fermer") } },
        )
    }
    renaming?.let { c ->
        NameDialog("Renommer la catégorie", c.name, { renaming = null }) { n ->
            app.store.update { it.copy(categories = it.categories.map { x -> if (x.id == c.id) x.copy(name = n) else x }) }; renaming = null
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (v.isNotBlank()) onOk(v.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun PinDialog(title: String, askConfirm: Boolean, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val kb = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(a, { a = it.filter(Char::isDigit).take(4) }, label = { Text("PIN à 4 chiffres") }, singleLine = true, keyboardOptions = kb, visualTransformation = PasswordVisualTransformation())
                if (askConfirm) OutlinedTextField(b, { b = it.filter(Char::isDigit).take(4) }, label = { Text("Confirmer") }, singleLine = true, keyboardOptions = kb, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(onClick = { if (a.length == 4 && (!askConfirm || a == b)) onOk(a) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun PasswordDialog(title: String, confirmField: Boolean, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(a, { a = it }, label = { Text("Mot de passe") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (confirmField) OutlinedTextField(b, { b = it }, label = { Text("Confirmer") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(onClick = { if (a.length >= 8 && (!confirmField || a == b)) onOk(a) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> FilterChip(selected == value, { onSelect(value) }, { Text(label) }) }
    }
}
