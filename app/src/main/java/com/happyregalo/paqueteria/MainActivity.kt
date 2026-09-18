package com.happyregalo.paqueteria

import android.Manifest
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA)
        }
        setContent { App() }
    }
}

val Green = Color(0xFF00834E)
val Magenta = Color(0xFFD81B60)

@Composable
fun App() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, secondary = Magenta)) {
        var screen by remember { mutableStateOf("home") }
        var found by remember { mutableStateOf<PackageEntity?>(null) }
        Surface(Modifier.fillMaxSize()) {
            when (screen) {
                "home" -> Home { screen = it }
                "entry" -> Entry { screen = "home" }
                "find" -> Find({ found = it; screen = "result" }, { screen = "home" })
                "store" -> Store { screen = "home" }
                "config" -> Config { screen = "home" }
                "result" -> Result(found, { screen = "home" }, { screen = "home" })
            }
        }
    }
}

@Composable
fun Home(go: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
        Text("Paquetería Happy Regalo", fontSize = 28.sp)
        Spacer(Modifier.height(28.dp))
        listOf("📦 ENTRADA" to "entry", "🔎 LOCALIZAR" to "find", "📍 ALMACÉN" to "store", "⚙️ CONFIGURACIÓN" to "config")
            .forEach { (t, s) ->
                Button({ go(s) }, Modifier.fillMaxWidth().height(72.dp)) { Text(t, fontSize = 20.sp) }
                Spacer(Modifier.height(14.dp))
            }
        Text("PAKAY TEST 1.1", fontSize = 10.sp, color = Color.Gray)
    }
}

