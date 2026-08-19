package com.oilterminal.tankcalc.calc

import com.oilterminal.tankcalc.data.CapacityPoint
import com.oilterminal.tankcalc.data.PointBracket
import java.math.BigDecimal
import java.math.RoundingMode

sealed class CalculationOutcome {
    data class Success(
        val inputUllageMm: Int,
        val exactMatch: Boolean,
        val first: CapacityPoint,
        val second: CapacityPoint?,
        val ratio: BigDecimal,
        val rawVolumeM3: BigDecimal,
        val roundedVolumeM3: BigDecimal
    ) : CalculationOutcome()

    data class Error(val message: String) : CalculationOutcome()
}

object CapacityCalculator {
    fun calculate(inputUllageMm: Int, bracket: PointBracket): CalculationOutcome {
        bracket.exact?.let { exact ->
            val volume = exact.volumeM3.toBigDecimal()
            return CalculationOutcome.Success(
                inputUllageMm = inputUllageMm,
                exactMatch = true,
                first = exact,
                second = null,
                ratio = BigDecimal.ZERO,
                rawVolumeM3 = volume,
                roundedVolumeM3 = volume.setScale(3, RoundingMode.HALF_UP)
            )
        }

        val lower = bracket.lower
        val upper = bracket.upper
        if (lower == null || upper == null) {
            val range = if (bracket.minUllageMm != null && bracket.maxUllageMm != null) {
                "有效范围为 ${bracket.minUllageMm}～${bracket.maxUllageMm} mm。"
            } else {
                "该船舱没有足够的舱容数据。"
            }
            return CalculationOutcome.Error("输入空高超出舱容表范围，无法外推。$range")
        }

        val h = BigDecimal(inputUllageMm)
        val h1 = BigDecimal(lower.ullageMm)
        val h2 = BigDecimal(upper.ullageMm)
        if (h2.compareTo(h1) == 0) {
            return CalculationOutcome.Error("舱容表存在重复空高，无法进行插值。")
        }

        val v1 = lower.volumeM3.toBigDecimal()
        val v2 = upper.volumeM3.toBigDecimal()
        val ratio = h.subtract(h1).divide(h2.subtract(h1), 12, RoundingMode.HALF_UP)
        val raw = v1.add(ratio.multiply(v2.subtract(v1)))
        return CalculationOutcome.Success(
            inputUllageMm = inputUllageMm,
            exactMatch = false,
            first = lower,
            second = upper,
            ratio = ratio,
            rawVolumeM3 = raw,
            roundedVolumeM3 = raw.setScale(3, RoundingMode.HALF_UP)
        )
    }
}
