package app.tcddalarm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Sunucu isteği reddetti (401/403/429). Token bitmiş ya da erişim engellenmiş olabilir. */
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
        route.put("departureDate", Responses.dateParam(day))
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
        return Responses.parseTrains(parseJson(text), cfg.kinds)
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
