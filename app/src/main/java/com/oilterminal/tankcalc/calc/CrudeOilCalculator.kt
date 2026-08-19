package com.oilterminal.tankcalc.calc

import java.math.BigDecimal
import java.math.RoundingMode

enum class DensityUnit(val displayName: String) {
    KG_PER_M3("kg/m³"),
    TON_PER_M3("t/m³")
}

data class TankMeasurementResult(
    val tankId: Long,
    val tankName: String,
    val ullageMm: Int,
    val middleTemperatureC: BigDecimal,
    val gaugeVolumeM3: BigDecimal
)

data class CrudeOilCalculationInput(
    val tanks: List<TankMeasurementResult>,
    val pipelineVolumeM3: BigDecimal,
    val waterVolumeM3: BigDecimal,
    val obqM3: BigDecimal,
    val densityInput: BigDecimal,
    val densityUnit: DensityUnit,
    val vcf: BigDecimal,
    val surveyQuantityT: BigDecimal
)

data class CrudeOilCalculationResult(
    val input: CrudeOilCalculationInput,
    val tankGaugeVolumeM3: BigDecimal,
    val totalGaugeVolumeM3: BigDecimal,
    val averageOilTemperatureC: BigDecimal,
    val steelExpansionFactor: BigDecimal,
    val densityTPerM3: BigDecimal,
    val correctedDensityTPerM3: BigDecimal,
    val standardVolumeWithoutSteelM3: BigDecimal,
    val standardVolumeWithSteelM3: BigDecimal,
    val netApparentMassWithoutSteelT: BigDecimal,
    val netApparentMassWithSteelT: BigDecimal,
    val differenceWithoutSteelT: BigDecimal,
    val differenceWithSteelT: BigDecimal
)

object CrudeOilCalculator {
    private val ZERO = BigDecimal.ZERO
    private val THOUSAND = BigDecimal("1000")
    private val REFERENCE_TEMPERATURE = BigDecimal("20")
    private val STEEL_EXPANSION_COEFFICIENT = BigDecimal("0.000036")
    private val DENSITY_CORRECTION_T_PER_M3 = BigDecimal("0.0011")

    fun calculate(input: CrudeOilCalculationInput): CrudeOilCalculationResult {
        require(input.tanks.isNotEmpty()) { "至少需要一个参与计算的船舱。" }
        require(input.tanks.size <= 12) { "当前原油计算报表最多支持 12 个船舱。" }
        require(input.pipelineVolumeM3 >= ZERO) { "管线量不能小于 0。" }
        require(input.waterVolumeM3 >= ZERO) { "水体积不能小于 0。" }
        require(input.obqM3 >= ZERO) { "OBQ 不能小于 0。" }
        require(input.densityInput > ZERO) { "密度必须大于 0。" }
        require(input.vcf > ZERO) { "VCF 必须大于 0。" }
        require(input.surveyQuantityT >= ZERO) { "商检量不能小于 0。" }
        input.tanks.forEach {
            require(it.gaugeVolumeM3 >= ZERO) { "${it.tankName} 的检尺容积不能小于 0。" }
        }

        // 按原表口径：逐舱检尺容积先保留 3 位小数，再汇总。
        val tankVolume = input.tanks.fold(ZERO) { sum, tank ->
            sum.add(tank.gaugeVolumeM3.setScale(3, RoundingMode.HALF_UP))
        }
        val totalGauge = tankVolume.add(input.pipelineVolumeM3)
        require(input.waterVolumeM3 <= totalGauge) { "水体积不能大于总检尺容积。" }

        // 空舱不在 input.tanks 中，因此不会参与平均温度。
        val temperatureSum = input.tanks.fold(ZERO) { sum, tank ->
            sum.add(tank.middleTemperatureC)
        }
        val averageTemperature = temperatureSum.divide(
            BigDecimal(input.tanks.size),
            12,
            RoundingMode.HALF_UP
        )
        val steelFactor = BigDecimal.ONE.add(
            averageTemperature.subtract(REFERENCE_TEMPERATURE)
                .multiply(STEEL_EXPANSION_COEFFICIENT)
        )

        val densityTPerM3 = when (input.densityUnit) {
            DensityUnit.KG_PER_M3 -> input.densityInput.divide(THOUSAND, 12, RoundingMode.HALF_UP)
            DensityUnit.TON_PER_M3 -> input.densityInput
        }
        val correctedDensity = densityTPerM3.subtract(DENSITY_CORRECTION_T_PER_M3)
        require(correctedDensity > ZERO) { "密度修正后的结果必须大于 0。" }

        val standardWithoutSteel = totalGauge
            .subtract(input.waterVolumeM3)
            .multiply(input.vcf)
            .subtract(input.obqM3)
        require(standardWithoutSteel >= ZERO) { "无钢膨总标准体积小于 0，请检查水体积、VCF 和 OBQ。" }

        val standardWithSteel = standardWithoutSteel.multiply(steelFactor)
        val massWithoutSteel = standardWithoutSteel.multiply(correctedDensity)
        val massWithSteel = standardWithSteel.multiply(correctedDensity)

        return CrudeOilCalculationResult(
            input = input,
            tankGaugeVolumeM3 = tankVolume,
            totalGaugeVolumeM3 = totalGauge,
            averageOilTemperatureC = averageTemperature,
            steelExpansionFactor = steelFactor,
            densityTPerM3 = densityTPerM3,
            correctedDensityTPerM3 = correctedDensity,
            standardVolumeWithoutSteelM3 = standardWithoutSteel,
            standardVolumeWithSteelM3 = standardWithSteel,
            netApparentMassWithoutSteelT = massWithoutSteel,
            netApparentMassWithSteelT = massWithSteel,
            differenceWithoutSteelT = massWithoutSteel.subtract(input.surveyQuantityT),
            differenceWithSteelT = massWithSteel.subtract(input.surveyQuantityT)
        )
    }

    fun formatVolume(value: BigDecimal): String =
        value.setScale(3, RoundingMode.HALF_UP).toPlainString()

    fun formatTemperature(value: BigDecimal): String =
        value.setScale(2, RoundingMode.HALF_UP).toPlainString()

    fun formatMass(value: BigDecimal): String =
        value.setScale(3, RoundingMode.HALF_UP).toPlainString()

    fun formatFactor(value: BigDecimal): String =
        value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
