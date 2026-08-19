package com.oilterminal.tankcalc

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import com.oilterminal.tankcalc.calc.DensityUnit
import com.oilterminal.tankcalc.ui.UiFactory
import java.math.BigDecimal
import java.math.RoundingMode

class QuickMassReviewActivity : Activity() {
    private var backAction: (() -> Unit)? = null
    private var draft: QuickDraft? = null

    private data class QuickDraft(
        val total: String,
        val water: String,
        val obq: String,
        val vcf: String,
        val density: String,
        val survey: String,
        val densityUnit: DensityUnit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showInput()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        backAction?.invoke() ?: super.onBackPressed()
    }

    private fun showInput() {
        val content = UiFactory.vertical(this)
        content.addView(UiFactory.title(this, "总量快速复核"))
        content.addView(
            UiFactory.muted(
                this,
                "不需要逐舱检尺。输入总检尺体积、VCF、底水、OBQ 和标密，按综合计算的无钢膨口径快速复核质量。"
            )
        )

        val total = labeledInput(content, "总检尺体积 / 总舱容（m³）", "例如：5826.392")
        val water = labeledInput(content, "底水（m³，可不填）", "不填按 0")
        val obq = labeledInput(content, "OBQ（m³，可不填）", "不填按 0")
        val vcf = labeledInput(content, "体积修正系数 VCF", "例如：0.9826")
        val density = labeledInput(content, "标密 P20", "例如：850 或 0.850")

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
                "与综合计算保持一致：kg/m³ 标密先换算为 t/m³，再减 0.0011 t/m³ 得到修正密度。"
            )
        )

        val survey = labeledInput(content, "商检结果（t，可不填）", "填写后自动显示差值和差率")

        draft?.let { saved ->
            total.setText(saved.total)
            water.setText(saved.water)
            obq.setText(saved.obq)
            vcf.setText(saved.vcf)
            density.setText(saved.density)
            survey.setText(saved.survey)
            if (saved.densityUnit == DensityUnit.KG_PER_M3) kgButton.isChecked = true
            else tonButton.isChecked = true
        }

        content.addView(
            UiFactory.button(this, "快速计算质量").apply {
                setOnClickListener {
                    draft = QuickDraft(
                        total = total.text.toString(),
                        water = water.text.toString(),
                        obq = obq.text.toString(),
                        vcf = vcf.text.toString(),
                        density = density.text.toString(),
                        survey = survey.text.toString(),
                        densityUnit = if (kgButton.isChecked) DensityUnit.KG_PER_M3 else DensityUnit.TON_PER_M3
                    )
                    val totalValue = decimalRequired(total, "总检尺体积") ?: return@setOnClickListener
                    val vcfValue = decimalRequired(vcf, "VCF") ?: return@setOnClickListener
                    val densityValue = decimalRequired(density, "标密") ?: return@setOnClickListener
                    val waterValue = decimalOrZero(water, "底水") ?: return@setOnClickListener
                    val obqValue = decimalOrZero(obq, "OBQ") ?: return@setOnClickListener
                    val surveyValue = decimalOrZero(survey, "商检结果") ?: return@setOnClickListener

                    if (totalValue <= BigDecimal.ZERO) return@setOnClickListener toast("总检尺体积必须大于 0。")
                    if (vcfValue <= BigDecimal.ZERO) return@setOnClickListener toast("VCF 必须大于 0。")
                    if (waterValue < BigDecimal.ZERO || obqValue < BigDecimal.ZERO) {
                        return@setOnClickListener toast("底水和 OBQ 不能小于 0。")
                    }
                    if (waterValue > totalValue) return@setOnClickListener toast("底水不能大于总检尺体积。")

                    val unit = if (kgButton.isChecked) DensityUnit.KG_PER_M3 else DensityUnit.TON_PER_M3
                    val densityT = when (unit) {
                        DensityUnit.KG_PER_M3 -> densityValue.divide(BigDecimal("1000"), 12, RoundingMode.HALF_UP)
                        DensityUnit.TON_PER_M3 -> densityValue
                    }
                    val correctedDensity = densityT.subtract(BigDecimal("0.0011"))
                    if (correctedDensity <= BigDecimal.ZERO) return@setOnClickListener toast("标密修正后必须大于 0。")

                    val standardVolume = totalValue.subtract(waterValue).multiply(vcfValue).subtract(obqValue)
                    if (standardVolume < BigDecimal.ZERO) return@setOnClickListener toast("标准体积小于 0，请检查底水、VCF 和 OBQ。")
                    val mass = standardVolume.multiply(correctedDensity)
                    showResult(
                        totalValue,
                        waterValue,
                        obqValue,
                        vcfValue,
                        densityValue,
                        unit,
                        correctedDensity,
                        standardVolume,
                        mass,
                        surveyValue
                    )
                }
            }
        )

        setPage("总量快速复核", content) { finish() }
    }

    private fun showResult(
        total: BigDecimal,
        water: BigDecimal,
        obq: BigDecimal,
        vcf: BigDecimal,
        density: BigDecimal,
        densityUnit: DensityUnit,
        correctedDensity: BigDecimal,
        standardVolume: BigDecimal,
        mass: BigDecimal,
        survey: BigDecimal
    ) {
        val content = UiFactory.vertical(this)
        content.addView(UiFactory.title(this, "快速复核结果"))

        val resultCard = UiFactory.card(this)
        resultCard.addView(UiFactory.muted(this, "计算质量（无钢膨口径）"))
        resultCard.addView(
            UiFactory.title(this, "${fmt(mass)} t", 32f).apply {
                setTextColor(UiFactory.BLUE)
            }
        )
        if (survey > BigDecimal.ZERO) {
            val diff = mass.subtract(survey)
            val rate = if (survey.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO else
                diff.divide(survey, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            resultCard.addView(
                UiFactory.body(
                    this,
                    "商检结果：${fmt(survey)} t\n差值：${signed(diff)} t\n差率：${signed(rate)}%"
                )
            )
        }
        content.addView(resultCard)

        content.addView(UiFactory.section(this, "计算过程"))
        content.addView(
            UiFactory.card(this).apply {
                addView(
                    UiFactory.body(
                        this@QuickMassReviewActivity,
                        "总检尺体积：${fmt(total)} m³\n" +
                            "底水：${fmt(water)} m³\n" +
                            "VCF：${vcf.stripTrailingZeros().toPlainString()}\n" +
                            "OBQ：${fmt(obq)} m³\n\n" +
                            "标准体积 = (总检尺体积 - 底水) × VCF - OBQ\n" +
                            "= ${fmt(standardVolume)} m³\n\n" +
                            "标密：${density.stripTrailingZeros().toPlainString()} ${densityUnit.displayName}\n" +
                            "修正密度：${correctedDensity.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()} t/m³\n\n" +
                            "质量 = 标准体积 × 修正密度\n= ${fmt(mass)} t",
                        15f
                    )
                )
            }
        )
        content.addView(UiFactory.button(this, "返回修改", primary = false).apply {
            setOnClickListener { showInput() }
        })
        setPage("总量快速复核结果", content) { showInput() }
    }

    private fun labeledInput(container: LinearLayout, label: String, hint: String): EditText {
        container.addView(
            UiFactory.body(this, label).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        )
        val input = UiFactory.editText(this, hint, numeric = true)
        container.addView(input)
        return input
    }

    private fun decimalRequired(input: EditText, field: String): BigDecimal? {
        val raw = input.text.toString().trim().replace("，", ".")
        return raw.toBigDecimalOrNull().also { if (it == null) toast("请输入有效的$field。") }
    }

    private fun decimalOrZero(input: EditText, field: String): BigDecimal? {
        val raw = input.text.toString().trim().replace("，", ".")
        if (raw.isBlank()) return BigDecimal.ZERO
        return raw.toBigDecimalOrNull().also { if (it == null) toast("请输入有效的$field，或留空按 0。") }
    }

    private fun fmt(value: BigDecimal): String = value.setScale(3, RoundingMode.HALF_UP).toPlainString()
    private fun signed(value: BigDecimal): String {
        val text = fmt(value)
        return if (value > BigDecimal.ZERO) "+$text" else text
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun setPage(title: String, content: View, onBack: () -> Unit) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiFactory.BACKGROUND)
        }
        root.addView(UiFactory.topBar(this, title, true, onBack))
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        backAction = onBack
        setContentView(root)
    }
}
