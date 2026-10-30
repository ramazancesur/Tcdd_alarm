package app.tcddalarm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Koltuk haritası ayrıştırıcı. Alan adları e-bilet mobil uygulamasının APK'sından okundu:
 * SeatMapsResponse.seatMaps[] (vagonlar) → seatMapTemplate.seatMaps[] (koltuk yerleşimi) ve
 * allocationSeats[] (dolu koltuklar, yolcu cinsiyetiyle).
 * Değerlerin anlamı (rotation, genderRestriction vb.) gerçek bir cevapla doğrulanmadı.
 */
object SeatMapParser {

    fun parse(root: Any?, forwardRot: Int?): List<Seat> {
        val wagons = findWagonList(root) ?: return emptyList()
        val fr = forwardRot?.let { ((it % 360) + 360) % 360 }
        val out = ArrayList<Seat>()
        for (w in wagons) out.addAll(parseWagon(w, fr))
        return out
    }

    private fun findWagonList(o: Any?): List<JSONObject>? {
        when (o) {
            is JSONObject -> {
                val lst = o.optJSONArray("seatMaps")
                if (lst != null && lst.length() > 0) {
                    val first = lst.optJSONObject(0)
                    if (first != null && first.has("seatMapTemplate")) return lst.objects()
                }
                val keys = o.keys()
                while (keys.hasNext()) {
                    val r = findWagonList(o.opt(keys.next()))
                    if (r != null) return r
                }
            }
            is JSONArray -> {
                for (i in 0 until o.length()) {
                    val r = findWagonList(o.opt(i))
                    if (r != null) return r
                }
            }
            else -> {}
        }
        return null
    }

    fun normGender(v: String?): String? {
        val s = v?.trim()?.uppercase(java.util.Locale.ROOT)?.replace("İ", "I") ?: return null
        return when (s) {
            "K", "F", "FEMALE", "WOMAN", "KADIN" -> "K"
            "E", "M", "MALE", "MAN", "ERKEK" -> "E"
            else -> null
        }
    }

