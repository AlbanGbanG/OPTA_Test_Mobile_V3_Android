package fr.opta.testmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.opta.testmobile.ble.ConnectionStatus
import fr.opta.testmobile.ble.LogLevel
import fr.opta.testmobile.ble.OptaConfig
import fr.opta.testmobile.ble.OptaLogEntry
import fr.opta.testmobile.ble.OptaMeasurements
import fr.opta.testmobile.ble.OptaUiState
import fr.opta.testmobile.ble.OptaTestRecord
import fr.opta.testmobile.ble.recipeReference
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun OptaApp(
    state: OptaUiState,
    onPrimaryAction: () -> Unit,
    primaryActionLabel: String,
    onRequestConfig: () -> Boolean,
    onRequestStatus: () -> Boolean,
    onSaveConfig: (OptaConfig) -> Boolean,
    onSendConsoleCommand: (String) -> Boolean,
    onClearLogs: () -> Unit,
    onAuthenticate: (String) -> Boolean,
    onLock: () -> Boolean,
    onStartCycle: () -> Boolean,
    onStopCycle: () -> Boolean,
    onAcknowledge: () -> Boolean,
    onRequestHistory: () -> Boolean,
    onExportArchive: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("●") },
                    label = { Text("Tableau") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("⚙") },
                    label = { Text("Paramètres") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("≡") },
                    label = { Text("Journal") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Text("▤") },
                    label = { Text("Essais") }
                )
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(state, onPrimaryAction, primaryActionLabel,
                    onAuthenticate, onLock, onStartCycle, onStopCycle, onAcknowledge)
                1 -> SettingsScreen(state, onRequestConfig, onSaveConfig, onAuthenticate, onLock)
                2 -> JournalScreen(
                    state = state,
                    onRequestConfig = onRequestConfig,
                    onRequestStatus = onRequestStatus,
                    onSendConsoleCommand = onSendConsoleCommand,
                    onClearLogs = onClearLogs
                )
                else -> HistoryScreen(state, onRequestHistory, onExportArchive)
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    state: OptaUiState,
    onPrimaryAction: () -> Unit,
    primaryActionLabel: String,
    onAuthenticate: (String) -> Boolean,
    onLock: () -> Boolean,
    onStartCycle: () -> Boolean,
    onStopCycle: () -> Boolean,
    onAcknowledge: () -> Boolean
) {
    var confirmStart by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ConnectionHeader(state) }
        item { AccessCard(state, onAuthenticate, onLock) }
        item { PressureCard(state.measurements) }
        item { ResultCard(state.measurements) }
        item { ProcessCard(state.measurements) }
        if (state.measurements.faultCode != 0) item { FaultCard(state.measurements) }
        item { DetailsCard(state.measurements) }
        item {
            SectionCard("Pilotage du cycle") {
                Text("Les commandes suivent l’automate et ses seuils. Aucune sortie ne peut être forcée manuellement depuis le téléphone.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmStart = true },
                        enabled = state.connected && state.authenticated &&
                            state.measurements.phaseCode in listOf(0, 6) &&
                            state.measurements.faultCode == 0 &&
                            state.measurements.pressureElementRaw <= state.config.restPressureMaxRaw,
                        modifier = Modifier.weight(1f)) { Text("START") }
                    Button(onClick = { onStopCycle() }, enabled = state.connected,
                        modifier = Modifier.weight(1f)) { Text("STOP / PURGE") }
                }
                OutlinedButton(onClick = { onAcknowledge() },
                    enabled = state.connected && state.authenticated &&
                        state.measurements.faultCode != 0 &&
                        state.measurements.pressureElementRaw <= state.config.restPressureMaxRaw &&
                        state.measurements.phaseCode in listOf(0, 8),
                    modifier = Modifier.fillMaxWidth()) { Text("Acquitter un défaut après purge") }
                if (state.commandFeedback.isNotBlank()) Text(state.commandFeedback,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            if (state.connectionStatus != ConnectionStatus.CONNECTED) {
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(primaryActionLabel)
                }
            } else {
                OutlinedButton(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text("Forcer une reconnexion")
                }
            }
        }
    }
    if (confirmStart) {
        AlertDialog(onDismissRequest = { confirmStart = false },
            title = { Text("Confirmer le démarrage à distance") },
            text = { Text("Vérifie que la zone de test est dégagée, les sécurités physiques opérationnelles et les capteurs étalonnés. Le cycle peut mettre le circuit sous pression.") },
            confirmButton = { Button(onClick = { onStartCycle(); confirmStart = false }) { Text("Démarrer") } },
            dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("Annuler") } })
    }
}

