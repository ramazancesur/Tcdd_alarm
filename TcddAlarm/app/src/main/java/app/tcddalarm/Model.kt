package app.tcddalarm

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

data class Station(val id: Int, val name: String, val pairs: List<Int>)

/** Koltuk türü: ekranda ayrı ayrı seçilebilir. */
enum class Kind(val label: String) {
    ECONOMY("Ekonomi"),
    BUSINESS("Business"),
    WHEELCHAIR("Tekerlekli sandalye")
}

/** Vagon / kabin / koltuk adından koltuk türünü çıkarır. */
fun kindOf(vararg texts: String?): Kind {
    val t = texts.filterNotNull().joinToString(" ") { trNorm(it) }
    return when {
        t.contains("tekerlek") || t.contains("wheelchair") -> Kind.WHEELCHAIR
        t.contains("busines") -> Kind.BUSINESS
        else -> Kind.ECONOMY
    }
}

/** Planlayıcının kullandığı tercihler. */
data class Prefs(
    val genders: List<String>,          // her yolcu için "K" veya "E"
    val kinds: Set<Kind>,               // izin verilen koltuk türleri
    val sideBySide: Boolean = false,    // 2 kişi: yan yana, 3+ kişi: aynı sıra
    val facing: Boolean = false,        // karşılıklı koltuk
    val direction: String = "A",        // "F" ileri, "B" geri, "A" fark etmez
    val strict: Boolean = false         // true: tercih karşılanmıyorsa öneri yapma
)

data class Seat(
    val wagon: String,
    val no: String,
    val row: Int?,
    val pos: Int?,               // sıradaki sütun (0'dan başlar)
    val free: Boolean,
    val gender: String?,         // dolu koltuktaki yolcunun cinsiyeti: "K" | "E" | null
    val direction: String?,      // "F" ileri | "B" geri | null
    val group: String?,          // karşılıklı (masa) grubu
    val kind: Kind,
    val rotation: Int? = null,   // koltuğun dönüş açısı (0/180)
    val block: String? = null,   // yan yana oturan kesintisiz koltuk bloğu
    val restrict: String? = null // koltuğa özel cinsiyet kısıtı: "K" | "E" | null
) {
    val seatId: String get() = "$wagon-$no"
    val num: Int? get() = no.filter { it.isDigit() }.toIntOrNull()
}

data class Plan(
    val seats: List<Seat>,
    val genders: List<String>,
    val unmet: List<String>,     // karşılanamayan tercihler
    val notes: List<String>      // veri eksikliği uyarıları
) {
    val ids: List<String> get() = seats.map { it.seatId }
}

data class Train(
    val id: Int?,
    val name: String,
    val departure: ZonedDateTime?,
    val seats: Int,                 // seçili koltuk türlerindeki toplam boş koltuk
    val cabins: Map<String, Int>,   // kabin adı → boş koltuk (seçili türler)
    val departureStationId: Int?,
    val arrivalStationId: Int?
) {
    fun departureClock(): String =
        departure?.withZoneSameInstant(TR_ZONE)?.format(HHMM) ?: "?"
}

data class Config(
    val originId: Int,
    val originName: String,
    val destinationId: Int,
    val destinationName: String,
    val dates: List<String>,        // yyyy-MM-dd
    val timeFrom: String,           // HH:mm
    val timeTo: String,             // HH:mm
    val genders: List<String>,
    val kinds: Set<Kind>,
    val sideBySide: Boolean,
    val facing: Boolean,
    val direction: String,
    val strict: Boolean,
    val intervalSec: Int,
    val forwardRotation: Int?,      // trenin gidiş yönüne bakan koltuğun rotation değeri (0/180) ya da null
    val baseUrl: String
) {
    fun prefs(): Prefs = Prefs(genders, kinds, sideBySide, facing, direction, strict)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("originId", originId)
        o.put("originName", originName)
        o.put("destinationId", destinationId)
        o.put("destinationName", destinationName)
        o.put("dates", JSONArray(dates))
        o.put("timeFrom", timeFrom)
        o.put("timeTo", timeTo)
        o.put("genders", JSONArray(genders))
        o.put("kinds", JSONArray(kinds.map { it.name }))
        o.put("sideBySide", sideBySide)
        o.put("facing", facing)
        o.put("direction", direction)
        o.put("strict", strict)
        o.put("intervalSec", intervalSec)
        o.put("forwardRotation", forwardRotation ?: -1)
        o.put("baseUrl", baseUrl)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Config {
            fun strings(k: String): List<String> {
                val a = o.optJSONArray(k) ?: return emptyList()
                val out = ArrayList<String>()
                for (i in 0 until a.length()) {
                    val v = a.opt(i)
                    if (v != null) out.add(v.toString())
                }
                return out
            }
            val kinds = strings("kinds")
                .mapNotNull { name -> Kind.entries.firstOrNull { it.name == name } }
                .toSet()
            return Config(
                originId = o.dbl("originId")?.toInt() ?: 0,
                originName = o.str("originName") ?: "",
                destinationId = o.dbl("destinationId")?.toInt() ?: 0,
                destinationName = o.str("destinationName") ?: "",
                dates = strings("dates"),
                timeFrom = o.str("timeFrom") ?: "00:00",
                timeTo = o.str("timeTo") ?: "23:59",
                genders = strings("genders").ifEmpty { listOf("K") },
                kinds = kinds.ifEmpty { setOf(Kind.ECONOMY, Kind.BUSINESS) },
                sideBySide = o.optBoolean("sideBySide", false),
                facing = o.optBoolean("facing", false),
                direction = o.str("direction") ?: "A",
                strict = o.optBoolean("strict", false),
                intervalSec = o.dbl("intervalSec")?.toInt() ?: 180,
                forwardRotation = o.dbl("forwardRotation")?.toInt()?.takeIf { it >= 0 },
                baseUrl = o.str("baseUrl") ?: TcddApi.DEFAULT_BASE
            )
        }
    }
}
