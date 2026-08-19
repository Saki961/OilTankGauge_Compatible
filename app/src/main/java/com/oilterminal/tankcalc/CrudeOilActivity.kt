package com.oilterminal.tankcalc

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.oilterminal.tankcalc.calc.CalculationOutcome
import com.oilterminal.tankcalc.calc.CapacityCalculator
import com.oilterminal.tankcalc.calc.CrudeOilCalculationInput
import com.oilterminal.tankcalc.calc.CrudeOilCalculationResult
import com.oilterminal.tankcalc.calc.CrudeOilCalculator
import com.oilterminal.tankcalc.calc.DensityUnit
import com.oilterminal.tankcalc.calc.TankMeasurementResult
import com.oilterminal.tankcalc.data.OilRepository
import com.oilterminal.tankcalc.data.TankSummary
import com.oilterminal.tankcalc.data.VesselSummary
import com.oilterminal.tankcalc.provider.OriginalFileProvider
import com.oilterminal.tankcalc.report.CrudeOilPdfExporter
import com.oilterminal.tankcalc.report.CrudeOilReportTankRow
import com.oilterminal.tankcalc.report.CrudeOilTextFormatter
import com.oilterminal.tankcalc.report.CrudeOilXlsxExporter
import com.oilterminal.tankcalc.ui.UiFactory
import com.oilterminal.tankcalc.util.NameNormalizer
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import java.util.concurrent.Executors

