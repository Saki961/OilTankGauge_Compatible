import com.oilterminal.tankcalc.calc.CrudeOilCalculationInput
import com.oilterminal.tankcalc.calc.CrudeOilCalculator
import com.oilterminal.tankcalc.calc.DensityUnit
import com.oilterminal.tankcalc.calc.TankMeasurementResult
import java.math.BigDecimal

fun main() {
    val tanks = listOf(
        TankMeasurementResult(1, "左一", 1000, BigDecimal("24"), BigDecimal("500.000")),
        TankMeasurementResult(2, "右一", 1100, BigDecimal("26"), BigDecimal("500.000"))
    )
    fun calculate(density: String, unit: DensityUnit) = CrudeOilCalculator.calculate(
        CrudeOilCalculationInput(
            tanks = tanks,
            pipelineVolumeM3 = BigDecimal("10"),
            waterVolumeM3 = BigDecimal("5"),
            obqM3 = BigDecimal("2"),
            densityInput = BigDecimal(density),
            densityUnit = unit,
            vcf = BigDecimal("0.98"),
            surveyQuantityT = BigDecimal("830")
        )
    )
    val kg = calculate("850", DensityUnit.KG_PER_M3)
    val ton = calculate("0.850", DensityUnit.TON_PER_M3)
    check(kg.netApparentMassWithSteelT.compareTo(ton.netApparentMassWithSteelT) == 0)
    check(kg.averageOilTemperatureC.compareTo(BigDecimal("25")) == 0)
    check(kg.totalGaugeVolumeM3.compareTo(BigDecimal("1010.000")) == 0)
    println("average=${CrudeOilCalculator.formatTemperature(kg.averageOilTemperatureC)}")
    println("gauge=${CrudeOilCalculator.formatVolume(kg.totalGaugeVolumeM3)}")
    println("withoutSteel=${CrudeOilCalculator.formatVolume(kg.standardVolumeWithoutSteelM3)}")
    println("withSteel=${CrudeOilCalculator.formatVolume(kg.standardVolumeWithSteelM3)}")
    println("massWithSteel=${CrudeOilCalculator.formatMass(kg.netApparentMassWithSteelT)}")
    println("density-unit-equivalence=PASS")
}
