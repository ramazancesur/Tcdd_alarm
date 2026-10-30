package app.tcddalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {

    private fun seat(
        number: Int,
        row: Int,
        position: Int,
        free: Boolean = true,
        gender: String? = null,
        wagon: String = "1",
        kind: Kind = Kind.ECONOMY,
        rotation: Int? = null,
        group: String? = null,
        direction: String? = null,
        block: String? = null,
        restrict: String? = null,
    ) = Seat(
        wagon,
        number.toString(),
        row,
        position,
        free,
        gender,
        direction,
        group,
        kind,
        rotation,
        block,
        restrict,
    )

    private fun prefs(
        vararg g: String,
        kinds: Set<Kind> = setOf(Kind.ECONOMY, Kind.BUSINESS),
        side: Boolean = false,
        facing: Boolean = false,
        strict: Boolean = false
    ) = Prefs(g.toList(), kinds, side, facing, "A", strict)

    @Test
    fun genderRuleBlocksSeatNextToOppositeGender() {
        val seats = listOf(
            seat(1, 0, 0), seat(2, 0, 1, free = false, gender = "E"), seat(3, 0, 2), seat(4, 0, 3)
        )
        assertEquals(listOf("1-3"), Planner.makePlan(seats, prefs("K"))!!.ids)
        assertEquals(listOf("1-1"), Planner.makePlan(seats, prefs("E"))!!.ids)
    }

    @Test
    fun sideBySideTwoPassengers() {
        val seats = listOf(
            seat(1, 0, 0), seat(2, 0, 1, free = false, gender = "K"), seat(3, 0, 2), seat(4, 0, 3),
            seat(5, 1, 0), seat(6, 1, 1)
        )
        val p = Planner.makePlan(seats, prefs("K", "E", side = true))!!
        assertEquals(listOf("1-3", "1-4"), p.ids)
        assertTrue(p.unmet.isEmpty())
    }

    @Test
    fun seatKindFilter() {
        val seats = listOf(
            seat(1, 0, 0, kind = Kind.BUSINESS), seat(2, 0, 1, kind = Kind.WHEELCHAIR), seat(3, 0, 2, kind = Kind.ECONOMY)
        )
        assertEquals(listOf("1-3"), Planner.makePlan(seats, prefs("K", kinds = setOf(Kind.ECONOMY)))!!.ids)
        assertEquals(listOf("1-1"), Planner.makePlan(seats, prefs("K", kinds = setOf(Kind.BUSINESS)))!!.ids)
        assertEquals(listOf("1-2"), Planner.makePlan(seats, prefs("K", kinds = setOf(Kind.WHEELCHAIR)))!!.ids)
        assertNull(Planner.makePlan(seats, prefs("K", kinds = emptySet())))
    }

    @Test
    fun strictVsSoftDirection() {
        val seats = listOf(seat(1, 0, 0, rotation = 180, direction = "B"), seat(2, 0, 1, rotation = 180, direction = "B"))
        val strict = Prefs(listOf("K"), setOf(Kind.ECONOMY), direction = "F", strict = true)
        assertNull(Planner.makePlan(seats, strict))
        val soft = Planner.makePlan(seats, strict.copy(strict = false))!!
        assertEquals(listOf("koltuk yönü"), soft.unmet)
    }

    @Test
    fun facingPairHasSameGroupAndOppositeRotation() {
        val seats = listOf(
            seat(1, 0, 0, rotation = 0, group = "g1"), seat(5, 1, 0, rotation = 180, group = "g1"),
            seat(2, 0, 1, rotation = 0, group = "g1"), seat(6, 1, 1, rotation = 180, group = "g1")
        )
        val p = Planner.makePlan(seats, prefs("E", "E", facing = true))!!
        assertTrue(p.unmet.isEmpty())
        assertEquals(2, p.seats.map { it.rotation }.toSet().size)
    }

    @Test
    fun missingFacingDataIsReportedNotIgnored() {
        val seats = listOf(seat(1, 0, 0), seat(2, 0, 1))
        val p = Planner.makePlan(seats, prefs("K", "E", facing = true))!!
        assertTrue(p.notes.any { it.contains("Karşılıklı") })
    }

    @Test
    fun unknownNeighborGenderDoesNotBlock() {
        val seats = listOf(
            seat(1, 0, 0), seat(2, 0, 1, free = false, gender = null), seat(3, 0, 2, free = false, gender = "E")
        )
        assertEquals(listOf("1-1"), Planner.makePlan(seats, prefs("K"))!!.ids)
    }

    @Test
    fun fivePassengersUseTheWholeRow() {
        val seats = (0..5).map { seat(it + 1, 0, it) }
        val p = Planner.makePlan(seats, prefs("K", "K", "E", "E", "K", side = true))!!
        assertEquals(5, p.seats.size)
        assertEquals(1, p.seats.map { it.row }.toSet().size)
        assertTrue(p.unmet.isEmpty())
    }

    @Test
    fun seatRestrictionRespected() {
        val seats = listOf(seat(1, 0, 0, restrict = "K"), seat(2, 0, 2))
        assertEquals(listOf("1-2"), Planner.makePlan(seats, prefs("E"))!!.ids)
        assertEquals(listOf("1-1"), Planner.makePlan(seats, prefs("K"))!!.ids)
    }

    @Test
    fun threePassengersSameRow() {
        val seats = (0..3).map { seat(it + 1, 0, it) } + (0..3).map { seat(it + 5, 1, it) }
        val p = Planner.makePlan(seats, prefs("K", "K", "E", side = true))!!
        assertEquals(1, p.seats.map { it.row }.toSet().size)
        assertFalse(p.unmet.isNotEmpty())
    }

    @Test
    fun trNormHandlesTurkishLetters() {
        assertEquals("istanbul(pendik)", trNorm("İSTANBUL(PENDİK)"))
        assertEquals("istanbul", trNorm("Istanbul"))
        assertEquals("eskisehir", trNorm("ESKİŞEHİR"))
        assertEquals("business", trNorm("BUSİNESS"))
        assertEquals("business", trNorm("BUSINESS"))
    }

    @Test
    fun kindOfClassifiesCabinNames() {
        assertEquals(Kind.BUSINESS, kindOf("BUSİNESS"))
        assertEquals(Kind.ECONOMY, kindOf("EKONOMİ"))
        assertEquals(Kind.WHEELCHAIR, kindOf("TEKERLEKLİ SANDALYE"))
        assertEquals(Kind.ECONOMY, kindOf(null, "x"))
    }
}
