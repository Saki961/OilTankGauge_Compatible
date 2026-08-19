import com.oilterminal.tankcalc.excel.TankWorkbookParser
import com.oilterminal.tankcalc.excel.XlsxReader
import java.io.File

fun main(args: Array<String>) {
    val file = File(args.firstOrNull() ?: error("请提供 xlsx 路径"))
    val parsed = TankWorkbookParser.parse(XlsxReader.read(file), file.name)
    println("vessels=${parsed.vessels.size}")
    parsed.vessels.forEach { vessel ->
        println("${vessel.name}: ${vessel.tanks.size} tanks")
        vessel.tanks.forEach { tank ->
            println("  ${tank.canonicalName}: ${tank.points.size} points " +
                "${tank.points.minOfOrNull { it.ullageMm }}.." +
                "${tank.points.maxOfOrNull { it.ullageMm }} mm")
        }
    }
    println("warnings=${parsed.warnings.size}")
}