@Composable
private fun AccessCard(
    state: OptaUiState,
    onAuthenticate: (String) -> Boolean,
    onLock: () -> Boolean
) {
    var code by remember { mutableStateOf("") }
    SectionCard("Accès protégé") {
        Text(if (state.authenticated) "Accès autorisé pour la session BLE (expiration après 5 minutes d’inactivité)."
            else "Entre le code à 6 chiffres configuré dans le moniteur série USB.",
            style = MaterialTheme.typography.bodySmall)
        if (state.authenticated) {
            OutlinedButton(onClick = { onLock() }, enabled = state.connected) { Text("Verrouiller") }
        } else {
            OutlinedTextField(value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Code d’accès") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { if (onAuthenticate(code)) code = "" },
                enabled = state.connected && code.length == 6) { Text("Déverrouiller") }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: OptaUiState,
    onRequestConfig: () -> Boolean,
    onSaveConfig: (OptaConfig) -> Boolean,
    onAuthenticate: (String) -> Boolean,
    onLock: () -> Boolean
) {
    var draft by remember { mutableStateOf(ConfigDraft.from(state.config)) }
    var pendingConfig by remember { mutableStateOf<OptaConfig?>(null) }

    LaunchedEffect(state.config, state.configLoaded) {
        if (state.configLoaded) draft = ConfigDraft.from(state.config)
    }

    val parsedConfig = draft.toConfigOrNull()
    val inputError = parsedConfig?.validationError()
        ?: if (draft.hasBlankOrInvalidValue()) "Vérifie les valeurs saisies." else null
    val blockedReason = state.settingsBlockedReason

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ConnectionHeader(state) }
        item { AccessCard(state, onAuthenticate, onLock) }
        item {
            SectionCard("Recette active") {
                NumberField(
                    label = "Numéro de recette",
                    value = draft.recipe,
                    suffix = "41 à 52",
                    decimal = false,
                    onValueChange = { draft = draft.copy(recipe = it) }
                )
                Text(
                    text = "Référence : ${recipeReference(draft.recipe.toIntOrNull() ?: 0)}",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        item {
            SectionCard("Pressions") {
                Text("Capteurs GEMS 3100R : 0 à 10 bar (0 à 5 V). Le seuil de sécurité R49 ne peut pas dépasser 10,00 bar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumberField("Pression cible", draft.pressureTargetBar, "bar", true) {
                    draft = draft.copy(pressureTargetBar = it)
                }
                NumberField("Chute maximale autorisée", draft.pressureDropMaxBar, "bar", true) {
                    draft = draft.copy(pressureDropMaxBar = it)
                }
                NumberField("Pression minimale contrôle débit", draft.flowMinBar, "bar", true) {
                    draft = draft.copy(flowMinBar = it)
                }
                NumberField("Pression maximale contrôle débit", draft.flowMaxBar, "bar", true) {
                    draft = draft.copy(flowMaxBar = it)
                }
                NumberField("Pression maximale au repos", draft.restMaxBar, "bar", true) {
                    draft = draft.copy(restMaxBar = it)
                }
                NumberField("Pression de sécurité", draft.safetyMaxBar, "bar", true) {
                    draft = draft.copy(safetyMaxBar = it)
                }
            }
        }
        item {
            SectionCard("Durées") {
                NumberField("Mise en pression", draft.pressureBuildSeconds, "secondes", false) {
                    draft = draft.copy(pressureBuildSeconds = it)
                }
                NumberField("Test de tenue", draft.holdSeconds, "secondes", false) {
                    draft = draft.copy(holdSeconds = it)
                }
                NumberField("Contrôle du débit", draft.flowSeconds, "secondes", false) {
                    draft = draft.copy(flowSeconds = it)
                }
                NumberField("Purge minimale", draft.purgeSeconds, "secondes", false) {
                    draft = draft.copy(purgeSeconds = it)
                }
                NumberField("Temporisation électrovannes", draft.valveDelayMs, "ms", false) {
                    draft = draft.copy(valveDelayMs = it)
                }
            }
        }
        item {
            SectionCard("Options") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.78f)) {
                        Text("Impression Zebra", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (draft.zebraEnabled) "Impression activée (archivage toujours actif)" else "Impression désactivée (archivage actif)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = draft.zebraEnabled,
                        onCheckedChange = { draft = draft.copy(zebraEnabled = it) }
                    )
                }
            }
        }
        if (blockedReason != null) {
            item { MessageCard("Modification bloquée", blockedReason, warning = true) }
        }
        if (inputError != null) {
            item { MessageCard("Valeurs à corriger", inputError, warning = true) }
        }
        if (state.commandFeedback.isNotBlank()) {
            item { MessageCard("Réponse de l’Opta", state.commandFeedback, warning = false) }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = { onRequestConfig() }, enabled = state.connected && !state.commandBusy) {
                    Text("Actualiser")
                }
                Button(
                    onClick = { pendingConfig = parsedConfig },
                    enabled = parsedConfig != null && inputError == null && blockedReason == null && !state.commandBusy
                ) {
                    Text(if (state.commandBusy) "Envoi…" else "Enregistrer les paramètres")
                }
            }
        }
        item {
            Text(
                "Les réglages requièrent le code et sont refusés pendant un cycle ou si la pression est trop élevée.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }

    pendingConfig?.let { config ->
        AlertDialog(
            onDismissRequest = { pendingConfig = null },
            title = { Text("Confirmer les modifications") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Recette ${config.recipe} — ${config.reference}")
                    Text("Pression cible : ${formatBar(config.pressureTargetRaw / 100.0)}")
                    Text("Tenue : ${config.holdSeconds} s")
                    Text("Sécurité : ${formatBar(config.safetyPressureMaxRaw / 100.0)}")
                    Text("Impression Zebra : ${if (config.zebraEnabled) "activée" else "désactivée"}")
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveConfig(config)
                    pendingConfig = null
                }) { Text("Confirmer") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfig = null }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun JournalScreen(
    state: OptaUiState,
    onRequestConfig: () -> Boolean,
    onRequestStatus: () -> Boolean,
    onSendConsoleCommand: (String) -> Boolean,
    onClearLogs: () -> Unit
) {
    var command by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ConnectionHeader(state) }
        item {
            SectionCard("Actions rapides") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { onRequestStatus() }, enabled = state.connected) {
                        Text("État")
                    }
                    OutlinedButton(onClick = { onRequestConfig() }, enabled = state.connected) {
                        Text("Paramètres")
                    }
                    OutlinedButton(onClick = { onSendConsoleCommand("SENSORS") }, enabled = state.connected) {
                        Text("Capteurs")
                    }
                    OutlinedButton(
                        onClick = { onSendConsoleCommand("ON") },
                        enabled = state.connected && state.authenticated
                    ) { Text("Zebra ON") }
                    OutlinedButton(
                        onClick = { onSendConsoleCommand("OFF") },
                        enabled = state.connected && state.authenticated
                    ) { Text("Zebra OFF") }
                }
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it.take(80) },
                    label = { Text("Commande maintenance") },
                    supportingText = { Text("Exemples : STATUS, SENSORS, GETCFG, HISTINFO. Code par écran Accès protégé.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (onSendConsoleCommand(command)) command = ""
                    },
                    enabled = state.connected && command.isNotBlank() && !state.commandBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.commandBusy) "Envoi en cours…" else "Envoyer")
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Journal des événements", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClearLogs) { Text("Effacer") }
            }
        }
        if (state.logs.isEmpty()) {
            item {
                MessageCard(
                    title = "Aucun événement",
                    message = "Les phases, validations, défauts et commandes apparaîtront ici.",
                    warning = false
                )
            }
        } else {
            items(state.logs.asReversed(), key = { it.id }) { entry ->
                LogCard(entry)
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    state: OptaUiState,
    onRequestHistory: () -> Boolean,
    onExportArchive: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ConnectionHeader(state) }
        item {
            SectionCard("Historique des essais") {
                Text("${state.tests.size} résultats mémorisés sur le téléphone. " +
                    "L’OPTA conserve ses 50 derniers essais et l’application les fusionne à chaque synchronisation.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onRequestHistory() }, enabled = state.connected && !state.historyLoading) {
                        Text(if (state.historyLoading) "Lecture…" else "Synchroniser OPTA")
                    }
                    Button(onClick = onExportArchive) { Text("Exporter CSV") }
                }
                if (state.historyLoading) Text("Synchronisation ${state.historyProgress} / ${state.historyCount}")
            }
        }
        if (state.tests.isEmpty()) {
            item { Text("Aucun résultat transféré pour le moment.") }
        } else {
            items(state.tests, key = { it.id }) { t ->
                SectionCard(if (t.isSuccess) "CONFORME · essai ${t.id}" else "DÉFAUT ${t.fault} · essai ${t.id}") {
                    InformationRow("N° série", t.serial)
                    InformationRow("Recette", "${t.recipe} · ${recipeReference(t.recipe)}")
                    InformationRow("Pression", "${t.pressureBar} bar")
                    InformationRow("Date OPTA", "${t.testDate} ${t.testTime}")
                }
            }
        }
    }
}

