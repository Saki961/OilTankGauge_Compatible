package com.oilterminal.tankcalc.report

import android.content.Context
import com.oilterminal.tankcalc.calc.CrudeOilCalculationResult
import com.oilterminal.tankcalc.data.VesselSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrudeOilXlsxExporter(private val context: Context) {
    fun export(
        vessel: VesselSummary,
        result: CrudeOilCalculationResult,
        tankRows: List<CrudeOilReportTankRow>
    ): File {
        val bytes = context.assets.open(TEMPLATE_ASSET).use { template ->
            CrudeOilXlsxTemplateWriter.write(
                template = template,
                vessel = vessel,
                result = result,
                tankRows = tankRows,
                generatedAt = Date()
            )
        }
        val reports = File(context.filesDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val safeName = vessel.name.replace(Regex("[\\/:*?\"<>|]"), "_").take(40)
        val target = File(reports, "${safeName}_原油计算_$stamp.xlsx")
        val temporary = File(reports, ".${target.name}.tmp")
        temporary.writeBytes(bytes)
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "无法保存 XLSX 报表。" }
        return target
    }

    companion object {
        private const val TEMPLATE_ASSET = "report/原油计算模板.xlsx"
    }
}
