import com.oilterminal.tankcalc.calc.CrudeOilCalculationInput
import com.oilterminal.tankcalc.calc.CrudeOilCalculator
import com.oilterminal.tankcalc.calc.DensityUnit
import com.oilterminal.tankcalc.calc.TankMeasurementResult
import com.oilterminal.tankcalc.data.VesselSummary
import com.oilterminal.tankcalc.report.CrudeOilReportTankRow
import com.oilterminal.tankcalc.report.CrudeOilTextFormatter
import java.math.BigDecimal
import java.util.Date

fun main() {
    val measurements = listOf(
        TankMeasurementResult(
            1,
            "左一",
            1000,
            BigDecimal("24"),
            BigDecimal("500.000")
        )
    )
    val result = CrudeOilCalculator.calculate(
        CrudeOilCalculationInput(
            tanks = measurements,
            pipelineVolumeM3 = BigDecimal.ZERO,
            waterVolumeM3 = BigDecimal.ZERO,
            obqM3 = BigDecimal.ZERO,
            densityInput = BigDecimal("919.5"),
            densityUnit = DensityUnit.KG_PER_M3,
            vcf = BigDecimal("0.98"),
            surveyQuantityT = BigDecimal.ZERO
        )
    )
    val text = CrudeOilTextFormatter.format(
        vessel = VesselSummary(
            1,
            "测试船",
            "测试船",
            1,
            "测试版本",
            0,
            "sample.xlsx",
            1
        ),
        result = result,
        tankRows = listOf(
            CrudeOilReportTankRow(
                "左一",
                true,
                1000,
                BigDecimal("24"),
                BigDecimal("500.000")
            )
        ),
        generatedAt = Date(0)
    )
    check("管线量：0.000 m³" in text)
    check("水体积：0.000 m³" in text)
    check("OBQ：0.000 m³" in text)
    check("商检量：0.000 t" in text)
    println(text)
}
