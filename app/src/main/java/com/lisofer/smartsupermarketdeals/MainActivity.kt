package com.lisofer.smartsupermarketdeals

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lisofer.smartsupermarketdeals.data.CapturedProduct
import com.lisofer.smartsupermarketdeals.data.Deal
import com.lisofer.smartsupermarketdeals.data.DealEvidence
import com.lisofer.smartsupermarketdeals.data.DealsDatabase
import com.lisofer.smartsupermarketdeals.data.Store
import com.lisofer.smartsupermarketdeals.parser.ProductJsonExtractor
import com.lisofer.smartsupermarketdeals.web.PedidosYaWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

private const val PEDIDOSYA_HOME = "https://www.pedidosya.com.ar/"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmartDealsApp(database = remember { DealsDatabase(applicationContext) })
                }
            }
        }
    }
}

private enum class Screen { HOME, ADD_STORE, SCAN }

@Composable
private fun SmartDealsApp(database: DealsDatabase) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.HOME) }
    var stores by remember { mutableStateOf(emptyList<Store>()) }
    var deals by remember { mutableStateOf(emptyList<Deal>()) }

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            database.stores() to database.topDeals()
        }
        stores = snapshot.first
        deals = snapshot.second
    }

    LaunchedEffect(Unit) { refresh() }

    when (screen) {
        Screen.HOME -> HomeScreen(
            stores = stores,
            deals = deals,
            onAddStore = { screen = Screen.ADD_STORE },
            onAnalyze = { screen = Screen.SCAN },
            onDeleteStore = { store ->
                scope.launch {
                    withContext(Dispatchers.IO) { database.deleteStore(store.id) }
                    refresh()
                }
            },
        )

        Screen.ADD_STORE -> AddStoreScreen(
            onBack = { screen = Screen.HOME },
            onSave = { name, url ->
                scope.launch {
                    withContext(Dispatchers.IO) { database.addStore(name, url) }
                    refresh()
                    screen = Screen.HOME
                }
            },
        )

        Screen.SCAN -> ScanScreen(
            stores = stores,
            database = database,
            onFinished = {
                scope.launch {
                    refresh()
                    screen = Screen.HOME
                }
            },
            onCancel = { screen = Screen.HOME },
        )
    }
}

@Composable
private fun HomeScreen(
    stores: List<Store>,
    deals: List<Deal>,
    onAddStore: () -> Unit,
    onAnalyze: () -> Unit,
    onDeleteStore: (Store) -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Smart Supermarket Deals",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Promociones publicadas e historial local · Top 30",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = stores.isNotEmpty(),
                        onClick = onAnalyze,
                    ) {
                        Text("Analizar ahora")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onAddStore,
                    ) {
                        Text("Agregar tienda")
                    }
                }
            }

            item {
                Text("Tiendas (${stores.size})", fontWeight = FontWeight.SemiBold)
            }

            if (stores.isEmpty()) {
                item {
                    InfoCard(
                        "Primero agregá una tienda. Solo tenés que abrirla una vez en " +
                            "PedidosYa y guardarla; no se cargan productos manualmente."
                    )
                }
            } else {
                items(stores, key = { it.id }) { store ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(store.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    store.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                )
                            }
                            OutlinedButton(onClick = { onDeleteStore(store) }) {
                                Text("Quitar")
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Text("Mejores oportunidades", fontWeight = FontWeight.SemiBold)
            }

            if (deals.isEmpty()) {
                item {
                    InfoCard(
                        "Todavía no se pudo leer el catálogo. La próxima versión de captura " +
                            "revisa tanto los datos internos como los productos visibles en pantalla."
                    )
                }
            } else {
                items(deals) { deal -> DealCard(deal) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AddStoreScreen(
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var currentUrl by remember { mutableStateOf(PEDIDOSYA_HOME) }
    var title by remember { mutableStateOf("Supermercado") }
    var capturedCount by remember { mutableIntStateOf(0) }
    var unsupported by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Agregar tienda", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Ingresá tu dirección si PedidosYa la pide y abrí el supermercado. " +
                        "Cuando estés dentro de la tienda, tocá Guardar.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (capturedCount > 0) {
                    Text("Productos detectados durante la prueba: $capturedCount")
                }
                if (unsupported) {
                    Text(
                        "Actualizá Android System WebView desde Play Store para habilitar la captura.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Volver") }
                    Button(
                        enabled = currentUrl.contains("pedidosya.com.ar"),
                        onClick = { onSave(cleanTitle(title), currentUrl) },
                    ) {
                        Text("Guardar esta tienda")
                    }
                }
            }

            PedidosYaWebView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                url = PEDIDOSYA_HOME,
                onUrlChanged = { currentUrl = it },
                onTitleChanged = { title = it },
                onJsonPayload = { payload ->
                    capturedCount += ProductJsonExtractor.extract(payload).size
                },
                onUnsupportedWebView = { unsupported = true },
            )
        }
    }
}

