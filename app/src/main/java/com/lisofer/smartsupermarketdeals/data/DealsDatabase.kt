package com.lisofer.smartsupermarketdeals.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.max

data class Store(
    val id: Long,
    val name: String,
    val url: String,
)

data class CapturedProduct(
    val key: String,
    val name: String,
    val price: Double,
    val originalPrice: Double?,
    val sourceUrl: String,
)

data class Deal(
    val productName: String,
    val storeName: String,
    val currentPrice: Double,
    val referencePrice: Double?,
    val discountPercent: Double,
    val isHistoricalMinimum: Boolean,
)

class DealsDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE stores (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE price_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id INTEGER NOT NULL,
                product_key TEXT NOT NULL,
                product_name TEXT NOT NULL,
                price REAL NOT NULL,
                original_price REAL,
                source_url TEXT,
                captured_at INTEGER NOT NULL,
                FOREIGN KEY(store_id) REFERENCES stores(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_history_product ON price_history(store_id, product_key, captured_at)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS price_history")
        db.execSQL("DROP TABLE IF EXISTS stores")
        onCreate(db)
    }

    fun addStore(name: String, url: String): Long {
        val normalizedUrl = url.trim()
        val normalizedName = name.trim().ifBlank { "Supermercado" }
        readableDatabase.query(
            "stores",
            arrayOf("id"),
            "url = ?",
            arrayOf(normalizedUrl),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val values = ContentValues().apply { put("name", normalizedName) }
                writableDatabase.update("stores", values, "id = ?", arrayOf(id.toString()))
                return id
            }
        }

        val values = ContentValues().apply {
            put("name", normalizedName)
            put("url", normalizedUrl)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("stores", null, values)
    }

    fun deleteStore(id: Long) {
        writableDatabase.delete("stores", "id = ?", arrayOf(id.toString()))
    }

    fun stores(): List<Store> {
        val result = mutableListOf<Store>()
        readableDatabase.query(
            "stores",
            arrayOf("id", "name", "url"),
            null,
            null,
            null,
            null,
            "created_at ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Store(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    url = cursor.getString(2),
                )
            }
        }
        return result
    }

    fun saveScan(storeId: Long, products: Collection<CapturedProduct>) {
        if (products.isEmpty()) return
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            products.distinctBy { it.key }.forEach { product ->
                val values = ContentValues().apply {
                    put("store_id", storeId)
                    put("product_key", product.key)
                    put("product_name", product.name)
                    put("price", product.price)
                    product.originalPrice?.let { put("original_price", it) }
                    put("source_url", product.sourceUrl)
                    put("captured_at", now)
                }
                writableDatabase.insert("price_history", null, values)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun topDeals(limit: Int = 30): List<Deal> {
        data class Row(
            val storeId: Long,
            val storeName: String,
            val key: String,
            val name: String,
            val price: Double,
            val originalPrice: Double?,
            val capturedAt: Long,
        )

        val rows = mutableListOf<Row>()
        val cutoff = System.currentTimeMillis() - HISTORY_WINDOW_MS
        readableDatabase.rawQuery(
            """
            SELECT h.store_id, s.name, h.product_key, h.product_name,
                   h.price, h.original_price, h.captured_at
            FROM price_history h
            JOIN stores s ON s.id = h.store_id
            WHERE h.captured_at >= ?
            ORDER BY h.store_id, h.product_key, h.captured_at DESC
            """.trimIndent(),
            arrayOf(cutoff.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += Row(
                    storeId = cursor.getLong(0),
                    storeName = cursor.getString(1),
                    key = cursor.getString(2),
                    name = cursor.getString(3),
                    price = cursor.getDouble(4),
                    originalPrice = if (cursor.isNull(5)) null else cursor.getDouble(5),
                    capturedAt = cursor.getLong(6),
                )
            }
        }

        return rows.groupBy { "${it.storeId}:${it.key}" }
            .mapNotNull { (_, history) ->
                val latest = history.maxByOrNull { it.capturedAt } ?: return@mapNotNull null
                val previous = history
                    .filter { it.capturedAt < latest.capturedAt }
                    .map { it.price }
                val historicalReference = previous.medianOrNull()
                val advertisedReference = latest.originalPrice?.takeIf { it > latest.price }
                val reference = historicalReference ?: advertisedReference
                val discount = reference
                    ?.takeIf { it > 0.0 }
                    ?.let { ((it - latest.price) / it) * 100.0 }
                    ?: 0.0
                val minimum = history.minOfOrNull { it.price } ?: latest.price
                Deal(
                    productName = latest.name,
                    storeName = latest.storeName,
                    currentPrice = latest.price,
                    referencePrice = reference,
                    discountPercent = max(0.0, discount),
                    isHistoricalMinimum = latest.price <= minimum,
                )
            }
            .sortedWith(
                compareByDescending<Deal> { it.discountPercent }
                    .thenByDescending { it.isHistoricalMinimum }
                    .thenBy { it.currentPrice }
            )
            .take(limit)
    }

    private fun List<Double>.medianOrNull(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    companion object {
        private const val DB_NAME = "smart_deals.db"
        private const val DB_VERSION = 1
        private const val HISTORY_WINDOW_MS = 90L * 24L * 60L * 60L * 1000L
    }
}
