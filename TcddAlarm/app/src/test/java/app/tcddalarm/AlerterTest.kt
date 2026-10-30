package app.tcddalarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlerterTest {

    private fun seat(no: Int, row: Int, pos: Int, free: Boolean = true) =
        Seat("1", no.toString(), row, pos, free, null, null, null, Kind.ECONOMY)

    private val head = "A → B\n2026-11-15 09:30 — YHT"
    private val key = "2026-11-15|YHT|09:30"
    private fun train(seats: Int) = Train(1, "YHT", null, seats, mapOf("EKONOMİ" to seats), 1, 2)

    private class Sink {
        val sent = ArrayList<Pair<String, String>>()
        val logs = ArrayList<String>()
    }

    private fun alerter(prefs: Prefs, sink: Sink) =
        Alerter(prefs, { s, b -> sink.sent.add(Pair(s, b)) }, { sink.logs.add(it) })

    private val twoPax = Prefs(listOf("K", "E"), setOf(Kind.ECONOMY), sideBySide = true)

    @Test
    fun notifiesOnlyOnceWhileSuggestionStaysFree() {
        val sink = Sink()
        val a = alerter(twoPax, sink)
        val st = AlertState()
        val seats = listOf(seat(1, 0, 0), seat(2, 0, 1), seat(3, 0, 2))
        repeat(3) { a.handle(st, key, head, train(3), seats) }
        assertEquals(1, sink.sent.size)
        assertTrue(sink.sent[0].first.contains("Boş yer var"))
    }

    @Test
    fun suggestsNewSeatsWhenSuggestedOnesAreSold() {
        val sink = Sink()
        val a = alerter(twoPax, sink)
        val st = AlertState()
        val seats = listOf(seat(1, 0, 0), seat(2, 0, 1), seat(3, 0, 2))
        a.handle(st, key, head, train(3), seats)
        val first = st.active.getValue(key)
        val sold = seats.map { if (it.sid == first[0]) it.copy(free = false, gender = "K") else it }
        a.handle(st, key, head, train(2), sold)
        assertEquals(2, sink.sent.size)
        assertTrue(sink.sent[1].first.contains("satıldı"))
        assertFalse(st.active.getValue(key) == first)
        a.handle(st, key, head, train(2), sold) // yeni öneri için tekrar bildirim yok
        assertEquals(2, sink.sent.size)
    }

    @Test
    fun resetsWhenTrainFillsUpThenNotifiesAgainWhenReopened() {
        val sink = Sink()
        val a = alerter(twoPax, sink)
        val st = AlertState()
        val seats = listOf(seat(1, 0, 0), seat(2, 0, 1))
        a.handle(st, key, head, train(2), seats)
        a.handle(st, key, head, train(0), seats.map { it.copy(free = false) })
        assertTrue(st.active.isEmpty())
        a.handle(st, key, head, train(2), seats)
        assertEquals(2, sink.sent.size)
    }

    @Test
    fun unreadableMapDoesNotDropAnActiveSuggestion() {
        val sink = Sink()
        val a = alerter(twoPax, sink)
        val st = AlertState()
        val seats = listOf(seat(1, 0, 0), seat(2, 0, 1))
        a.handle(st, key, head, train(2), seats)
        a.handle(st, key, head, train(2), emptyList())
        assertEquals(1, sink.sent.size)
        assertTrue(st.active.containsKey(key))
    }

    @Test
    fun countOnlyNoticeWhenSeatMapUnreadable() {
        val sink = Sink()
        val a = alerter(twoPax, sink)
        val st = AlertState()
        repeat(3) { a.handle(st, key, head, train(4), emptyList()) }
        assertEquals(1, sink.sent.size)
        assertTrue(sink.sent[0].second.contains("okunamadı"))
    }

    @Test
    fun notEnoughSeatsForPassengersMeansNoAlert() {
        val sink = Sink()
        val a = alerter(twoPax, sink)
        a.handle(AlertState(), key, head, train(1), listOf(seat(1, 0, 0)))
        assertTrue(sink.sent.isEmpty())
    }

    @Test
    fun strictModeStaysSilentWhenPreferencesCannotBeMet() {
        val sink = Sink()
        val strict = twoPax.copy(strict = true)
        val a = alerter(strict, sink)
        // 1 ve 3 numaralı koltuk farklı bloklarda: yan yana olamaz
        a.handle(AlertState(), key, head, train(2), listOf(seat(1, 0, 0), seat(2, 0, 1, free = false), seat(3, 0, 2), seat(4, 0, 3, free = false)))
        assertTrue(sink.sent.isEmpty())
    }

    @Test
    fun stateSurvivesJsonRoundTrip() {
        val st = AlertState()
        st.active[key] = listOf("1-1", "1-2")
        st.countNotified.add("x|y|z")
        val back = AlertState.fromJson(JSONObject(st.toJson().toString()))
        assertEquals(st.active, back.active)
        assertEquals(st.countNotified, back.countNotified)
    }
}