@Composable
private fun ScanScreen(
    stores: List<Store>,
    database: DealsDatabase,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
) {
    if (stores.isEmpty()) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    var pageFinishedToken by remember { mutableIntStateOf(0) }
    var unsupported by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Abriendo tienda…") }
    val products = remember(index) { mutableStateMapOf<String, CapturedProduct>() }
    val store = stores[index]

    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
    LaunchedEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    LaunchedEffect(index, pageFinishedToken, products.size) {
        if (pageFinishedToken == 0) return@LaunchedEffect
        val promoCount = products.values.count { product ->
            product.originalPrice != null ||
                product.advertisedDiscountPercent != null ||
                product.promoLabel != null
        }
        status = if (products.isEmpty()) {
            "Esperando el catálogo y leyendo la pantalla…"
        } else {
            "${products.size} productos · $promoCount con señal promocional"
        }
        delay(if (products.isEmpty()) 24_000 else 6_000)
        val snapshot = products.values.toList()
        withContext(Dispatchers.IO) { database.saveScan(store.id, snapshot) }
        if (index == stores.lastIndex) {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onFinished()
        } else {
            index += 1
            pageFinishedToken = 0
            status = "Abriendo siguiente tienda…"
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "Analizando ${index + 1}/${stores.size}: ${store.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Column(modifier = Modifier.weight(1f)) {
                        Text(status)
                        Text(
                            "No busca solamente la palabra promo: revisa precio anterior, " +
                                "porcentaje, etiquetas y cambios contra tu historial.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            activity?.window?.clearFlags(
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            )
                            onCancel()
                        }
                    }) {
                        Text("Cancelar")
                    }
                }
                if (unsupported) {
                    Text(
                        "Tu WebView no admite la captura. Actualizá Android System WebView.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            PedidosYaWebView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                url = store.url,
                freshLoad = true,
                onJsonPayload = { payload ->
                    ProductJsonExtractor.extract(payload).forEach { product ->
                        products[product.key] = product
                    }
                },
                onPageFinished = { pageFinishedToken += 1 },
                onUnsupportedWebView = { unsupported = true },
            )
        }
    }
}

@Composable
private fun DealCard(deal: Deal) {
    val formatter = remember { NumberFormat.getCurrencyInstance(Locale("es", "AR")) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    deal.productName,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(formatter.format(deal.currentPrice), fontWeight = FontWeight.Bold)
            }
            Text(deal.storeName, style = MaterialTheme.typography.bodySmall)
            deal.promoLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(label, color = MaterialTheme.colorScheme.primary)
            }

            val reference = deal.referencePrice
            if (reference != null && deal.discountPercent > 0.0) {
                val sourceText = when (deal.evidence) {
                    DealEvidence.HISTORICAL -> "vs. tu historial"
                    DealEvidence.ADVERTISED -> "según precio publicado"
                    DealEvidence.BOTH -> "confirmado por precio publicado e historial"
                    DealEvidence.BASELINE -> ""
                }
                Text(
                    "${deal.discountPercent.toInt()}% debajo de ${formatter.format(reference)}" +
                        if (sourceText.isNotBlank()) " · $sourceText" else "" +
                        if (deal.isHistoricalMinimum) " · mínimo registrado" else "",
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (deal.evidence == DealEvidence.ADVERTISED) {
                Text(
                    "Tiene señal promocional, pero PedidosYa no expuso un precio comparable.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Precio base guardado; se vuelve comparable en la próxima medición.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(14.dp)) {
            Text(text)
        }
    }
}

private fun cleanTitle(title: String): String {
    return title
        .replace("PedidosYa", "", ignoreCase = true)
        .replace("|", "")
        .replace("-", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Supermercado" }
        .take(80)
}
