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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateListOf
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
import com.lisofer.smartsupermarketdeals.data.DealsDatabase
import com.lisofer.smartsupermarketdeals.data.PromotionDeal
import com.lisofer.smartsupermarketdeals.data.PromotionGroup
import com.lisofer.smartsupermarketdeals.data.PromotionKind
import com.lisofer.smartsupermarketdeals.data.Store
import com.lisofer.smartsupermarketdeals.parser.ProductJsonExtractor
import com.lisofer.smartsupermarketdeals.web.PedidosYaWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PEDIDOSYA_HOME = "https://www.pedidosya.com.ar/"
private const val SCAN_TIMEOUT_MS = 330_000L
private const val POST_EXPLORATION_SETTLE_MS = 5_000L

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
    var promotionGroups by remember { mutableStateOf(emptyList<PromotionGroup>()) }
    var scanNotice by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            database.stores() to database.promotionGroups()
        }
        stores = snapshot.first
        promotionGroups = snapshot.second
    }

    LaunchedEffect(Unit) { refresh() }

    when (screen) {
        Screen.HOME -> HomeScreen(
            stores = stores,
            promotionGroups = promotionGroups,
            scanNotice = scanNotice,
            onAddStore = {
                scanNotice = null
                screen = Screen.ADD_STORE
            },
            onAnalyze = {
                scanNotice = null
                screen = Screen.SCAN
            },
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
            onFinished = { notice ->
                scope.launch {
                    refresh()
                    scanNotice = notice
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
    promotionGroups: List<PromotionGroup>,
    scanNotice: String?,
    onAddStore: () -> Unit,
    onAnalyze: () -> Unit,
    onDeleteStore: (Store) -> Unit,
) {
    var selectedCategory by remember(promotionGroups) {
        mutableStateOf(promotionGroups.firstOrNull()?.key)
    }
    val selectedGroup = promotionGroups.firstOrNull { it.key == selectedCategory }
        ?: promotionGroups.firstOrNull()

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
                    text = "Todos los productos encontrados, separados por descuento",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            scanNotice?.let { notice ->
                item { InfoCard(notice) }
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
                        Text("Buscar descuentos")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onAddStore,
                    ) {
                        Text("Agregar tienda")
                    }
                }
            }

            item { Text("Tiendas (${stores.size})", fontWeight = FontWeight.SemiBold) }

            if (stores.isEmpty()) {
                item {
                    InfoCard(
                        "Primero agregá una tienda. Entrá manualmente a Supermercados en PedidosYa, " +
                            "abrí la sucursal y guardala."
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
                                Text(store.url, style = MaterialTheme.typography.bodySmall, maxLines = 2)
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
                Text("Descuentos encontrados", fontWeight = FontWeight.SemiBold)
            }

            if (promotionGroups.isEmpty()) {
                item {
                    InfoCard(
                        "Todavía no hay promociones cuantificables. La app busca porcentajes, " +
                            "2x1, 3x2, lleva N paga M y descuentos en la segunda unidad."
                    )
                }
            } else {
                item {
                    Text(
                        "Se abrió automáticamente el filtro con mayor ahorro efectivo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(promotionGroups, key = { it.key }) { group ->
                            FilterChip(
                                selected = group.key == selectedGroup?.key,
                                onClick = { selectedCategory = group.key },
                                label = { Text("${group.title} (${group.products.size})") },
                            )
                        }
                    }
                }
                selectedGroup?.let { group ->
                    item {
                        Column {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Ahorro efectivo aproximado: " +
                                    "${formatPercent(group.effectiveDiscountPercent)}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    items(
                        items = group.products,
                        key = { deal -> "${deal.storeName}|${deal.productKey}|${deal.categoryKey}" },
                    ) { deal ->
                        PromotionCard(deal)
                    }
                }
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
                    "Cargá tu dirección, entrá manualmente a Supermercados y abrí la tienda. " +
                        "Usá la flecha pequeña de arriba o el botón Atrás del teléfono para navegar.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (capturedCount > 0) {
                    Text("Lecturas de productos detectadas: $capturedCount")
                }
                if (unsupported) {
                    Text(
                        "Actualizá Android System WebView desde Play Store para habilitar la captura.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Volver a la app") }
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
    onFinished: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    if (stores.isEmpty()) {
        LaunchedEffect(Unit) { onFinished("No hay tiendas guardadas para analizar.") }
        return
    }

    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    var pageFinishedToken by remember(index) { mutableIntStateOf(0) }
    var explorationComplete by remember(index) { mutableStateOf(false) }
    var saved by remember(index) { mutableStateOf(false) }
    var unsupported by remember { mutableStateOf(false) }
    var status by remember(index) { mutableStateOf("Abriendo tienda…") }
    var totalPromotions by remember { mutableIntStateOf(0) }
    val failures = remember { mutableStateListOf<String>() }
    val products = remember(index) { mutableStateMapOf<String, CapturedProduct>() }
    val store = stores[index]

    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
    LaunchedEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    suspend fun advanceOrFinish(newTotal: Int) {
        if (index == stores.lastIndex) {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val notice = when {
                newTotal > 0 && failures.isEmpty() ->
                    "Búsqueda terminada: se guardaron $newTotal promociones."
                newTotal > 0 ->
                    "Se guardaron $newTotal promociones. Sin resultados nuevos en: " +
                        failures.joinToString().plus(". No se borraron sus resultados anteriores.")
                else ->
                    "No se detectó ninguna promoción nueva. Verificá que la URL guardada sea la " +
                        "página interna de un supermercado; los resultados anteriores no se borraron."
            }
            onFinished(notice)
        } else {
            index += 1
        }
    }

    suspend fun finishCurrentStore(timeout: Boolean) {
        if (saved) return
        val promotionalProducts = products.values.filter {
            it.effectiveDiscountPercent != null &&
                it.promotionCategory != null &&
                it.promotionKind != null
        }

        if (promotionalProducts.isEmpty() && !timeout) {
            status = "Todavía no encontré descuentos; sigo leyendo la página…"
            return
        }

        saved = true
        if (promotionalProducts.isEmpty()) {
            failures += store.name
            status = "Sin promociones detectadas; no reemplazo los resultados anteriores."
            delay(900)
            advanceOrFinish(totalPromotions)
            return
        }

        status = "Guardando ${promotionalProducts.size} promociones…"
        withContext(Dispatchers.IO) {
            database.saveScan(store.id, promotionalProducts)
        }
        val newTotal = totalPromotions + promotionalProducts.size
        totalPromotions = newTotal
        advanceOrFinish(newTotal)
    }

    LaunchedEffect(index) {
        delay(SCAN_TIMEOUT_MS)
        status = "Se alcanzó el límite de seguridad; guardando todo lo encontrado…"
        finishCurrentStore(timeout = true)
    }

    LaunchedEffect(index, explorationComplete, products.size) {
        if (!explorationComplete) return@LaunchedEffect
        val promoCount = products.values.count { it.effectiveDiscountPercent != null }
        if (promoCount == 0) {
            status = "El barrido terminó sin descuentos; sigo esperando datos internos…"
            return@LaunchedEffect
        }
        status = "El catálogo dejó de aportar ofertas nuevas; consolidando resultados…"
        delay(POST_EXPLORATION_SETTLE_MS)
        finishCurrentStore(timeout = false)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "Buscando ${index + 1}/${stores.size}: ${store.name}",
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
                            "El barrido recorre la página completa, categorías, listas internas, " +
                                "carruseles y respuestas de datos antes de finalizar.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                autoExplore = true,
                onJsonPayload = { payload ->
                    ProductJsonExtractor.extract(payload).forEach { incoming ->
                        products[incoming.key] = ProductJsonExtractor.prefer(
                            products[incoming.key],
                            incoming,
                        )
                    }
                    val promoCount = products.values.count { it.effectiveDiscountPercent != null }
                    status = "${products.size} productos · $promoCount promociones"
                },
                onPageFinished = {
                    pageFinishedToken += 1
                    explorationComplete = false
                    status = "Página cargada; preparando barrido exhaustivo…"
                },
                onExplorationProgress = { step ->
                    val promoCount = products.values.count { it.effectiveDiscountPercent != null }
                    status = "Barrido exhaustivo: paso $step · $promoCount promociones"
                },
                onExplorationFinished = { explorationComplete = true },
                onUnsupportedWebView = { unsupported = true },
            )
        }
    }
}

@Composable
private fun PromotionCard(deal: PromotionDeal) {
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
            Text(
                deal.categoryTitle,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            deal.promoLabel
                ?.takeIf { it.isNotBlank() && !it.equals(deal.categoryTitle, ignoreCase = true) }
                ?.let { label ->
                    Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }

            val explanation = when (deal.promotionKind) {
                PromotionKind.DIRECT_PERCENT ->
                    "Descuento directo: ${formatPercent(deal.effectiveDiscountPercent)}%"
                PromotionKind.MULTIBUY ->
                    "Ahorro efectivo: ${formatPercent(deal.effectiveDiscountPercent)}% " +
                        "cumpliendo la cantidad de la promoción"
                PromotionKind.SECOND_UNIT ->
                    "Ahorro efectivo: ${formatPercent(deal.effectiveDiscountPercent)}% sobre dos unidades"
            }
            Text(explanation, style = MaterialTheme.typography.bodySmall)

            deal.originalPrice?.takeIf { it > deal.currentPrice }?.let { original ->
                Text(
                    "Precio anterior publicado: ${formatter.format(original)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(14.dp)) { Text(text) }
    }
}

private fun formatPercent(value: Double): String {
    return if (abs(value - value.roundToInt()) < 0.05) {
        value.roundToInt().toString()
    } else {
        String.format(Locale("es", "AR"), "%.1f", value)
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
