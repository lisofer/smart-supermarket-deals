package com.lisofer.smartsupermarketdeals.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import com.lisofer.smartsupermarketdeals.web.fastCoverageSearchScript
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
import org.json.JSONArray
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
                "Búsqueda cancelada. Se conservaron los resultados ya guardados."
            } catch (error: Throwable) {
                "La búsqueda se interrumpió: ${error.message ?: "error inesperado"}. " +
                    "Se conservaron los resultados obtenidos."
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
        val partialStores = mutableListOf<String>()
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

            if (!result.completed) partialStores += store.name
            if (promotionalProducts.isEmpty()) emptyStores += store.name
            totalPromotions += promotionalProducts.size

            BackgroundScanCoordinator.update {
                it.copy(
                    status = buildString {
                        append(store.name)
                        append(": ")
                        append(promotionalProducts.size)
                        append(" promociones guardadas")
                        if (!result.completed) append(" · recuperación parcial")
                    },
                    promotionsFound = totalPromotions,
                    pendingBatches = 0,
                )
            }
            updateProgressNotification(force = true)
        }

        return when {
            totalPromotions > 0 && failures.isEmpty() && partialStores.isEmpty() && emptyStores.isEmpty() ->
                "Búsqueda terminada: se guardaron $totalPromotions promociones."
            totalPromotions > 0 -> buildString {
                append("Búsqueda terminada: se guardaron $totalPromotions promociones.")
                if (partialStores.isNotEmpty()) {
                    append(" El motor web se reinició y se conservaron los resultados recuperados de: ")
                    append(partialStores.joinToString())
                    append(".")
                }
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
        val pendingBatches = AtomicInteger(0)
        val payloadQueue = Channel<String>(Channel.UNLIMITED)
        val deduplicator = PayloadDeduplicator()
        val consumer = launch(Dispatchers.Default) {
            for (payload in payloadQueue) {
                ProductJsonExtractor.extract(payload).forEach { incoming ->
                    products.compute(incoming.key) { _, current ->
                        ProductJsonExtractor.prefer(current, incoming)
                    }
                }
                pendingBatches.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
                publishStoreProgress(
                    store = store,
                    storeIndex = storeIndex,
                    storeCount = storeCount,
                    previousPromotions = previousPromotions,
                    products = products,
                    pendingBatches = pendingBatches,
                    status = null,
                )
            }
        }

        var completed = false
        var attempts = 0
        while (!completed && attempts < MAX_WEBVIEW_ATTEMPTS && isActive) {
            attempts += 1
            val outcome = runWebViewAttempt(
                store = store,
                storeIndex = storeIndex,
                storeCount = storeCount,
                previousPromotions = previousPromotions,
                products = products,
                pendingBatches = pendingBatches,
                payloadQueue = payloadQueue,
                deduplicator = deduplicator,
                attempt = attempts,
            )
            completed = outcome == AttemptOutcome.COMPLETE
            if (!completed && attempts < MAX_WEBVIEW_ATTEMPTS) {
                publishStoreProgress(
                    store,
                    storeIndex,
                    storeCount,
                    previousPromotions,
                    products,
                    pendingBatches,
                    "El motor web se detuvo; reiniciando sin perder ${products.size} productos…",
                )
                delay(RESTART_DELAY_MS)
            }
        }

        val drainDeadline = System.currentTimeMillis() + FINAL_DRAIN_TIMEOUT_MS
        while (pendingBatches.get() > 0 && System.currentTimeMillis() < drainDeadline) {
            delay(100)
        }
        payloadQueue.close()
        joinAll(consumer)

        StoreScanResult(products.values.toList(), completed)
    }

    private suspend fun runWebViewAttempt(
        store: Store,
        storeIndex: Int,
        storeCount: Int,
        previousPromotions: Int,
        products: ConcurrentHashMap<String, CapturedProduct>,
        pendingBatches: AtomicInteger,
        payloadQueue: Channel<String>,
        deduplicator: PayloadDeduplicator,
        attempt: Int,
    ): AttemptOutcome = coroutineScope {
        val handler = Handler(Looper.getMainLooper())
        val completion = CompletableDeferred<AttemptOutcome>()
        val lastActivityAt = AtomicLong(System.currentTimeMillis())
        val payloadBuffer = mutableListOf<JSONObject>()
        var bufferedChars = 0
        var scriptReportedComplete = false
        var coverageActive = false
        var reloads = 0

        fun verifiedCount(): Int = products.values.count(::isVerifiedPromotion)

        fun publish(status: String) {
            publishStoreProgress(
                store,
                storeIndex,
                storeCount,
                previousPromotions,
                products,
                pendingBatches,
                status,
            )
        }

        fun flushPayloads() {
            if (payloadBuffer.isEmpty()) return
            val array = JSONArray()
            payloadBuffer.forEach { array.put(it) }
            payloadBuffer.clear()
            bufferedChars = 0

            val batch = JSONObject()
                .put("event", "payload_batch")
                .put("payloads", array)
                .toString()
            pendingBatches.incrementAndGet()
            if (payloadQueue.trySend(batch).isFailure) {
                pendingBatches.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
            }
        }

        val idleFlushRunnable = Runnable { flushPayloads() }
        val completionRunnable = Runnable {
            flushPayloads()
            if (!completion.isCompleted) completion.complete(AttemptOutcome.COMPLETE)
        }

        fun scheduleCompletion() {
            handler.removeCallbacks(completionRunnable)
            handler.postDelayed(completionRunnable, COMPLETION_QUIET_MS)
        }

        fun queueEnvelope(envelope: JSONObject, rawLength: Int) {
            val url = envelope.optString("url")
            val body = envelope.optString("body")
            if (body.isBlank() || !deduplicator.accept(url, body)) return

            lastActivityAt.set(System.currentTimeMillis())
            payloadBuffer += envelope
            bufferedChars += rawLength
            handler.removeCallbacks(idleFlushRunnable)
            handler.postDelayed(idleFlushRunnable, PAYLOAD_IDLE_FLUSH_MS)

            if (
                payloadBuffer.size >= MAX_BUFFERED_PAYLOADS ||
                bufferedChars >= MAX_BUFFERED_PAYLOAD_CHARS
            ) {
                handler.removeCallbacks(idleFlushRunnable)
                flushPayloads()
            }
            if (scriptReportedComplete) scheduleCompletion()
        }

        val webView = WebView(this@PromotionScanService).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportMultipleWindows(false)
            settings.offscreenPreRaster = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
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
            lastActivityAt.set(System.currentTimeMillis())
            val raw = message.data ?: return@addWebMessageListener
            val envelope = runCatching { JSONObject(raw) }.getOrNull()
                ?: return@addWebMessageListener

            when (envelope.optString("event")) {
                "coverage_started" -> {
                    coverageActive = true
                    publish("Recorriendo el catálogo completo…")
                }
                "coverage_complete" -> {
                    coverageActive = false
                    scriptReportedComplete = true
                    flushPayloads()
                    publish("Catálogo recorrido; procesando las últimas promociones…")
                    scheduleCompletion()
                }
                "explore_complete" -> {
                    if (!coverageActive) {
                        scriptReportedComplete = true
                        flushPayloads()
                        scheduleCompletion()
                    }
                }
                "explore_progress" -> {
                    val phase = envelope.optString("phase")
                    publish(
                        if (phase.isNotBlank()) {
                            "$phase · ${products.size} productos · ${verifiedCount()} promociones"
                        } else {
                            "${products.size} productos · ${verifiedCount()} promociones"
                        }
                    )
                }
                "explore_started", "catalog_routes", "route_change" -> Unit
                else -> queueEnvelope(envelope, raw.length)
            }
        }

        WebViewCompat.addDocumentStartJavaScript(webView, exhaustiveCatalogScript, ALLOWED_ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(webView, promotionCardCaptureScript, ALLOWED_ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(webView, searchEndpointHarvesterScript, ALLOWED_ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(webView, fastCoverageSearchScript, ALLOWED_ORIGINS)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                lastActivityAt.set(System.currentTimeMillis())
                publish(
                    if (attempt == 1) {
                        "Página cargada; preparando búsqueda…"
                    } else {
                        "Motor web reiniciado; retomando la búsqueda…"
                    }
                )
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

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true && !completion.isCompleted) {
                    publish("Error de carga; reintentando…")
                    handler.postDelayed({ view?.reload() }, MAIN_FRAME_RETRY_MS)
                }
            }
        }

        val stallMonitor = launch(Dispatchers.Main.immediate) {
            while (isActive && !completion.isCompleted) {
                delay(STALL_CHECK_INTERVAL_MS)
                val idleFor = System.currentTimeMillis() - lastActivityAt.get()
                if (idleFor < STALL_RELOAD_MS) continue

                if (reloads < MAX_RELOADS_PER_ATTEMPT) {
                    reloads += 1
                    lastActivityAt.set(System.currentTimeMillis())
                    publish(
                        "La búsqueda quedó sin actividad; reactivando el motor web " +
                            "sin perder ${products.size} productos…"
                    )
                    runCatching {
                        webView.resumeTimers()
                        webView.onResume()
                        webView.reload()
                    }
                } else {
                    completion.complete(AttemptOutcome.STALLED)
                }
            }
        }

        publish(if (attempt == 1) "Abriendo ${store.name}…" else "Reintentando ${store.name}…")
        webView.loadUrl(store.url)

        val outcome = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
            completion.await()
        } ?: AttemptOutcome.TIMEOUT

        stallMonitor.cancel()
        handler.removeCallbacks(idleFlushRunnable)
        handler.removeCallbacks(completionRunnable)
        flushPayloads()
        delay(FINAL_ATTEMPT_DRAIN_MS)
        activeWebView = null
        disposeWebView(webView)
        outcome
    }

    private fun publishStoreProgress(
        store: Store,
        storeIndex: Int,
        storeCount: Int,
        previousPromotions: Int,
        products: ConcurrentHashMap<String, CapturedProduct>,
        pendingBatches: AtomicInteger,
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
                    val pending = pendingBatches.get()
                    if (pending > 0) append(" · $pending lotes pendientes")
                },
                productsFound = products.size,
                promotionsFound = previousPromotions + verified,
                pendingBatches = pendingBatches.get(),
            )
        }
        updateProgressNotification()
    }

    private fun disposeWebView(webView: WebView) {
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
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

    private enum class AttemptOutcome {
        COMPLETE,
        STALLED,
        TIMEOUT,
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

        private const val MAX_BUFFERED_PAYLOADS = 20
        private const val MAX_BUFFERED_PAYLOAD_CHARS = 500_000
        private const val PAYLOAD_IDLE_FLUSH_MS = 250L
        private const val COMPLETION_QUIET_MS = 2_500L
        private const val START_EXPLORATION_DELAY_MS = 1_100L
        private const val FINAL_ATTEMPT_DRAIN_MS = 500L
        private const val FINAL_DRAIN_TIMEOUT_MS = 12_000L
        private const val ATTEMPT_TIMEOUT_MS = 180_000L
        private const val MAX_WEBVIEW_ATTEMPTS = 2
        private const val MAX_RELOADS_PER_ATTEMPT = 1
        private const val STALL_CHECK_INTERVAL_MS = 5_000L
        private const val STALL_RELOAD_MS = 30_000L
        private const val MAIN_FRAME_RETRY_MS = 1_500L
        private const val RESTART_DELAY_MS = 700L
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