@Composable
private fun ConnectionHeader(state: OptaUiState) {
    val connected = state.connectionStatus == ConnectionStatus.CONNECTED
    val statusColor = when (state.connectionStatus) {
        ConnectionStatus.CONNECTED -> Color(0xFF12844C)
        ConnectionStatus.SEARCHING,
        ConnectionStatus.CONNECTING,
        ConnectionStatus.DISCOVERING -> Color(0xFFCE7A00)
        ConnectionStatus.ERROR,
        ConnectionStatus.NOT_FOUND,
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("OPTA TEST MOBILE 3 · V10", style = MaterialTheme.typography.titleLarge)
                    Text(
                        state.deviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        if (connected) "Connecté" else statusShortLabel(state.connectionStatus),
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                state.connectionMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PressureCard(data: OptaMeasurements) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 26.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("PRESSION ÉLÉMENT · I2", style = MaterialTheme.typography.labelLarge)
            Text(text = formatBar(data.pressureElementBar),
                fontSize = 48.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text("PRESSION ENTRÉE · I1", style = MaterialTheme.typography.labelLarge)
            Text(text = formatBar(data.pressureInletBar),
                fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("GEMS 3100R · 0–10 bar · sortie 0–5 V",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun ResultCard(data: OptaMeasurements) {
    val normalized = data.result.trim().uppercase(Locale.FRANCE)
    val visual = when {
        data.faultCode != 0 || normalized.contains("DEFAUT") -> ResultVisual(
            "TEST NON CONFORME", "Défaut ${data.faultCode}", Color(0xFFFFDAD6), Color(0xFF93000A)
        )
        normalized == "OK" -> ResultVisual(
            "TEST VALIDÉ", "Cycle terminé avec succès", Color(0xFFD4F6DF), Color(0xFF00522B)
        )
        normalized.contains("COURS") -> ResultVisual(
            "TEST EN COURS", data.phaseText.ifBlank { "Cycle en cours" }, Color(0xFFFFE1B3), Color(0xFF6C4500)
        )
        else -> ResultVisual(
            "PRÊT", "En attente du démarrage", MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = visual.container)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(visual.title, style = MaterialTheme.typography.headlineMedium, color = visual.content)
            Spacer(Modifier.height(4.dp))
            Text(
                visual.subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = visual.content.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ProcessCard(data: OptaMeasurements) {
    SectionCard("Cycle") {
        InformationRow("Phase", data.phaseText.ifBlank { "Phase ${data.phaseCode}" }, true)
        HorizontalDivider()
        InformationRow("Temps restant", "${data.remainingSeconds} s", true)
        HorizontalDivider()
        InformationRow("Recette", "${data.reference} · ${data.recipe}")
        HorizontalDivider()
        InformationRow("D0 · échappement", if (data.exhaustActive) "OUVERT" else "FERMÉ")
        HorizontalDivider()
        InformationRow("D1 · entrée air", if (data.inletActive) "OUVERTE" else "FERMÉE")
    }
}

@Composable
private fun FaultCard(data: OptaMeasurements) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFDAD6))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("DÉFAUT ${data.faultCode}", style = MaterialTheme.typography.titleLarge, color = Color(0xFF93000A))
            Spacer(Modifier.height(6.dp))
            Text(data.faultText, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF93000A))
        }
    }
}

@Composable
private fun DetailsCard(data: OptaMeasurements) {
    SectionCard("Détails du test") {
        InformationRow("Pression de référence", formatBar(data.pressureTestBar))
        HorizontalDivider()
        InformationRow("Numéro de série", data.serialNumber.ifBlank { "—" })
        HorizontalDivider()
        InformationRow("Écran IHM", data.screenCode.toString())
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    suffix: String,
    decimal: Boolean,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            val allowed = text.filter { it.isDigit() || (decimal && (it == ',' || it == '.')) }
            onValueChange(allowed.take(8))
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun InformationRow(label: String, value: String, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.48f)
        )
        Text(
            text = value,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun MessageCard(title: String, message: String, warning: Boolean) {
    val container = if (warning) Color(0xFFFFE1B3) else MaterialTheme.colorScheme.secondaryContainer
    val content = if (warning) Color(0xFF6C4500) else MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = content)
            Spacer(Modifier.height(4.dp))
            Text(message, color = content)
        }
    }
}

@Composable
private fun LogCard(entry: OptaLogEntry) {
    val visual = when (entry.level) {
        LogLevel.ERROR -> LogVisual(Color(0xFFFFDAD6), Color(0xFF93000A), "ERREUR")
        LogLevel.WARNING -> LogVisual(Color(0xFFFFE1B3), Color(0xFF6C4500), "ALERTE")
        LogLevel.SUCCESS -> LogVisual(Color(0xFFD4F6DF), Color(0xFF00522B), "VALIDÉ")
        LogLevel.PHASE -> LogVisual(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, "PHASE")
        LogLevel.CONFIG -> LogVisual(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, "RÉGLAGE")
        LogLevel.COMMAND -> LogVisual(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "COMMANDE")
        LogLevel.INFO -> LogVisual(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, "INFO")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = visual.container),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(visual.label, fontWeight = FontWeight.Bold, color = visual.content)
                Text(entry.time, color = visual.content.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(5.dp))
            Text(entry.message, color = visual.content)
        }
    }
}

