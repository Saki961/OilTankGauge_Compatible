package com.oilterminal.tankcalc.report

import com.oilterminal.tankcalc.calc.CrudeOilCalculationResult
import com.oilterminal.tankcalc.calc.DensityUnit
import com.oilterminal.tankcalc.data.VesselSummary
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class CrudeOilReportTankRow(
    val tankName: String,
    val included: Boolean,
    val ullageMm: Int?,
    val middleTemperatureC: BigDecimal?,
    val gaugeVolumeM3: BigDecimal?
)

object CrudeOilXlsxTemplateWriter {
    fun write(
        template: InputStream,
        vessel: VesselSummary,
        result: CrudeOilCalculationResult,
        tankRows: List<CrudeOilReportTankRow>,
        generatedAt: Date
    ): ByteArray {
        require(tankRows.size <= 12) { "当前原油计算报表最多支持 12 个船舱。" }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(template).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val sheetPath = findWorksheetPath(entries, "原油计算")
        val sheetBytes = entries[sheetPath]
            ?: error("模板缺少原油计算工作表：$sheetPath")
        entries[sheetPath] = patchSheet(sheetBytes, vessel, result, tankRows, generatedAt)

        return ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { output ->
                entries.forEach { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun findWorksheetPath(entries: Map<String, ByteArray>, sheetName: String): String {
        val workbook = parse(entries.getValue("xl/workbook.xml"))
        val relationships = parse(entries.getValue("xl/_rels/workbook.xml.rels"))
        val relationshipTargets = mutableMapOf<String, String>()
        val relNodes = relationships.documentElement.childNodes
        for (i in 0 until relNodes.length) {
            val node = relNodes.item(i)
            if (node is Element && node.localName == "Relationship") {
                relationshipTargets[node.getAttribute("Id")] = node.getAttribute("Target")
            }
        }
        val sheets = workbook.getElementsByTagNameNS(MAIN_NS, "sheet")
        for (i in 0 until sheets.length) {
            val sheet = sheets.item(i) as Element
            if (sheet.getAttribute("name") == sheetName) {
                val id = sheet.getAttributeNS(OFFICE_REL_NS, "id")
                val target = relationshipTargets[id] ?: error("模板工作表关系缺失。")
                return when {
                    target.startsWith("/xl/") -> target.removePrefix("/")
                    target.startsWith("/") -> target.removePrefix("/")
                    target.startsWith("xl/") -> target
                    else -> "xl/$target"
                }
            }
        }
        error("模板中没有名为“$sheetName”的工作表。")
    }

    private fun patchSheet(
        bytes: ByteArray,
        vessel: VesselSummary,
        result: CrudeOilCalculationResult,
        tankRows: List<CrudeOilReportTankRow>,
        generatedAt: Date
    ): ByteArray {
        val document = parse(bytes)
        setText(document, "B3", vessel.name)
        setText(document, "F3", vessel.versionLabel.orEmpty())
        setNumber(document, "J3", tankRows.size.toString())
        setText(document, "J2", SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(generatedAt))

        setText(document, "G5", "水体积\n(m³)")
        setNumber(document, "H5", plain(result.input.waterVolumeM3))
        setText(document, "K5", "OBQ\n(m³)")
        setNumber(document, "L5", plain(result.input.obqM3))

        val densityLabel = when (result.input.densityUnit) {
            DensityUnit.KG_PER_M3 -> "密度P20\n(kg/m³)"
            DensityUnit.TON_PER_M3 -> "密度P20\n(t/m³)"
        }
        setText(document, "A6", densityLabel)
        setNumber(document, "B6", plain(result.input.densityInput))
        setFormula(
            document,
            "F6",
            "1+(H23-20)*(0.000012*3)",
            plain(result.steelExpansionFactor)
        )
        setText(document, "H6", "修正密度\n(t/m³)")
        val densityFormula = when (result.input.densityUnit) {
            DensityUnit.KG_PER_M3 -> "B6/1000-0.0011"
            DensityUnit.TON_PER_M3 -> "B6-0.0011"
        }
        setFormula(document, "K6", densityFormula, plain(result.correctedDensityTPerM3))

        setText(document, "L8", "总体积\n(m³)")
        for (index in 0 until 12) {
            val row = 10 + index
            clear(document, "G$row")
            clear(document, "I$row")
            clear(document, "J$row")
            val tank = tankRows.getOrNull(index)
            if (tank == null) {
                clear(document, "A$row")
                clear(document, "E$row")
                clear(document, "H$row")
                clear(document, "K$row")
                clear(document, "L$row")
            } else {
                setText(document, "A$row", tank.tankName)
                if (tank.included) {
                    setNumber(document, "E$row", requireNotNull(tank.ullageMm).toString())
                    setNumber(document, "H$row", plain(requireNotNull(tank.middleTemperatureC)))
                    setNumber(document, "K$row", plain(tank.middleTemperatureC))
                    setNumber(
                        document,
                        "L$row",
                        plain(requireNotNull(tank.gaugeVolumeM3).setScale(3, RoundingMode.HALF_UP))
                    )
                } else {
                    clear(document, "E$row")
                    clear(document, "H$row")
                    clear(document, "K$row")
                    clear(document, "L$row")
                }
            }
        }

        setNumber(document, "L22", plain(result.input.pipelineVolumeM3))
        clear(document, "F23")
        setFormula(document, "H23", "AVERAGE(H10:H21)", plain(result.averageOilTemperatureC))
        setFormula(document, "L23", "SUM(L10:L22)", plain(result.totalGaugeVolumeM3))

        setText(document, "A24", "体积修正\n系数VCF")
        setNumber(document, "B24", plain(result.input.vcf))
        setText(document, "D24", "无钢膨总标准体积\n(m³)")
        setFormula(
            document,
            "F24",
            "(L23-H5)*B24-L5",
            plain(result.standardVolumeWithoutSteelM3)
        )
        setText(document, "H24", "无钢净表观质量\n(t)")
        setFormula(document, "K24", "F24*K6", plain(result.netApparentMassWithoutSteelT))

        setText(document, "A25", "商检量\n(t)")
        setNumber(document, "B25", plain(result.input.surveyQuantityT))
        setText(document, "D25", "有钢膨总标准体积\n(m³)")
        setFormula(document, "F25", "F24*F6", plain(result.standardVolumeWithSteelM3))
        setText(document, "H25", "有钢净表观质量\n(t)")
        setFormula(document, "K25", "F25*K6", plain(result.netApparentMassWithSteelT))

        replaceRow26Merges(document)
        setText(document, "A26", "无钢膨量差 (t)", style = "136")
        setFormula(
            document,
            "D26",
            "K24-B25",
            plain(result.differenceWithoutSteelT),
            style = "135"
        )
        setText(document, "G26", "有钢膨量差 (t)", style = "136")
        setFormula(
            document,
            "J26",
            "K25-B25",
            plain(result.differenceWithSteelT),
            style = "135"
        )
        styleAndClear(document, listOf("B26", "C26"), "136")
        styleAndClear(document, listOf("E26", "F26"), "135")
        styleAndClear(document, listOf("H26", "I26"), "136")
        styleAndClear(document, listOf("K26", "L26"), "135")

        return serialize(document)
    }

    private fun replaceRow26Merges(document: Document) {
        val worksheet = document.documentElement
        val merges = (document.getElementsByTagNameNS(MAIN_NS, "mergeCells").item(0) as? Element)
            ?: document.createElementNS(MAIN_NS, "mergeCells").also { worksheet.appendChild(it) }
        val children = merges.childNodes
        for (i in children.length - 1 downTo 0) {
            val node = children.item(i)
            if (node is Element && node.localName == "mergeCell" && node.getAttribute("ref") == "A26:L26") {
                merges.removeChild(node)
            }
        }
        listOf("A26:C26", "D26:F26", "G26:I26", "J26:L26").forEach { ref ->
            val exists = (0 until merges.childNodes.length).any { i ->
                val node = merges.childNodes.item(i)
                node is Element && node.localName == "mergeCell" && node.getAttribute("ref") == ref
            }
            if (!exists) merges.appendChild(document.createElementNS(MAIN_NS, "mergeCell").apply {
                setAttribute("ref", ref)
            })
        }
        merges.setAttribute(
            "count",
            (0 until merges.childNodes.length).count { merges.childNodes.item(it) is Element }.toString()
        )
    }

    private fun styleAndClear(document: Document, refs: List<String>, style: String) {
        refs.forEach { ref ->
            cell(document, ref).setAttribute("s", style)
            clear(document, ref)
        }
    }

    private fun setText(document: Document, ref: String, value: String, style: String? = null) {
        val cell = cell(document, ref)
        style?.let { cell.setAttribute("s", it) }
        removeValueChildren(cell)
        cell.setAttribute("t", "inlineStr")
        val inline = document.createElementNS(MAIN_NS, "is")
        val text = document.createElementNS(MAIN_NS, "t")
        text.setAttributeNS(XML_NS, "xml:space", "preserve")
        text.textContent = value
        inline.appendChild(text)
        cell.appendChild(inline)
    }


    private fun setFormula(
        document: Document,
        ref: String,
        formula: String,
        cachedValue: String,
        style: String? = null
    ) {
        val cell = cell(document, ref)
        style?.let { cell.setAttribute("s", it) }
        removeValueChildren(cell)
        cell.removeAttribute("t")
        cell.appendChild(document.createElementNS(MAIN_NS, "f").apply { textContent = formula })
        cell.appendChild(document.createElementNS(MAIN_NS, "v").apply { textContent = cachedValue })
    }

    private fun setNumber(document: Document, ref: String, value: String, style: String? = null) {
        val cell = cell(document, ref)
        style?.let { cell.setAttribute("s", it) }
        removeValueChildren(cell)
        cell.removeAttribute("t")
        cell.appendChild(document.createElementNS(MAIN_NS, "v").apply { textContent = value })
    }

    private fun clear(document: Document, ref: String) {
        val cell = cell(document, ref)
        removeValueChildren(cell)
        cell.removeAttribute("t")
    }

    private fun removeValueChildren(cell: Element) {
        val children = cell.childNodes
        for (i in children.length - 1 downTo 0) {
            val node = children.item(i)
            if (node is Element && node.localName in setOf("f", "v", "is")) {
                cell.removeChild(node)
            }
        }
    }

    private fun cell(document: Document, ref: String): Element {
        val cells = document.getElementsByTagNameNS(MAIN_NS, "c")
        for (i in 0 until cells.length) {
            val candidate = cells.item(i) as Element
            if (candidate.getAttribute("r") == ref) return candidate
        }
        val rowNumber = ref.dropWhile { it.isLetter() }.toInt()
        val rows = document.getElementsByTagNameNS(MAIN_NS, "row")
        var row: Element? = null
        for (i in 0 until rows.length) {
            val candidate = rows.item(i) as Element
            if (candidate.getAttribute("r") == rowNumber.toString()) {
                row = candidate
                break
            }
        }
        val targetRow = row ?: document.createElementNS(MAIN_NS, "row").apply {
            setAttribute("r", rowNumber.toString())
            val sheetData = document.getElementsByTagNameNS(MAIN_NS, "sheetData").item(0) as Element
            sheetData.appendChild(this)
        }
        return document.createElementNS(MAIN_NS, "c").apply {
            setAttribute("r", ref)
            targetRow.appendChild(this)
        }
    }

    private fun parse(bytes: ByteArray): Document = parse(ByteArrayInputStream(bytes))

    private fun parse(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }

        // Android 各系统版本的 XML 解析器并不保证支持全部 Xerces 特性。
        // 支持时继续启用安全项；不支持时跳过，避免读取内置可信模板前崩溃。
        safeSetFeature(
            factory,
            "http://apache.org/xml/features/disallow-doctype-decl",
            true
        )
        safeSetFeature(
            factory,
            "http://xml.org/sax/features/external-general-entities",
            false
        )
        safeSetFeature(
            factory,
            "http://xml.org/sax/features/external-parameter-entities",
            false
        )
        safeSetFeature(
            factory,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
            false
        )
        runCatching {
            factory.isXIncludeAware = false
        }
        runCatching {
            factory.isExpandEntityReferences = false
        }

        return factory.newDocumentBuilder().parse(input)
    }

    private fun safeSetFeature(
        factory: DocumentBuilderFactory,
        feature: String,
        enabled: Boolean
    ) {
        runCatching {
            factory.setFeature(feature, enabled)
        }
    }

    private fun serialize(document: Document): ByteArray {
        val output = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(document), StreamResult(output))
        return output.toByteArray()
    }

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
}
