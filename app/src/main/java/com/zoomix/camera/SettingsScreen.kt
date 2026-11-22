package com.zoomix.camera

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    BackHandler(onBack = onBack)

    var mode by remember { mutableStateOf(SecureStore.glassMode(ctx)) }
    val tier = GlassConfig.resolve(ctx, mode)
    var provider by remember { mutableStateOf(SecureStore.provider(ctx)) }
    var key by remember(provider) { mutableStateOf(SecureStore.apiKey(ctx, provider) ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var keepOriginal by remember { mutableStateOf(SecureStore.keepOriginal(ctx)) }
    var videoQuality by remember { mutableStateOf(SecureStore.videoQuality(ctx)) }
    var micOn by remember { mutableStateOf(SecureStore.micOn(ctx)) }
    var selfieAi by remember { mutableStateOf(SecureStore.selfieAi(ctx)) }
    var selfieMirror by remember { mutableStateOf(SecureStore.selfieMirror(ctx)) }
    var zoomMap by remember { mutableStateOf(SecureStore.zoomMap(ctx)) }
    var volumeZoom by remember { mutableStateOf(SecureStore.volumeZoom(ctx)) }
    val camLines = remember { CameraDiag.describeCameras(ctx) }
    val lastAi = remember { SecureStore.lastAi(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF16213E))))
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Voltar") }
            Text("Ajustes", fontWeight = FontWeight.Bold)
        }

        GlassPanel(tier, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("IA para melhorar fotos", fontWeight = FontWeight.Bold)
                Provider.values().forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            provider = p
                            SecureStore.setProvider(ctx, p)
                            msg = ""
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = provider == p, onClick = null)
                        Text(p.label, Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Chave de API (${provider.label})") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.getText()?.let { key = it.text.trim() } }) { Text("Colar") }
                    OutlinedButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Ocultar" else "Mostrar") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        SecureStore.setApiKey(ctx, provider, key)
                        msg = "Chave salva neste aparelho."
                    }) { Text("Salvar") }
                    Button(enabled = !testing, onClick = {
                        testing = true
                        msg = "Testando…"
                        val p = provider
                        val k = key.trim()
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { AiClients.testKey(p, k) }
                            msg = err ?: "Chave aceita. Isso não confirma que a cobrança/crédito está ativo."
                            testing = false
                        }
                    }) { Text("Testar chave") }
                }
                if (msg.isNotEmpty()) Text(msg)
                Text(
                    "Última melhoria: " + lastAi.ifEmpty { "nenhuma ainda" },
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = { showGuide = !showGuide }) {
                    Text(if (showGuide) "Esconder instruções" else "Como conseguir minha chave")
                }
                if (showGuide) {
                    val guide = ProviderGuide.of(provider)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Passo a passo", fontWeight = FontWeight.SemiBold)
                        guide.steps.forEachIndexed { i, s -> Text("${i + 1}. $s") }
                        Text("Avisos", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        guide.warnings.forEach { Text("• $it") }
                    }
                }
            }
        }

        GlassPanel(tier, Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Manter a foto original", fontWeight = FontWeight.Bold)
                    Text("Desligado: só a melhorada fica na galeria.")
                }
                Switch(checked = keepOriginal, onCheckedChange = {
                    keepOriginal = it
                    SecureStore.setKeepOriginal(ctx, it)
                })
            }
        }

        GlassPanel(tier, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Câmera", fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Melhorar selfies com IA")
                        Text("Desligado por padrão: a IA pode alterar rostos.")
                    }
                    Switch(checked = selfieAi, onCheckedChange = {
                        selfieAi = it
                        SecureStore.setSelfieAi(ctx, it)
                    })
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Espelhar câmera frontal")
                        Text("Aplica o espelhamento às fotos e aos vídeos salvos. A prévia continua espelhada.")
                    }
                    Switch(checked = selfieMirror, onCheckedChange = {
                        selfieMirror = it
                        SecureStore.setSelfieMirror(ctx, it)
                    })
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Controle de zoom pelos botões de volume")
                        Text("Um clique abre a régua; os próximos ajustam o zoom.")
                    }
                    Switch(checked = volumeZoom, onCheckedChange = {
                        volumeZoom = it
                        SecureStore.setVolumeZoom(ctx, it)
                    })
                }
                Text("Mini-tela do zoom digital", fontWeight = FontWeight.SemiBold)
                listOf(
                    "auto" to "Automático (só celular forte)",
                    "on" to "Ligada",
                    "off" to "Desligada"
                ).forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            zoomMap = id
                            SecureStore.setZoomMap(ctx, id)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = zoomMap == id, onClick = null)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
                Text("Vale na próxima vez que você abrir a câmera.")
                Text("Câmeras detectadas pelo Android", fontWeight = FontWeight.SemiBold)
                camLines.forEach { Text(it) }
            }
        }

        GlassPanel(tier, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Vídeo", fontWeight = FontWeight.Bold)
                listOf(
                    "max" to "Máxima do aparelho (até 4K)",
                    "fhd" to "Full HD",
                    "hd" to "HD (mais leve)"
                ).forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            videoQuality = id
                            SecureStore.setVideoQuality(ctx, id)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = videoQuality == id, onClick = null)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Gravar áudio (microfone)")
                    Switch(checked = micOn, onCheckedChange = {
                        micOn = it
                        SecureStore.setMicOn(ctx, it)
                    })
                }
                Text("A qualidade vale na próxima vez que você abrir o modo Vídeo.")
            }
        }

        GlassPanel(tier, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Interface (vidro líquido)", fontWeight = FontWeight.Bold)
                listOf("auto" to "Automático", "leve" to "Leve (celular fraco)", "completo" to "Completo").forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            mode = id
                            SecureStore.setGlassMode(ctx, id)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == id, onClick = null)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        Text("Zoomix 1.1", modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
