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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
                Button({ go(s) }, Modifier.fillMaxWidth().height(72.dp)) {
                    Text(t, fontSize = 20.sp)
                }
                Spacer(Modifier.height(14.dp))
            }
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
            listOf(
                SlotConfig(1, "A", 30),
                SlotConfig(2, "B", 20),
                SlotConfig(3, "C", 12),
                SlotConfig(4, "D", 6)
            ).forEach { db.config().save(it) }
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
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("COLOCAR EN", fontSize = 25.sp)
            Text(slot ?: "...", fontSize = 72.sp, color = Green)
            Button(
                { 
                    scope.launch {
                        db.packages().save(PackageEntity(code!!, size!!, slot!!))
                        done()
                    }
                },
                enabled = slot != null
            ) {
                Text("ACEPTAR")
            }
            OutlinedButton({ slot = null; size = null }) {
                Text("CAMBIAR TAMAÑO")
            }
        }
    }
}

suspend fun firstFree(db: AppDb, size: Int): String {
    val cfg = db.config().all().first { it.size == size }
    val used = db.packages().activeNow().map { it.location }.toSet()
    return (1..cfg.count).map { "${cfg.prefix}${it.toString().padStart(2, '0')}" }.firstOrNull { it !in used } ?: "SIN HUECO"
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
                val p = all.firstOrNull {
                    digits.contains(it.shipmentId.filter(Char::isDigit)) ||
                    it.shipmentId.filter(Char::isDigit).contains(digits)
                }
                if (p != null) onFound(p)
            }
        }
    } else {
        Column(Modifier.padding(20.dp)) {
            Text("LOCALIZAR", fontSize = 28.sp)
            Button({ mode = "ocr" }, Modifier.fillMaxWidth()) {
                Text("🔍 LEER PANTALLA")
            }
            OutlinedTextField(
                manual,
                { manual = it },
                label = { Text("Número completo o últimos dígitos") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                {
                    scope.launch {
                        val q = manual.filter { it.isDigit() }
                        val exact = db.packages().byId(q)
                        val hits = exact?.let { listOf(it) } ?: db.packages().bySuffix(q)
                        if (hits.size == 1) {
                            onFound(hits[0])
                        } else {
                            msg = if (hits.isEmpty()) "No encontrado" else "Hay ${hits.size} coincidencias; escribe más dígitos."
                        }
                    }
                },
                Modifier.fillMaxWidth()
            ) {
                Text("BUSCAR")
            }
            Text(msg)
            TextButton(back) {
                Text("VOLVER")
            }
        }
    }
}

@Composable
fun Result(p: PackageEntity?, done: () -> Unit, back: () -> Unit) {
    val c = LocalContext.current
    val db = remember { AppDb.get(c) }
    val scope = rememberCoroutineScope()
    
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("UBICACIÓN", fontSize = 26.sp)
        Text(p?.location ?: "?", fontSize = 80.sp, color = Green)
        Button(
            {
                p?.let {
                    scope.launch {
                        db.packages().deliver(it.shipmentId)
                        done()
                    }
                }
            }
        ) {
            Text("ENTREGADO")
        }
        TextButton(back) {
            Text("VOLVER")
        }
    }
}

@Composable
fun Store(back: () -> Unit) {
    val c = LocalContext.current
    val db = remember { AppDb.get(c) }
    val list by db.packages().active().collectAsState(initial = emptyList())
    
    Column(Modifier.padding(16.dp)) {
        Text("ALMACÉN", fontSize = 28.sp)
        LazyColumn(Modifier.weight(1f)) {
            items(list) { p ->
                Card(Modifier.fillMaxWidth().padding(4.dp)) {
                    Row(Modifier.padding(14.dp)) {
                        Text("${p.location} ✕ T${p.size} ✕ ⏱${p.shipmentId.takeLast(6)}")
                    }
                }
            }
        }
        Button(back, Modifier.fillMaxWidth()) {
            Text("VOLVER")
        }
    }
}

@Composable
fun Config(back: () -> Unit) {
    Text("Configuración inicial: A01-A30, B01-B20, C01-C12, D01-D06", Modifier.padding(24.dp))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        Button(back, Modifier.fillMaxWidth().padding(20.dp)) {
            Text("VOLVER")
        }
    }
}

@Composable
fun Scanner(ocr: Boolean, onRead: (String) -> Unit) {
    val c = LocalContext.current
    var locked by remember { mutableStateOf(false) }
    
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).also { pv ->
                val f = ProcessCameraProvider.getInstance(ctx)
                f.addListener({
                    val provider = f.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(pv.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    val exec = Executors.newSingleThreadExecutor()
                    
                    analysis.setAnalyzer(exec) { proxy ->
                        if (locked) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        val img = proxy.image
                        if (img == null) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
                        
                        if (!ocr) {
                            BarcodeScanning.getClient().process(input)
                                .addOnSuccessListener { bs ->
                                    bs.firstOrNull()?.rawValue?.let {
                                        locked = true
                                        vibrate(c)
                                        onRead(it)
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        } else {
                            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(input)
                                .addOnSuccessListener { t ->
                                    Regex("\\d{8,}").findAll(t.text.replace(" ", ""))
                                        .firstOrNull()?.value?.let {
                                            locked = true
                                            vibrate(c)
                                            onRead(it)
                                        }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                    }
                    
                    provider.unbindAll()
                    provider.bindToLifecycle(c as ComponentActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

fun vibrate(c: Context) {
    val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
}
