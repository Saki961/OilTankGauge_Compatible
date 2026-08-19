package com.oilterminal.tankcalc.data

data class VesselSummary(
    val id: Long,
    val name: String,
    val normalizedName: String,
    val activeDatasetId: Long?,
    val versionLabel: String?,
    val importedAt: Long?,
    val sourceFileName: String?,
    val tankCount: Int
)

data class TankSummary(
    val id: Long,
    val datasetId: Long,
    val canonicalName: String,
    val sourceName: String,
    val sortOrder: Int,
    val pointCount: Int,
    val minUllageMm: Int?,
    val maxUllageMm: Int?
)

data class SourceFileRecord(
    val id: Long,
    val originalName: String,
    val storedPath: String,
    val sha256: String,
    val importedAt: Long
)

data class CapacityPoint(
    val id: Long,
    val tankId: Long,
    val ullageMm: Int,
    val volumeM3: String,
    val sourceSheet: String,
    val ullageCell: String,
    val volumeCell: String,
    val rawUllage: String,
    val rawVolume: String,
    val confidence: Double
)

data class PointBracket(
    val exact: CapacityPoint?,
    val lower: CapacityPoint?,
    val upper: CapacityPoint?,
    val minUllageMm: Int?,
    val maxUllageMm: Int?
)

data class ImportOutcome(
    val duplicate: Boolean,
    val vesselCount: Int,
    val tankCount: Int,
    val pointCount: Int,
    val message: String
)

data class RecentCalculation(
    val vesselName: String,
    val tankName: String,
    val inputUllageMm: Int,
    val resultVolumeM3: String,
    val calculatedAt: Long
)
