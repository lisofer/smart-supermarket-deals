package com.lisofer.smartsupermarketdeals.scan

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BackgroundScanState(
    val isRunning: Boolean = false,
    val currentStoreName: String? = null,
    val storeIndex: Int = 0,
    val storeCount: Int = 0,
    val status: String = "",
    val productsFound: Int = 0,
    val promotionsFound: Int = 0,
    val pendingBatches: Int = 0,
    val completedNotice: String? = null,
    val runId: Long = 0L,
)

object BackgroundScanCoordinator {
    private const val PREFS = "background_scan_state"
    private const val KEY_NOTICE = "last_notice"
    private const val KEY_RUN_ID = "last_run_id"

    private val mutableState = MutableStateFlow(BackgroundScanState())
    val state: StateFlow<BackgroundScanState> = mutableState.asStateFlow()

    fun restore(context: Context) {
        if (mutableState.value.isRunning) return
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notice = preferences.getString(KEY_NOTICE, null)
        val runId = preferences.getLong(KEY_RUN_ID, 0L)
        if (!notice.isNullOrBlank() && runId > mutableState.value.runId) {
            mutableState.value = mutableState.value.copy(
                completedNotice = notice,
                runId = runId,
            )
        }
    }

    internal fun begin(context: Context, storeCount: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_NOTICE)
            .apply()
        mutableState.value = BackgroundScanState(
            isRunning = true,
            storeCount = storeCount,
            status = "Preparando búsqueda exhaustiva…",
            runId = System.currentTimeMillis(),
        )
    }

    internal fun update(transform: (BackgroundScanState) -> BackgroundScanState) {
        mutableState.value = transform(mutableState.value)
    }

    internal fun finish(context: Context, notice: String) {
        val runId = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOTICE, notice)
            .putLong(KEY_RUN_ID, runId)
            .apply()
        mutableState.value = mutableState.value.copy(
            isRunning = false,
            status = notice,
            pendingBatches = 0,
            completedNotice = notice,
            runId = runId,
        )
    }
}
