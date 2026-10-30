package app.tcddalarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {

    /** 6 sıra × (2+2) koltuk, adım 2, koridor y=2→6; çift sıralar rotation 0, tek sıralar 180 (masalar). */
    private fun wagonJson(trainCarOnSale: Boolean = true, restrictFirst: String? = null, saleableCorridor: Boolean = false): String {
        val seats = StringBuilder()
        for (r in 0 until 6) {
            for ((i, y) in listOf(0, 2, 6, 8).withIndex()) {
                val no = r * 4 + i + 1
                val rot = if (r % 2 == 0) 0 else 180
                val restrict = if (no == 1 && restrictFirst != null) "\"$restrictFirst\"" else "null"
                seats.append(
                    """{"seatNumber":"$no","positionX":${r * 2},"positionY":$y,"rotation":$rot,"isAllocated":false,""" +
                        """"genderRestriction":$restrict,"item":{"saleable":true,"itemType":{"name":"SEAT"}}},"""
                )
            }
        }
        seats.append("""{"seatNumber":"","positionX":0,"positionY":4,"item":{"saleable":$saleableCorridor}}""")
        return """
            {"data":{"seatMaps":[{
              "trainCarId":7,"trainCarIndex":3,"trainCarOnSale":$trainCarOnSale,
              "seatMapTemplate":{"car":{"name":"EKONOMİ"},"seatMaps":[$seats]},
              "allocationSeats":[{"seatNumber":"2","genders":["MALE"]}]
            }]}}
        """.trimIndent()
    }

    private fun parse(json: String, fr: Int? = null) = SeatMapParser.parse(JSONObject(json), fr)

    @Test
    fun parsesRealSchemaLayout() {
        val sm = parse(wagonJson())
        val by = sm.associateBy { it.no }
        assertEquals(24, sm.size)
        assertEquals("3", by.getValue("1").wagon)
        assertTrue(by.getValue("1").free)
        assertEquals(false, by.getValue("2").free)
        assertEquals("E", by.getValue("2").gender)
        // aynı blok: 1-2 ve 3-4; koridor bloğu böler
        assertEquals(by.getValue("1").blk, by.getValue("2").blk)
        assertNotEquals(by.getValue("1").blk, by.getValue("3").blk)
        // masa: karşılıklı sıralar aynı grupta, komşu masa farklı grupta
        assertNotNull(by.getValue("1").group)
        assertEquals(by.getValue("1").group, by.getValue("5").group)
        assertEquals(by.getValue("3").group, by.getValue("7").group)
        assertNotEquals(by.getValue("1").group, by.getValue("3").group)
        assertEquals(Kind.ECONOMY, by.getValue("1").kind)
    }

    @Test
    fun genderRuleUsesAllocationSeats() {
        val sm = parse(wagonJson())
        val p = Planner.makePlan(sm, Prefs(listOf("K"), setOf(Kind.ECONOMY)))!!
        // 1 numaralı koltuğun yanındaki 2'de erkek var → kadına 1 verilmez
        assertTrue("3-1" !in p.ids)
    }

    @Test
    fun facingPlanFromRealSchema() {
        val sm = parse(wagonJson())
        val p = Planner.makePlan(sm, Prefs(listOf("E", "E"), setOf(Kind.ECONOMY), facing = true))!!
        assertTrue(p.unmet.isEmpty())
        assertEquals(1, p.seats.map { it.group }.toSet().size)
        assertEquals(2, p.seats.map { it.rot }.toSet().size)
    }

    @Test
    fun directionNeedsCalibration() {
        val none = parse(wagonJson(), null)
        val p = Planner.makePlan(none, Prefs(listOf("E"), setOf(Kind.ECONOMY), direction = "F"))!!
        assertTrue(p.notes.any { it.contains("yön") })
        val cal = parse(wagonJson(), 180).associateBy { it.no }
        assertEquals("F", cal.getValue("5").direction)
        assertEquals("B", cal.getValue("1").direction)
    }

    @Test
    fun seatRestrictionAndOffSaleWagon() {
        val r = parse(wagonJson(restrictFirst = "FEMALE")).associateBy { it.no }
        assertEquals("K", r.getValue("1").restrict)
        assertTrue(parse(wagonJson(trainCarOnSale = false)).none { it.free })
    }

    @Test
    fun unknownSchemaGivesEmptyList() {
        assertTrue(parse("""{"foo":[1,2,3]}""").isEmpty())
    }

    @Test
    fun parsesStationsWithPairs() {
        val st = Responses.parseStations(
            parseJson(
                """[{"id":98,"name":"ANKARA GAR","ticketSaleActive":true,"pairs":[48,{"id":60}]},
                    {"id":5,"name":"KAPALI","ticketSaleActive":false},
                    {"id":48,"name":"İSTANBUL(PENDİK)","pairs":["98"]}]"""
            )
        )
        assertEquals(2, st.size)
        assertEquals("ANKARA GAR", st[0].name)
        assertEquals(listOf(48, 60), st[0].pairs)
        assertEquals(listOf(98), st[1].pairs)
    }

    @Test
    fun parsesTrainsAndFiltersCabinKinds() {
        val json = """
        {"trainLegs":[{"trainAvailabilities":[{"trains":[{
          "id":172246,"name":"81001","commercialName":"YHT ANKARA - İSTANBUL",
          "departureStationId":98,"arrivalStationId":48,
          "trainSegments":[
            {"departureTime":1793300000000,"segment":{"departureStation":{"id":77}}},
            {"departureTime":1793350800000,"segment":{"departureStation":{"id":98}}}],
          "cabinClassAvailabilities":[
            {"cabinClass":{"name":"EKONOMİ"},"availabilityCount":7},
            {"cabinClass":{"name":"BUSİNESS"},"availabilityCount":3},
            {"cabinClass":{"name":"TEKERLEKLİ SANDALYE"},"availabilityCount":2}]
        }]}]}]}
        """
        val eco = Responses.parseTrains(parseJson(json), setOf(Kind.ECONOMY))
        assertEquals(1, eco.size)
        assertEquals("YHT ANKARA - İSTANBUL", eco[0].name)
        assertEquals(7, eco[0].seats)
        assertEquals(172246, eco[0].id)
        // biniş istasyonu (98) segmentinin saati seçilir, ilk segmentinki değil
        assertEquals(1793350800000L, eco[0].departure!!.toInstant().toEpochMilli())
        val allKinds = setOf(Kind.ECONOMY, Kind.BUSINESS, Kind.WHEELCHAIR)
        val all = Responses.parseTrains(parseJson(json), allKinds)
        assertEquals(12, all[0].seats)
        val wheel = Responses.parseTrains(parseJson(json), setOf(Kind.WHEELCHAIR))
        assertEquals(2, wheel[0].seats)
        val unknown = Responses.parseTrains(parseJson("""{"x":1}"""), setOf(Kind.ECONOMY))
        assertNull(unknown.firstOrNull())
    }

    @Test
    fun dateParamIsPreviousDay2100() {
        assertEquals("14-11-2026 21:00:00", Responses.dateParam("2026-11-15"))
        assertEquals("31-12-2026 21:00:00", Responses.dateParam("2027-01-01"))
    }

    @Test
    fun configRoundTrip() {
        val c = Config(
            98, "ANKARA GAR", 48, "İSTANBUL(PENDİK)", listOf("2026-11-15", "2026-11-16"), "08:00", "14:30",
            listOf("K", "E"), setOf(Kind.ECONOMY, Kind.WHEELCHAIR), true, false, "F", true, 120, 180,
            TcddApi.DEFAULT_BASE
        )
        val back = Config.fromJson(JSONObject(c.toJson().toString()))
        assertEquals(c, back)
        val none = Config.fromJson(JSONObject(c.copy(forwardRotation = null).toJson().toString()))
        assertNull(none.forwardRotation)
    }
}
