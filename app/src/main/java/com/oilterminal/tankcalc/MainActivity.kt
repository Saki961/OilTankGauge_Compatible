package com.oilterminal.tankcalc

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import com.oilterminal.tankcalc.calc.CalculationOutcome
import com.oilterminal.tankcalc.calc.CapacityCalculator
import com.oilterminal.tankcalc.calc.CapacityAuditService
import com.oilterminal.tankcalc.data.ImportOutcome
import com.oilterminal.tankcalc.data.OilRepository
import com.oilterminal.tankcalc.data.TankSummary
import com.oilterminal.tankcalc.data.VesselSummary
import com.oilterminal.tankcalc.excel.ParsedWorkbook
import com.oilterminal.tankcalc.excel.TankWorkbookParser
import com.oilterminal.tankcalc.excel.XlsxReader
import com.oilterminal.tankcalc.excel.XlsxSheet
import com.oilterminal.tankcalc.excel.XlsxWorkbook
import com.oilterminal.tankcalc.provider.OriginalFileProvider
import com.oilterminal.tankcalc.ui.UiFactory
import com.oilterminal.tankcalc.util.NameNormalizer
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var repository: OilRepository
    private val executor = Executors.newSingleThreadExecutor()
    private var backAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = OilRepository(this)
        showHome()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val action = backAction
        if (action != null) action() else super.onBackPressed()
    }

    private fun showHome() {
        backAction = null
        val content = UiFactory.vertical(this)
        content.addView(UiFactory.title(this, "原油舱容检尺"))
        content.addView(
            UiFactory.body(
                this,
                "船舶原始舱容表保存在本机；旧船再次到港时，直接输入船号检索并计算。"
            )
        )
        content.addView(
            UiFactory.muted(
                this,
                "当前资料库：${repository.vesselCount()} 艘船。纵倾默认读取 0.0m 数据。"
            )
        )

        content.addView(UiFactory.section(this, "原油计算"))
        content.addView(UiFactory.button(this, "综合检尺计算与报表输出").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, CrudeOilActivity::class.java)) }
        })
        content.addView(UiFactory.button(this, "单舱快速检尺", primary = false).apply {
            setOnClickListener { showQuickCalculation() }
        })
        content.addView(UiFactory.button(this, "总量快速复核", primary = false).apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, QuickMassReviewActivity::class.java))
            }
        })

        content.addView(UiFactory.section(this, "船舶舱容资料"))
        content.addView(UiFactory.button(this, "船舶资料库", primary = false).apply {
            setOnClickListener { showVesselLibrary() }
        })
        content.addView(UiFactory.button(this, "导入新的 XLSX 舱容表").apply {
            setOnClickListener { launchImportPicker() }
        })
        content.addView(UiFactory.button(this, "导入内置示例表", primary = false).apply {
            setOnClickListener { importBundledSample() }
        })

        val recent = repository.recentCalculations()
        if (recent.isNotEmpty()) {
            content.addView(UiFactory.section(this, "最近计算"))
            recent.forEach { item ->
                content.addView(UiFactory.card(this).apply {
                    addView(
                        UiFactory.body(
                            this@MainActivity,
                            "${item.vesselName} · ${item.tankName}"
                        ).apply { setTypeface(typeface, Typeface.BOLD) }
                    )
                    addView(
                        UiFactory.muted(
                            this@MainActivity,
                            "${item.inputUllageMm} mm → ${item.resultVolumeM3} m³"
                        )
                    )
                })
            }
        }

        setPage("首页", content, showBack = false)
    }

    private fun showQuickCalculation(preselectedVesselId: Long? = null) {
        var selectedVessel: VesselSummary? = null
        var tanks: List<TankSummary> = emptyList()

        val content = UiFactory.vertical(this)
        content.addView(UiFactory.section(this, "1. 输入船号"))
        val vesselInput = UiFactory.editText(this, "例如：新平江1014")
        val selectedLabel = UiFactory.muted(this, "尚未选择船舶")
        val tankSpinner = Spinner(this)
        val emptyAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("请先匹配船舶")
        )
        tankSpinner.adapter = emptyAdapter

        fun selectVessel(vessel: VesselSummary) {
            selectedVessel = vessel
            vesselInput.setText(vessel.name)
            selectedLabel.text =
                "已匹配：${vessel.name} · ${vessel.tankCount} 个船舱\n" +
                "版本：${vessel.versionLabel ?: "未标记"}"
            tanks = repository.tanksForVessel(vessel.id)
            tankSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                tanks.map {
                    val range = if (it.minUllageMm != null && it.maxUllageMm != null) {
                        "（${it.minUllageMm}～${it.maxUllageMm} mm）"
                    } else ""
                    "${it.canonicalName} $range"
                }
            )
        }

        val searchButton = UiFactory.button(this, "匹配本地船舶")
        searchButton.setOnClickListener {
            val query = vesselInput.text.toString().trim()
            val matches = repository.searchVessels(query)
            when {
                matches.isEmpty() -> toast("本地没有匹配的船舶，请先导入该船舱容表。")
                matches.size == 1 -> selectVessel(matches.first())
                else -> {
                    val exact = matches.firstOrNull {
                        it.normalizedName == NameNormalizer.vessel(query)
                    }
                    if (exact != null) {
                        selectVessel(exact)
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("选择船舶")
                            .setItems(matches.map { it.name }.toTypedArray()) { _, which ->
                                selectVessel(matches[which])
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
        }

        content.addView(vesselInput)
        content.addView(searchButton)
        content.addView(selectedLabel)
        content.addView(UiFactory.section(this, "2. 选择船舱"))
        content.addView(tankSpinner)

        content.addView(UiFactory.section(this, "3. 输入检尺空高"))
        val ullageInput = UiFactory.editText(this, "默认输入毫米，例如：1653", numeric = true)
        content.addView(ullageInput)

        val unitGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.START
        }
        val mmButton = RadioButton(this).apply {
            text = "毫米 mm"
            id = View.generateViewId()
            isChecked = true
        }
        val meterButton = RadioButton(this).apply {
            text = "米 m"
            id = View.generateViewId()
        }
        unitGroup.addView(mmButton)
        unitGroup.addView(meterButton)
        content.addView(unitGroup)
        content.addView(
            UiFactory.muted(
                this,
                "内部统一换算为毫米定位数据点；容积结果保留 3 位小数。"
            )
        )

        val calculateButton = UiFactory.button(this, "计算检尺容积")
        calculateButton.setOnClickListener {
            val vessel = selectedVessel
            if (vessel == null) {
                toast("请先匹配船舶。")
                return@setOnClickListener
            }
            if (tanks.isEmpty() || tankSpinner.selectedItemPosition !in tanks.indices) {
                toast("该船舶没有可用船舱数据。")
                return@setOnClickListener
            }
            val entered = ullageInput.text.toString().trim()
                .replace("，", ".")
                .toBigDecimalOrNull()
            if (entered == null || entered < BigDecimal.ZERO) {
                toast("请输入有效的检尺空高。")
                return@setOnClickListener
            }

            val inputMm = if (mmButton.isChecked) {
                entered.setScale(0, RoundingMode.HALF_UP).toInt()
            } else {
                entered.multiply(BigDecimal("1000"))
                    .setScale(0, RoundingMode.HALF_UP)
                    .toInt()
            }
            val tank = tanks[tankSpinner.selectedItemPosition]
            val outcome = CapacityCalculator.calculate(
                inputMm,
                repository.findBracket(tank.id, inputMm)
            )
            when (outcome) {
                is CalculationOutcome.Error ->
                    AlertDialog.Builder(this)
                        .setTitle("无法计算")
                        .setMessage(outcome.message)
                        .setPositiveButton("知道了", null)
                        .show()

                is CalculationOutcome.Success -> {
                    repository.recordCalculation(
                        vesselId = vessel.id,
                        tankId = tank.id,
                        inputUllageMm = inputMm,
                        resultVolumeM3 = outcome.roundedVolumeM3.toPlainString()
                    )
                    showCalculationResult(
                        vessel = vessel,
                        tank = tank,
                        enteredText = entered.toPlainString(),
                        enteredAsMm = mmButton.isChecked,
                        result = outcome
                    )
                }
            }
        }
        content.addView(calculateButton)

        setPage("快速计算", content) { showHome() }

        if (preselectedVesselId != null) {
            repository.vesselById(preselectedVesselId)?.let(::selectVessel)
        }
    }

    private fun showCalculationResult(
        vessel: VesselSummary,
        tank: TankSummary,
        enteredText: String,
        enteredAsMm: Boolean,
        result: CalculationOutcome.Success
    ) {
        val content = UiFactory.vertical(this)
        val resultCard = UiFactory.card(this)
        resultCard.addView(UiFactory.muted(this, "检尺容积"))
        resultCard.addView(
            UiFactory.title(
                this,
                "${result.roundedVolumeM3.toPlainString()} m³",
                34f
            ).apply { setTextColor(UiFactory.BLUE) }
        )
        resultCard.addView(
            UiFactory.body(
                this,
                "${vessel.name} · ${tank.canonicalName}\n" +
                    "输入：$enteredText ${if (enteredAsMm) "mm" else "m"}\n" +
                    "换算空高：${result.inputUllageMm} mm\n" +
                    "纵倾数据列：0.0m"
            )
        )
        content.addView(resultCard)

        content.addView(UiFactory.section(this, "计算过程"))
        val first = result.first
        val second = result.second
        val process = if (result.exactMatch) {
            """
            输入空高正好命中舱容表数据点，不需要插值。

            H = ${result.inputUllageMm} mm
            V = ${first.volumeM3} m³

            来源：${first.sourceSheet}
            空高单元格：${first.ullageCell}（原值：${first.rawUllage}）
            容积单元格：${first.volumeCell}（原值：${first.rawVolume}）

            最终检尺容积：
            ${result.roundedVolumeM3.toPlainString()} m³
            """.trimIndent()
        } else {
            requireNotNull(second)
            val hDiff = result.inputUllageMm - first.ullageMm
            val interval = second.ullageMm - first.ullageMm
            val volumeDiff = second.volumeM3.toBigDecimal()
                .subtract(first.volumeM3.toBigDecimal())
            """
            表上第一点：
            H1 = ${first.ullageMm} mm
            V1 = ${first.volumeM3} m³
            来源 = ${first.sourceSheet}!${first.ullageCell}/${first.volumeCell}

            表上第二点：
            H2 = ${second.ullageMm} mm
            V2 = ${second.volumeM3} m³
            来源 = ${second.sourceSheet}!${second.ullageCell}/${second.volumeCell}

            空高差：
            H - H1 = ${result.inputUllageMm} - ${first.ullageMm} = $hDiff mm

            表格间隔：
            H2 - H1 = ${second.ullageMm} - ${first.ullageMm} = $interval mm

            容积差：
            V2 - V1 = ${second.volumeM3} - ${first.volumeM3}
                    = ${volumeDiff.stripTrailingZeros().toPlainString()} m³

            插值比例：
            (H-H1)/(H2-H1) = ${result.ratio.stripTrailingZeros().toPlainString()}

            公式：
            V = V1 + (H-H1)/(H2-H1) × (V2-V1)

            未舍入结果：
            ${result.rawVolumeM3.stripTrailingZeros().toPlainString()} m³

            最终检尺容积（四舍五入 3 位）：
            ${result.roundedVolumeM3.toPlainString()} m³
            """.trimIndent()
        }
        content.addView(UiFactory.card(this).apply {
            addView(UiFactory.body(this@MainActivity, process, 15f))
        })
        content.addView(UiFactory.button(this, "浏览该船原始 Excel", primary = false).apply {
            setOnClickListener { showWorkbookBrowser(vessel.id) }
        })
        content.addView(UiFactory.button(this, "继续计算").apply {
            setOnClickListener { showQuickCalculation(vessel.id) }
        })
        setPage("计算结果", content) { showQuickCalculation(vessel.id) }
    }

    private fun showVesselLibrary() {
        val vessels = repository.listVessels()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiFactory.BACKGROUND)
        }
        root.addView(UiFactory.topBar(this, "船舶资料库", true) { showHome() })

        if (vessels.isEmpty()) {
            val empty = UiFactory.vertical(this)
            empty.addView(UiFactory.title(this, "尚未导入船舶"))
            empty.addView(UiFactory.body(this, "请导入一张 XLSX 舱容表，识别结果确认后会保存到本机。"))
            empty.addView(UiFactory.button(this, "导入舱容表").apply {
                setOnClickListener { launchImportPicker() }
            })
            root.addView(empty)
        } else {
            val list = ListView(this)
            list.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_2,
                android.R.id.text1,
                vessels.map {
                    "${it.name}\n${it.tankCount} 个船舱 · ${it.sourceFileName ?: "无原始文件"}"
                }
            )
            list.setOnItemClickListener { _, _, position, _ ->
                showVesselDetail(vessels[position].id)
            }
            root.addView(
                list,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            root.addView(UiFactory.button(this, "导入新船或新版表").apply {
                setOnClickListener { launchImportPicker() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    UiFactory.dp(this@MainActivity, 16),
                    UiFactory.dp(this@MainActivity, 4),
                    UiFactory.dp(this@MainActivity, 16),
                    UiFactory.dp(this@MainActivity, 12)
                )
            })
        }
        backAction = { showHome() }
        setContentView(root)
    }

    private fun showVesselDetail(vesselId: Long) {
        val vessel = repository.vesselById(vesselId) ?: run {
            toast("船舶记录不存在。")
            showVesselLibrary()
            return
        }
        val tanks = repository.tanksForVessel(vesselId)
        val source = repository.sourceFileForVessel(vesselId)
        val content = UiFactory.vertical(this)

        content.addView(UiFactory.title(this, vessel.name))
        content.addView(
            UiFactory.body(
                this,
                "当前版本：${vessel.versionLabel ?: "未标记"}\n" +
                    "原始文件：${source?.originalName ?: "无"}\n" +
                    "船舱数量：${tanks.size}"
            )
        )
        content.addView(UiFactory.button(this, "修改船号", primary = false).apply {
            setOnClickListener { showVesselRenameDialog(vesselId, vessel.name) }
        })
        content.addView(UiFactory.button(this, "直接进入快速计算").apply {
            setOnClickListener { showQuickCalculation(vesselId) }
        })
        content.addView(UiFactory.button(this, "舱容数据自查", primary = false).apply {
            setOnClickListener { showCapacityAudit(vesselId) }
        })
        content.addView(UiFactory.button(this, "App 内浏览原始表", primary = false).apply {
            setOnClickListener { showWorkbookBrowser(vesselId) }
        })
        content.addView(UiFactory.button(this, "使用 WPS / Excel 打开", primary = false).apply {
            setOnClickListener { openWorkbookExternally(vesselId) }
        })

        content.addView(UiFactory.section(this, "船舱与数据范围"))
        tanks.forEach { tank ->
            content.addView(UiFactory.card(this).apply {
                addView(
                    UiFactory.body(
                        this@MainActivity,
                        tank.canonicalName
                    ).apply { setTypeface(typeface, Typeface.BOLD) }
                )
                addView(
                    UiFactory.muted(
                        this@MainActivity,
                        "原表标识：${tank.sourceName}\n" +
                            "数据点：${tank.pointCount}\n" +
                            "空高范围：${tank.minUllageMm ?: "-"}～${tank.maxUllageMm ?: "-"} mm"
                    )
                )
                addView(UiFactory.button(this@MainActivity, "修改船舱映射", primary = false).apply {
                    setOnClickListener { showTankRenameDialog(vesselId, tank) }
                })
            })
        }

        content.addView(UiFactory.button(this, "删除该船全部本地数据", primary = false).apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("确认删除")
                    .setMessage("将删除 ${vessel.name} 的舱容数据和计算历史。原始文件若未被其他船使用，也会从资料库移除。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        repository.deleteVessel(vesselId)
                        showVesselLibrary()
                    }
                    .show()
            }
        })

        setPage("船舶详情", content) { showVesselLibrary() }
    }

    private fun showCapacityAudit(vesselId: Long) {
        val vessel = repository.vesselById(vesselId) ?: run {
            toast("船舶记录不存在。")
            return
        }
        val tanks = repository.tanksForVessel(vesselId)
        val content = UiFactory.vertical(this)
        content.addView(UiFactory.title(this, "${vessel.name} · 舱容数据自查"))
        content.addView(
            UiFactory.muted(
                this,
                "只检查当前活动数据集中 App 实际用于取数/插值的舱容点，不遍历原 Excel 的其他页面或未采用数据列。异常时显示相邻点和来源单元格，便于回原表核对。"
            )
        )

        var totalIssues = 0
        tanks.forEach { tank ->
            val audit = CapacityAuditService.audit(repository.capacityPointsForTank(tank.id))
            totalIssues += audit.issues.size
            content.addView(
                UiFactory.card(this).apply {
                    val status = if (audit.issues.isEmpty()) "✓ 正常" else "⚠ ${audit.issues.size} 处建议复核"
                    addView(
                        UiFactory.body(this@MainActivity, "${tank.canonicalName}  $status").apply {
                            setTypeface(typeface, Typeface.BOLD)
                        }
                    )
                    addView(
                        UiFactory.muted(
                            this@MainActivity,
                            "采用数据点：${audit.pointCount}\n" +
                                "常见空高间隔：${audit.medianUllageStepMm ?: "-"} mm\n" +
                                "常见相邻容积变化：${audit.medianVolumeDeltaM3?.setScale(3, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString() ?: "-"} m³"
                        )
                    )
                    audit.issues.take(8).forEach { issue ->
                        addView(
                            UiFactory.body(
                                this@MainActivity,
                                "⚠ ${issue.reason}\n" +
                                    "${issue.first.ullageMm} mm → ${issue.first.volumeM3} m³  (${issue.first.sourceSheet}!${issue.first.ullageCell}/${issue.first.volumeCell})\n" +
                                    "${issue.second.ullageMm} mm → ${issue.second.volumeM3} m³  (${issue.second.sourceSheet}!${issue.second.ullageCell}/${issue.second.volumeCell})",
                                13f
                            )
                        )
                    }
                    if (audit.issues.size > 8) {
                        addView(UiFactory.muted(this@MainActivity, "其余 ${audit.issues.size - 8} 处未展开；优先核对以上异常及其原表来源。"))
                    }
                }
            )
        }

        content.addView(
            UiFactory.body(
                this,
                if (totalIssues == 0) "自查完成：当前采用的数据系列未发现明显规律异常。"
                else "自查完成：共发现 $totalIssues 处建议复核的数据间隔。此功能用于提示异常，不会自动修改原始舱容数据。"
            )
        )
        content.addView(UiFactory.button(this, "浏览原始 Excel 核对", primary = false).apply {
            setOnClickListener { showWorkbookBrowser(vesselId) }
        })
        setPage("舱容数据自查", content) { showVesselDetail(vesselId) }
    }

    private fun showVesselRenameDialog(vesselId: Long, currentName: String) {
        val input = UiFactory.editText(this, "例如：新平江1014")
        input.setText(currentName)
        AlertDialog.Builder(this)
            .setTitle("修改船号")
            .setMessage("修改后，快速计算将按新船号进行本地匹配。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                runCatching {
                    repository.renameVessel(vesselId, input.text.toString())
                }.onSuccess {
                    showVesselDetail(vesselId)
                }.onFailure {
                    toast("修改失败：${it.message ?: "可能与已有船号重复"}")
                }
            }
            .show()
    }

    private fun showTankRenameDialog(vesselId: Long, tank: TankSummary) {
        val choices = buildList {
            for (number in 1..10) {
                add("左${NameNormalizer.chineseNumber(number)}")
                add("右${NameNormalizer.chineseNumber(number)}")
            }
            for (number in 1..10) {
                add("货舱${NameNormalizer.chineseNumber(number)}")
            }
        }.distinct().toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("将“${tank.sourceName}”映射为")
            .setItems(choices) { _, which ->
                runCatching { repository.renameTank(tank.id, choices[which]) }
                    .onSuccess { showVesselDetail(vesselId) }
                    .onFailure { toast("修改失败：可能与已有船舱名称重复。") }
            }
            .setNeutralButton("自定义") { _, _ ->
                val input = UiFactory.editText(this, "自定义船舱名称")
                input.setText(tank.canonicalName)
                AlertDialog.Builder(this)
                    .setTitle("自定义船舱名称")
                    .setView(input)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存") { _, _ ->
                        runCatching {
                            repository.renameTank(tank.id, input.text.toString())
                        }.onSuccess {
                            showVesselDetail(vesselId)
                        }.onFailure {
                            toast("修改失败：${it.message}")
                        }
                    }
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "application/octet-stream"
                )
            )
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val name = displayName(uri) ?: "导入舱容表.xlsx"
            val stream = contentResolver.openInputStream(uri) ?: run {
                toast("无法读取所选文件。")
                return
            }
            prepareImport(stream, name)
        }
    }

    private fun importBundledSample() {
        val stream = assets.open("sample/原油舱容计算a.xlsx")
        prepareImport(stream, "原油舱容计算a.xlsx")
    }

    private data class PreparedImport(
        val file: File,
        val originalName: String,
        val sha256: String,
        val parsed: ParsedWorkbook
    )

    private fun prepareImport(input: InputStream, originalName: String) {
        if (!originalName.lowercase().endsWith(".xlsx")) {
            input.close()
            toast("当前版本只支持 .xlsx 文件，不支持旧版 .xls。")
            return
        }
        val originals = File(filesDir, "originals").apply { mkdirs() }
        val storedName =
            "${System.currentTimeMillis()}_${NameNormalizer.safeFileName(originalName)}"
        val destination = File(originals, storedName)

        runInBackground(
            title = "正在读取并识别舱容表",
            task = {
                input.use { source ->
                    destination.outputStream().use { output -> source.copyTo(output) }
                }
                val workbook = XlsxReader.read(destination)
                val parsed = TankWorkbookParser.parse(workbook, originalName)
                PreparedImport(
                    file = destination,
                    originalName = originalName,
                    sha256 = sha256(destination),
                    parsed = parsed
                )
            },
            onSuccess = { prepared ->
                if (prepared.parsed.vessels.isEmpty()) {
                    prepared.file.delete()
                    AlertDialog.Builder(this)
                        .setTitle("没有识别到可导入数据")
                        .setMessage(
                            "请确认文件是 XLSX 舱容表，并包含空高、容积或纵倾 0.0m 数据。"
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                } else {
                    showImportPreview(prepared)
                }
            },
            onError = {
                destination.delete()
                showError("读取失败", it)
            }
        )
    }

    private fun showImportPreview(prepared: PreparedImport) {
        val message = buildString {
            prepared.parsed.vessels.forEach { vessel ->
                append("船号：${vessel.name}\n")
                vessel.tanks.forEach { tank ->
                    val min = tank.points.minOfOrNull { it.ullageMm }
                    val max = tank.points.maxOfOrNull { it.ullageMm }
                    append(
                        "  • ${tank.canonicalName}：${tank.points.size} 点，" +
                            "${min ?: "-"}～${max ?: "-"} mm\n"
                    )
                }
                append('\n')
            }
            append("纵倾：默认采用 0.0m\n")
            append("原始文件：将原样保存到 App 本地目录\n")
            if (prepared.parsed.warnings.isNotEmpty()) {
                append("\n识别提示（${prepared.parsed.warnings.size} 条）：\n")
                prepared.parsed.warnings.take(8).forEach {
                    append("• $it\n")
                }
                if (prepared.parsed.warnings.size > 8) {
                    append("其余提示将在后续版本的导入报告中展开。\n")
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("确认导入识别结果")
            .setMessage(message)
            .setNegativeButton("取消") { _, _ -> prepared.file.delete() }
            .setPositiveButton("保存到本地资料库") { _, _ ->
                runInBackground(
                    title = "正在写入本地数据库",
                    task = {
                        repository.saveParsedWorkbook(
                            originalName = prepared.originalName,
                            storedPath = prepared.file.absolutePath,
                            sha256 = prepared.sha256,
                            parsed = prepared.parsed
                        )
                    },
                    onSuccess = { outcome ->
                        if (outcome.duplicate) prepared.file.delete()
                        showImportOutcome(outcome)
                    },
                    onError = {
                        prepared.file.delete()
                        showError("保存失败", it)
                    }
                )
            }
            .setOnCancelListener { prepared.file.delete() }
            .show()
    }

    private fun showImportOutcome(outcome: ImportOutcome) {
        AlertDialog.Builder(this)
            .setTitle(if (outcome.duplicate) "无需重复导入" else "导入完成")
            .setMessage(outcome.message)
            .setNegativeButton("返回首页") { _, _ -> showHome() }
            .setPositiveButton("查看船舶资料库") { _, _ -> showVesselLibrary() }
            .show()
    }

    private fun showWorkbookBrowser(vesselId: Long) {
        val source = repository.sourceFileForVessel(vesselId)
        if (source == null) {
            toast("没有找到原始文件。")
            return
        }
        val file = File(source.storedPath)
        runInBackground(
            title = "正在打开原始 Excel",
            task = { XlsxReader.read(file) },
            onSuccess = { workbook ->
                renderWorkbook(vesselId, source.originalName, workbook)
            },
            onError = { showError("无法浏览原始表格", it) }
        )
    }

    private fun renderWorkbook(
        vesselId: Long,
        originalName: String,
        workbook: XlsxWorkbook
    ) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(UiFactory.topBar(this, "原始 Excel 浏览", true) {
            showVesselDetail(vesselId)
        })
        root.addView(UiFactory.muted(this, originalName).apply {
            setPadding(
                UiFactory.dp(this@MainActivity, 12),
                UiFactory.dp(this@MainActivity, 6),
                UiFactory.dp(this@MainActivity, 12),
                UiFactory.dp(this@MainActivity, 4)
            )
        })

        val sheetSpinner = Spinner(this)
        sheetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            workbook.sheets.map { it.name }
        )
        root.addView(sheetSpinner)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            container,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        fun showSheet(sheet: XlsxSheet) {
            container.removeAllViews()
            val maxRows = minOf(sheet.maxRow, 200)
            val maxColumns = minOf(sheet.maxColumn, 40)
            if (sheet.maxRow > maxRows || sheet.maxColumn > maxColumns) {
                container.addView(
                    UiFactory.muted(
                        this,
                        "为保证手机流畅，当前最多显示 200 行 × 40 列；可使用 WPS/Excel 查看完整原版。"
                    ).apply {
                        setPadding(
                            UiFactory.dp(this@MainActivity, 8),
                            UiFactory.dp(this@MainActivity, 4),
                            UiFactory.dp(this@MainActivity, 8),
                            UiFactory.dp(this@MainActivity, 4)
                        )
                    }
                )
            }

            val table = TableLayout(this)
            val header = TableRow(this)
            header.addView(gridCell("", header = true))
            for (column in 1..maxColumns) {
                header.addView(gridCell(columnName(column), header = true))
            }
            table.addView(header)

            for (row in 1..maxRows) {
                val tableRow = TableRow(this)
                tableRow.addView(gridCell(row.toString(), header = true))
                for (column in 1..maxColumns) {
                    tableRow.addView(
                        gridCell(sheet.value(row, column).orEmpty(), header = false)
                    )
                }
                table.addView(tableRow)
            }

            val vertical = ScrollView(this).apply { addView(table) }
            val horizontal = HorizontalScrollView(this).apply { addView(vertical) }
            container.addView(
                horizontal,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        sheetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                workbook.sheets.getOrNull(position)?.let(::showSheet)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        backAction = { showVesselDetail(vesselId) }
        setContentView(root)
    }

    private fun gridCell(text: String, header: Boolean): TextView =
        TextView(this).apply {
            this.text = text.take(80)
            textSize = if (header) 13f else 12f
            setTextColor(UiFactory.TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                UiFactory.dp(this@MainActivity, 6),
                UiFactory.dp(this@MainActivity, 5),
                UiFactory.dp(this@MainActivity, 6),
                UiFactory.dp(this@MainActivity, 5)
            )
            if (header) setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(if (header) 0xFFE4EDF4.toInt() else Color.WHITE)
                setStroke(UiFactory.dp(this@MainActivity, 1), UiFactory.BORDER)
            }
            layoutParams = TableRow.LayoutParams(
                UiFactory.dp(this@MainActivity, if (header && text.length < 4) 60 else 125),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            minHeight = UiFactory.dp(this@MainActivity, 42)
            maxLines = 3
        }

    private fun openWorkbookExternally(vesselId: Long) {
        val source = repository.sourceFileForVessel(vesselId)
        if (source == null) {
            toast("没有找到原始文件。")
            return
        }
        val file = File(source.storedPath)
        val uri = OriginalFileProvider.uriFor(this, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                uri,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("手机上没有可打开 XLSX 的应用，请安装 WPS 或 Excel。")
        }
    }

    private fun setPage(
        title: String,
        content: View,
        showBack: Boolean = true,
        onBack: () -> Unit = { showHome() }
    ) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiFactory.BACKGROUND)
        }
        root.addView(UiFactory.topBar(this, title, showBack, onBack))
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(
            scroll,
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
        val progress = ProgressBar(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(progress)
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

    private fun displayName(uri: Uri): String? {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun showError(title: String, error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun columnName(column: Int): String {
        var value = column
        val result = StringBuilder()
        while (value > 0) {
            value--
            result.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return result.reverse().toString()
    }

    companion object {
        private const val REQUEST_IMPORT = 1001
    }
}
