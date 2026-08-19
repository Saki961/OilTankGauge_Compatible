package com.oilterminal.tankcalc.excel

import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class XlsxSheet(
    val name: String,
    val cells: Map<Int, Map<Int, String>>,
    val maxRow: Int,
    val maxColumn: Int
) {
    fun value(row: Int, column: Int): String? = cells[row]?.get(column)
}

data class XlsxWorkbook(val sheets: List<XlsxSheet>)

object XlsxReader {
    private const val REL_NS =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun read(file: File): XlsxWorkbook {
        ZipFile(file).use { zip ->
            val sharedStrings = readSharedStrings(zip)
            val relationships = readRelationships(zip)
            val workbookEntry = zip.getEntry("xl/workbook.xml")
                ?: error("不是有效的 XLSX 文件：缺少 xl/workbook.xml")
            val workbookDoc = zip.getInputStream(workbookEntry).use(::parseXml)
            val sheetNodes = workbookDoc.getElementsByTagNameNS("*", "sheet")
            val sheets = mutableListOf<XlsxSheet>()

            for (index in 0 until sheetNodes.length) {
                val element = sheetNodes.item(index) as Element
                val name = element.getAttribute("name").ifBlank { "Sheet${index + 1}" }
                val relationshipId = element.getAttributeNS(REL_NS, "id")
                    .ifBlank { element.getAttribute("r:id") }
                val target = relationships[relationshipId] ?: continue
                val normalizedTarget = normalizeZipPath(
                    when {
                        target.startsWith("/") -> target.removePrefix("/")
                        target.startsWith("xl/") -> target
                        else -> "xl/$target"
                    }
                )
                val entry = zip.getEntry(normalizedTarget) ?: continue
                val sheet = zip.getInputStream(entry).use { input ->
                    readSheet(name, input, sharedStrings)
                }
                sheets += sheet
            }
            return XlsxWorkbook(sheets)
        }
    }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val document = zip.getInputStream(entry).use(::parseXml)
        val nodes = document.getElementsByTagNameNS("*", "si")
        return buildList {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                val textNodes = element.getElementsByTagNameNS("*", "t")
                val value = buildString {
                    for (textIndex in 0 until textNodes.length) {
                        append(textNodes.item(textIndex).textContent ?: "")
                    }
                }
                add(value)
            }
        }
    }

    private fun readRelationships(zip: ZipFile): Map<String, String> {
        val entry = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return emptyMap()
        val document = zip.getInputStream(entry).use(::parseXml)
        val nodes = document.getElementsByTagNameNS("*", "Relationship")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                val id = element.getAttribute("Id")
                val target = element.getAttribute("Target")
                if (id.isNotBlank() && target.isNotBlank()) put(id, target)
            }
        }
    }

    private fun readSheet(
        name: String,
        input: InputStream,
        sharedStrings: List<String>
    ): XlsxSheet {
        val document = parseXml(input)
        val nodes = document.getElementsByTagNameNS("*", "c")
        val rows = linkedMapOf<Int, MutableMap<Int, String>>()
        var maxRow = 0
        var maxColumn = 0

        for (index in 0 until nodes.length) {
            val cell = nodes.item(index) as Element
            val reference = cell.getAttribute("r")
            val row = reference.dropWhile { it.isLetter() }.toIntOrNull() ?: continue
            val column = columnIndex(reference.takeWhile { it.isLetter() })
            if (column <= 0) continue

            val type = cell.getAttribute("t")
            val value = when (type) {
                "s" -> {
                    val stringIndex = firstChildText(cell, "v")?.toIntOrNull()
                    if (stringIndex != null) sharedStrings.getOrNull(stringIndex).orEmpty() else ""
                }
                "inlineStr" -> {
                    val texts = cell.getElementsByTagNameNS("*", "t")
                    buildString {
                        for (textIndex in 0 until texts.length) {
                            append(texts.item(textIndex).textContent ?: "")
                        }
                    }
                }
                "b" -> if (firstChildText(cell, "v") == "1") "TRUE" else "FALSE"
                else -> firstChildText(cell, "v")
                    ?: firstChildText(cell, "t")
                    ?: ""
            }

            if (value.isNotEmpty()) {
                rows.getOrPut(row) { linkedMapOf() }[column] = value
                maxRow = maxOf(maxRow, row)
                maxColumn = maxOf(maxColumn, column)
            }
        }

        return XlsxSheet(
            name = name,
            cells = rows,
            maxRow = maxRow,
            maxColumn = maxColumn
        )
    }

    private fun parseXml(input: InputStream) =
        secureFactory().newDocumentBuilder().parse(input)

    private fun secureFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
    }

    private fun firstChildText(parent: Element, localName: String): String? {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        return if (nodes.length > 0) nodes.item(0).textContent else null
    }

    private fun normalizeZipPath(path: String): String {
        val stack = mutableListOf<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack += part
            }
        }
        return stack.joinToString("/")
    }

    private fun columnIndex(letters: String): Int {
        var result = 0
        letters.uppercase().forEach { ch ->
            if (ch !in 'A'..'Z') return 0
            result = result * 26 + (ch - 'A' + 1)
        }
        return result
    }
}
