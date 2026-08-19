import com.oilterminal.tankcalc.calc.CalculationOutcome
import com.oilterminal.tankcalc.calc.CapacityCalculator
import com.oilterminal.tankcalc.data.CapacityPoint
import com.oilterminal.tankcalc.data.PointBracket

fun main() {
    val lower = CapacityPoint(
        id = 1, tankId = 1, ullageMm = 1650, volumeM3 = "285.620",
        sourceSheet = "Sheet1", ullageCell = "C10", volumeCell = "D10",
        rawUllage = "1.650", rawVolume = "285.620", confidence = 1.0
    )
    val upper = CapacityPoint(
        id = 2, tankId = 1, ullageMm = 1660, volumeM3 = "286.443",
        sourceSheet = "Sheet1", ullageCell = "C11", volumeCell = "D11",
        rawUllage = "1.660", rawVolume = "286.443", confidence = 1.0
    )
    val result = CapacityCalculator.calculate(
        1653,
        PointBracket(null, lower, upper, 100, 3000)
    )
    check(result is CalculationOutcome.Success)
    check(result.roundedVolumeM3.toPlainString() == "285.867")
    println("calculator=PASS result=${result.roundedVolumeM3}")
}
