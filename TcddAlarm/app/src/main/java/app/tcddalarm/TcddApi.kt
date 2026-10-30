package app.tcddalarm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Sunucu isteği reddetti (401/403/429): token süresi dolmuş ya da otomatik erişim engelleniyor olabilir. */
class BlockedException(message: String) : RuntimeException(message)

/**
 * TCDD e-bilet istemcisi. Yol adları ve istek/cevap alanları e-bilet mobil uygulamasının APK'sından alındı;
 * sunucuya karşı gerçek bir istekle doğrulanmadı (bkz. README).
 */
class TcddApi(
    private val baseUrl: String,
    private val cdnUrl: String,
    private val token: String
) {
    companion object {
        const val DEFAULT_BASE = "https://web-api-prod-ytp.tcddtasimacilik.gov.tr/tms"
        const val DEFAULT_CDN = "https://cdn-api-prod-ytp.tcddtasimacilik.gov.tr"
        const val SITE = "https://ebilet.tcddtasimacilik.gov.tr/"
        private const val UNIT_ID = "3895"

        /**
         * departureDate biçimi: yerel gece yarısını UTC olarak ifade eder → "bir gün önce 21:00:00".
         * (Kamuya açık bir istemcinin dokümantasyonuna göre; doğrulanmadı.)
         */
        fun dateParam(day: String): String =
            LocalDate.parse(day).minusDays(1).atTime(21, 0)
                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"))

        fun parseStations(root: Any?): List<Station> {
            var arr: JSONArray? = null
            if (root is JSONArray) {
                arr = root
            } else if (root is JSONObject) {
                for (k in listOf("stations", "data", "result", "content", "items")) {
                    val a = root.optJSONArray(k)
                    if (a != null) {
                        arr = a
                        break
                    }
                }
            }
            val out = ArrayList<Station>()
            for (s in arr.objects()) {
                if (s.opt("ticketSaleActive") == false) continue
                val id = s.dbl("id")?.toInt() ?: s.dbl("stationId")?.toInt() ?: continue
                val name = s.str("name") ?: s.str("stationName") ?: continue
                val pairs = ArrayList<Int>()
                val pa = s.optJSONArray("pairs")
                if (pa != null) {
                    for (i in 0 until pa.length()) {
                        val p = pa.opt(i)
                        val pid: Int? = when (p) {
                            is Number -> p.toInt()
                            is JSONObject -> p.dbl("id")?.toInt()
                            is String -> p.toIntOrNull()
                            else -> null
                        }
                        if (pid != null) pairs.add(pid)
                    }
                }
                out.add(Station(id, name, pairs))
            }
            return out.sortedBy { trNorm(it.name) }
        }

        private fun timeOf(v: Any?): ZonedDateTime? {
            val n: Double? = when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (n != null) {
                val ms = if (n > 1e11) n.toLong() else (n * 1000).toLong()
                return Instant.ofEpochMilli(ms).atZone(TR_ZONE)
            }
            if (v is String) {
                try {
                    return ZonedDateTime.parse(v)
                } catch (e: Exception) {
                    // sıradaki biçimi dene
                }
                try {
                    return java.time.LocalDateTime.parse(v, DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")).atZone(TR_ZONE)
                } catch (e: Exception) {
                    // tanınmayan biçim
                }
            }
            return null
        }

        /** Sefer cevabından, seçili koltuk türleri için boş yer sayılarıyla tren listesi çıkarır. */
        fun parseTrains(root: Any?, kinds: Set<Kind>): List<Train> {
            val lists = ArrayList<JSONArray>()
            walkObjects(root) { o ->
                val a = o.optJSONArray("trains")
                if (a != null) lists.add(a)
            }
            val out = ArrayList<Train>()
            for (lst in lists) {
                for (t in lst.objects()) {
                    val fromId = t.dbl("departureStationId")?.toInt()
                    val toId = t.dbl("arrivalStationId")?.toInt()

                    // Kalkış saati: kullanıcının biniş istasyonundan kalkan segment; yoksa ilk bulunan
                    var exact: ZonedDateTime? = null
                    var first: ZonedDateTime? = null
                    walkObjects(t) { o ->
                        if (o.has("departureTime")) {
                            val z = timeOf(o.opt("departureTime"))
                            if (z != null) {
                                if (first == null) first = z
                                val sid = o.obj("segment")?.obj("departureStation")?.dbl("id")?.toInt()
                                if (exact == null && sid != null && fromId != null && sid == fromId) exact = z
                            }
                        }
                    }

                    val cabins = LinkedHashMap<String, Int>()
                    var total = 0
                    walkObjects(t) { o ->
                        val ca = o.optJSONArray("cabinClassAvailabilities")
                        if (ca != null) {
                            for (c in ca.objects()) {
                                val name = c.obj("cabinClass")?.str("name") ?: c.str("name") ?: "?"
                                val cnt = c.dbl("availabilityCount")?.toInt() ?: 0
                                if (kindOf(name) in kinds) {
                                    cabins[name] = (cabins[name] ?: 0) + cnt
                                    total += cnt
                                }
                            }
                        }
                    }

                    var nested: String? = null
                    walkObjects(t) { o ->
                        if (nested == null) nested = o.str("commercialName")
                    }
                    val name = t.str("commercialName") ?: t.str("name") ?: nested ?: "Tren"
                    val id = t.dbl("id")?.toInt() ?: t.dbl("trainId")?.toInt()
                    out.add(Train(id, name, exact ?: first, total, cabins, fromId, toId))
                }
            }
            return out
        }
    }

    // --- HTTP -----------------------------------------------------------------

    private fun request(method: String, url: String, body: String?, withAuth: Boolean): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 45000
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("Accept-Language", "tr")
            c.setRequestProperty("Origin", "https://ebilet.tcddtasimacilik.gov.tr")
            c.setRequestProperty("Referer", SITE)
            c.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            c.setRequestProperty("unit-id", UNIT_ID)
            if (withAuth && token.isNotBlank()) c.setRequestProperty("Authorization", token.trim())
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code == 401 || code == 403 || code == 429) throw BlockedException("HTTP $code")
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    /** İstasyon listesinin ham JSON metni. CDN dosyası genelde herkese açıktır; reddederse token ile dener. */
    fun fetchStationsRaw(): String {
        val url = "$cdnUrl/datas/station-pairs-MOBIL.json"
        return try {
            request("GET", url, null, false)
        } catch (e: BlockedException) {
            request("GET", url, null, true)
        }
    }

    fun trains(cfg: Config, day: String): List<Train> {
        val route = JSONObject()
        route.put("departureStationId", cfg.fromId)
        route.put("arrivalStationId", cfg.toId)
        route.put("departureDate", dateParam(day))
        val body = JSONObject()
        body.put("searchRoutes", JSONArray().put(route))
        body.put("passengerTypeCounts", JSONArray().put(JSONObject().put("id", 0).put("count", cfg.genders.size)))
        body.put("blTrainTypes", JSONArray().put("TURISTIK_TREN"))
        if (baseUrl.contains("web-api")) {
            // web istemcisinin gönderdiği ek alanlar
            route.put("departureStationName", cfg.fromName)
            route.put("arrivalStationName", cfg.toName)
            body.put("searchReservation", false)
            body.put("searchType", "DOMESTIC")
        }
        val text = request("POST", "$baseUrl/train/train-availability", body.toString(), true)
        return parseTrains(parseJson(text), cfg.kinds)
    }

    fun seatMap(cfg: Config, train: Train): List<Seat> {
        val id = train.id ?: return emptyList()
        val body = JSONObject()
        body.put("fromStationId", train.fromId ?: cfg.fromId)
        body.put("toStationId", train.toId ?: cfg.toId)
        body.put("trainId", id)
        val text = request("POST", "$baseUrl/seat-maps/load-by-train-id", body.toString(), true)
        return SeatMapParser.parse(parseJson(text), cfg.forwardRotation)
    }
}
