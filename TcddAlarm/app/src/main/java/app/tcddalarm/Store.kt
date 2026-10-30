package app.tcddalarm

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Ayarların, token'ın, bildirim durumunun ve istasyon listesinin telefonda saklanması. */
class Store(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tcdd_alarm", Context.MODE_PRIVATE)
    private val stationFile = File(appContext.filesDir, "stations.json")

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) {
            prefs.edit().putString("token", value).apply()
        }

    /** Servis çalışıyor olmalı mı (sistem servisi yeniden başlatırsa devam etsin diye). */
    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(value) {
            prefs.edit().putBoolean("running", value).apply()
        }

    var config: Config?
        get() {
            val s = prefs.getString("config", null) ?: return null
            return try {
                Config.fromJson(JSONObject(s))
            } catch (e: Exception) {
                null
            }
        }
        set(value) {
            prefs.edit().putString("config", value?.toJson()?.toString()).apply()
        }

    fun loadState(): AlertState {
        val s = prefs.getString("state", null) ?: return AlertState()
        return try {
            AlertState.fromJson(JSONObject(s))
        } catch (e: Exception) {
            AlertState()
        }
    }

    fun saveState(state: AlertState) {
        prefs.edit().putString("state", state.toJson().toString()).apply()
    }

    fun clearState() {
        prefs.edit().remove("state").apply()
    }

    fun cachedStations(): List<Station>? {
        if (!stationFile.exists()) return null
        return try {
            val raw = stationFile.readText(Charsets.UTF_8)
            val list = Responses.parseStations(parseJson(raw))
            if (list.isEmpty()) null else list
        } catch (e: Exception) {
            null
        }
    }

    fun cacheStationsRaw(raw: String) {
        stationFile.writeText(raw, Charsets.UTF_8)
    }

    fun clearStations() {
        if (stationFile.exists()) stationFile.delete()
    }
}
