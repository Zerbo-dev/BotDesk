package com.botdesk.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.botdesk.app.td.AuthUi
import kotlinx.coroutines.launch

@Composable
fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
fun LoadingScreen(text: String) {
    CenteredColumn {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text)
    }
}

@Composable
fun CredentialsScreen() {
    val app = LocalApp.current
    var id by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    CenteredColumn {
        Text("BotDesk", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "Créez une application sur my.telegram.org (API development tools), puis saisissez son api_id et son api_hash. " +
                "Ils sont stockés chiffrés sur ce téléphone et ne sont jamais envoyés ailleurs que vers Telegram.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(id, { id = it.filter(Char::isDigit) }, label = { Text("api_id") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(hash, { hash = it.trim() }, label = { Text("api_hash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ErrorText(error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            val n = id.toIntOrNull()
            if (n == null || hash.length < 16) error = "api_id ou api_hash invalide" else app.tg.submitCredentials(n, hash)
        }, modifier = Modifier.fillMaxWidth()) { Text("Continuer") }
    }
}

@Composable
fun LoginScreen(state: AuthUi) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var input by remember(state) { mutableStateOf("") }
    var error by remember(state) { mutableStateOf<String?>(null) }
    var busy by remember(state) { mutableStateOf(false) }

    fun submit() {
        if (input.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                when (state) {
                    is AuthUi.WaitPhone -> app.tg.submitPhone(input.trim())
                    is AuthUi.WaitCode -> app.tg.submitCode(input.trim())
                    is AuthUi.WaitPassword -> app.tg.submitPassword(input)
                    else -> Unit
                }
            } catch (e: Exception) {
                error = e.message ?: "Erreur"
            } finally {
                busy = false
            }
        }
    }

    val (title, label, kb) = when (state) {
        is AuthUi.WaitPhone -> Triple("Connexion à Telegram", "Numéro (ex. +22670000000)", KeyboardType.Phone)
        is AuthUi.WaitCode -> Triple("Code de vérification", "Code reçu dans Telegram", KeyboardType.Number)
        is AuthUi.WaitPassword -> Triple("Mot de passe (2FA)", "Mot de passe" + (if (state.hint.isNotBlank()) " — indice : ${state.hint}" else ""), KeyboardType.Password)
        else -> Triple("", "", KeyboardType.Text)
    }

    CenteredColumn {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            input, { input = it }, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = kb),
            visualTransformation = if (state is AuthUi.WaitPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Patientez…" else "Valider")
        }
    }
}