    private fun genderList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val x = arr.opt(i)
            val raw: String? = if (x is JSONObject) {
                x.str("gender") ?: x.str("name") ?: x.str("code") ?: x.str("type")
            } else {
                x?.toString()
            }
            val g = normGender(raw)
            if (g != null) out.add(g)
        }
        return out
    }

    /** Farklı koordinatlar arasındaki en küçük pozitif adım (ölçekten bağımsız komşuluk için). */
    private fun unit(values: List<Double>): Double {
        val vs = values.toSortedSet().toList()
        var best = Double.MAX_VALUE
        for (i in 1 until vs.size) {
            val gap = vs[i] - vs[i - 1]
            if (gap > 0 && gap < best) best = gap
        }
        return if (best == Double.MAX_VALUE) 1.0 else best
    }

    private fun fmt(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private class Raw(val no: String, val x: Double?, val y: Double?, val t: JSONObject, val item: JSONObject)

    private fun kindFor(carName: String, r: Raw): Kind {
        val props = StringBuilder()
        for (p in r.item.optJSONArray("itemProperties").objects()) {
            props.append(p.str("name") ?: "").append(' ')
        }
        return kindOf(carName, r.item.str("name"), r.item.obj("itemType")?.str("name"), r.t.str("name"), props.toString())
    }

    private fun parseWagon(w: JSONObject, forwardRot: Int?): List<Seat> {
        val tpl = w.obj("seatMapTemplate") ?: JSONObject()
        val car = tpl.obj("car") ?: JSONObject()
        val wagon = w.str("trainCarIndex") ?: w.str("trainCarId") ?: car.str("name") ?: car.str("id") ?: "?"
        val carName = car.str("name") ?: ""
        val onSale = w.opt("trainCarOnSale") != false

        val occupied = HashMap<String, List<String>>()
        for (a in w.optJSONArray("allocationSeats").objects()) {
            val n = a.str("seatNumber") ?: continue
            occupied[n] = genderList(a.optJSONArray("genders"))
        }

        val raw = ArrayList<Raw>()
        for (t in tpl.optJSONArray("seatMaps").objects()) {
            val no = t.str("seatNumber") ?: continue
            val item = t.obj("item") ?: JSONObject()
            if (item.opt("saleable") == false) continue // koltuk değil (koridor, masa, WC vb.)
            raw.add(Raw(no, t.dbl("positionX"), t.dbl("positionY"), t, item))
        }
        if (raw.isEmpty()) return emptyList()

        val havePos = raw.all { it.x != null && it.y != null }
        val lc = LinkedHashMap<String, Pair<Double, Double>>() // koltuk → (uzunlamasına, enine)
        var uL = 1.0
        var uC = 1.0
        var minL = 0.0
        var minC = 0.0
        if (havePos) {
            val xs = raw.map { it.x!! }
            val ys = raw.map { it.y!! }
            // Vagonun genişliği boyundan küçüktür: daha dar eksen "yan yana" (enine) eksendir.
            val crossIsY = (ys.max() - ys.min()) <= (xs.max() - xs.min())
            val ux = unit(xs)
            val uy = unit(ys)
            for (r in raw) lc[r.no] = if (crossIsY) Pair(r.x!!, r.y!!) else Pair(r.y!!, r.x!!)
            if (crossIsY) { uL = ux; uC = uy } else { uL = uy; uC = ux }
            minL = lc.values.minOf { it.first }
            minC = lc.values.minOf { it.second }
        }

        // Kesintisiz blok: yan yana oturan koltuklar; koridor boşluğu bloğu böler.
        val blk = HashMap<String, String>()
        if (havePos) {
            val rows = HashMap<Double, MutableList<Pair<Double, String>>>()
            for ((no, p) in lc) rows.getOrPut(p.first) { ArrayList() }.add(Pair(p.second, no))
            for ((_, lst) in rows) {
                lst.sortBy { it.first }
                var start = 0.0
                var prev: Double? = null
                for ((c, no) in lst) {
                    if (prev == null || c - prev > uC * 1.01) start = c
                    blk[no] = fmt(start)
                    prev = c
                }
            }
        }

        val rotOf = HashMap<String, Int?>()
        for (r in raw) {
            val d = r.t.dbl("rotation")
            rotOf[r.no] = if (d == null) null else ((d.toInt() % 360) + 360) % 360
        }
        val byPos = HashMap<Pair<Double, Double>, String>()
        for ((no, p) in lc) byPos[p] = no

        // Karşılıklı (masa) grubu: uzunlamasına komşu ve zıt (180°) yöne bakan koltuk çifti
        fun facingGroup(no: String): String? {
            if (!havePos) return null
            val rn = rotOf[no] ?: return null
            val p = lc[no] ?: return null
            for (dL in doubleArrayOf(-uL, uL)) {
                val other = byPos[Pair(p.first + dL, p.second)] ?: continue
                val ro = rotOf[other] ?: continue
                if ((((ro - rn) % 360) + 360) % 360 == 180) {
                    return "$wagon-${fmt(minOf(p.first, p.first + dL))}-${blk[no]}"
                }
            }
            return null
        }

        val out = ArrayList<Seat>()
        for (r in raw) {
            val no = r.no
            val genders = occupied[no]
            val allocated = r.t.optBoolean("isAllocated", false) || genders != null
            val rot = rotOf[no]
            var direction: String? = null
            if (forwardRot != null && rot != null) {
                direction = if (rot == forwardRot) "F" else if (rot == (forwardRot + 180) % 360) "B" else null
            }
            var row: Int? = null
            var pos: Int? = null
            val p = lc[no]
            if (havePos && p != null) {
                row = Math.round((p.first - minL) / uL).toInt()
                pos = Math.round((p.second - minC) / uC).toInt()
            }
            out.add(
                Seat(
                    wagon = wagon,
                    no = no,
                    row = row,
                    pos = pos,
                    free = onSale && !allocated,
                    gender = genders?.firstOrNull(),
                    direction = direction,
                    group = facingGroup(no),
                    kind = kindFor(carName, r),
                    rot = rot,
                    blk = blk[no],
                    restrict = normGender(r.t.str("genderRestriction"))
                )
            )
        }
        return out
    }
}
