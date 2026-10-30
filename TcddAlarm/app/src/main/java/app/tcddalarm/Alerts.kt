package app.tcddalarm

import org.json.JSONArray
import org.json.JSONObject

/** Bildirim durumu: hangi sefer için hangi koltuklar önerildi. Süreç ölse de korunur. */
class AlertState(
    val active: MutableMap<String, List<String>> = HashMap(),
    val countNotified: MutableSet<String> = HashSet()
) {
    fun toJson(): JSONObject {
        val a = JSONObject()
        for ((k, v) in active) a.put(k, JSONArray(v))
        val o = JSONObject()
        o.put("active", a)
        o.put("count", JSONArray(countNotified.toList()))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject?): AlertState {
            val st = AlertState()
            if (o == null) return st
            val a = o.optJSONObject("active")
            if (a != null) {
                val keys = a.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = a.optJSONArray(k) ?: continue
                    val ids = ArrayList<String>()
                    for (i in 0 until arr.length()) ids.add(arr.opt(i).toString())
                    st.active[k] = ids
                }
            }
            val c = o.optJSONArray("count")
            if (c != null) {
                for (i in 0 until c.length()) st.countNotified.add(c.opt(i).toString())
            }
            return st
        }
    }
}

/**
 * Bildirim kararı:
 *  - Uygun koltuklar bulununca BİR KEZ bildirir; önerilen koltuklar boş kaldıkça tekrar bildirmez.
 *  - Önerilen koltuklardan biri satılırsa yeni koltuk önerir.
 *  - Koltuk haritası okunamazsa yalnızca boş yer sayısını (tek sefer) bildirir.
 */
class Alerter(
    private val prefs: Prefs,
    private val send: (String, String) -> Unit,
    private val log: (String) -> Unit
) {
    fun handle(state: AlertState, key: String, head: String, train: Train, seats: List<Seat>) {
        if (train.seats < prefs.genders.size) {
            forget(state, key)
            return
        }
        if (seats.isEmpty()) {
            notifyCountOnly(state, key, head, train)
            return
        }
        suggest(state, key, head, seats)
    }

    private fun forget(state: AlertState, key: String) {
        state.active.remove(key)
        state.countNotified.remove(key)
    }

    private fun notifyCountOnly(state: AlertState, key: String, head: String, train: Train) {
        if (key in state.active || !state.countNotified.add(key)) return
        val cabins = train.cabins.filter { it.value > 0 }
            .entries.joinToString(", ") { "${it.key}: ${it.value}" }
        send(
            "🚆 Boş yer var!",
            "$head\n$cabins\nKoltuk haritası okunamadı, koltuk önerisi yapılamadı.",
        )
    }

    private fun suggest(state: AlertState, key: String, head: String, seats: List<Seat>) {
        val previous = state.active[key]
        if (previous != null && Planner.stillFree(previous, seats)) return
        val plan = Planner.makePlan(seats, prefs)
        if (plan == null) {
            if (previous != null) {
                log("$key: önerilen koltuklar satıldı, tercihlere uyan başka koltuk yok.")
                state.active.remove(key)
            }
            return
        }
        state.active[key] = plan.ids
        val body = "$head\n${Planner.describe(plan)}"
        if (previous != null) {
            send("🔄 Önerilen koltuk satıldı, yeni öneri", body)
        } else {
            send("🚆 Boş yer var!", body)
        }
    }
}
