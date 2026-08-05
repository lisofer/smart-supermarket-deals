package com.lisofer.smartsupermarketdeals

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lisofer.smartsupermarketdeals.data.DealsDatabase
import com.lisofer.smartsupermarketdeals.data.PromotionDeal
import com.lisofer.smartsupermarketdeals.data.PromotionGroup
import com.lisofer.smartsupermarketdeals.data.PromotionKind
import com.lisofer.smartsupermarketdeals.data.Store
import com.lisofer.smartsupermarketdeals.parser.ProductJsonExtractor
import com.lisofer.smartsupermarketdeals.scan.BackgroundScanCoordinator
import com.lisofer.smartsupermarketdeals.scan.BackgroundScanState
import com.lisofer.smartsupermarketdeals.scan.PromotionScanService
import com.lisofer.smartsupermarketdeals.web.PedidosYaWebView
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PEDIDOSYA_HOME = "https://www.pedidosya.com.ar/"
private const val NOTIFICATION_PERMISSION_REQUEST = 1201

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BackgroundScanCoordinator.restore(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmartDealsApp(
                        database = remember { DealsDatabase(applicationContext) },
                        onStartScan = {
                            requestNotificationPermissionIfNeeded()
                            PromotionScanService.start(applicationContext)
                        },
                        onCancelScan = {
                            PromotionScanService.cancel(applicationContext)
                        },
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }
}

private enum class Screen { HOME, ADD_STORE, SCAN }

@Composable
private fun SmartDealsApp(
    database: DealsDatabase,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scanState by BackgroundScanCoordinator.state.collectAsState()
    var screen by remember { mutableStateOf(Screen.HOME) }
    var stores by remember { mutableStateOf(emptyList<Store>()) }
    var promotionGroups by remember { mutableStateOf(emptyList<PromotionGroup>()) }
    var scanNotice by remember { mutableStateOf(scanState.completedNotice) }

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            database.stores() to database.promotionGroups()
        }
        stores = snapshot.first
        promotionGroups = snapshot.second
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(scanState.runId, scanState.isRunning, scanState.completedNotice) {
        if (!scanState.isRunning && !scanState.completedNotice.isNullOrBlank()) {
            refresh()
            scanNotice = scanState.completedNotice
            if (screen == Screen.SCAN) screen = Screen.HOME
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            stores = stores,
            promotionGroups = promotionGroups,
            scanNotice = scanNotice,
            scanState = scanState,
            onAddStore = {
                scanNotice = null
                screen = Screen.ADD_STORE
            },
            onAnalyze = {
                scanNotice = null
                if (!scanState.isRunning) onStartScan()
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

        Screen.SCAN -> BackgroundScanScreen(
            state = scanState,
            onBack = { screen = Screen.HOME },
            onCancel = onCancelScan,
        )
    }
}

@Composable
private fun HomeScreen(
    stores: List<Store>,
    promotionGroups: List<PromotionGroup>,
    scanNotice: String?,
    scanState: BackgroundScanState,
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
                    text = "Promociones verificadas, separadas por tipo de descuento",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (scanState.isRunning) {
                item {
                    InfoCard(
                        buildString {
                            append("Búsqueda en segundo plano: ")
                            append(scanState.status.ifBlank { "analizando catálogo…" })
                            if (scanState.pendingBatches > 0) {
                                append(" · ${scanState.pendingBatches} lotes pendientes")
                            }
                        }
                    )
                }
            } else {
                scanNotice?.let { notice -> item { InfoCard(notice) } }
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
                        Text(if (scanState.isRunning) "Ver progreso" else "Buscar descuentos")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onAddStore,
                    ) { Text("Agregar tienda") }
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
                            OutlinedButton(
                                enabled = !scanState.isRunning,
                                onClick = { onDeleteStore(store) },
                            ) { Text("Quitar") }
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
                        "No hay promociones verificadas. La búsqueda considera descuentos directos, " +
                            "segunda unidad y promociones por cantidad."
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
                    ) { deal -> PromotionCard(deal) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun BackgroundScanScreen(
    state: BackgroundScanState,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Búsqueda exhaustiva",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            if (state.isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator()
                    Column(modifier = Modifier.weight(1f)) {
                        if (state.storeCount > 0) {
                            Text(
                                "Tienda ${state.storeIndex}/${state.storeCount}: " +
                                    (state.currentStoreName ?: "cargando"),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(state.status.ifBlank { "Analizando catálogo…" })
                        Text(
                            "${state.productsFound} productos · " +
                                "${state.promotionsFound} promociones · " +
                                "${state.pendingBatches} lotes pendientes",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                InfoCard(
                    "Podés salir de la app o apagar la pantalla. Android mantendrá una notificación " +
                        "mientras trabaja y te avisará cuando termine."
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Dejar en segundo plano") }
                    Button(onClick = onCancel) { Text("Cancelar búsqueda") }
                }
            } else {
                InfoCard(state.completedNotice ?: "La búsqueda todavía no comenzó.")
                OutlinedButton(onClick = onBack) { Text("Volver") }
            }
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
                if (capturedCount > 0) Text("Lecturas de productos detectadas: $capturedCount")
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
                    ) { Text("Guardar esta tienda") }
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
                ?.let { label -> Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2) }

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
