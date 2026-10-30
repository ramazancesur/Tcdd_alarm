package app.tcddalarm

/** Koltuk önerme motoru (Python sürümündeki seats.py'nin Kotlin karşılığı). */
object Planner {

    private class Ctx(val hasGroups: Boolean, val hasRot: Boolean, val hasDir: Boolean, val hasGender: Boolean)

    private class Key(val unmet: Int, val w1: Int, val w2: Int, val w3: String, val num: Int, val no: String)

    private val keyOrder = Comparator<Key> { a, b ->
        var c = a.unmet.compareTo(b.unmet)
        if (c == 0) c = a.w1.compareTo(b.w1)
        if (c == 0) c = a.w2.compareTo(b.w2)
        if (c == 0) c = a.w3.compareTo(b.w3)
        if (c == 0) c = a.num.compareTo(b.num)
        if (c == 0) c = a.no.compareTo(b.no)
        c
    }

    // --- Komşuluk ve sıra anahtarları ---------------------------------------------

    /** Yan yana oturan koltuk bloğu. Blok bilgisi yoksa satır/sütun, o da yoksa numaraya göre. */
    fun blockKey(s: Seat): String? {
        val row = s.row
        val pos = s.pos
        val blk = s.blk
        if (blk != null && row != null) return "${s.wagon}|$row|$blk"
        if (row != null && pos != null) return "${s.wagon}|$row|${pos / 2}"
        val n = s.num ?: return null
        return "${s.wagon}|n|${(n - 1) / 2}"
    }

    fun rowKey(s: Seat): String? {
        val row = s.row
        if (row != null) return "${s.wagon}|$row"
        val n = s.num ?: return null
        return "${s.wagon}|n|${(n - 1) / 4}"
    }

    // --- Cinsiyet kuralı ----------------------------------------------------------

    /** Koltuğa özel kısıt + kural: yanındaki koltuk doluysa komşu aynı cinsiyetten olmalı. */
    private fun genderOk(seat: Seat, g: String, blocks: Map<String, List<Seat>>, mine: Set<String>, ctx: Ctx): Boolean {
        if (seat.restrict != null && seat.restrict != g) return false
        if (!ctx.hasGender) return true
        val key = blockKey(seat) ?: return true
        for (n in blocks[key].orEmpty()) {
            if (n.sid == seat.sid || n.sid in mine || n.free) continue
            // Cinsiyeti bilinmeyen dolu komşu kimseyi elemez.
            if (n.gender != null && n.gender != g) return false
        }
        return true
    }

    private fun permutations(items: List<String>): List<List<String>> {
        if (items.size <= 1) return listOf(items)
        val out = LinkedHashSet<List<String>>()
        fun rec(prefix: List<String>, rest: List<String>) {
            if (rest.isEmpty()) {
                out.add(prefix)
                return
            }
            val seen = HashSet<String>()
            for (i in rest.indices) {
                if (!seen.add(rest[i])) continue
                rec(prefix + rest[i], rest.take(i) + rest.drop(i + 1))
            }
        }
        rec(emptyList(), items)
        return out.toList().sortedBy { it.joinToString("") }
    }

    private fun assign(
        chosen: List<Seat>, perms: List<List<String>>, blocks: Map<String, List<Seat>>, ctx: Ctx
    ): List<String>? {
        val mine = chosen.map { it.sid }.toSet()
        for (perm in perms) {
            var ok = true
            for (i in chosen.indices) {
                if (!genderOk(chosen[i], perm[i], blocks, mine, ctx)) {
                    ok = false
                    break
                }
            }
            if (ok) return perm
        }
        return null
    }

    // --- Tercih değerlendirme -----------------------------------------------------

    private fun evaluate(chosen: List<Seat>, prefs: Prefs, ctx: Ctx): Pair<List<String>, List<String>> {
        val n = chosen.size
        val unmet = ArrayList<String>()
        val notes = ArrayList<String>()
        if (n > 1 && prefs.sideBySide) {
            val ok = if (n == 2) {
                val k = blockKey(chosen[0])
                k != null && k == blockKey(chosen[1])
            } else {
                val keys = chosen.map { rowKey(it) }.toSet()
                keys.size == 1 && !keys.contains(null)
            }
            if (!ok) unmet.add("yan yana / aynı sıra")
        }
        if (n > 1 && prefs.facing) {
            if (!ctx.hasGroups) {
                notes.add("Karşılıklı koltuk bilgisi haritadan çıkarılamadı, bu tercih uygulanamadı.")
            } else {
                val groups = chosen.map { it.group }.toSet()
                val rots = chosen.mapNotNull { it.rot }.toSet()
                val ok = groups.size == 1 && !groups.contains(null) && (rots.size >= 2 || !ctx.hasRot)
                if (!ok) unmet.add("karşılıklı")
            }
        }
        if ((prefs.direction == "F" || prefs.direction == "B") && !(n > 1 && prefs.facing)) {
            if (!ctx.hasDir) {
                notes.add("Koltuk yönü çözülemedi (ayarlardan 'İleri yön rotation değeri' seçilmemiş), bu tercih uygulanamadı.")
            } else if (chosen.any { it.direction != prefs.direction }) {
                unmet.add("koltuk yönü")
            }
        }
        return Pair(unmet, notes)
    }

