package com.oilterminal.tankcalc.excel

import com.oilterminal.tankcalc.util.NameNormalizer
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

data class ParsedPoint(
    val ullageMm: Int,
    val volumeM3: String,
    val sourceSheet: String,
    val ullageCell: String,
    val volumeCell: String,
    val rawUllage: String,
    val rawVolume: String,
    val confidence: Double
)

data class ParsedTank(
    val canonicalName: String,
    val sourceName: String,
    val sortOrder: Int,
    val points: List<ParsedPoint>
)

data class ParsedVessel(
    val name: String,
    val normalizedName: String,
    val tanks: List<ParsedTank>
)

data class ParsedWorkbook(
    val vessels: List<ParsedVessel>,
    val warnings: List<String>
)

object TankWorkbookParser {
    private val numberRegex = Regex("""[-+]?\d+(?:\.\d+)?""")
    private val vesselRegex = Regex("""([\u4E00-\u9FFF]{1,16}\s*[A-Za-z]*\s*\d{1,12})""")
    private val cargoTankRegex =
        Regex("""第?\s*([一二三四五六七八九十两\d]+)\s*(?:号)?\s*(?:货油舱|货舱|油舱)""")

    fun parse(workbook: XlsxWorkbook, fallbackFileName: String): ParsedWorkbook {
        val warnings = mutableListOf<String>()
        val fallbackVessel = detectVessel(fallbackFileName.substringBeforeLast('.'))
        val vesselBuilders = linkedMapOf<String, VesselBuilder>()

        workbook.sheets.forEach { sheet ->
            if (!looksLikeTankSheet(sheet)) return@forEach

            val vesselName =
                detectVessel(scanText(sheet, rowLimit = 25))
                    ?: detectVessel(scanText(sheet, rowLimit = 80))
                    ?: fallbackVessel
            if (vesselName == null) {
                warnings += "${sheet.name}：未识别船号，已跳过。"
                return@forEach
            }

            val extracted = extractZeroTrimMatrix(sheet).ifEmpty {
                extractRepeatedCapacityGroups(sheet)
            }
            if (extracted.isEmpty()) {
                warnings += "${sheet.name}：识别到舱容表，但未提取到 0.0m 空高—容积数据。"
                return@forEach
            }

            val vesselKey = NameNormalizer.vessel(vesselName)
            val vessel = vesselBuilders.getOrPut(vesselKey) {
                VesselBuilder(vesselName)
            }
            val identity = detectTank(sheet)
            val canonicalName = identity.canonicalName ?: vessel.nextFallbackTankName()
            if (identity.canonicalName == null) {
                warnings +=
                    "${vesselName}/${sheet.name}：未识别明确船舱标识，已按表格顺序暂映射为 $canonicalName，请导入后核对。"
            }

            val tank = vessel.tanks.getOrPut(canonicalName) {
                vessel.nextSortOrder += 1
                TankBuilder(
                    canonicalName = canonicalName,
                    sourceName = identity.sourceName,
                    sortOrder = vessel.nextSortOrder
                )
            }

            extracted.forEach { point ->
                val previous = tank.points[point.ullageMm]
                if (previous == null) {
                    tank.points[point.ullageMm] = point
                } else {
                    val difference = abs(
                        previous.volumeM3.toDouble() - point.volumeM3.toDouble()
                    )
                    if (difference > 0.02) {
                        warnings +=
                            "${vesselName}/${tank.canonicalName}：空高 ${point.ullageMm} mm " +
                            "在 ${previous.sourceSheet} 与 ${point.sourceSheet} 的容积不一致，" +
                            "保留可信度较高的数据。"
                    }
                    if (point.confidence > previous.confidence) {
                        tank.points[point.ullageMm] = point
                    }
                }
            }
        }

        val vessels = vesselBuilders.values.map { vessel ->
            ParsedVessel(
                name = vessel.name,
                normalizedName = NameNormalizer.vessel(vessel.name),
                tanks = vessel.tanks.values
                    .filter { it.points.size >= 2 }
                    .sortedBy { it.sortOrder }
                    .map { tank ->
                        ParsedTank(
                            canonicalName = tank.canonicalName,
                            sourceName = tank.sourceName,
                            sortOrder = tank.sortOrder,
                            points = tank.points.values.sortedBy { it.ullageMm }
                        )
                    }
            )
        }.filter { it.tanks.isNotEmpty() }

        return ParsedWorkbook(vessels, warnings.distinct())
    }

