package com.oilterminal.tankcalc.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.oilterminal.tankcalc.excel.ParsedWorkbook
import com.oilterminal.tankcalc.util.NameNormalizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OilRepository(context: Context) {
    private val database = OilDatabase(context.applicationContext)

    fun vesselCount(): Int {
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM vessels", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun listVessels(): List<VesselSummary> {
        val sql =
            """
            SELECT v.id, v.name, v.normalized_name,
                   d.id, d.version_label, d.imported_at,
                   sf.original_name,
                   COUNT(t.id)
            FROM vessels v
            LEFT JOIN datasets d ON d.vessel_id = v.id AND d.is_active = 1
            LEFT JOIN source_files sf ON sf.id = d.source_file_id
            LEFT JOIN tanks t ON t.dataset_id = d.id
            GROUP BY v.id
            ORDER BY v.updated_at DESC, v.name
            """.trimIndent()
        return database.readableDatabase.rawQuery(sql, null).use(::readVessels)
    }

    fun searchVessels(query: String): List<VesselSummary> {
        val normalized = NameNormalizer.vessel(query)
        if (normalized.isBlank()) return emptyList()
        val sql =
            """
            SELECT v.id, v.name, v.normalized_name,
                   d.id, d.version_label, d.imported_at,
                   sf.original_name,
                   COUNT(t.id)
            FROM vessels v
            LEFT JOIN datasets d ON d.vessel_id = v.id AND d.is_active = 1
            LEFT JOIN source_files sf ON sf.id = d.source_file_id
            LEFT JOIN tanks t ON t.dataset_id = d.id
            WHERE v.normalized_name LIKE ?
            GROUP BY v.id
            ORDER BY CASE WHEN v.normalized_name = ? THEN 0 ELSE 1 END,
                     v.updated_at DESC
            LIMIT 30
            """.trimIndent()
        return database.readableDatabase.rawQuery(
            sql,
            arrayOf("%$normalized%", normalized)
        ).use(::readVessels)
    }

    fun vesselById(id: Long): VesselSummary? =
        listVessels().firstOrNull { it.id == id }

    fun tanksForVessel(vesselId: Long): List<TankSummary> {
        val sql =
            """
            SELECT t.id, t.dataset_id, t.canonical_name, t.source_name,
                   t.sort_order, COUNT(p.id), MIN(p.ullage_mm), MAX(p.ullage_mm)
            FROM tanks t
            JOIN datasets d ON d.id = t.dataset_id
            LEFT JOIN capacity_points p ON p.tank_id = t.id
            WHERE d.vessel_id = ? AND d.is_active = 1
            GROUP BY t.id
            ORDER BY t.sort_order, t.canonical_name
            """.trimIndent()
        return database.readableDatabase.rawQuery(
            sql,
            arrayOf(vesselId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TankSummary(
                            id = cursor.getLong(0),
                            datasetId = cursor.getLong(1),
                            canonicalName = cursor.getString(2),
                            sourceName = cursor.getString(3),
                            sortOrder = cursor.getInt(4),
                            pointCount = cursor.getInt(5),
                            minUllageMm = cursor.getNullableInt(6),
                            maxUllageMm = cursor.getNullableInt(7)
                        )
                    )
                }
            }
        }
    }

    fun sourceFileForVessel(vesselId: Long): SourceFileRecord? {
        val sql =
            """
            SELECT sf.id, sf.original_name, sf.stored_path, sf.sha256, sf.imported_at
            FROM source_files sf
            JOIN datasets d ON d.source_file_id = sf.id
            WHERE d.vessel_id = ? AND d.is_active = 1
            LIMIT 1
            """.trimIndent()
        return database.readableDatabase.rawQuery(
            sql,
            arrayOf(vesselId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else SourceFileRecord(
                id = cursor.getLong(0),
                originalName = cursor.getString(1),
                storedPath = cursor.getString(2),
                sha256 = cursor.getString(3),
                importedAt = cursor.getLong(4)
            )
        }
    }

    fun findBracket(tankId: Long, ullageMm: Int): PointBracket {
        val db = database.readableDatabase
        val exact = queryPoint(
            db,
            "SELECT * FROM capacity_points WHERE tank_id=? AND ullage_mm=? LIMIT 1",
            arrayOf(tankId.toString(), ullageMm.toString())
        )
        val lower = if (exact == null) queryPoint(
            db,
            "SELECT * FROM capacity_points WHERE tank_id=? AND ullage_mm<? ORDER BY ullage_mm DESC LIMIT 1",
            arrayOf(tankId.toString(), ullageMm.toString())
        ) else null
        val upper = if (exact == null) queryPoint(
            db,
            "SELECT * FROM capacity_points WHERE tank_id=? AND ullage_mm>? ORDER BY ullage_mm ASC LIMIT 1",
            arrayOf(tankId.toString(), ullageMm.toString())
        ) else null

        var min: Int? = null
        var max: Int? = null
        db.rawQuery(
            "SELECT MIN(ullage_mm), MAX(ullage_mm) FROM capacity_points WHERE tank_id=?",
            arrayOf(tankId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                min = cursor.getNullableInt(0)
                max = cursor.getNullableInt(1)
            }
        }
        return PointBracket(exact, lower, upper, min, max)
    }

    fun capacityPointsForTank(tankId: Long): List<CapacityPoint> {
        return database.readableDatabase.rawQuery(
            "SELECT * FROM capacity_points WHERE tank_id=? ORDER BY ullage_mm ASC",
            arrayOf(tankId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(pointFromCursor(cursor))
            }
        }
    }

    fun saveParsedWorkbook(
        originalName: String,
        storedPath: String,
        sha256: String,
        parsed: ParsedWorkbook
    ): ImportOutcome {
        val db = database.writableDatabase
        db.rawQuery(
            "SELECT id FROM source_files WHERE sha256=? LIMIT 1",
            arrayOf(sha256)
        ).use {
            if (it.moveToFirst()) {
                return ImportOutcome(
                    duplicate = true,
                    vesselCount = 0,
                    tankCount = 0,
                    pointCount = 0,
                    message = "该原始表格已经导入过，未重复写入数据库。"
                )
            }
        }

        var vesselCount = 0
        var tankCount = 0
        var pointCount = 0
        val now = System.currentTimeMillis()
        val versionLabel = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            .format(Date(now))

        db.beginTransaction()
        try {
            val sourceId = db.insertOrThrow(
                "source_files",
                null,
                ContentValues().apply {
                    put("original_name", originalName)
                    put("stored_path", storedPath)
                    put("sha256", sha256)
                    put("imported_at", now)
                }
            )

            parsed.vessels.forEach { vessel ->
                val vesselId = findOrCreateVessel(
                    db = db,
                    name = vessel.name,
                    normalizedName = vessel.normalizedName,
                    now = now
                )
                db.execSQL(
                    "UPDATE datasets SET is_active=0 WHERE vessel_id=?",
                    arrayOf(vesselId)
                )
                val datasetId = db.insertOrThrow(
                    "datasets",
                    null,
                    ContentValues().apply {
                        put("vessel_id", vesselId)
                        put("source_file_id", sourceId)
                        put("version_label", "$versionLabel · $originalName")
                        put("imported_at", now)
                        put("is_active", 1)
                    }
                )
                vesselCount += 1

                vessel.tanks.forEach { tank ->
                    val tankId = db.insertOrThrow(
                        "tanks",
                        null,
                        ContentValues().apply {
                            put("dataset_id", datasetId)
                            put("canonical_name", tank.canonicalName)
                            put("source_name", tank.sourceName)
                            put("sort_order", tank.sortOrder)
                        }
                    )
                    tankCount += 1

                    tank.points.forEach { point ->
                        db.insertOrThrow(
                            "capacity_points",
                            null,
                            ContentValues().apply {
                                put("tank_id", tankId)
                                put("ullage_mm", point.ullageMm)
                                put("volume_m3", point.volumeM3)
                                put("trim_m", "0.0")
                                put("source_sheet", point.sourceSheet)
                                put("ullage_cell", point.ullageCell)
                                put("volume_cell", point.volumeCell)
                                put("raw_ullage", point.rawUllage)
                                put("raw_volume", point.rawVolume)
                                put("confidence", point.confidence)
                            }
                        )
                        pointCount += 1
                    }
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return ImportOutcome(
            duplicate = false,
            vesselCount = vesselCount,
            tankCount = tankCount,
            pointCount = pointCount,
            message = "成功导入 $vesselCount 艘船、$tankCount 个船舱、$pointCount 个舱容数据点。"
        )
    }

    fun renameVessel(vesselId: Long, newName: String) {
        val value = newName.trim()
        val normalized = NameNormalizer.vessel(value)
        require(value.isNotBlank()) { "船号不能为空" }
        require(normalized.isNotBlank()) { "船号没有可用于匹配的字符" }

        val updated = database.writableDatabase.update(
            "vessels",
            ContentValues().apply {
                put("name", value)
                put("normalized_name", normalized)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(vesselId.toString())
        )
        require(updated == 1) { "船舶记录不存在" }
    }

    fun renameTank(tankId: Long, newName: String) {
        val value = newName.trim()
        require(value.isNotBlank()) { "船舱名称不能为空" }
        database.writableDatabase.update(
            "tanks",
            ContentValues().apply { put("canonical_name", value) },
            "id=?",
            arrayOf(tankId.toString())
        )
    }

    fun deleteVessel(vesselId: Long) {
        val db = database.writableDatabase
        val orphanPaths = mutableListOf<String>()
        db.beginTransaction()
        try {
            db.delete("vessels", "id=?", arrayOf(vesselId.toString()))
            db.rawQuery(
                """
                SELECT stored_path FROM source_files
                WHERE id NOT IN (SELECT DISTINCT source_file_id FROM datasets)
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) orphanPaths += cursor.getString(0)
            }
            db.execSQL(
                """
                DELETE FROM source_files
                WHERE id NOT IN (SELECT DISTINCT source_file_id FROM datasets)
                """.trimIndent()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        orphanPaths.forEach { path -> runCatching { File(path).delete() } }
    }

    fun recordCalculation(
        vesselId: Long,
        tankId: Long,
        inputUllageMm: Int,
        resultVolumeM3: String
    ) {
        database.writableDatabase.insert(
            "calculation_history",
            null,
            ContentValues().apply {
                put("vessel_id", vesselId)
                put("tank_id", tankId)
                put("input_ullage_mm", inputUllageMm)
                put("result_volume_m3", resultVolumeM3)
                put("calculated_at", System.currentTimeMillis())
            }
        )
    }

    fun recentCalculations(limit: Int = 5): List<RecentCalculation> {
        val sql =
            """
            SELECT v.name, t.canonical_name, h.input_ullage_mm,
                   h.result_volume_m3, h.calculated_at
            FROM calculation_history h
            JOIN vessels v ON v.id = h.vessel_id
            JOIN tanks t ON t.id = h.tank_id
            ORDER BY h.calculated_at DESC
            LIMIT ?
            """.trimIndent()
        return database.readableDatabase.rawQuery(
            sql,
            arrayOf(limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RecentCalculation(
                            vesselName = cursor.getString(0),
                            tankName = cursor.getString(1),
                            inputUllageMm = cursor.getInt(2),
                            resultVolumeM3 = cursor.getString(3),
                            calculatedAt = cursor.getLong(4)
                        )
                    )
                }
            }
        }
    }

    private fun findOrCreateVessel(
        db: SQLiteDatabase,
        name: String,
        normalizedName: String,
        now: Long
    ): Long {
        db.rawQuery(
            "SELECT id FROM vessels WHERE normalized_name=? LIMIT 1",
            arrayOf(normalizedName)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                db.update(
                    "vessels",
                    ContentValues().apply {
                        put("name", name)
                        put("updated_at", now)
                    },
                    "id=?",
                    arrayOf(id.toString())
                )
                return id
            }
        }
        return db.insertOrThrow(
            "vessels",
            null,
            ContentValues().apply {
                put("name", name)
                put("normalized_name", normalizedName)
                put("created_at", now)
                put("updated_at", now)
            }
        )
    }

    private fun readVessels(cursor: Cursor): List<VesselSummary> =
        buildList {
            while (cursor.moveToNext()) {
                add(
                    VesselSummary(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        normalizedName = cursor.getString(2),
                        activeDatasetId = cursor.getNullableLong(3),
                        versionLabel = cursor.getNullableString(4),
                        importedAt = cursor.getNullableLong(5),
                        sourceFileName = cursor.getNullableString(6),
                        tankCount = cursor.getInt(7)
                    )
                )
            }
        }

    private fun queryPoint(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>
    ): CapacityPoint? {
        return db.rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) null else pointFromCursor(cursor)
        }
    }

    private fun pointFromCursor(cursor: Cursor): CapacityPoint {
        // SELECT * 字段顺序对应 OilDatabase 中 capacity_points 的建表顺序。
        return CapacityPoint(
            id = cursor.getLong(0),
            tankId = cursor.getLong(1),
            ullageMm = cursor.getInt(2),
            volumeM3 = cursor.getString(3),
            sourceSheet = cursor.getString(5),
            ullageCell = cursor.getString(6),
            volumeCell = cursor.getString(7),
            rawUllage = cursor.getString(8),
            rawVolume = cursor.getString(9),
            confidence = cursor.getDouble(10)
        )
    }

    private fun Cursor.getNullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun Cursor.getNullableInt(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun Cursor.getNullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)
}