@Composable
fun Entry(done: () -> Unit) {
    val c = LocalContext.current
    val db = remember { AppDb.get(c) }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf<Int?>(null) }
    var slot by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (db.config().all().isEmpty()) {
            listOf(SlotConfig(1, "A", 30), SlotConfig(2, "B", 20), SlotConfig(3, "C", 12), SlotConfig(4, "D", 6))
                .forEach { db.config().save(it) }
        }
    }

    if (code == null) {
        Scanner(false) { code = it }
    } else if (size == null) {
        Column(Modifier.padding(20.dp)) {
            Text("¿QUÉ TAMAÑO TIENE?", fontSize = 24.sp)
            (1..4).forEach { n ->
                Button({ size = n; scope.launch { slot = firstFree(db, n) } }, Modifier.fillMaxWidth().padding(5.dp).height(64.dp)) {
                    Text("$n - " + listOf("Muy pequeño / sobre", "Pequeño", "Mediano", "Grande")[n - 1])
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("COLOCAR EN", fontSize = 25.sp)
            Text(slot ?: "...", fontSize = 72.sp, color = Green)
            Button({ scope.launch { db.packages().save(PackageEntity(code!!, size!!, slot!!)); done() } }, enabled = slot != null) { Text("ACEPTAR") }
            OutlinedButton({ slot = null; size = null }) { Text("CAMBIAR TAMAÑO") }
        }
    }
}

suspend fun firstFree(db: AppDb, size: Int): String {
    val cfg = db.config().all().first { it.size == size }
    val used = db.packages().activeNow().map { it.location }.toSet()
    return (1..cfg.count).map { "${cfg.prefix}${it.toString().padStart(2, '0')}" }.firstOrNull { it !in used } ?: "SIN HUECO"
}

suspend fun firstFreeForMove(db: AppDb, packageEntity: PackageEntity): String? {
    val config = db.config().all().firstOrNull { it.size == packageEntity.size } ?: return null
    val occupied = db.packages().activeNow().filter { it.shipmentId != packageEntity.shipmentId }.map { it.location }.toSet()
    return (1..config.count).map { "${config.prefix}${it.toString().padStart(2, '0')}" }.firstOrNull { it != packageEntity.location && it !in occupied }
}

@Composable
fun Find(onFound: (PackageEntity) -> Unit, back: () -> Unit) {
    val c = LocalContext.current
    val db = remember { AppDb.get(c) }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    if (mode == "ocr") {
        Scanner(true) { raw ->
            scope.launch {
                val digits = raw.filter { it.isDigit() }
                val all = db.packages().activeNow()
                val p = all.firstOrNull { digits.contains(it.shipmentId.filter(Char::isDigit)) || it.shipmentId.filter(Char::isDigit).contains(digits) }
                if (p != null) onFound(p)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(20.dp).navigationBarsPadding()) {
            Text("LOCALIZAR", fontSize = 28.sp)
            Button({ mode = "ocr" }, Modifier.fillMaxWidth()) { Text("🔍 LEER PANTALLA") }
            OutlinedTextField(manual, { manual = it }, label = { Text("Número completo o últimos dígitos") }, modifier = Modifier.fillMaxWidth())
            Button({ scope.launch {
                val q = manual.filter { it.isDigit() }
                val exact = db.packages().byId(q)
                val hits = exact?.let { listOf(it) } ?: db.packages().bySuffix(q)
                if (hits.size == 1) onFound(hits[0]) else msg = if (hits.isEmpty()) "No encontrado" else "Hay ${hits.size} coincidencias; escribe más dígitos."
            } }, Modifier.fillMaxWidth()) { Text("BUSCAR") }
            Text(msg)
            Spacer(Modifier.weight(1f))
            TextButton(back, Modifier.fillMaxWidth()) { Text("VOLVER") }
        }
    }
}

@Composable
fun Result(p: PackageEntity?, done: () -> Unit, back: () -> Unit) {
    val c = LocalContext.current
    val db = remember { AppDb.get(c) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(20.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("UBICACIÓN", fontSize = 26.sp)
        Text(p?.location ?: "?", fontSize = 80.sp, color = Green)
        Button({ p?.let { scope.launch { db.packages().deliver(it.shipmentId); done() } } }) { Text("ENTREGADO") }
        Spacer(Modifier.weight(1f))
        TextButton(back, Modifier.fillMaxWidth()) { Text("VOLVER") }
    }
}

@Composable
fun Store(back: () -> Unit) {
    val c = LocalContext.current
    val db = remember { AppDb.get(c) }
    val list by db.packages().active().collectAsState(initial = emptyList())
    var packageToMove by remember { mutableStateOf<PackageEntity?>(null) }
    if (packageToMove != null) {
        MovePackage(packageToMove!!, { packageToMove = null })
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp).navigationBarsPadding()) {
            Text("ALMACÉN", fontSize = 28.sp)
            LazyColumn(Modifier.weight(1f)) {
                items(list) { p ->
                    Card(Modifier.fillMaxWidth().padding(4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${p.location}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("T${p.size} • ${p.shipmentId.takeLast(6)}", fontSize = 14.sp, color = Color.Gray)
                            }
                            Button({ packageToMove = p }, Modifier.height(40.dp)) { Text("MOVER") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(back, Modifier.fillMaxWidth()) { Text("VOLVER") }
        }
    }
}

@Composable
fun MovePackage(packageEntity: PackageEntity, back: () -> Unit) {
    val c = LocalContext.current
    val db = remember { AppDb.get(c) }
    val scope = rememberCoroutineScope()
    var proposed by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(packageEntity.shipmentId) { proposed = firstFreeForMove(db, packageEntity); selected = proposed.orEmpty() }
    Column(Modifier.fillMaxSize().padding(20.dp).navigationBarsPadding()) {
        Text("MOVER PAQUETE", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Ubicación actual", fontSize = 14.sp, color = Color.Gray); Text(packageEntity.location, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Green) } }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Tamaño", fontSize = 14.sp, color = Color.Gray); Text("T${packageEntity.size}", fontSize = 26.sp, fontWeight = FontWeight.Bold) } }
        Spacer(Modifier.height(12.dp))
        Text("Nueva ubicación propuesta", fontSize = 14.sp, color = Color.Gray)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(selected.ifEmpty { "SIN HUECO" }, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Green) } }
        Spacer(Modifier.height(8.dp))
        Button({ selected = proposed.orEmpty(); message = "" }, enabled = proposed != null, modifier = Modifier.fillMaxWidth()) { Text("USAR UBICACIÓN PROPUESTA") }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(manual, { manual = it.uppercase().trim(); message = "" }, label = { Text("Otra ubicación") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button({ scope.launch { val candidate = manual.uppercase().trim(); if (candidate.isEmpty()) message = "Introduce una ubicación" else { val occupied = db.packages().activeNow().any { it.shipmentId != packageEntity.shipmentId && it.location.equals(candidate, ignoreCase = true) }; if (occupied) message = "UBICACIÓN OCUPADA" else { selected = candidate; message = "" } } } }, modifier = Modifier.fillMaxWidth()) { Text("ELEGIR OTRA UBICACIÓN") }
        if (message.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(message, color = if (message.startsWith("PAQUETE")) Green else Color.Red, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (selected.isNotEmpty() && !selected.equals(packageEntity.location, ignoreCase = true)) confirming = true }, enabled = selected.isNotEmpty() && !selected.equals(packageEntity.location, ignoreCase = true), modifier = Modifier.weight(1f)) { Text("MOVER") }
            OutlinedButton({ back() }, Modifier.weight(1f)) { Text("CANCELAR") }
        }
    }
    if (confirming) {
        AlertDialog(onDismissRequest = { confirming = false }, title = { Text("MOVER PAQUETE") }, text = { Text("De: ${packageEntity.location}\nA: $selected") }, confirmButton = {
            TextButton({ scope.launch { val destination = selected; val updated = db.packages().moveIfFree(packageEntity.shipmentId, destination); if (updated == 1) { confirming = false; message = "PAQUETE MOVIDO A $destination"; delay(1000); back() } else { confirming = false; message = "UBICACIÓN OCUPADA" } } }) { Text("CONFIRMAR") }
        }, dismissButton = { TextButton({ confirming = false }) { Text("CANCELAR") } })
    }
}

@Composable
fun Config(back: () -> Unit) {
    val c = LocalContext.current
    val db = remember { AppDb.get(c) }
    val scope = rememberCoroutineScope()
    var configs by remember { mutableStateOf<List<SlotConfig>>(emptyList()) }
    var editedConfigs by remember { mutableStateOf<Map<Int, Pair<String, String>>>(emptyMap()) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        configs = db.config().all()
        if (configs.isEmpty()) { listOf(SlotConfig(1, "A", 30), SlotConfig(2, "B", 20), SlotConfig(3, "C", 12), SlotConfig(4, "D", 6)).also { defaults -> defaults.forEach { db.config().save(it) }; configs = defaults } }
        editedConfigs = configs.associate { it.size to (it.prefix to it.count.toString()) }
    }
    Column(Modifier.fillMaxSize().padding(20.dp).navigationBarsPadding()) {
        Text("CONFIGURACIÓN", fontSize = 28.sp)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(configs.size) { index ->
                val cfg = configs[index]
                val (editedPrefix, editedCount) = editedConfigs[cfg.size] ?: (cfg.prefix to cfg.count.toString())
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("TAMAÑO ${cfg.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(editedPrefix, { newPrefix -> editedConfigs = editedConfigs.toMutableMap().apply { this[cfg.size] = newPrefix.uppercase().trim() to editedCount } }, label = { Text("Prefijo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(editedCount, { newCount -> editedConfigs = editedConfigs.toMutableMap().apply { this[cfg.size] = editedPrefix to newCount.filter { it.isDigit() } } }, label = { Text("Número de ubicaciones") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                }
            }
        }
        if (error.isNotEmpty()) { Text(error, color = Color.Red, fontSize = 14.sp); Spacer(Modifier.height(8.dp)) }
        if (saved) { Text("✓ CONFIGURACIÓN GUARDADA", color = Green, fontSize = 16.sp); Spacer(Modifier.height(8.dp)) }
        Button({ error = ""; val newConfigs = editedConfigs.map { (size, pair) -> SlotConfig(size, pair.first, pair.second.toIntOrNull() ?: 0) }; val validation = validateConfig(newConfigs); if (validation == null) scope.launch { newConfigs.forEach { db.config().save(it) }; configs = newConfigs; saved = true } else error = validation }, Modifier.fillMaxWidth().height(56.dp)) { Text("GUARDAR CONFIGURACIÓN", fontSize = 16.sp) }
        Spacer(Modifier.height(8.dp))
        TextButton(back, Modifier.fillMaxWidth()) { Text("VOLVER") }
    }
}

fun validateConfig(configs: List<SlotConfig>): String? {
    configs.forEach { cfg ->
        if (cfg.prefix.isEmpty()) return "Prefijo no puede estar vacío"
        if (cfg.count < 1) return "El número de ubicaciones debe ser mínimo 1"
    }
    if (configs.map { it.prefix }.size != configs.map { it.prefix }.toSet().size) return "Dos tamaños no pueden tener el mismo prefijo"
    return null
}

@Composable
fun Scanner(ocr: Boolean, onRead: (String) -> Unit) {
    val c = LocalContext.current
    var locked by remember { mutableStateOf(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var analysisUseCase by remember { mutableStateOf<ImageAnalysis?>(null) }
    AndroidView(factory = { ctx ->
        PreviewView(ctx).also { pv ->
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({
                val provider = f.get(); cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysisUseCase = analysis
                val exec = Executors.newSingleThreadExecutor()
                analysis.setAnalyzer(exec) { proxy ->
                    if (locked) { proxy.close(); return@setAnalyzer }
                    val img = proxy.image ?: run { proxy.close(); return@setAnalyzer }
                    val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
                    if (!ocr) {
                        BarcodeScanning.getClient().process(input).addOnSuccessListener { bs -> bs.firstOrNull()?.rawValue?.let { locked = true; vibrate(c); ContextCompat.getMainExecutor(ctx).execute { onRead(it) }; try { cameraProvider?.unbindAll() } catch (e: Exception) { e.printStackTrace() } } }.addOnCompleteListener { proxy.close() }
                    } else {
                        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(input).addOnSuccessListener { t -> Regex("\\d{8,}").findAll(t.text.replace(" ", "")).firstOrNull()?.value?.let { locked = true; vibrate(c); ContextCompat.getMainExecutor(ctx).execute { onRead(it) }; try { cameraProvider?.unbindAll() } catch (e: Exception) { e.printStackTrace() } } }.addOnCompleteListener { proxy.close() }
                    }
                }
                provider.unbindAll(); provider.bindToLifecycle(ctx as ComponentActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
        }
    }, modifier = Modifier.fillMaxSize())
}

fun vibrate(c: Context) {
    try {
        val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (e: Exception) { e.printStackTrace() }
}
