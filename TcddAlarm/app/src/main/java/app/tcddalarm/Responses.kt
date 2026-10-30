package app.tcddalarm

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * e-bilet JSON cevaplarının okunması.
 *
 * HTTP istemcisinden ayrı durur; birim testler ağa ihtiyaç duymaz.
 */
object Responses {
    private val DATE_PARAM: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")
    private val SEGMENT_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")

    /** Yerel gece yarısının UTC karşılığı: önceki gün 21:00. */
    fun dateParam(day: String): String =
        LocalDate.parse(day).minusDays(1).atTime(21, 0).format(DATE_PARAM)

    fun parseStations(root: Any?): List<Station> {
        val rows = stationRows(root) ?: return emptyList()
        val stations = ArrayList<Station>()
        for (row in rows.objects()) {
            if (row.opt("ticketSaleActive") == false) continue
            val id = row.dbl("id")?.toInt() ?: row.dbl("stationId")?.toInt() ?: continue
            val name = row.str("name") ?: row.str("stationName") ?: continue
            stations.add(Station(id, name, pairIds(row.optJSONArray("pairs"))))
        }
        return stations.sortedBy { trNorm(it.name) }
    }

    fun parseTrains(root: Any?, kinds: Set<Kind>): List<Train> {
        val lists = ArrayList<JSONArray>()
        walkObjects(root) { obj ->
            val trains = obj.optJSONArray("trains")
            if (trains != null) lists.add(trains)
        }
        val out = ArrayList<Train>()
        for (list in lists) {
            for (train in list.objects()) out.add(readTrain(train, kinds))
        }
        return out
    }

    private fun stationRows(root: Any?): JSONArray? {
        if (root is JSONArray) return root
        if (root !is JSONObject) return null
        for (key in listOf("stations", "data", "result", "content", "items")) {
            val rows = root.optJSONArray(key)
            if (rows != null) return rows
        }
        return null
    }

    private fun pairIds(pairs: JSONArray?): List<Int> {
        if (pairs == null) return emptyList()
        val ids = ArrayList<Int>()
        for (index in 0 until pairs.length()) {
            val id = when (val pair = pairs.opt(index)) {
                is Number -> pair.toInt()
                is JSONObject -> pair.dbl("id")?.toInt()
                is String -> pair.toIntOrNull()
                else -> null
            }
            if (id != null) ids.add(id)
        }
        return ids
    }

    private fun readTrain(train: JSONObject, kinds: Set<Kind>): Train {
        val departureStationId = train.dbl("departureStationId")?.toInt()
        val arrivalStationId = train.dbl("arrivalStationId")?.toInt()
        val departure = boardingTime(train, departureStationId)
        val cabins = cabins(train, kinds)
        return Train(
            id = train.dbl("id")?.toInt() ?: train.dbl("trainId")?.toInt(),
            name = commercialName(train),
            departure = departure,
            seats = cabins.values.sum(),
            cabins = cabins,
            departureStationId = departureStationId,
            arrivalStationId = arrivalStationId,
        )
    }

    private fun boardingTime(train: JSONObject, departureStationId: Int?): ZonedDateTime? {
        var exact: ZonedDateTime? = null
        var first: ZonedDateTime? = null
        walkObjects(train) { obj ->
            if (!obj.has("departureTime")) return@walkObjects
            val moment = timeOf(obj.opt("departureTime")) ?: return@walkObjects
            if (first == null) first = moment
            val stationId = obj.obj("segment")?.obj("departureStation")?.dbl("id")?.toInt()
            if (exact == null && stationId != null && departureStationId != null &&
                stationId == departureStationId
            ) {
                exact = moment
            }
        }
        return exact ?: first
    }

    private fun cabins(train: JSONObject, kinds: Set<Kind>): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        walkObjects(train) { obj ->
            val rows = obj.optJSONArray("cabinClassAvailabilities") ?: return@walkObjects
            for (cabin in rows.objects()) {
                val name = cabin.obj("cabinClass")?.str("name") ?: cabin.str("name") ?: "?"
                val count = cabin.dbl("availabilityCount")?.toInt() ?: 0
                if (kindOf(name) in kinds) counts[name] = (counts[name] ?: 0) + count
            }
        }
        return counts
    }

    private fun commercialName(train: JSONObject): String {
        var nested: String? = null
        walkObjects(train) { obj ->
            if (nested == null) nested = obj.str("commercialName")
        }
        return train.str("commercialName") ?: train.str("name") ?: nested ?: "Tren"
    }

    private fun timeOf(value: Any?): ZonedDateTime? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
        if (number != null) {
            val millis = if (number > 1e11) number.toLong() else (number * 1000).toLong()
            return Instant.ofEpochMilli(millis).atZone(TR_ZONE)
        }
        if (value !is String) return null
        return parseZoned(value) ?: parseLocal(value)
    }

    private fun parseZoned(value: String): ZonedDateTime? =
        try {
            ZonedDateTime.parse(value)
        } catch (ignored: Exception) {
            null
        }

    private fun parseLocal(value: String): ZonedDateTime? =
        try {
            LocalDateTime.parse(value, SEGMENT_TIME).atZone(TR_ZONE)
        } catch (ignored: Exception) {
            null
        }
}
