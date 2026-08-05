package com.lisofer.smartsupermarketdeals.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Store(
    val id: Long,
    val name: String,
    val url: String,
)

enum class PromotionKind {
    DIRECT_PERCENT,
    MULTIBUY,
    SECOND_UNIT,
}

enum class PromotionEvidence {
    PRICE_PAIR,
    PRODUCT_TEXT,
    PRODUCT_STRUCTURE,
    INHERITED_SECTION,
}

data class CapturedProduct(
    val key: String,
    val name: String,
    val price: Double,
    val originalPrice: Double?,
    val advertisedDiscountPercent: Double?,
    val promoLabel: String?,
    val promotionCategory: String?,
    val promotionTitle: String?,
    val effectiveDiscountPercent: Double?,
    val promotionKind: PromotionKind?,
    val sourceUrl: String,
    val promotionEvidence: PromotionEvidence? = null,
)

data class PromotionDeal(
    val productKey: String,
    val productName: String,
    val storeName: String,
    val currentPrice: Double,
    val originalPrice: Double?,
    val promoLabel: String?,
    val categoryKey: String,
    val categoryTitle: String,
    val effectiveDiscountPercent: Double,
    val promotionKind: PromotionKind,
)

data class PromotionGroup(
    val key: String,
    val title: String,
    val effectiveDiscountPercent: Double,
    val products: List<PromotionDeal>,
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
                advertised_discount REAL,
                promo_label TEXT,
                promotion_category TEXT,
                promotion_title TEXT,
                effective_discount REAL,
                promotion_kind TEXT,
                source_url TEXT,
                captured_at INTEGER NOT NULL,
                FOREIGN KEY(store_id) REFERENCES stores(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_current_promos ON price_history(store_id, promotion_category)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE price_history ADD COLUMN advertised_discount REAL")
            db.execSQL("ALTER TABLE price_history ADD COLUMN promo_label TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE price_history ADD COLUMN promotion_category TEXT")
            db.execSQL("ALTER TABLE price_history ADD COLUMN promotion_title TEXT")
            db.execSQL("ALTER TABLE price_history ADD COLUMN effective_discount REAL")
            db.execSQL("ALTER TABLE price_history ADD COLUMN promotion_kind TEXT")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_current_promos " +
                    "ON price_history(store_id, promotion_category)"
            )
        }
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

    /**
     * Replaces the previous results for this store. An empty, successfully completed scan clears
     * stale promotions instead of keeping false positives from an older version.
     */
    fun saveScan(storeId: Long, products: Collection<CapturedProduct>) {
        val promotions = products
            .map(PromotionCanonicalizer::captured)
            .filter { product ->
                product.promotionCategory != null &&
                    product.promotionTitle != null &&
                    product.effectiveDiscountPercent != null &&
                    product.effectiveDiscountPercent > 0.0 &&
                    product.promotionKind != null &&
                    product.promotionEvidence != null
            }
            .groupBy { "${it.key}|${it.promotionCategory}" }
            .mapNotNull { (_, versions) ->
                versions.maxWithOrNull(
                    compareBy<CapturedProduct> { evidenceScore(it.promotionEvidence) }
                        .thenBy { if (it.originalPrice == null) 0 else 1 }
                        .thenBy { if (it.promoLabel.isNullOrBlank()) 0 else 1 }
                        .thenBy { it.effectiveDiscountPercent ?: 0.0 }
                )
            }

        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(
                "price_history",
                "store_id = ?",
                arrayOf(storeId.toString()),
            )

            promotions.forEach { product ->
                val values = ContentValues().apply {
                    put("store_id", storeId)
                    put("product_key", product.key)
                    put("product_name", product.name)
                    put("price", product.price)
                    product.originalPrice?.let { put("original_price", it) }
                    product.advertisedDiscountPercent?.let { put("advertised_discount", it) }
                    product.promoLabel?.let { put("promo_label", it) }
                    put("promotion_category", product.promotionCategory)
                    put("promotion_title", product.promotionTitle)
                    put("effective_discount", product.effectiveDiscountPercent)
                    put("promotion_kind", product.promotionKind?.name)
                    put("source_url", product.sourceUrl)
                    put("captured_at", now)
                }
                writableDatabase.insertOrThrow("price_history", null, values)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun promotionGroups(): List<PromotionGroup> {
        val deals = mutableListOf<PromotionDeal>()
        readableDatabase.rawQuery(
            """
            SELECT h.product_key, h.product_name, s.name, h.price,
                   h.original_price, h.promo_label, h.promotion_category,
                   h.promotion_title, h.effective_discount, h.promotion_kind
            FROM price_history h
            JOIN stores s ON s.id = h.store_id
            WHERE h.promotion_category IS NOT NULL
              AND h.effective_discount > 0
              AND h.promotion_kind IS NOT NULL
            ORDER BY h.effective_discount DESC, h.product_name ASC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = runCatching {
                    PromotionKind.valueOf(cursor.getString(9))
                }.getOrNull() ?: continue

                val deal = PromotionDeal(
                    productKey = cursor.getString(0),
                    productName = cursor.getString(1),
                    storeName = cursor.getString(2),
                    currentPrice = cursor.getDouble(3),
                    originalPrice = if (cursor.isNull(4)) null else cursor.getDouble(4),
                    promoLabel = if (cursor.isNull(5)) null else cursor.getString(5),
                    categoryKey = cursor.getString(6),
                    categoryTitle = cursor.getString(7),
                    effectiveDiscountPercent = cursor.getDouble(8),
                    promotionKind = kind,
                )
                deals += PromotionCanonicalizer.deal(deal)
            }
        }

        return deals
            .distinctBy { "${it.storeName}|${it.productKey}|${it.categoryKey}" }
            .groupBy { it.categoryKey }
            .map { (key, categoryDeals) ->
                val ordered = categoryDeals.sortedWith(
                    compareByDescending<PromotionDeal> { it.effectiveDiscountPercent }
                        .thenBy { it.currentPrice }
                        .thenBy { it.productName }
                )
                PromotionGroup(
                    key = key,
                    title = ordered.first().categoryTitle,
                    effectiveDiscountPercent = ordered.maxOf { it.effectiveDiscountPercent },
                    products = ordered,
                )
            }
            .sortedWith(
                compareByDescending<PromotionGroup> { it.effectiveDiscountPercent }
                    .thenByDescending { it.products.size }
                    .thenBy { it.title }
            )
    }

    private fun evidenceScore(evidence: PromotionEvidence?): Int = when (evidence) {
        PromotionEvidence.PRICE_PAIR -> 5
        PromotionEvidence.PRODUCT_TEXT -> 4
        PromotionEvidence.PRODUCT_STRUCTURE -> 3
        PromotionEvidence.INHERITED_SECTION -> 1
        null -> 0
    }

    companion object {
        private const val DB_NAME = "smart_deals.db"
        private const val DB_VERSION = 3
    }
}
