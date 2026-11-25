package asia.locality.map

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future

class LocalityTrace(val period: HistoryPeriod, val placeName: String)

/**
 * Owns the parsed dataset and its loading, so rotation, locale and theme changes
 * recreate the activity without parsing the offline data again.
 */
class MapViewModel(app: Application) : AndroidViewModel(app) {
    interface Listener {
        /** The first load or a period change finished; [data] is now current. */
        fun onSnapshot(data: MapData)
    }

    var data by mutableStateOf<MapData?>(null)
        private set
    var timeline by mutableStateOf<Timeline?>(null)
        private set
    var year by mutableIntStateOf(Timeline.DEFAULT_YEAR)
        private set
    var statusId by mutableStateOf<Int?>(R.string.loading)
        private set
    var changingPeriod by mutableStateOf(false)
        private set
    var trace by mutableStateOf<List<LocalityTrace>>(emptyList())
        private set
    var visitedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var listener: Listener? = null

    private val context = app
    private val preferences = app.getSharedPreferences("MainActivity", 0)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    // Retain immutable geometry for reuse when a later context becomes eligible again.
    // These cached features are never added to an ineligible snapshot.
    private var fixedReferences = emptyList<MapData.Feature>()
    private val snapshotCache = HashMap<Int, MapData>()
    private var traceGeneration = 0

    init {
        visitedIds = preferences.getStringSet("visited_labels", null)?.toSet().orEmpty()
    }

    fun toggleVisited(labelId: String) {
        if (labelId.isBlank()) return
        val next = if (labelId in visitedIds) visitedIds - labelId else visitedIds + labelId
        visitedIds = next
        preferences.edit { putStringSet("visited_labels", next) }
    }
    private var generation = 0
    private var pending: Future<*>? = null
    private var initialLoading = false
    private var requestedYear = Timeline.DEFAULT_YEAR
    private var failedYear: Int? = null

    fun clearStatus() { if (statusId != R.string.load_failed) statusId = null }

    /** Loads the catalogue and first snapshot once; later calls (e.g. after rotation) do nothing. */
    fun ensureLoaded(initialYear: Int) {
        if (data != null || initialLoading) return
        initialLoading = true
        requestedYear = initialYear
        statusId = R.string.loading
        worker.execute {
            try {
                val catalogue = Timeline.load(context)
                val loaded = MapData.load(context, catalogue.restoreYear(initialYear))
                main.post {
                    initialLoading = false
                    fixedReferences = loaded.fixedFeatures()
                    snapshotCache[loaded.year] = loaded
                    timeline = catalogue
                    year = loaded.year
                    data = loaded
                    preferences.edit { putInt("year", year) }
                    if (statusId == R.string.loading) statusId = null
                    listener?.onSnapshot(loaded)
                }
            } catch (ex: Exception) {
                Log.e("AsiaLocalityMap", "Offline data failed", ex)
                main.post { initialLoading = false; statusId = R.string.load_failed }
            }
        }
    }

    fun retry() {
        val target = failedYear
        if (data == null) ensureLoaded(requestedYear)
        else if (target != null) { failedYear = null; changeYear(target) }
        else statusId = null
    }

    fun changeYear(value: Int) {
        val previous = data ?: return
        if (value == year || value !in (timeline?.years ?: emptySet())) return
        val cached = snapshotCache[value]
        if (cached != null) {
            pending?.cancel(true)
            generation++
            applySnapshot(cached)
            return
        }
        changingPeriod = true
        statusId = R.string.loading
        failedYear = null
        val token = ++generation
        pending?.cancel(true)
        pending = worker.submit {
            // A cancelled predecessor may leave its interrupt flag set on this pool thread.
            Thread.interrupted()
            try {
                val loaded = MapData.load(context, value, previous.land, fixedReferences)
                main.post {
                    snapshotCache[loaded.year] = loaded
                    if (token == generation) applySnapshot(loaded)
                }
            } catch (_: CancellationException) {
                // A more recent selection owns the next snapshot.
            } catch (error: Exception) {
                Log.e("AsiaLocalityMap", "Historical snapshot failed: $value", error)
                main.post {
                    if (token == generation) { changingPeriod = false; failedYear = value; statusId = R.string.load_failed }
                }
            }
        }
    }

    /** Resolve historical jurisdiction names across all periods of [region] at ([lon], [lat]). */
    fun updateTrace(region: String, lon: Double, lat: Double) {
        val catalogue = timeline ?: return
        val base = data ?: return
        val periods = catalogue.forRegion(region)
        if (periods.size <= 1) {
            traceGeneration++
            trace = emptyList()
            return
        }
        val token = ++traceGeneration
        val existing = HashMap(snapshotCache)
        val currentYear = year
        val fixed = fixedReferences
        worker.execute {
            Thread.interrupted()
            try {
                val entries = mutableListOf<LocalityTrace>()
                val loadedSnapshots = HashMap<Int, MapData>()
                for (period in periods) {
                    if (token != traceGeneration) return@execute
                    val targetYear = catalogue.contextYearFor(period, currentYear)
                    val snap = existing[targetYear] ?: loadedSnapshots[targetYear]
                        ?: MapData.load(context, targetYear, base.land, fixed).also { loadedSnapshots[targetYear] = it }
                    val area = snap.at(lon, lat).firstOrNull { it.region == region }
                        ?: snap.nearestSeat(lon, lat, 18.0)?.takeIf { it.region == region }
                    if (area != null) entries.add(LocalityTrace(period, area.name))
                }
                main.post {
                    snapshotCache.putAll(loadedSnapshots)
                    if (token == traceGeneration) trace = entries
                }
            } catch (_: Exception) {
                // Background trace lookup is non-fatal and must not disturb the active map.
            }
        }
    }

    fun clearTrace() {
        traceGeneration++
        trace = emptyList()
    }

    private fun applySnapshot(loaded: MapData) {
        year = loaded.year
        data = loaded
        loaded.fixedFeatures().takeIf { it.isNotEmpty() }?.let { fixedReferences = it }
        changingPeriod = false
        statusId = null
        preferences.edit { putInt("year", year) }
        listener?.onSnapshot(loaded)
    }

    override fun onCleared() {
        listener = null
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }
}
