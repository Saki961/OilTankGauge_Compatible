import com.oilterminal.tankcalc.calc.CrudeOilCalculationInput
import com.oilterminal.tankcalc.calc.CrudeOilCalculator
import com.oilterminal.tankcalc.calc.DensityUnit
import com.oilterminal.tankcalc.calc.TankMeasurementResult
import com.oilterminal.tankcalc.data.VesselSummary
import com.oilterminal.tankcalc.report.CrudeOilReportTankRow
import com.oilterminal.tankcalc.report.CrudeOilXlsxTemplateWriter
import java.io.File
import java.math.BigDecimal
import java.util.Date

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: template.xlsx output.xlsx" }
    val measurements = listOf(
        TankMeasurementResult(1, "左一", 1000, BigDecimal("24"), BigDecimal("500.000")),
        TankMeasurementResult(3, "左二", 1100, BigDecimal("26"), BigDecimal("500.000"))
    )
    val result = CrudeOilCalculator.calculate(
        CrudeOilCalculationInput(
            tanks = measurements,
            pipelineVolumeM3 = BigDecimal("10"),
            waterVolumeM3 = BigDecimal("5"),
            obqM3 = BigDecimal("2"),
            densityInput = BigDecimal("850"),
            densityUnit = DensityUnit.KG_PER_M3,
            vcf = BigDecimal("0.98"),
            surveyQuantityT = BigDecimal("830")
        )
    )
    val reportRows = listOf(
        CrudeOilReportTankRow("左一", true, 1000, BigDecimal("24"), BigDecimal("500.000")),
        CrudeOilReportTankRow("右一", false, null, null, null),
        CrudeOilReportTankRow("左二", true, 1100, BigDecimal("26"), BigDecimal("500.000"))
    )
    val bytes = File(args[0]).inputStream().use {
        CrudeOilXlsxTemplateWriter.write(
            template = it,
            vessel = VesselSummary(1, "测试船", "测试船", 1, "测试版本", 0, "sample.xlsx", 3),
            result = result,
            tankRows = reportRows,
            generatedAt = Date(0)
        )
    }
    File(args[1]).writeBytes(bytes)
    println("xlsx-bytes=${bytes.size}")
}