private data class ResultVisual(
    val title: String,
    val subtitle: String,
    val container: Color,
    val content: Color
)

private data class LogVisual(
    val container: Color,
    val content: Color,
    val label: String
)

private data class ConfigDraft(
    val recipe: String,
    val pressureTargetBar: String,
    val pressureDropMaxBar: String,
    val flowMinBar: String,
    val flowMaxBar: String,
    val pressureBuildSeconds: String,
    val holdSeconds: String,
    val flowSeconds: String,
    val purgeSeconds: String,
    val restMaxBar: String,
    val safetyMaxBar: String,
    val valveDelayMs: String,
    val zebraEnabled: Boolean
) {
    fun hasBlankOrInvalidValue(): Boolean = toConfigOrNull() == null

    fun toConfigOrNull(): OptaConfig? {
        return OptaConfig(
            recipe = recipe.toIntOrNull() ?: return null,
            pressureTargetRaw = parseBarRaw(pressureTargetBar) ?: return null,
            pressureDropMaxRaw = parseBarRaw(pressureDropMaxBar) ?: return null,
            flowPressureMinRaw = parseBarRaw(flowMinBar) ?: return null,
            flowPressureMaxRaw = parseBarRaw(flowMaxBar) ?: return null,
            pressureBuildSeconds = pressureBuildSeconds.toIntOrNull() ?: return null,
            holdSeconds = holdSeconds.toIntOrNull() ?: return null,
            flowCheckSeconds = flowSeconds.toIntOrNull() ?: return null,
            purgeSeconds = purgeSeconds.toIntOrNull() ?: return null,
            restPressureMaxRaw = parseBarRaw(restMaxBar) ?: return null,
            safetyPressureMaxRaw = parseBarRaw(safetyMaxBar) ?: return null,
            valveDelayMs = valveDelayMs.toIntOrNull() ?: return null,
            zebraEnabled = zebraEnabled
        )
    }

    companion object {
        fun from(config: OptaConfig): ConfigDraft = ConfigDraft(
            recipe = config.recipe.toString(),
            pressureTargetBar = formatRaw(config.pressureTargetRaw),
            pressureDropMaxBar = formatRaw(config.pressureDropMaxRaw),
            flowMinBar = formatRaw(config.flowPressureMinRaw),
            flowMaxBar = formatRaw(config.flowPressureMaxRaw),
            pressureBuildSeconds = config.pressureBuildSeconds.toString(),
            holdSeconds = config.holdSeconds.toString(),
            flowSeconds = config.flowCheckSeconds.toString(),
            purgeSeconds = config.purgeSeconds.toString(),
            restMaxBar = formatRaw(config.restPressureMaxRaw),
            safetyMaxBar = formatRaw(config.safetyPressureMaxRaw),
            valveDelayMs = config.valveDelayMs.toString(),
            zebraEnabled = config.zebraEnabled
        )
    }
}

private fun parseBarRaw(value: String): Int? {
    val number = value.replace(',', '.').toDoubleOrNull() ?: return null
    if (number < 0.0 || number > 655.35) return null
    return (number * 100.0).roundToInt()
}

private fun formatRaw(raw: Int): String = String.format(Locale.FRANCE, "%.2f", raw / 100.0)
private fun formatBar(value: Double): String = String.format(Locale.FRANCE, "%.2f bar", value)

private fun statusShortLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.IDLE -> "Arrêté"
    ConnectionStatus.BLUETOOTH_UNAVAILABLE -> "Indisponible"
    ConnectionStatus.BLUETOOTH_DISABLED -> "Bluetooth coupé"
    ConnectionStatus.PERMISSION_REQUIRED -> "Autorisation"
    ConnectionStatus.SEARCHING -> "Recherche"
    ConnectionStatus.CONNECTING -> "Connexion"
    ConnectionStatus.DISCOVERING -> "Initialisation"
    ConnectionStatus.CONNECTED -> "Connecté"
    ConnectionStatus.NOT_FOUND -> "Introuvable"
    ConnectionStatus.DISCONNECTED -> "Déconnecté"
    ConnectionStatus.ERROR -> "Erreur"
}
