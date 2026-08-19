package com.oilterminal.tankcalc.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class OilDatabase(context: Context) :
    SQLiteOpenHelper(context, "oil_tank_gauge.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE vessels (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                normalized_name TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE source_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                original_name TEXT NOT NULL,
                stored_path TEXT NOT NULL,
                sha256 TEXT NOT NULL UNIQUE,
                imported_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE datasets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                vessel_id INTEGER NOT NULL,
                source_file_id INTEGER NOT NULL,
                version_label TEXT NOT NULL,
                imported_at INTEGER NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(vessel_id) REFERENCES vessels(id) ON DELETE CASCADE,
                FOREIGN KEY(source_file_id) REFERENCES source_files(id) ON DELETE RESTRICT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE tanks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dataset_id INTEGER NOT NULL,
                canonical_name TEXT NOT NULL,
                source_name TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                FOREIGN KEY(dataset_id) REFERENCES datasets(id) ON DELETE CASCADE,
                UNIQUE(dataset_id, canonical_name)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE capacity_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tank_id INTEGER NOT NULL,
                ullage_mm INTEGER NOT NULL,
                volume_m3 TEXT NOT NULL,
                trim_m TEXT NOT NULL DEFAULT '0.0',
                source_sheet TEXT NOT NULL,
                ullage_cell TEXT NOT NULL,
                volume_cell TEXT NOT NULL,
                raw_ullage TEXT NOT NULL,
                raw_volume TEXT NOT NULL,
                confidence REAL NOT NULL,
                FOREIGN KEY(tank_id) REFERENCES tanks(id) ON DELETE CASCADE,
                UNIQUE(tank_id, ullage_mm)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE calculation_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                vessel_id INTEGER NOT NULL,
                tank_id INTEGER NOT NULL,
                input_ullage_mm INTEGER NOT NULL,
                result_volume_m3 TEXT NOT NULL,
                calculated_at INTEGER NOT NULL,
                FOREIGN KEY(vessel_id) REFERENCES vessels(id) ON DELETE CASCADE,
                FOREIGN KEY(tank_id) REFERENCES tanks(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_vessels_normalized ON vessels(normalized_name)")
        db.execSQL("CREATE INDEX idx_points_tank_ullage ON capacity_points(tank_id, ullage_mm)")
        db.execSQL("CREATE INDEX idx_datasets_active ON datasets(vessel_id, is_active)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 首版数据库。后续版本在此增加迁移脚本，不能直接清空现场数据。
    }
}