    private fun looksLikeTankSheet(sheet: XlsxSheet): Boolean {
        val rowLimit = minOf(sheet.maxRow, 100)
        var hasUllageHeader = false
        var hasCapacityHeader = false
        var hasZeroTrimHeader = false
        var numericDataRows = 0

        for (row in 1..rowLimit) {
            var numericCells = 0
            for (column in 1..minOf(sheet.maxColumn, 80)) {
                val value = sheet.value(row, column)
                if (isUllageHeader(value)) hasUllageHeader = true
                if (isCapacityHeader(value)) hasCapacityHeader = true
                if (isZeroTrimHeader(value)) hasZeroTrimHeader = true
                if (parseNumbers(value.orEmpty()).isNotEmpty()) numericCells += 1
            }
            if (numericCells >= 2) numericDataRows += 1
        }

        val titleText = scanText(sheet, rowLimit = minOf(30, rowLimit)).lowercase()
        val hasCapacityTitle =
            titleText.contains("tank capacity table") ||
                titleText.contains("tnnk capacity table") ||
                titleText.contains("舱容表") ||
                titleText.contains("容量表")

        // 至少要有三行数值数据，避免把“原油计算”结果页误识别为舱容表。
        return hasUllageHeader &&
            (hasCapacityHeader || hasZeroTrimHeader) &&
            numericDataRows >= 3 &&
            (hasCapacityTitle || hasZeroTrimHeader || hasNearbyHeaderPair(sheet))
    }

    private fun hasNearbyHeaderPair(sheet: XlsxSheet): Boolean {
        val limit = minOf(sheet.maxRow, 100)
        for (row in 1..limit) {
            for (column in 1..minOf(sheet.maxColumn, 80)) {
                if (!isUllageHeader(sheet.value(row, column))) continue
                for (capacityRow in maxOf(1, row - 2)..minOf(limit, row + 2)) {
                    for (capacityColumn in column + 1..minOf(sheet.maxColumn, column + 8)) {
                        if (isCapacityHeader(sheet.value(capacityRow, capacityColumn))) return true
                    }
                }
            }
        }
        return false
    }

    private fun scanText(sheet: XlsxSheet, rowLimit: Int): String {
        return buildString {
            for (row in 1..minOf(rowLimit, sheet.maxRow)) {
                for (column in 1..minOf(sheet.maxColumn, 80)) {
                    val value = sheet.value(row, column)
                    if (!value.isNullOrBlank()) append(value).append(' ')
                }
            }
        }
    }

    private fun detectVessel(text: String): String? {
        val normalized = NameNormalizer.toHalfWidth(text)
        val rejectedPrefixes = listOf(
            "第", "单位", "容量", "容积", "实高", "空高", "纵倾",
            "日期", "船舱", "货舱", "编号", "表号", "有效期", "检定", "截至", "至"
        )
        return vesselRegex.findAll(normalized)
            .map { it.groupValues[1].replace(Regex("""\s+"""), "") }
            .filterNot { candidate ->
                rejectedPrefixes.any(candidate::startsWith) ||
                    candidate.contains("JD", ignoreCase = true) ||
                    candidate.startsWith("NO", ignoreCase = true) ||
                    candidate.matches(Regex("""V\d{6,}""", RegexOption.IGNORE_CASE))
            }
            .maxByOrNull { candidate ->
                candidate.count { it.code in 0x4E00..0x9FFF } * 10 + candidate.length
            }
    }

    private data class TankIdentity(
        val canonicalName: String?,
        val sourceName: String
    )

