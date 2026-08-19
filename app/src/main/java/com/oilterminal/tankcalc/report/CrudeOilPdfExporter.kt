package com.oilterminal.tankcalc.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.oilterminal.tankcalc.calc.CrudeOilCalculationResult
import com.oilterminal.tankcalc.calc.CrudeOilCalculator
import com.oilterminal.tankcalc.data.VesselSummary
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrudeOilPdfExporter(
    private val context: Context
) {
    fun export(
        vessel: VesselSummary,
        result: CrudeOilCalculationResult,
        tankRows: List<CrudeOilReportTankRow>
    ): File {
        require(tankRows.size <= 12) {
            "当前原油计算报表最多支持 12 个船舱。"
        }

        val generatedAt = Date()
        val reports = File(
            context.filesDir,
            "reports"
        ).apply {
            mkdirs()
        }
        val stamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.CHINA
        ).format(generatedAt)
        val safeName = vessel.name
            .replace(
                Regex("[\\\\/:*?\"<>|]"),
                "_"
            )
            .take(40)
        val target = File(
            reports,
            "${safeName}_原油计算_$stamp.pdf"
        )
        val temporary = File(
            reports,
            ".${target.name}.tmp"
        )

        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(
                PAGE_WIDTH,
                PAGE_HEIGHT,
                1
            ).create()
            val page = document.startPage(pageInfo)
            drawReport(
                page.canvas,
                vessel,
                result,
                tankRows,
                generatedAt
            )
            document.finishPage(page)

            FileOutputStream(temporary).use {
                document.writeTo(it)
            }
        } finally {
            document.close()
        }

        if (target.exists()) {
            target.delete()
        }
        check(temporary.renameTo(target)) {
            "无法保存 PDF 报表。"
        }
        return target
    }

    private fun drawReport(
        canvas: Canvas,
        vessel: VesselSummary,
        result: CrudeOilCalculationResult,
        tankRows: List<CrudeOilReportTankRow>,
        generatedAt: Date
    ) {
        val titlePaint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.BLACK
            textSize = 20f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
        }
        val normalPaint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.BLACK
            textSize = 10f
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.NORMAL
            )
        }
        val boldPaint = Paint(normalPaint).apply {
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
        }
        val borderPaint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.DKGRAY
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
        }
        val headerFill = Paint().apply {
            color = Color.rgb(228, 235, 241)
            style = Paint.Style.FILL
        }
        val highlightFill = Paint().apply {
            color = Color.rgb(244, 247, 249)
            style = Paint.Style.FILL
        }

        canvas.drawText(
            "原油综合计算报告",
            PAGE_WIDTH / 2f,
            32f,
            titlePaint
        )
        canvas.drawText(
            "船舶：${vessel.name}    " +
                "舱容版本：${vessel.versionLabel ?: "未标记"}",
            MARGIN,
            54f,
            normalPaint
        )
        val dateText = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.CHINA
        ).format(generatedAt)
        normalPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            "生成时间：$dateText",
            PAGE_WIDTH - MARGIN,
            54f,
            normalPaint
        )
        normalPaint.textAlign = Paint.Align.LEFT

        val tableTop = 72f
        val colX = floatArrayOf(
            MARGIN,
            146f,
            250f,
            356f,
            526f
        )
        val rowHeight = 23f

        drawCell(
            canvas,
            "船舱",
            colX[0],
            tableTop,
            colX[1],
            tableTop + rowHeight,
            boldPaint,
            borderPaint,
            headerFill
        )
        drawCell(
            canvas,
            "检尺空高(mm)",
            colX[1],
            tableTop,
            colX[2],
            tableTop + rowHeight,
            boldPaint,
            borderPaint,
            headerFill
        )
        drawCell(
            canvas,
            "中部温度(℃)",
            colX[2],
            tableTop,
            colX[3],
            tableTop + rowHeight,
            boldPaint,
            borderPaint,
            headerFill
        )
        drawCell(
            canvas,
            "检尺容积(m³)",
            colX[3],
            tableTop,
            colX[4],
            tableTop + rowHeight,
            boldPaint,
            borderPaint,
            headerFill
        )

        for (index in 0 until 12) {
            val row = tankRows.getOrNull(index)
            val top =
                tableTop + rowHeight * (index + 1)
            val bottom = top + rowHeight

            val tankName = row?.tankName.orEmpty()
            val ullage = when {
                row == null -> ""
                row.included ->
                    row.ullageMm?.toString().orEmpty()
                else -> "空舱"
            }
            val temperature =
                if (row?.included == true) {
                    row.middleTemperatureC?.let(
                        CrudeOilCalculator::formatTemperature
                    ).orEmpty()
                } else {
                    ""
                }
            val volume =
                if (row?.included == true) {
                    row.gaugeVolumeM3?.let(
                        CrudeOilCalculator::formatVolume
                    ).orEmpty()
                } else {
                    ""
                }

            drawCell(
                canvas,
                tankName,
                colX[0],
                top,
                colX[1],
                bottom,
                normalPaint,
                borderPaint
            )
            drawCell(
                canvas,
                ullage,
                colX[1],
                top,
                colX[2],
                bottom,
                normalPaint,
                borderPaint
            )
            drawCell(
                canvas,
                temperature,
                colX[2],
                top,
                colX[3],
                bottom,
                normalPaint,
                borderPaint
            )
            drawCell(
                canvas,
                volume,
                colX[3],
                top,
                colX[4],
                bottom,
                normalPaint,
                borderPaint
            )
        }

        var summaryTop =
            tableTop + rowHeight * 13 + 7f
        summaryTop = drawPairRow(
            canvas,
            "船舱容积合计",
            "${CrudeOilCalculator.formatVolume(
                result.tankGaugeVolumeM3
            )} m³",
            colX[0],
            colX[4],
            summaryTop,
            rowHeight,
            normalPaint,
            boldPaint,
            borderPaint,
            highlightFill
        )
        summaryTop = drawPairRow(
            canvas,
            "管线量",
            "${CrudeOilCalculator.formatVolume(
                result.input.pipelineVolumeM3
            )} m³",
            colX[0],
            colX[4],
            summaryTop,
            rowHeight,
            normalPaint,
            boldPaint,
            borderPaint,
            null
        )
        drawPairRow(
            canvas,
            "总检尺容积",
            "${CrudeOilCalculator.formatVolume(
                result.totalGaugeVolumeM3
            )} m³",
            colX[0],
            colX[4],
            summaryTop,
            rowHeight,
            normalPaint,
            boldPaint,
            borderPaint,
            highlightFill
        )

        val rightLeft = 548f
        val rightRight = PAGE_WIDTH - MARGIN
        var y = tableTop

        drawCell(
            canvas,
            "整船输入与计算结果",
            rightLeft,
            y,
            rightRight,
            y + rowHeight,
            boldPaint,
            borderPaint,
            headerFill
        )
        y += rowHeight

        val densityInput =
            result.input.densityInput
                .stripTrailingZeros()
                .toPlainString() +
                " " +
                result.input.densityUnit.displayName

        val resultRows = listOf(
            "水体积" to
                "${CrudeOilCalculator.formatVolume(
                    result.input.waterVolumeM3
                )} m³",
            "OBQ" to
                "${CrudeOilCalculator.formatVolume(
                    result.input.obqM3
                )} m³",
            "VCF" to
                CrudeOilCalculator.formatFactor(
                    result.input.vcf
                ),
            "商检量" to
                "${CrudeOilCalculator.formatMass(
                    result.input.surveyQuantityT
                )} t",
            "密度 P20" to densityInput,
            "修正密度" to
                "${CrudeOilCalculator.formatFactor(
                    result.correctedDensityTPerM3
                )} t/m³",
            "平均油温" to
                "${CrudeOilCalculator.formatTemperature(
                    result.averageOilTemperatureC
                )} ℃",
            "钢膨系数" to
                CrudeOilCalculator.formatFactor(
                    result.steelExpansionFactor
                ),
            "无钢膨总标准体积" to
                "${CrudeOilCalculator.formatVolume(
                    result.standardVolumeWithoutSteelM3
                )} m³",
            "有钢膨总标准体积" to
                "${CrudeOilCalculator.formatVolume(
                    result.standardVolumeWithSteelM3
                )} m³",
            "无钢净表观质量" to
                "${CrudeOilCalculator.formatMass(
                    result.netApparentMassWithoutSteelT
                )} t",
            "有钢净表观质量" to
                "${CrudeOilCalculator.formatMass(
                    result.netApparentMassWithSteelT
                )} t",
            "无钢膨量差" to
                "${CrudeOilCalculator.formatMass(
                    result.differenceWithoutSteelT
                )} t",
            "有钢膨量差" to
                "${CrudeOilCalculator.formatMass(
                    result.differenceWithSteelT
                )} t"
        )

        resultRows.forEachIndexed {
                index,
                pair ->
            val fill =
                if (index >= resultRows.size - 2) {
                    highlightFill
                } else {
                    null
                }
            y = drawPairRow(
                canvas,
                pair.first,
                pair.second,
                rightLeft,
                rightRight,
                y,
                rowHeight,
                normalPaint,
                boldPaint,
                borderPaint,
                fill
            )
        }

        val notePaint = Paint(normalPaint).apply {
            textSize = 8.5f
        }
        var noteY = 468f
        canvas.drawText(
            "计算公式：",
            MARGIN,
            noteY,
            boldPaint
        )
        noteY += 15f
        canvas.drawText(
            "无钢膨标准体积 =（总检尺容积 - 水体积）" +
                "× VCF - OBQ",
            MARGIN,
            noteY,
            notePaint
        )
        noteY += 14f
        canvas.drawText(
            "钢膨系数 = 1 +（平均油温 - 20）" +
                "×（0.000012 × 3）",
            MARGIN,
            noteY,
            notePaint
        )
        noteY += 14f
        canvas.drawText(
            "净表观质量 = 标准体积 ×（P20修正密度）；" +
                "量差 = 净表观质量 - 商检量",
            MARGIN,
            noteY,
            notePaint
        )
        noteY += 16f
        canvas.drawText(
            "说明：空舱不参与平均温度；" +
                "管线量、水体积、OBQ、商检量" +
                "未填写时按 0 计算。",
            MARGIN,
            noteY,
            notePaint
        )
    }

    private fun drawPairRow(
        canvas: Canvas,
        label: String,
        value: String,
        left: Float,
        right: Float,
        top: Float,
        height: Float,
        normalPaint: Paint,
        boldPaint: Paint,
        borderPaint: Paint,
        fillPaint: Paint?
    ): Float {
        val middle =
            left + (right - left) * 0.58f
        drawCell(
            canvas,
            label,
            left,
            top,
            middle,
            top + height,
            normalPaint,
            borderPaint,
            fillPaint
        )
        drawCell(
            canvas,
            value,
            middle,
            top,
            right,
            top + height,
            boldPaint,
            borderPaint,
            fillPaint
        )
        return top + height
    }

    private fun drawCell(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        textPaint: Paint,
        borderPaint: Paint,
        fillPaint: Paint? = null
    ) {
        fillPaint?.let {
            canvas.drawRect(
                left,
                top,
                right,
                bottom,
                it
            )
        }
        canvas.drawRect(
            left,
            top,
            right,
            bottom,
            borderPaint
        )

        val availableWidth =
            (right - left - 8f).coerceAtLeast(1f)
        val value = ellipsize(
            text,
            textPaint,
            availableWidth
        )
        val baseline =
            (top + bottom) / 2f -
                (
                    textPaint.ascent() +
                        textPaint.descent()
                    ) / 2f

        canvas.save()
        canvas.clipRect(
            left + 2f,
            top + 1f,
            right - 2f,
            bottom - 1f
        )
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            value,
            left + 4f,
            baseline,
            textPaint
        )
        canvas.restore()
    }

    private fun ellipsize(
        text: String,
        paint: Paint,
        maxWidth: Float
    ): String {
        if (paint.measureText(text) <= maxWidth) {
            return text
        }

        val suffix = "…"
        var value = text
        while (
            value.isNotEmpty() &&
            paint.measureText(value + suffix) > maxWidth
        ) {
            value = value.dropLast(1)
        }
        return value + suffix
    }

    companion object {
        private const val PAGE_WIDTH = 842
        private const val PAGE_HEIGHT = 595
        private const val MARGIN = 24f
    }
}