class CrudeOilActivity : Activity() {
    private lateinit var repository: OilRepository
    private val executor = Executors.newSingleThreadExecutor()
    private var backAction: (() -> Unit)? = null
    private var currentDraft: CrudeOilDraft? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = OilRepository(this)
        showInput()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        backAction?.invoke() ?: super.onBackPressed()
    }

    private data class TankRow(
        val tank: TankSummary,
        val included: CheckBox,
        val ullage: EditText,
        val sounding: EditText,
        val immersion: EditText,
        val optionalToggle: CheckBox,
        val optionalContainer: LinearLayout,
        val autoUllageNote: TextView,
        val temperature: EditText,
        val result: TextView
    )

    private data class TankInputDraft(
        val tankId: Long,
        val included: Boolean,
        val ullage: String,
        val sounding: String,
        val immersion: String,
        val temperature: String
    )

    private data class CrudeOilDraft(
        val vesselId: Long,
        val tanks: List<TankInputDraft>,
        val pipeline: String,
        val water: String,
        val obq: String,
        val vcf: String,
        val survey: String,
        val density: String,
        val densityUnit: DensityUnit
    )

    private fun showInput(
        preselectedVesselId: Long? = null,
        draft: CrudeOilDraft? = currentDraft
    ) {
        var selectedVessel: VesselSummary? = null
        var tankRows: List<TankRow> = emptyList()

        val content = UiFactory.vertical(this)
        content.addView(UiFactory.title(this, "原油综合计算"))
        content.addView(
            UiFactory.muted(
                this,
                "最多 12 个船舱；温度可留空按 0。空高可人工填写，也可展开“下尺 / 浸油”自动计算。"
            )
        )

        content.addView(UiFactory.section(this, "1. 选择船舶"))
        val vesselInput = UiFactory.editText(this, "输入船号")
        val selectedLabel = UiFactory.muted(this, "尚未选择船舶")
        val tankContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun renderTanks(vessel: VesselSummary) {
            val tanks = repository.tanksForVessel(vessel.id)
            if (tanks.size > 12) {
                selectedVessel = null
                tankRows = emptyList()
                tankContainer.removeAllViews()
                selectedLabel.text =
                    "${vessel.name} 有 ${tanks.size} 个船舱，当前报表最多支持 12 个。"
                AlertDialog.Builder(this)
                    .setTitle("船舱数量超限")
                    .setMessage("当前原油计算报表最多支持 12 个船舱，不会截断数据。")
                    .setPositiveButton("知道了", null)
                    .show()
                return
            }

            selectedVessel = vessel
            vesselInput.setText(vessel.name)
            selectedLabel.text =
                "已选择：${vessel.name} · ${tanks.size} 个船舱\n" +
                    "版本：${vessel.versionLabel ?: "未标记"}"
            tankContainer.removeAllViews()

            tankRows = tanks.map { tank ->
                val card = UiFactory.card(this)
                val included = CheckBox(this).apply {
                    text = tank.canonicalName
                    isChecked = true
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                }
                val range = if (
                    tank.minUllageMm != null &&
                    tank.maxUllageMm != null
                ) {
                    "有效空高：${tank.minUllageMm}～${tank.maxUllageMm} mm"
                } else {
                    "没有可用空高范围"
                }
                val rangeLabel = UiFactory.muted(this, range)
                val ullage =
                    UiFactory.editText(this, "空高（mm，可人工填写）", numeric = true)
                val optionalToggle = CheckBox(this).apply {
                    text = "下尺 / 浸油自动计算空高（可选）"
                    isChecked = false
                }
                val sounding =
                    UiFactory.editText(this, "下尺（mm）", numeric = true)
                val immersion =
                    UiFactory.editText(this, "浸油（mm）", numeric = true)
                val autoUllageNote = UiFactory.muted(this, "下尺和浸油都填写后：空高 = 下尺 - 浸油")
                val optionalContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    addView(sounding)
                    addView(immersion)
                    addView(autoUllageNote)
                }
                val temperature =
                    UiFactory.editText(this, "中部温度（℃，可不填，默认 0）", numeric = true)
                val result = UiFactory.muted(this, "检尺容积：等待计算")

                fun updateAutoUllage() {
                    val soundingValue = optionalDecimal(sounding)
                    val immersionValue = optionalDecimal(immersion)
                    if (soundingValue != null && immersionValue != null) {
                        val auto = soundingValue.subtract(immersionValue)
                        if (auto >= BigDecimal.ZERO) {
                            val mm = auto.setScale(0, RoundingMode.HALF_UP).toPlainString()
                            if (ullage.text.toString() != mm) ullage.setText(mm)
                            autoUllageNote.text = "已自动计算空高：$mm mm（下尺 - 浸油）"
                        } else {
                            autoUllageNote.text = "下尺必须大于或等于浸油，请检查。"
                        }
                    } else {
                        autoUllageNote.text = "下尺和浸油都填写后：空高 = 下尺 - 浸油"
                    }
                }
                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateAutoUllage()
                    override fun afterTextChanged(s: Editable?) = Unit
                }
                sounding.addTextChangedListener(watcher)
                immersion.addTextChangedListener(watcher)

                optionalToggle.setOnCheckedChangeListener { _, checked ->
                    optionalContainer.visibility = if (checked) View.VISIBLE else View.GONE
                }

                val saved = draft?.takeIf { it.vesselId == vessel.id }
                    ?.tanks?.firstOrNull { it.tankId == tank.id }
                if (saved != null) {
                    included.isChecked = saved.included
                    ullage.setText(saved.ullage)
                    sounding.setText(saved.sounding)
                    immersion.setText(saved.immersion)
                    temperature.setText(saved.temperature)
                    optionalToggle.isChecked = saved.sounding.isNotBlank() || saved.immersion.isNotBlank()
                    updateAutoUllage()
                }

                included.setOnCheckedChangeListener { _, checked ->
                    ullage.isEnabled = checked
                    optionalToggle.isEnabled = checked
                    sounding.isEnabled = checked
                    immersion.isEnabled = checked
                    temperature.isEnabled = checked
                    result.text =
                        if (checked) "检尺容积：等待计算"
                        else "空舱：不参与计算"
                }
                included.isChecked = saved?.included ?: true
                ullage.isEnabled = included.isChecked
                optionalToggle.isEnabled = included.isChecked
                sounding.isEnabled = included.isChecked
                immersion.isEnabled = included.isChecked
                temperature.isEnabled = included.isChecked
                result.text = if (included.isChecked) {
                    "检尺容积：等待计算"
                } else {
                    "空舱：不参与计算"
                }

                card.addView(included)
                card.addView(rangeLabel)
                card.addView(ullage)
                card.addView(optionalToggle)
                card.addView(optionalContainer)
                card.addView(temperature)
                card.addView(result)
                tankContainer.addView(card)
                TankRow(
                    tank, included, ullage, sounding, immersion, optionalToggle,
                    optionalContainer, autoUllageNote, temperature, result
                )
            }
        }

        content.addView(vesselInput)
        content.addView(
            UiFactory.button(this, "匹配本地船舶").apply {
                setOnClickListener {
                    val query = vesselInput.text.toString().trim()
                    val matches = repository.searchVessels(query)
                    when {
                        matches.isEmpty() ->
                            toast("本地没有匹配的船舶，请先导入舱容表。")

                        matches.size == 1 ->
                            renderTanks(matches.first())

                        else -> {
                            val exact = matches.firstOrNull {
                                it.normalizedName ==
                                    NameNormalizer.vessel(query)
                            }
                            if (exact != null) {
                                renderTanks(exact)
                            } else {
                                AlertDialog.Builder(
                                    this@CrudeOilActivity
                                )
                                    .setTitle("选择船舶")
                                    .setItems(
                                        matches
                                            .map { it.name }
                                            .toTypedArray()
                                    ) { _, index ->
                                        renderTanks(matches[index])
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                        }
                    }
                }
            }
        )
        content.addView(selectedLabel)
        content.addView(
            UiFactory.section(this, "2. 船舱检尺与中部温度")
        )
        content.addView(tankContainer)

        content.addView(UiFactory.section(this, "3. 整船输入"))
        val pipeline = labeledInput(
            content,
            "管线量（m³，可不填）",
            "不填时按 0 计算"
        )
        val water = labeledInput(
            content,
            "水体积（m³，可不填）",
            "不填时按 0 计算"
        )
        val obq = labeledInput(
            content,
            "OBQ（m³，可不填）",
            "不填时按 0 计算"
        )
        val vcf = labeledInput(
            content,
            "体积修正系数 VCF",
            "例如：0.9826"
        )
        val survey = labeledInput(
            content,
            "商检量（t，可不填）",
            "不填时按 0 计算"
        )
        val density = labeledInput(
            content,
            "密度 P20",
            "例如：850 或 0.850"
        )

        val densityGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.START
        }
        val kgButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = "kg/m³"
            isChecked = true
        }
        val tonButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = "t/m³"
        }
        densityGroup.addView(kgButton)
        densityGroup.addView(tonButton)
        content.addView(densityGroup)
        content.addView(
            UiFactory.muted(
                this,
                "kg/m³ 输入时修正为 P20-1.1；" +
                    "t/m³ 输入时修正为 P20-0.0011。" +
                    "内部统一换算为 t/m³。"
            )
        )

        draft?.let { saved ->
            pipeline.setText(saved.pipeline)
            water.setText(saved.water)
            obq.setText(saved.obq)
            vcf.setText(saved.vcf)
            survey.setText(saved.survey)
            density.setText(saved.density)
            if (saved.densityUnit == DensityUnit.KG_PER_M3) {
                kgButton.isChecked = true
            } else {
                tonButton.isChecked = true
            }
        }

        content.addView(
            UiFactory.button(this, "计算并查看结果").apply {
                setOnClickListener {
                    val vessel = selectedVessel
                    if (vessel == null) {
                        toast("请先选择船舶。")
                        return@setOnClickListener
                    }

                    currentDraft = CrudeOilDraft(
                        vesselId = vessel.id,
                        tanks = tankRows.map { row ->
                            TankInputDraft(
                                tankId = row.tank.id,
                                included = row.included.isChecked,
                                ullage = row.ullage.text.toString(),
                                sounding = row.sounding.text.toString(),
                                immersion = row.immersion.text.toString(),
                                temperature = row.temperature.text.toString()
                            )
                        },
                        pipeline = pipeline.text.toString(),
                        water = water.text.toString(),
                        obq = obq.text.toString(),
                        vcf = vcf.text.toString(),
                        survey = survey.text.toString(),
                        density = density.text.toString(),
                        densityUnit = if (kgButton.isChecked) DensityUnit.KG_PER_M3 else DensityUnit.TON_PER_M3
                    )

                    val measurements =
                        mutableListOf<TankMeasurementResult>()
                    val reportRows =
                        mutableListOf<CrudeOilReportTankRow>()

                    for (row in tankRows) {
                        if (!row.included.isChecked) {
                            reportRows += CrudeOilReportTankRow(
                                tankName = row.tank.canonicalName,
                                included = false,
                                ullageMm = null,
                                middleTemperatureC = null,
                                gaugeVolumeM3 = null
                            )
                            continue
                        }

                        val ullageMm = resolveUllageMm(row)
                            ?: return@setOnClickListener

                        val temperatureValue = decimalOrZero(
                            row.temperature,
                            "${row.tank.canonicalName} 的中部温度"
                        ) ?: return@setOnClickListener

                        val outcome = CapacityCalculator.calculate(
                            ullageMm,
                            repository.findBracket(
                                row.tank.id,
                                ullageMm
                            )
                        )
                        when (outcome) {
                            is CalculationOutcome.Error -> {
                                AlertDialog.Builder(
                                    this@CrudeOilActivity
                                )
                                    .setTitle(
                                        "${row.tank.canonicalName} " +
                                            "无法计算"
                                    )
                                    .setMessage(outcome.message)
                                    .setPositiveButton(
                                        "知道了",
                                        null
                                    )
                                    .show()
                                return@setOnClickListener
                            }

                            is CalculationOutcome.Success -> {
                                val takeSource = if (outcome.exactMatch) {
                                    "精确取数 ${outcome.first.sourceSheet}!${outcome.first.volumeCell}"
                                } else {
                                    val second = requireNotNull(outcome.second)
                                    "插值 ${outcome.first.ullageMm}/${second.ullageMm} mm"
                                }
                                row.result.text =
                                    "检尺容积：" +
                                        outcome.roundedVolumeM3.toPlainString() +
                                        " m³\n取数：$takeSource"

                                measurements += TankMeasurementResult(
                                    tankId = row.tank.id,
                                    tankName =
                                        row.tank.canonicalName,
                                    ullageMm = ullageMm,
                                    middleTemperatureC =
                                        temperatureValue,
                                    gaugeVolumeM3 =
                                        outcome.roundedVolumeM3
                                )

                                reportRows += CrudeOilReportTankRow(
                                    tankName =
                                        row.tank.canonicalName,
                                    included = true,
                                    ullageMm = ullageMm,
                                    middleTemperatureC =
                                        temperatureValue,
                                    gaugeVolumeM3 =
                                        outcome.roundedVolumeM3
                                )
                            }
                        }
                    }

                    if (measurements.isEmpty()) {
                        toast("至少勾选一个非空船舱。")
                        return@setOnClickListener
                    }

                    val densityUnit =
                        if (kgButton.isChecked) {
                            DensityUnit.KG_PER_M3
                        } else {
                            DensityUnit.TON_PER_M3
                        }

                    val input = CrudeOilCalculationInput(
                        tanks = measurements,
                        pipelineVolumeM3 = decimalOrZero(
                            pipeline,
                            "管线量"
                        ) ?: return@setOnClickListener,
                        waterVolumeM3 = decimalOrZero(
                            water,
                            "水体积"
                        ) ?: return@setOnClickListener,
                        obqM3 = decimalOrZero(
                            obq,
                            "OBQ"
                        ) ?: return@setOnClickListener,
                        densityInput = decimal(
                            density,
                            "密度"
                        ) ?: return@setOnClickListener,
                        densityUnit = densityUnit,
                        vcf = decimal(
                            vcf,
                            "VCF"
                        ) ?: return@setOnClickListener,
                        surveyQuantityT = decimalOrZero(
                            survey,
                            "商检量"
                        ) ?: return@setOnClickListener
                    )

                    val result = try {
                        CrudeOilCalculator.calculate(input)
                    } catch (error: IllegalArgumentException) {
                        toast(
                            error.message ?: "输入数据不正确。"
                        )
                        return@setOnClickListener
                    }

                    showResult(vessel, result, reportRows)
                }
            }
        )

        setPage(
            "原油综合计算",
            content,
            showBack = true
        ) {
            finish()
        }

        preselectedVesselId?.let {
            repository.vesselById(it)?.let(::renderTanks)
        }
    }

    private fun showResult(
        vessel: VesselSummary,
        result: CrudeOilCalculationResult,
        reportRows: List<CrudeOilReportTankRow>
    ) {
        val content = UiFactory.vertical(this)
        val outputCard = UiFactory.card(this)

        outputCard.addView(
            UiFactory.title(this, "输出计算结果", 21f)
        )
        outputCard.addView(
            UiFactory.muted(
                this,
                "三种输出互相独立；某一种生成失败时，" +
                    "另外两种仍可正常使用。"
            )
        )

        outputCard.addView(
            UiFactory.button(
                this,
                "生成并打开 PDF 报表"
            ).apply {
                setOnClickListener {
                    runInBackground(
                        title = "正在生成 PDF 报表",
                        task = {
                            CrudeOilPdfExporter(
                                this@CrudeOilActivity
                            ).export(
                                vessel,
                                result,
                                reportRows
                            )
                        },
                        onSuccess = { file ->
                            openReport(file)
                        },
                        onError = { error ->
                            showExportError("PDF", error)
                        }
                    )
                }
            }
        )

        outputCard.addView(
            UiFactory.button(
                this,
                "生成并打开 Excel 表格"
            ).apply {
                setOnClickListener {
                    runInBackground(
                        title = "正在生成 Excel 报表",
                        task = {
                            CrudeOilXlsxExporter(
                                this@CrudeOilActivity
                            ).export(
                                vessel,
                                result,
                                reportRows
                            )
                        },
                        onSuccess = { file ->
                            openReport(file)
                        },
                        onError = { error ->
                            showExportError("Excel", error)
                        }
                    )
                }
            }
        )

        outputCard.addView(
            UiFactory.button(
                this,
                "查看、复制或分享纯文本结果",
                primary = false
            ).apply {
                setOnClickListener {
                    showTextResult(
                        vessel,
                        result,
                        reportRows
                    )
                }
            }
        )
        content.addView(outputCard)

        content.addView(UiFactory.section(this, "计算结果"))
        val tankDetails =
            result.input.tanks.joinToString("\n") {
                "${it.tankName}：" +
                    CrudeOilCalculator.formatVolume(
                        it.gaugeVolumeM3
                    ) +
                    " m³"
            }

        resultCard(
            content,
            "检尺容积",
            "${CrudeOilCalculator.formatVolume(
                result.totalGaugeVolumeM3
            )} m³",
            "$tankDetails\n" +
                "船舱合计：" +
                CrudeOilCalculator.formatVolume(
                    result.tankGaugeVolumeM3
                ) +
                " m³\n" +
                "管线量：" +
                CrudeOilCalculator.formatVolume(
                    result.input.pipelineVolumeM3
                ) +
                " m³"
        )
        resultCard(
            content,
            "油温",
            "${CrudeOilCalculator.formatTemperature(
                result.averageOilTemperatureC
            )} ℃"
        )
        resultCard(
            content,
            "无钢膨总标准体积",
            "${CrudeOilCalculator.formatVolume(
                result.standardVolumeWithoutSteelM3
            )} m³"
        )
        resultCard(
            content,
            "有钢膨总标准体积",
            "${CrudeOilCalculator.formatVolume(
                result.standardVolumeWithSteelM3
            )} m³",
            "钢膨系数：" +
                CrudeOilCalculator.formatFactor(
                    result.steelExpansionFactor
                )
        )
        resultCard(
            content,
            "无钢净表观质量",
            "${CrudeOilCalculator.formatMass(
                result.netApparentMassWithoutSteelT
            )} t"
        )
        resultCard(
            content,
            "有钢净表观质量",
            "${CrudeOilCalculator.formatMass(
                result.netApparentMassWithSteelT
            )} t",
            "修正密度：" +
                CrudeOilCalculator.formatFactor(
                    result.correctedDensityTPerM3
                ) +
                " t/m³"
        )
        resultCard(
            content,
            "有钢膨量差",
            "${CrudeOilCalculator.formatMass(
                result.differenceWithSteelT
            )} t"
        )
        resultCard(
            content,
            "无钢膨量差",
            "${CrudeOilCalculator.formatMass(
                result.differenceWithoutSteelT
            )} t"
        )

        content.addView(
            UiFactory.button(
                this,
                "返回修改（保留本次填写）",
                primary = false
            ).apply {
                setOnClickListener {
                    showInput(vessel.id, currentDraft)
                }
            }
        )
        content.addView(
            UiFactory.button(
                this,
                "新建计算（清空填写）",
                primary = false
            ).apply {
                setOnClickListener {
                    AlertDialog.Builder(this@CrudeOilActivity)
                        .setTitle("新建计算")
                        .setMessage("将清空本次各舱和整船输入，保留当前船舶选择。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清空并新建") { _, _ ->
                            currentDraft = null
                            showInput(vessel.id, null)
                        }
                        .show()
                }
            }
        )

        setPage(
            "原油计算结果",
            content,
            showBack = true
        ) {
            showInput(vessel.id, currentDraft)
        }
    }

    private fun showTextResult(
        vessel: VesselSummary,
        result: CrudeOilCalculationResult,
        reportRows: List<CrudeOilReportTankRow>
    ) {
        val text = CrudeOilTextFormatter.format(
            vessel,
            result,
            reportRows,
            Date()
        )

        AlertDialog.Builder(this)
            .setTitle("纯文本计算结果")
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "原油综合计算结果",
                        text
                    )
                )
                toast("计算结果已复制。")
            }
            .setNeutralButton("分享") { _, _ ->
                shareText(text)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun resultCard(
        container: LinearLayout,
        label: String,
        value: String,
        details: String? = null
    ) {
        container.addView(
            UiFactory.card(this).apply {
                addView(
                    UiFactory.muted(
                        this@CrudeOilActivity,
                        label
                    )
                )
                addView(
                    UiFactory.title(
                        this@CrudeOilActivity,
                        value,
                        25f
                    ).apply {
                        setTextColor(UiFactory.BLUE)
                    }
                )
                if (!details.isNullOrBlank()) {
                    addView(
                        UiFactory.body(
                            this@CrudeOilActivity,
                            details,
                            14f
                        )
                    )
                }
            }
        )
    }

    private fun labeledInput(
        container: LinearLayout,
        label: String,
        hint: String
    ): EditText {
        container.addView(
            UiFactory.body(this, label).apply {
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        val input = UiFactory.editText(
            this,
            hint,
            numeric = true
        )
        container.addView(input)
        return input
    }

    private fun optionalDecimal(input: EditText): BigDecimal? {
        val raw = input.text.toString().trim().replace("，", ".")
        if (raw.isBlank()) return null
        return raw.toBigDecimalOrNull()
    }

    private fun resolveUllageMm(row: TankRow): Int? {
        val soundingRaw = row.sounding.text.toString().trim()
        val immersionRaw = row.immersion.text.toString().trim()
        val hasSounding = soundingRaw.isNotBlank()
        val hasImmersion = immersionRaw.isNotBlank()

        val value = if (hasSounding || hasImmersion) {
            if (!hasSounding || !hasImmersion) {
                toast("${row.tank.canonicalName} 的下尺和浸油需同时填写；或两项都留空后人工填写空高。")
                return null
            }
            val sounding = soundingRaw.replace("，", ".").toBigDecimalOrNull()
            val immersion = immersionRaw.replace("，", ".").toBigDecimalOrNull()
            if (sounding == null || immersion == null) {
                toast("${row.tank.canonicalName} 的下尺/浸油格式不正确。")
                return null
            }
            val auto = sounding.subtract(immersion)
            if (auto < BigDecimal.ZERO) {
                toast("${row.tank.canonicalName} 的下尺不能小于浸油。")
                return null
            }
            row.ullage.setText(auto.setScale(0, RoundingMode.HALF_UP).toPlainString())
            auto
        } else {
            decimal(row.ullage, "${row.tank.canonicalName} 的检尺空高") ?: return null
        }

        if (value < BigDecimal.ZERO || value > BigDecimal(Int.MAX_VALUE)) {
            toast("${row.tank.canonicalName} 的检尺空高超出允许范围。")
            return null
        }
        return value.setScale(0, RoundingMode.HALF_UP).toInt()
    }

    private fun decimal(
        input: EditText,
        fieldName: String
    ): BigDecimal? {
        val raw = input.text
            .toString()
            .trim()
            .replace("，", ".")
        val value = raw.toBigDecimalOrNull()
        if (value == null) {
            toast("请输入有效的$fieldName。")
        }
        return value
    }

    private fun decimalOrZero(
        input: EditText,
        fieldName: String
    ): BigDecimal? {
        val raw = input.text
            .toString()
            .trim()
            .replace("，", ".")
        if (raw.isBlank()) {
            return BigDecimal.ZERO
        }

        val value = raw.toBigDecimalOrNull()
        if (value == null) {
            toast(
                "请输入有效的$fieldName，或留空按 0 计算。"
            )
        }
        return value
    }

    private fun openReport(file: File) {
        val uri = OriginalFileProvider.uriFor(this, file)
        val mimeType =
            OriginalFileProvider.mimeTypeForName(file.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            shareReport(file, mimeType)
        }
    }

    private fun shareReport(
        file: File,
        mimeType: String
    ) {
        val uri = OriginalFileProvider.uriFor(this, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(
                Intent.createChooser(
                    intent,
                    "分享计算报表"
                )
            )
        } catch (_: ActivityNotFoundException) {
            toast("手机上没有可打开或分享该报表的应用。")
        }
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_SUBJECT,
                "原油综合计算结果"
            )
            putExtra(Intent.EXTRA_TEXT, text)
        }

        try {
            startActivity(
                Intent.createChooser(
                    intent,
                    "分享纯文本计算结果"
                )
            )
        } catch (_: ActivityNotFoundException) {
            toast("手机上没有可分享文本的应用。")
        }
    }

    private fun setPage(
        title: String,
        content: View,
        showBack: Boolean,
        onBack: () -> Unit
    ) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiFactory.BACKGROUND)
        }

        root.addView(
            UiFactory.topBar(
                this,
                title,
                showBack,
                onBack
            )
        )
        root.addView(
            ScrollView(this).apply {
                addView(content)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        backAction = if (showBack) onBack else null
        setContentView(root)
    }

    private fun <T> runInBackground(
        title: String,
        task: () -> T,
        onSuccess: (T) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ProgressBar(this))
            .setCancelable(false)
            .create()
        dialog.show()

        executor.execute {
            runCatching(task)
                .onSuccess { result ->
                    runOnUiThread {
                        if (!isFinishing) {
                            dialog.dismiss()
                            onSuccess(result)
                        }
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        if (!isFinishing) {
                            dialog.dismiss()
                            onError(error)
                        }
                    }
                }
        }
    }

    private fun showExportError(
        format: String,
        error: Throwable
    ) {
        val allMessages = generateSequence(error) {
            it.cause
        }
            .mapNotNull { it.message }
            .joinToString(" ")

        val message = when {
            allMessages.contains(
                "disallow-doctype-decl",
                ignoreCase = true
            ) ->
                "当前设备的 XML 解析器不兼容。" +
                    "新版已经加入兼容处理，请确认安装的是最新版本。"

            else ->
                "$format 报表生成失败。" +
                    "计算结果没有丢失，" +
                    "可继续使用另外两种输出方式。\n\n" +
                    "技术信息：${error.javaClass.simpleName}"
        }

        AlertDialog.Builder(this)
            .setTitle("无法生成 $format 报表")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }
}