    private fun detectTank(sheet: XlsxSheet): TankIdentity {
        val text = NameNormalizer.toHalfWidth(scanText(sheet, rowLimit = 20))
        val bracket = Regex("""[［\[]([^］\]]{1,60})[］\]]""")
            .find(text)?.groupValues?.get(1)
        val scope = bracket ?: text

        val pMatch = Regex("""(?:^|[^A-Za-z0-9])(\d{1,2})\s*[Pp](?:[.\s]|$)""")
            .find(scope)
        val sMatch = Regex("""(?:^|[^A-Za-z0-9])(\d{1,2})\s*[Ss](?:[.\s]|$)""")
            .find(scope)
        val portMatch = Regex("""(?:PORT|P)\s*[-.]?\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
            .find(scope)
        val starboardMatch =
            Regex("""(?:STARBOARD|STBD|S)\s*[-.]?\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
                .find(scope)

        val side = when {
            scope.contains("左") || pMatch != null || portMatch != null -> "左"
            scope.contains("右") || sMatch != null || starboardMatch != null -> "右"
            else -> null
        }

        val number = pMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: sMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: portMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: starboardMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex(
                """(?:左|右)[^0-9一二三四五六七八九十两]{0,8}([0-9]+|[一二三四五六七八九十两]+)"""
            ).find(scope)?.groupValues?.get(1)?.let(NameNormalizer::parseChineseNumber)
            ?: cargoTankRegex.find(text)?.groupValues?.get(1)
                ?.let(NameNormalizer::parseChineseNumber)

        val explicitSource = pMatch?.value
            ?: sMatch?.value
            ?: portMatch?.value
            ?: starboardMatch?.value
            ?: cargoTankRegex.find(text)?.value
            ?: bracket

        val canonical = when {
            side != null && number != null -> side + NameNormalizer.chineseNumber(number)
            number != null -> "货舱" + NameNormalizer.chineseNumber(number)
            else -> null
        }
        return TankIdentity(
            canonicalName = canonical,
            sourceName = explicitSource?.trim()?.ifBlank { sheet.name } ?: sheet.name
        )
    }

    private fun extractZeroTrimMatrix(sheet: XlsxSheet): List<ParsedPoint> {
        val pairs = linkedSetOf<ColumnPair>()
        val rowLimit = minOf(sheet.maxRow, 100)

        for (row in 1..rowLimit) {
            for (column in 1..minOf(sheet.maxColumn, 80)) {
                if (!isZeroTrimHeader(sheet.value(row, column))) continue

                val candidates = mutableListOf<Triple<Int, Int, Int>>()
                for (headerRow in maxOf(1, row - 6)..minOf(rowLimit, row + 2)) {
                    for (headerColumn in maxOf(1, column - 12) until column) {
                        if (isUllageHeader(sheet.value(headerRow, headerColumn))) {
                            val score = abs(row - headerRow) * 20 + (column - headerColumn)
                            candidates += Triple(score, headerRow, headerColumn)
                        }
                    }
                }
                val selected = candidates.minByOrNull { it.first } ?: continue
                pairs += ColumnPair(
                    startRow = maxOf(row, selected.second) + 1,
                    ullageColumn = selected.third,
                    volumeColumn = column
                )
            }
        }

        return pairs.flatMap { pair ->
            collectColumnPair(
                sheet = sheet,
                startRow = pair.startRow,
                ullageColumn = pair.ullageColumn,
                volumeColumn = pair.volumeColumn,
                allowCombinedCell = false
            )
        }
    }

    private fun extractRepeatedCapacityGroups(sheet: XlsxSheet): List<ParsedPoint> {
        val pairs = linkedSetOf<ColumnPair>()
        val rowLimit = minOf(sheet.maxRow, 100)

        for (ullageRow in 1..rowLimit) {
            for (ullageColumn in 1..minOf(sheet.maxColumn, 80)) {
                if (!isUllageHeader(sheet.value(ullageRow, ullageColumn))) continue

                var best: Triple<Int, Int, Int>? = null
                for (capacityRow in maxOf(1, ullageRow - 2)..minOf(rowLimit, ullageRow + 2)) {
                    for (capacityColumn in ullageColumn + 1..minOf(sheet.maxColumn, ullageColumn + 8)) {
                        if (!isCapacityHeader(sheet.value(capacityRow, capacityColumn))) continue
                        val score = abs(capacityRow - ullageRow) * 20 +
                            (capacityColumn - ullageColumn)
                        if (best == null || score < best.first) {
                            best = Triple(score, capacityRow, capacityColumn)
                        }
                    }
                }
                best?.let {
                    pairs += ColumnPair(
                        startRow = maxOf(ullageRow, it.second) + 1,
                        ullageColumn = ullageColumn,
                        volumeColumn = it.third
                    )
                }
            }
        }

        return pairs.flatMap { pair ->
            collectColumnPair(
                sheet = sheet,
                startRow = pair.startRow,
                ullageColumn = pair.ullageColumn,
                volumeColumn = pair.volumeColumn,
                allowCombinedCell = true
            )
        }
    }

    private fun collectColumnPair(
        sheet: XlsxSheet,
        startRow: Int,
        ullageColumn: Int,
        volumeColumn: Int,
        allowCombinedCell: Boolean
    ): List<ParsedPoint> {
        val points = mutableListOf<ParsedPoint>()
        for (row in startRow..sheet.maxRow) {
            val rawUllage = sheet.value(row, ullageColumn).orEmpty().trim()
            val rawVolume = sheet.value(row, volumeColumn).orEmpty().trim()
            if (rawUllage.isBlank() && rawVolume.isBlank()) continue
            if (isFooterOrDateText(rawUllage) || isFooterOrDateText(rawVolume)) continue

            val ullageNumbers = parseNumbers(rawUllage)
            val volumeNumbers = parseNumbers(rawVolume)
            val rawPair = when {
                ullageNumbers.isNotEmpty() && volumeNumbers.isNotEmpty() ->
                    ullageNumbers.first() to volumeNumbers.first()

                allowCombinedCell &&
                    ullageNumbers.size >= 2 &&
                    ullageNumbers[0] >= BigDecimal.ZERO &&
                    ullageNumbers[0] <= BigDecimal("30") &&
                    ullageNumbers[1] >= BigDecimal.ZERO ->
                    ullageNumbers[0] to ullageNumbers[1]

                else -> null
            } ?: continue

            val ullageMm = normalizeUllage(rawPair.first) ?: continue
            val effectiveRawVolume = rawVolume.ifBlank { rawUllage }
            val volume = normalizeVolume(rawPair.second, effectiveRawVolume) ?: continue
            val confidence =
                numericConfidence(rawUllage) * 0.5 +
                    numericConfidence(effectiveRawVolume) * 0.5

            points += ParsedPoint(
                ullageMm = ullageMm,
                volumeM3 = volume.stripTrailingZeros().toPlainString(),
                sourceSheet = sheet.name,
                ullageCell = cellReference(row, ullageColumn),
                volumeCell = cellReference(row, volumeColumn),
                rawUllage = rawUllage,
                rawVolume = effectiveRawVolume,
                confidence = confidence
            )
        }
        return points
    }

    private fun parseNumbers(text: String): List<BigDecimal> {
        val normalized = NameNormalizer.toHalfWidth(text)
            .replace(",", "")
            .replace("，", "")
            .replace("−", "-")
        return numberRegex.findAll(normalized)
            .mapNotNull { runCatching { it.value.toBigDecimal() }.getOrNull() }
            .toList()
    }

    private fun normalizeUllage(value: BigDecimal): Int? {
        if (value < BigDecimal.ZERO) return null
        val meters = if (value > BigDecimal("50") && value <= BigDecimal("30000")) {
            value.divide(BigDecimal("1000"), 8, RoundingMode.HALF_UP)
        } else {
            value
        }
        if (meters > BigDecimal("30")) return null
        return meters.multiply(BigDecimal("1000"))
            .setScale(0, RoundingMode.HALF_UP)
            .toInt()
    }

    private fun normalizeVolume(value: BigDecimal, raw: String): BigDecimal? {
        if (value < BigDecimal.ZERO) return null
        val normalizedRaw = NameNormalizer.toHalfWidth(raw)
        val firstToken = numberRegex.find(normalizedRaw)?.value.orEmpty()
        val looksLikeMissingDecimal =
            !firstToken.contains('.') &&
                value > BigDecimal("10000") &&
                value <= BigDecimal("999999")

        val normalized = if (looksLikeMissingDecimal) {
            value.divide(BigDecimal("1000"), 6, RoundingMode.HALF_UP)
        } else {
            value
        }
        return normalized.takeIf { it <= BigDecimal("100000") }
    }

    private fun isFooterOrDateText(raw: String): Boolean {
        val text = NameNormalizer.toHalfWidth(raw).lowercase()
        val keywords = listOf(
            "有效期", "检定", "校验", "签字", "日期", "年", "月", "日",
            "date", "valid", "expire", "issued"
        )
        return keywords.any(text::contains)
    }

    private fun numericConfidence(raw: String): Double {
        val trimmed = NameNormalizer.toHalfWidth(raw).trim()
        return when {
            trimmed.isEmpty() -> 0.70
            trimmed.matches(Regex("""[-+]?\d+(?:\.\d+)?""")) -> 1.0
            trimmed.count { it.isDigit() } >= 2 -> 0.85
            else -> 0.70
        }
    }

    private fun isZeroTrimHeader(value: String?): Boolean {
        val text = NameNormalizer.toHalfWidth(value.orEmpty())
            .trim()
            .lowercase()
            .replace("米", "m")
            .replace(Regex("""\s+"""), "")
        return text.matches(Regex("""^[+-]?0(?:\.0+)?m?$"""))
    }

    private fun isUllageHeader(value: String?): Boolean {
        val text = NameNormalizer.toHalfWidth(value.orEmpty()).lowercase()
        return text.contains("空高") ||
            text.contains("ullage") ||
            text.contains("ullagc")
    }

    private fun isCapacityHeader(value: String?): Boolean {
        val text = NameNormalizer.toHalfWidth(value.orEmpty()).lowercase()
        return text.contains("容量") ||
            text.contains("容积") ||
            text.contains("capacity")
    }

    private fun cellReference(row: Int, column: Int): String {
        var value = column
        val letters = StringBuilder()
        while (value > 0) {
            value -= 1
            letters.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return letters.reverse().toString() + row
    }

    private data class ColumnPair(
        val startRow: Int,
        val ullageColumn: Int,
        val volumeColumn: Int
    )

    private class VesselBuilder(val name: String) {
        val tanks = linkedMapOf<String, TankBuilder>()
        var nextSortOrder = 0
        private var nextFallbackTable = 0

        fun nextFallbackTankName(): String {
            val index = nextFallbackTable++
            val number = index / 2 + 1
            val side = if (index % 2 == 0) "左" else "右"
            return side + NameNormalizer.chineseNumber(number)
        }
    }

    private class TankBuilder(
        val canonicalName: String,
        val sourceName: String,
        val sortOrder: Int
    ) {
        val points = linkedMapOf<Int, ParsedPoint>()
    }
}
