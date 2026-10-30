package app.tcddalarm

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val TR_ZONE: ZoneId = ZoneId.of("Europe/Istanbul")
val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Türkçe harfleri ve büyük/küçük farkını yok sayarak karşılaştırma için normalize eder. */
fun trNorm(s: String): String {
    val t = s.replace("İ", "i").replace("I", "ı").lowercase(Locale.ROOT).replace("ı", "i")
    val d = Normalizer.normalize(t, Normalizer.Form.NFKD)
    val sb = StringBuilder(d.length)
    for (ch in d) {
        if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
    }
    return sb.toString()
}

/** "HH:mm" saat aralığı kontrolü; saat okunamadıysa kaçırmamak için true döner. */
fun inRange(t: ZonedDateTime?, from: String, to: String): Boolean {
    if (t == null) return true
    val hhmm = t.withZoneSameInstant(TR_ZONE).format(HHMM)
    return hhmm >= from && hhmm <= to
}

// --- org.json yardımcıları (Android'in org.json'ı ile JVM test sürümü aynı davranır) ---------

internal fun JSONObject.str(k: String): String? {
    if (!has(k) || isNull(k)) return null
    val s = opt(k)?.toString() ?: return null
    return if (s.isEmpty()) null else s
}

internal fun JSONObject.dbl(k: String): Double? {
    if (!has(k) || isNull(k)) return null
    return when (val v = opt(k)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}

internal fun JSONObject.obj(k: String): JSONObject? = optJSONObject(k)

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i)
        if (o != null) out.add(o)
    }
    return out
}

/** JSON ağacındaki tüm nesneleri (iç içe dahil) sırayla gezer. */
internal fun walkObjects(o: Any?, fn: (JSONObject) -> Unit) {
    when (o) {
        is JSONObject -> {
            fn(o)
            val keys = o.keys()
            while (keys.hasNext()) walkObjects(o.opt(keys.next()), fn)
        }
        is JSONArray -> {
            for (i in 0 until o.length()) walkObjects(o.opt(i), fn)
        }
        else -> {}
    }
}

internal fun parseJson(text: String): Any? {
    val t = text.trim()
    return if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
}
