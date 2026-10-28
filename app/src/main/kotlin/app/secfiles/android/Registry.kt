package app.secfiles.android

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A container the app knows about. Holds no secrets of any kind. */
data class VolumeEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uri: String,
    val sizeBytes: Long,
    val inAppStorage: Boolean,
) {
    val parsedUri: Uri get() = Uri.parse(uri)
    override fun equals(other: Any?) = other is VolumeEntry && other.id == id
    override fun hashCode() = id.hashCode()
}

class VolumeRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("volumes", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<VolumeEntry> {
        val arr = runCatching { JSONArray(prefs.getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                VolumeEntry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    uri = o.getString("uri"),
                    sizeBytes = o.optLong("size"),
                    inAppStorage = o.optBoolean("inApp"),
                )
            }.getOrNull()
        }
    }

    @Synchronized
    fun save(list: List<VolumeEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("name", e.name); put("uri", e.uri); put("size", e.sizeBytes)
                put("inApp", e.inAppStorage)
            })
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Minutes of no activity before every volume is locked; 0 = never. */
    var autoLockMinutes: Int
        get() = prefs.getInt("autoLockMinutes", 5)
        set(v) = prefs.edit().putInt("autoLockMinutes", v).apply()

    var lockOnScreenOff: Boolean
        get() = prefs.getBoolean("lockOnScreenOff", true)
        set(v) = prefs.edit().putBoolean("lockOnScreenOff", v).apply()

    /** Lock everything the moment the app UI is closed or backgrounded. */
    var lockOnLeave: Boolean
        get() = prefs.getBoolean("lockOnLeave", false)
        set(v) = prefs.edit().putBoolean("lockOnLeave", v).apply()

    var allowScreenshots: Boolean
        get() = prefs.getBoolean("allowScreenshots", false)
        set(v) = prefs.edit().putBoolean("allowScreenshots", v).apply()

    /** Draw a small eject button over other apps while a volume is unlocked (needs the overlay permission). */
    var ejectOverlay: Boolean
        get() = prefs.getBoolean("ejectOverlay", true)
        set(v) = prefs.edit().putBoolean("ejectOverlay", v).apply()
}
