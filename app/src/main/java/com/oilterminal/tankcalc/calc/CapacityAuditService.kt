package com.oilterminal.tankcalc.calc

import com.oilterminal.tankcalc.data.CapacityPoint
import java.math.BigDecimal
import java.math.RoundingMode

data class CapacityAuditIssue(
    val first: CapacityPoint,
    val second: CapacityPoint,
    val ullageStepMm: Int,
    val volumeDeltaM3: BigDecimal,
    val reason: String
)

data class CapacityAuditResult(
    val pointCount: Int,
    val medianUllageStepMm: Int?,
    val medianVolumeDeltaM3: BigDecimal?,
    val issues: List<CapacityAuditIssue>
)

object CapacityAuditService {
    fun audit(points: List<CapacityPoint>): CapacityAuditResult {
        if (points.size < 2) {
            return CapacityAuditResult(points.size, null, null, emptyList())
        }

        val ordered = points.sortedBy { it.ullageMm }
        val pairs = ordered.zipWithNext().map { (a, b) ->
            val step = b.ullageMm - a.ullageMm
            val delta = b.volumeM3.toBigDecimal().subtract(a.volumeM3.toBigDecimal())
            Triple(a, b, step to delta)
        }
        val positiveSteps = pairs.map { it.third.first }.filter { it > 0 }.sorted()
        val volumeAbs = pairs.map { it.third.second.abs() }
            .filter { it > BigDecimal.ZERO }
            .sorted()
        val medianStep = medianInt(positiveSteps)
        val medianVolume = medianDecimal(volumeAbs)
        val issues = mutableListOf<CapacityAuditIssue>()

        for ((a, b, values) in pairs) {
            val (step, delta) = values
            val reasons = mutableListOf<String>()
            if (step <= 0) reasons += "空高未递增或存在重复空高"

            // 舱容表通常随空高增加而容积减少；明显反向时优先提示。
            if (delta > BigDecimal("0.0005")) {
                reasons += "空高增加但容积反而增加"
            }

            if (medianStep != null && step > 0) {
                val low = maxOf(1, (medianStep * 0.45).toInt())
                val high = maxOf(medianStep + 1, (medianStep * 2.5).toInt())
                if (step < low || step > high) {
                    reasons += "空高间隔 $step mm 偏离本舱常见间隔 ${medianStep} mm"
                }
            }

            if (medianVolume != null && medianVolume > BigDecimal.ZERO) {
                val absDelta = delta.abs()
                val upper = medianVolume.multiply(BigDecimal("3.5"))
                val lower = medianVolume.multiply(BigDecimal("0.08"))
                if (absDelta > upper) {
                    reasons += "相邻容积变化 ${fmt(absDelta)} m³ 明显偏大"
                } else if (absDelta < lower && absDelta > BigDecimal.ZERO) {
                    reasons += "相邻容积变化 ${fmt(absDelta)} m³ 明显偏小"
                }
            }

            if (a.confidence < 0.70 || b.confidence < 0.70) {
                reasons += "来源数据识别可信度偏低"
            }

            if (reasons.isNotEmpty()) {
                issues += CapacityAuditIssue(a, b, step, delta, reasons.joinToString("；"))
            }
        }

        return CapacityAuditResult(
            pointCount = ordered.size,
            medianUllageStepMm = medianStep,
            medianVolumeDeltaM3 = medianVolume,
            issues = issues
        )
    }

    private fun medianInt(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid]
        else ((values[mid - 1] + values[mid]) / 2.0).roundToIntSafe()
    }

    private fun medianDecimal(values: List<BigDecimal>): BigDecimal? {
        if (values.isEmpty()) return null
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid]
        else values[mid - 1].add(values[mid]).divide(BigDecimal("2"), 6, RoundingMode.HALF_UP)
    }

    private fun Double.roundToIntSafe(): Int = kotlin.math.round(this).toInt()

    private fun fmt(value: BigDecimal): String =
        value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
