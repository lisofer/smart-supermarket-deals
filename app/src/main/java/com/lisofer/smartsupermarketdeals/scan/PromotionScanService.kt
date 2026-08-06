package com.lisofer.smartsupermarketdeals.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lisofer.smartsupermarketdeals.MainActivity
import com.lisofer.smartsupermarketdeals.data.CapturedProduct
import com.lisofer.smartsupermarketdeals.data.DealsDatabase
import com.lisofer.smartsupermarketdeals.data.Store
import com.lisofer.smartsupermarketdeals.parser.ProductJsonExtractor
import com.lisofer.smartsupermarketdeals.web.exhaustiveCatalogScript
import com.lisofer.smartsupermarketdeals.web.promotionCardCaptureScript
import com.lisofer.smartsupermarketdeals.web.searchEndpointHarvesterScript
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal fun shouldPersistIncompleteScan(
    completed: Boolean,
    verifiedPromotionCount: Int,
): Boolean = completed || verifiedPromotionCount > 0

class PromotionScanService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanJob: Job? = null
    private var activeWebView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationUpdateAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelCurrentScan()
            ACTION_START, null -> startScanIfNeeded()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scanJob?.cancel()
        activeWebView?.let(::disposeWebView)
        activeWebView = null
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startScanIfNeeded() {
        if (scanJob?.isActive == true) return

        ServiceCompat.startForeground(
            this,
            PROGRESS_NOTIFICATION_ID,
            progressNotification("Preparando búsqueda…", indeterminate = true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        acquireWakeLock()

        scanJob = serviceScope.launch {
            val notice = try {
                runCompleteScan()
            } catch (_: CancellationException) {
                "Búsqueda cancelada. Se conservaron los resultados anteriores."
            } catch (error: Throwable) {
                "La búsqueda se interrumpió: ${error.message ?: "error inesperado"}. " +
                    "Se conservaron los resultados anteriores."
            }

            BackgroundScanCoordinator.finish(applicationContext, notice)
            showFinishedNotification(notice)
            releaseWakeLock()
            ServiceCompat.stopForeground(this@PromotionScanService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cancelCurrentScan() {
        scanJob?.cancel(CancellationException("Cancelada por el usuario"))
    }

    private suspend fun runCompleteScan(): String {
        val database = DealsDatabase(applicationContext)
        val stores = withContext(Dispatchers.IO) { database.stores() }
        if (stores.isEmpty()) {
            BackgroundScanCoordinator.begin(applicationContext, 0)
            return "No hay tiendas guardadas para analizar."
        }

        BackgroundScanCoordinator.begin(applicationContext, stores.size)
        var totalPromotions = 0
        val failures = mutableListOf<String>()
        val emptyStores = mutableListOf<String>()

        for ((index, store) in stores.withIndex()) {
            if (!serviceScope.isActive) throw CancellationException()

            BackgroundScanCoordinator.update {
                it.copy(
                    currentStoreName = store.name,
                    storeIndex = index + 1,
                    storeCount = stores.size,
                    status = "Abriendo ${store.name}…",
                    productsFound = 0,
                    promotionsFound = totalPromotions,
                    pendingBatches = 0,
                )
            }
            updateProgressNotification(force = true)

            val result = scanStore(store, index + 1, stores.size, totalPromotions)
            val promotionalProducts = result.products.filter(::isVerifiedPromotion)
            if (!shouldPersistIncompleteScan(result.completed, promotionalProducts.size)) {
                failures += store.name
                continue
            }

            withContext(Dispatchers.IO) {
                database.saveScan(store.id, promotionalProducts)
            }

            if (promotionalProducts.isEmpty()) emptyStores += store.name
            totalPromotions += promotionalProducts.size

            BackgroundScanCoordinator.update {
                it.copy(
                    status = "${store.name}: ${promotionalProducts.size} promociones guardadas",
                    promotionsFound = totalPromotions,
                    pendingBatches = 0,
                )
            }
            updateProgressNotification(force = true)
        }

        return when {
            totalPromotions > 0 && failures.isEmpty() && emptyStores.isEmpty() ->
                "Búsqueda terminada: se guardaron $totalPromotions promociones."
            totalPromotions > 0 -> buildString {
                append("Búsqueda terminada: se guardaron $totalPromotions promociones.")
                if (emptyStores.isNotEmpty()) {
                    append(" Sin promociones en: ${emptyStores.joinToString()}.")
                }
                if (failures.isNotEmpty()) {
                    append(" No se pudo completar: ${failures.joinToString()}.")
                }
            }
            failures.isEmpty() ->
                "Búsqueda terminada: no se encontraron promociones verificadas."
            else ->
                "La búsqueda no pudo completarse en: ${failures.joinToString()}. " +
                    "Se conservaron los resultados anteriores."
        }
    }

    private suspend fun scanStore(
        store: Store,
        storeIndex: Int,
        storeCount: Int,
        previousPromotions: Int,
    ): StoreScanResult = coroutineScope {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return@coroutineScope StoreScanResult(emptyList(), completed = false)
        }

        val products = ConcurrentHashMap<String, CapturedProduct>()
        val pendingPayloads = AtomicInteger(0)
        val payloadQueue = Channel<String>(Channel.UNLIMITED)
        val consumer = launch(Dispatchers.Default) {
            for (payload in payloadQueue) {
                ProductJsonExtractor.extract(payload).forEach { incoming ->
                    products.compute(incoming.key) { _, current ->
                        ProductJsonExtractor.prefer(current, incoming)
                    }
                }
                pendingPayloads.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
                publishStoreProgress(
                    store = store,
                    storeIndex = storeIndex,
                    storeCount = storeCount,
                    previousPromotions = previousPromotions,
                    products = products,
                    pendingPayloads = pendingPayloads,
                    status = null,
                )
            }
        }

        val completed = try {
            runLegacyV122WebView(
                store = store,
                storeIndex = storeIndex,
                storeCount = storeCount,
                previousPromotions = previousPromotions,
                products = products,
                pendingPayloads = pendingPayloads,
                payloadQueue = payloadQueue,
            )
        } finally {
            payloadQueue.close()
            joinAll(consumer)
        }

        StoreScanResult(products.values.toList(), completed)
    }

    /**
     * Keeps the version-1.2.2 search lifecycle inside a foreground service: one WebView and one
     * start, without reloads or replacement. The completion wrapper is deliberately redundant so
     * a lost final bridge message cannot leave a finished search spinning forever.
     */
    private suspend fun runLegacyV122WebView(
        store: Store,
        storeIndex: Int,
        storeCount: Int,
        previousPromotions: Int,
        products: ConcurrentHashMap<String, CapturedProduct>,
        pendingPayloads: AtomicInteger,
        payloadQueue: Channel<String>,
    ): Boolean = coroutineScope {
        check(LegacyV122ScanContract.WEBVIEW_INSTANCES_PER_STORE == 1)
        check(!LegacyV122ScanContract.RELOAD_ON_STALL)
        check(!LegacyV122ScanContract.RESTART_AFTER_TIMEOUT)
        check(!LegacyV122ScanContract.CATEGORY_FIRST)
        check(!LegacyV122ScanContract.STRATEGIC_COVERAGE)

        val completion = CompletableDeferred<Boolean>()
        val searchFinishedAt = AtomicLong(0L)
        val lastPayloadAt = AtomicLong(System.currentTimeMillis())
        var completionWatcher: Job? = null
        var searchStateWatcher: Job? = null

        fun verifiedCount(): Int = products.values.count(::isVerifiedPromotion)

        fun publish(status: String) {
            publishStoreProgress(
                store = store,
                storeIndex = storeIndex,
                storeCount = storeCount,
                previousPromotions = previousPromotions,
                products = products,
                pendingPayloads = pendingPayloads,
                status = status,
            )
        }

        fun watchForFinalPayloads() {
            if (completionWatcher?.isActive == true) return
            completionWatcher = launch {
                while (isActive && !completion.isCompleted) {
                    delay(100)
                    val now = System.currentTimeMillis()
                    if (
                        ScanCompletionPolicy.shouldFinish(
                            searchFinishedAt = searchFinishedAt.get(),
                            lastPayloadAt = lastPayloadAt.get(),
                            pendingPayloads = pendingPayloads.get(),
                            now = now,
                        )
                    ) {
                        completion.complete(true)
                    }
                }
            }
        }

        fun signalSearchFinished() {
            searchFinishedAt.compareAndSet(0L, System.currentTimeMillis())
            publish("Búsqueda terminada; procesando las últimas promociones…")
            watchForFinalPayloads()
        }

        val webView = WebView(this@PromotionScanService).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            resumeTimers()
            onResume()
        }
        activeWebView = webView

        WebViewCompat.addWebMessageListener(
            webView,
            "SmartDealsBridge",
            ALLOWED_ORIGINS,
        ) { _, message, sourceOrigin, _, _ ->
            val host = sourceOrigin.host.orEmpty()
            if (host != "pedidosya.com.ar" && !host.endsWith(".pedidosya.com.ar")) {
                return@addWebMessageListener
            }

            val raw = message.data ?: return@addWebMessageListener
            val envelope = runCatching { JSONObject(raw) }.getOrNull()
                ?: return@addWebMessageListener

            when (envelope.optString("event")) {
                "explore_progress" -> {
                    val phase = envelope.optString("phase")
                    publish(
                        if (phase.isNotBlank()) {
                            "$phase · ${products.size} productos · ${verifiedCount()} promociones"
                        } else {
                            "${products.size} productos · ${verifiedCount()} promociones"
                        }
                    )
                    if (
                        ScanCompletionPolicy.isCompletionProgress(
                            phase = phase,
                            endpointHarvestComplete = envelope.optBoolean("endpointHarvestComplete"),
                        )
                    ) {
                        signalSearchFinished()
                    }
                }
                "explore_complete" -> signalSearchFinished()
                "explore_started", "catalog_routes", "route_change" -> Unit
                else -> {
                    lastPayloadAt.set(System.currentTimeMillis())
                    pendingPayloads.incrementAndGet()
                    if (payloadQueue.trySend(raw).isFailure) {
                        pendingPayloads.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
                    }
                }
            }
        }

        // Same search order and adaptive endpoint harvester as version 1.2.2. The badge observer
        // only enriches commercial labels; it does not replace or restart the search engine.
        WebViewCompat.addDocumentStartJavaScript(webView, exhaustiveCatalogScript, ALLOWED_ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(webView, promotionCardCaptureScript, ALLOWED_ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(webView, searchEndpointHarvesterScript, ALLOWED_ORIGINS)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                publish("Página cargada; iniciando la búsqueda de la versión 1.2.2…")
                val rootLiteral = JSONObject.quote(store.url)
                view.evaluateJavascript(
                    "window.__smartDealsEndpointMode = true; " +
                        "window.__smartDealsSetRoot && window.__smartDealsSetRoot($rootLiteral);",
                    null,
                )
                view.postDelayed({
                    runCatching {
                        view.evaluateJavascript(
                            "window.__smartDealsSetRoot && window.__smartDealsSetRoot($rootLiteral); " +
                                "window.__smartDealsStartExplore && window.__smartDealsStartExplore();",
                            null,
                        )
                    }
                }, START_EXPLORATION_DELAY_MS)
            }
        }

        publish("Abriendo ${store.name}…")
        webView.loadUrl(store.url)

        searchStateWatcher = launch {
            while (isActive && !completion.isCompleted) {
                delay(SEARCH_STATE_POLL_MS)
                runCatching {
                    webView.evaluateJavascript(
                        "Boolean(window.__smartDealsSearchFinished)",
                    ) { value ->
                        if (value == "true" && !completion.isCompleted) {
                            signalSearchFinished()
                        }
                    }
                }
            }
        }

        try {
            val finished = withTimeoutOrNull(ScanCompletionPolicy.STORE_SCAN_TIMEOUT_MS) {
                completion.await()
            } ?: false
            if (!finished) {
                publish("Se alcanzó el límite de seguridad; guardando todo lo ya procesado…")
            }
            finished
        } finally {
            completionWatcher?.cancel()
            searchStateWatcher?.cancel()
            activeWebView = null
            disposeWebView(webView)
        }
    }

    private fun publishStoreProgress(
        store: Store,
        storeIndex: Int,
        storeCount: Int,
        previousPromotions: Int,
        products: ConcurrentHashMap<String, CapturedProduct>,
        pendingPayloads: AtomicInteger,
        status: String?,
    ) {
        val verified = products.values.count(::isVerifiedPromotion)
        BackgroundScanCoordinator.update {
            it.copy(
                currentStoreName = store.name,
                storeIndex = storeIndex,
                storeCount = storeCount,
                status = status ?: buildString {
                    append(products.size)
                    append(" productos · ")
                    append(verified)
                    append(" promociones")
                    val pending = pendingPayloads.get()
                    if (pending > 0) append(" · $pending respuestas pendientes")
                },
                productsFound = products.size,
                promotionsFound = previousPromotions + verified,
                pendingBatches = pendingPayloads.get(),
            )
        }
        updateProgressNotification()
    }

    private fun disposeWebView(webView: WebView) {
        runCatching { webView.stopLoading() }
        runCatching { webView.onPause() }
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
    }

    private fun isVerifiedPromotion(product: CapturedProduct): Boolean {
        return product.effectiveDiscountPercent != null &&
            product.promotionCategory != null &&
            product.promotionKind != null &&
            product.promotionEvidence != null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmartDeals:BackgroundPromotionScan",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun updateProgressNotification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationUpdateAt < NOTIFICATION_THROTTLE_MS) return
        lastNotificationUpdateAt = now

        val state = BackgroundScanCoordinator.state.value
        val text = buildString {
            if (state.storeCount > 0) append("${state.storeIndex}/${state.storeCount} · ")
            append(state.status.ifBlank { "Buscando promociones…" })
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            PROGRESS_NOTIFICATION_ID,
            progressNotification(text, indeterminate = state.storeCount == 0),
        )
    }

    private fun progressNotification(text: String, indeterminate: Boolean) =
        NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Buscando promociones de PedidosYa")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, 0, indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancelar",
                cancelPendingIntent(),
            )
            .build()

    private fun showFinishedNotification(notice: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            COMPLETE_NOTIFICATION_ID,
            NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Búsqueda terminada")
                .setContentText(notice)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice))
                .setContentIntent(openAppPendingIntent())
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, PromotionScanService::class.java).apply {
            action = ACTION_CANCEL
        }
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL_ID,
                "Búsqueda de promociones",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Muestra el progreso de la búsqueda"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                COMPLETE_CHANNEL_ID,
                "Resultados de promociones",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Avisa cuando termina la búsqueda"
            }
        )
    }

    private data class StoreScanResult(
        val products: List<CapturedProduct>,
        val completed: Boolean,
    )

    companion object {
        private const val ACTION_START =
            "com.lisofer.smartsupermarketdeals.action.START_EXHAUSTIVE_SCAN"
        private const val ACTION_CANCEL =
            "com.lisofer.smartsupermarketdeals.action.CANCEL_EXHAUSTIVE_SCAN"

        private const val PROGRESS_CHANNEL_ID = "promotion_scan_progress"
        private const val COMPLETE_CHANNEL_ID = "promotion_scan_complete"
        private const val PROGRESS_NOTIFICATION_ID = 4101
        private const val COMPLETE_NOTIFICATION_ID = 4102

        private const val START_EXPLORATION_DELAY_MS = 1_350L
        private const val SEARCH_STATE_POLL_MS = 1_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1_000L
        private const val NOTIFICATION_THROTTLE_MS = 900L

        private val ALLOWED_ORIGINS = setOf(
            "https://pedidosya.com.ar",
            "https://*.pedidosya.com.ar",
        )

        fun start(context: Context) {
            val intent = Intent(context, PromotionScanService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, PromotionScanService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
