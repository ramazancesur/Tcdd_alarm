package app.tcddalarm

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * İstasyon arama penceresi.
 *
 * Seçim, Activity alanlarına doğrudan yazılmaz; [onChange] ile geri verilir.
 */
internal class StationPicker(
    private val activity: Activity,
    private val store: Store,
    private val widgets: FormWidgets,
    private val toast: (String) -> Unit,
) {
    fun show(
        choosingOrigin: Boolean,
        origin: () -> Station?,
        destination: () -> Station?,
        token: String,
        base: String,
        onChange: (Station?, Station?) -> Unit,
    ) {
        if (!choosingOrigin && origin() == null) {
            toast("Önce kalkış istasyonunu seçin")
            return
        }
        val search = EditText(activity).apply {
            hint = "İstasyon adı yazın"
            setSingleLine(true)
        }
        val status = TextView(activity).apply { text = "İstasyonlar yükleniyor…" }
        val list = ListView(activity)
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, ArrayList<String>())
        list.adapter = adapter
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(widgets.dp(16), widgets.dp(8), widgets.dp(16), 0)
            addView(search)
            addView(status)
            addView(
                list,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    widgets.dp(320),
                ),
            )
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (choosingOrigin) "Nereden" else "Nereye")
            .setView(box)
            .setNegativeButton("Kapat", null)
            .create()
        dialog.show()

        var all = emptyList<Station>()
        var shown = emptyList<Station>()
        fun refilter() {
            val query = trNorm(search.text.toString().trim())
            val currentOrigin = origin()
            var pool = all
            if (!choosingOrigin && currentOrigin != null && currentOrigin.pairs.isNotEmpty()) {
                pool = pool.filter { it.id in currentOrigin.pairs }
            }
            shown = pool.filter { query.isEmpty() || trNorm(it.name).contains(query) }.take(80)
            adapter.clear()
            adapter.addAll(shown.map { it.name })
            adapter.notifyDataSetChanged()
            status.text = "${shown.size} istasyon"
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refilter()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val station = shown.getOrNull(position) ?: return@setOnItemClickListener
            if (choosingOrigin) {
                val currentDestination = destination()
                val dropped = currentDestination != null && (
                    currentDestination.id == station.id ||
                        (station.pairs.isNotEmpty() && currentDestination.id !in station.pairs)
                    )
                onChange(station, if (dropped) null else currentDestination)
            } else {
                onChange(origin(), station)
            }
            dialog.dismiss()
        }
        Thread {
            try {
                val loaded = load(token, base)
                activity.runOnUiThread {
                    all = loaded
                    val current = origin()
                    if (current != null && current.pairs.isEmpty()) {
                        val filled = loaded.firstOrNull { it.id == current.id } ?: current
                        onChange(filled, destination())
                    }
                    refilter()
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    status.text = "İstasyon listesi alınamadı: ${error.message}\n" +
                        "Gelişmiş bölümüne token girip tekrar deneyin."
                }
            }
        }.start()
    }

    private fun load(token: String, base: String): List<Station> {
        store.cachedStations()?.let { return it }
        val raw = TcddApi(base, TcddApi.DEFAULT_CDN, token).fetchStationsRaw()
        val list = Responses.parseStations(parseJson(raw))
        if (list.isEmpty()) throw IllegalStateException("Liste boş ya da biçimi tanınmadı")
        store.cacheStationsRaw(raw)
        return list
    }
}