    // --- Aday üretimi -------------------------------------------------------------

    private fun <T> combinations(items: List<T>, k: Int): List<List<T>> {
        val out = ArrayList<List<T>>()
        fun rec(start: Int, cur: List<T>) {
            if (cur.size == k) {
                out.add(cur)
                return
            }
            for (i in start until items.size) rec(i + 1, cur + items[i])
        }
        rec(0, emptyList())
        return out
    }

    private fun candidates(pool: List<Seat>, n: Int): List<List<Seat>> {
        val out = LinkedHashMap<Set<String>, List<Seat>>()
        fun add(t: List<Seat>) {
            out.getOrPut(t.map { it.sid }.toSet()) { t }
        }
        val byWagon = LinkedHashMap<String, MutableList<Seat>>()
        for (s in pool) byWagon.getOrPut(s.wagon) { ArrayList() }.add(s)

        for (free in byWagon.values) {
            if (n == 1) {
                for (s in free) add(listOf(s))
                continue
            }
            val groups = LinkedHashMap<String, MutableList<Seat>>()
            val rows = LinkedHashMap<String, MutableList<Seat>>()
            for (s in free) {
                val g = s.group
                if (g != null) groups.getOrPut(g) { ArrayList() }.add(s)
                val rk = rowKey(s)
                if (rk != null) rows.getOrPut(rk) { ArrayList() }.add(s)
            }
            if (n <= 6) {
                for (g in groups.values) {
                    if (g.size in n..8) for (c in combinations(g, n)) add(c)
                }
                for (r in rows.values) {
                    if (r.size in n..8) for (c in combinations(r, n)) add(c)
                }
            }
            val ordered = free.sortedWith(compareBy({ it.row ?: 0 }, { it.pos ?: 0 }, { it.num ?: 0 }))
            for (i in 0..(ordered.size - n)) add(ordered.subList(i, i + n))
        }
        return out.values.toList()
    }

    // --- Dış arayüz ---------------------------------------------------------------

    /** Tercihlere en uygun koltuk grubunu seçer; yoksa null döner. */
    fun makePlan(seats: List<Seat>, prefs: Prefs): Plan? {
        val n = prefs.genders.size
        if (n < 1 || seats.isEmpty()) return null
        val pool = seats.filter { it.free && it.kind in prefs.kinds }
        val blocks = HashMap<String, MutableList<Seat>>()
        for (s in seats) {
            val k = blockKey(s)
            if (k != null) blocks.getOrPut(k) { ArrayList() }.add(s)
        }
        val ctx = Ctx(
            hasGroups = seats.any { it.group != null },
            hasRot = seats.any { it.rot != null },
            hasDir = seats.any { it.direction != null },
            hasGender = seats.any { !it.free && it.gender != null }
        )
        val perms = permutations(prefs.genders)

        var bestKey: Key? = null
        var best: Plan? = null
        for (cand in candidates(pool, n)) {
            val perm = assign(cand, perms, blocks, ctx) ?: continue
            val (unmet, notes) = evaluate(cand, prefs, ctx)
            if (prefs.strict && unmet.isNotEmpty()) continue
            val first = cand[0]
            val wn = first.wagon.toIntOrNull()
            val key = Key(
                unmet = unmet.size,
                w1 = if (wn != null) 0 else 1,
                w2 = wn ?: 0,
                w3 = if (wn != null) "" else first.wagon,
                num = first.num ?: 0,
                no = first.no
            )
            val bk = bestKey
            if (bk == null || keyOrder.compare(key, bk) < 0) {
                bestKey = key
                best = Plan(cand, perm, unmet, notes)
            }
        }
        val plan = best ?: return null
        if (!ctx.hasGender) {
            return plan.copy(notes = plan.notes + "Haritada dolu koltukların yolcu cinsiyeti yok, komşu cinsiyet kuralı kontrol edilemedi.")
        }
        return plan
    }

    fun stillFree(ids: List<String>, seats: List<Seat>): Boolean {
        val free = seats.filter { it.free }.map { it.sid }.toSet()
        return ids.all { it in free }
    }

    private fun genderLabel(g: String): String = if (g == "K") "Kadın" else "Erkek"

    fun describe(plan: Plan): String {
        val lines = ArrayList<String>()
        for (i in plan.seats.indices) {
            val s = plan.seats[i]
            val dir = when (s.direction) {
                "F" -> "ileri yön"
                "B" -> "geri yön"
                else -> null
            }
            val extra = listOfNotNull(dir, s.kind.label).joinToString(", ")
            lines.add("Vagon ${s.wagon} · Koltuk ${s.no} (${genderLabel(plan.genders[i])}) — $extra")
        }
        if (plan.unmet.isNotEmpty()) lines.add("Karşılanamayan tercih: " + plan.unmet.joinToString(", "))
        for (note in plan.notes) lines.add("Not: $note")
        return lines.joinToString("\n")
    }
}
