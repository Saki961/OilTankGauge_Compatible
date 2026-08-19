package com.oilterminal.tankcalc.report

import com.oilterminal.tankcalc.calc.CrudeOilCalculationResult
import com.oilterminal.tankcalc.calc.CrudeOilCalculator
import com.oilterminal.tankcalc.data.VesselSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrudeOilTextFormatter {
    fun format(
        vessel: VesselSummary,
        result: CrudeOilCalculationResult,
        tankRows: List<CrudeOilReportTankRow>,
        generatedAt: Date
    ): String = buildString {
        appendLine("原油综合计算结果")
        appendLine()
        appendLine("船舶：${vessel.name}")
        appendLine(
            "舱容版本：" +
                (vessel.versionLabel ?: "未标记")
        )
        appendLine(
            "生成时间：" +
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.CHINA
                ).format(generatedAt)
        )
        appendLine()
        appendLine("船舱明细：")

        tankRows.forEach { row ->
            if (row.included) {
                appendLine(
                    "${row.tankName}：" +
                        "空高 ${row.ullageMm} mm，" +
                        "中部温度 " +
                        CrudeOilCalculator.formatTemperature(
                            requireNotNull(
                                row.middleTemperatureC
                            )
                        ) +
                        " ℃，检尺容积 " +
                        CrudeOilCalculator.formatVolume(
                            requireNotNull(
                                row.gaugeVolumeM3
                            )
                        ) +
                        " m³"
                )
            } else {
                appendLine(
                    "${row.tankName}：空舱，不参与计算"
                )
            }
        }

        appendLine()
        appendLine("整船输入：")
        appendLine(
            "管线量：" +
                CrudeOilCalculator.formatVolume(
                    result.input.pipelineVolumeM3
                ) +
                " m³"
        )
        appendLine(
            "水体积：" +
                CrudeOilCalculator.formatVolume(
                    result.input.waterVolumeM3
                ) +
                " m³"
        )
        appendLine(
            "OBQ：" +
                CrudeOilCalculator.formatVolume(
                    result.input.obqM3
                ) +
                " m³"
        )
        appendLine(
            "VCF：" +
                CrudeOilCalculator.formatFactor(
                    result.input.vcf
                )
        )
        appendLine(
            "商检量：" +
                CrudeOilCalculator.formatMass(
                    result.input.surveyQuantityT
                ) +
                " t"
        )
        appendLine(
            "密度 P20：" +
                result.input.densityInput
                    .stripTrailingZeros()
                    .toPlainString() +
                " " +
                result.input.densityUnit.displayName
        )
        appendLine(
            "修正密度：" +
                CrudeOilCalculator.formatFactor(
                    result.correctedDensityTPerM3
                ) +
                " t/m³"
        )

        appendLine()
        appendLine("计算结果：")
        appendLine(
            "船舱检尺容积合计：" +
                CrudeOilCalculator.formatVolume(
                    result.tankGaugeVolumeM3
                ) +
                " m³"
        )
        appendLine(
            "总检尺容积：" +
                CrudeOilCalculator.formatVolume(
                    result.totalGaugeVolumeM3
                ) +
                " m³"
        )
        appendLine(
            "油温：" +
                CrudeOilCalculator.formatTemperature(
                    result.averageOilTemperatureC
                ) +
                " ℃"
        )
        appendLine(
            "钢膨系数：" +
                CrudeOilCalculator.formatFactor(
                    result.steelExpansionFactor
                )
        )
        appendLine(
            "无钢膨总标准体积：" +
                CrudeOilCalculator.formatVolume(
                    result.standardVolumeWithoutSteelM3
                ) +
                " m³"
        )
        appendLine(
            "有钢膨总标准体积：" +
                CrudeOilCalculator.formatVolume(
                    result.standardVolumeWithSteelM3
                ) +
                " m³"
        )
        appendLine(
            "无钢净表观质量：" +
                CrudeOilCalculator.formatMass(
                    result.netApparentMassWithoutSteelT
                ) +
                " t"
        )
        appendLine(
            "有钢净表观质量：" +
                CrudeOilCalculator.formatMass(
                    result.netApparentMassWithSteelT
                ) +
                " t"
        )
        appendLine(
            "有钢膨量差：" +
                CrudeOilCalculator.formatMass(
                    result.differenceWithSteelT
                ) +
                " t"
        )
        appendLine(
            "无钢膨量差：" +
                CrudeOilCalculator.formatMass(
                    result.differenceWithoutSteelT
                ) +
                " t"
        )
    }.trimEnd()
}
