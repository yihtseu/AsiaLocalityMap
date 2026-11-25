package asia.locality.map

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.net.toUri
import androidx.core.content.edit
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val NEARBY_SEAT_KM = 50.0
/** Fraction of the viewport width within which an empty-ground tap may pick a seat. */
private const val TAP_REACH = 0.25

class MainActivity : ComponentActivity(), InkMapView.Listener, MapViewModel.Listener, SensorEventListener {
    private val model by viewModels<MapViewModel>()
    private var map: InkMapView? = null
    private var controlsHeight = 0
    private lateinit var locations: LocationManager
    private var sensors: SensorManager? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val data get() = model.data
    private val year get() = model.year
    private var viewRegion by mutableStateOf("cn")
    private var boundMap: InkMapView? = null
    private var boundData: MapData? = null
    private var locationStatusId by mutableStateOf<Int?>(null)
    private var searchOpen by mutableStateOf(false)
    private var message by mutableStateOf<UiMessage?>(null)
    private var current: Location? = null
    private var restoredViewport: DoubleArray? = null
    private var selectedId: String? = null
    private var selectedFeature by mutableStateOf<MapData.Feature?>(null)
    private var distanceNote by mutableStateOf<String?>(null)
    private val handler = Handler(Looper.getMainLooper())
    private var locating = false
    private var destroyed = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) locate() else { locationStatusId = R.string.permission_denied }
    }
    private val locationTimeout = Runnable {
        stopLocation()
        locationStatusId = R.string.location_timeout
    }
    private val locationListener = LocationListener { location ->
        if (locating && SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos in 0L..120_000_000_000L) {
            current = location
            restoredViewport = null
            map?.setHere(location.longitude, location.latitude, if (location.hasAccuracy()) location.accuracy else 0f)
            refreshHere()
            stopLocation()
            locationStatusId = null
            showAt(location.longitude, location.latitude, true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The paper look is always light, so keep dark system-bar icons even in system dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        locations = getSystemService(LocationManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        restoredViewport = savedInstanceState?.getDoubleArray("viewport")
        selectedId = savedInstanceState?.getString("selected")
        searchOpen = savedInstanceState?.getBoolean("search") ?: false
        val preferences = getPreferences(0)
        val initialYear = savedInstanceState?.getInt("year")?.takeIf { it != 0 }
            ?: preferences.getInt("year", Timeline.DEFAULT_YEAR)
        viewRegion = savedInstanceState?.getString("region") ?: "cn"
        if (savedInstanceState?.containsKey("longitude") == true) {
            current = Location("saved-ui").apply {
                longitude = savedInstanceState.getDouble("longitude")
                latitude = savedInstanceState.getDouble("latitude")
                if (savedInstanceState.getFloat("accuracy") > 0f) accuracy = savedInstanceState.getFloat("accuracy")
            }
        }
        setContent {
            LocalityApp(
                data = model.data,
                timeline = model.timeline, year = model.year, region = viewRegion, changingPeriod = model.changingPeriod,
                onPeriod = { period -> model.timeline?.let { changeYear(it.contextYearFor(period, model.year)) } },
                selectedFeature = selectedFeature,
                trace = model.trace,
                distanceNote = distanceNote,
                visitedIds = model.visitedIds,
                onToggleVisited = { labelId ->
                    model.toggleVisited(labelId)
                    map?.setVisited(model.visitedIds)
                },
                // A failed load must not be hidden behind a transient location note.
                statusId = model.statusId.takeIf { it == R.string.load_failed } ?: (locationStatusId ?: model.statusId),
                onRetry = model::retry,
                searchOpen = searchOpen,
                message = message,
                onMapReady = ::attachMap,
                onControlsHeight = { controlsHeight = it; map?.setLabelTopInset(it) },
                onMapReleased = { view ->
                    if (map === view) {
                        boundMap = null
                        if (data != null && restoredViewport == null) restoredViewport = view.viewport()
                        map = null
                    }
                },
                onSearch = { searchOpen = true },
                onCloseSearch = { searchOpen = false },
                onLocate = ::locate,
                onZoom = { map?.zoom(it) },
                onChoose = ::searchResult,
                onDismissMessage = { message = null }
            )
        }
        model.listener = this
        model.ensureLoaded(initialYear)
    }

    private fun attachMap(view: InkMapView) {
        map = view
        view.setLabelTopInset(controlsHeight)
        view.listener = this
        view.post { if (!destroyed && map === view) bindMap(view) }
    }

    private fun bindMap(view: InkMapView) {
        val loaded = data ?: return
        // Binding is idempotent: it can be reached from attachMap and from a finished load.
        if (boundMap === view && boundData === loaded) return
        boundMap = view
        boundData = loaded
        view.setData(loaded)
        view.setVisited(model.visitedIds)
        val saved = restoredViewport
        val savedSelection = selectedId
        current?.let {
            view.setHere(it.longitude, it.latitude, if (it.hasAccuracy()) it.accuracy else 0f)
            refreshHere()
            showAt(it.longitude, it.latitude, saved == null)
        }
        if (saved != null) {
            view.restoreViewport(saved)
            savedSelection?.let { id -> loaded.features.find { it.id == id }?.let(::choose) }
            restoredViewport = null
        }
    }

    private fun changeYear(value: Int) {
        if (data == null || value == year) return
        stopLocation()
        locationStatusId = null
        map?.stopCamera()
        model.changeYear(value)
    }

    override fun onSnapshot(data: MapData) {
        if (boundData == null) { map?.let(::bindMap); return }
        val viewport = map?.viewport() ?: restoredViewport
        val selection = selectedId
        boundMap = map
        boundData = data
        map?.setData(data)
        map?.setVisited(model.visitedIds)
        selectedId = null
        selectedFeature = null
        distanceNote = null
        refreshHere()
        // Keep the viewed place and zoom, including when a camera transition was in progress.
        if (viewport != null) {
            map?.restoreViewport(viewport)
            showAt(viewport[0], MapData.latitude(viewport[1]), false)
        }
        data.features.find { it.id == selection }?.let(::choose)
    }

    override fun onViewportChanged(lon: Double, lat: Double) {
        val view = map ?: return
        viewRegion = model.timeline?.regionForViewport(lon, lat, view.viewport()[2],
            view.height.toDouble() / view.width.coerceAtLeast(1), viewRegion) ?: viewRegion
    }

    /** Re-derive the current-location label and accessibility text from the loaded snapshot. */
    private fun refreshHere() {
        val loaded = data ?: return
        val here = current ?: return
        map?.nameHere(loaded.localAreaAt(here.longitude, here.latitude))
        map?.contentDescription = descriptionAt(here.longitude, here.latitude)
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locate() {
        if (locating) return
        if (!hasLocationPermission()) {
            if (getPreferences(0).getBoolean("permissionAsked", false) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) &&
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                locationStatusId = R.string.permission_denied
                showMessage(getString(R.string.permission_title), getString(R.string.permission_body), getString(R.string.open_settings)) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                }
                return
            }
            getPreferences(0).edit { putBoolean("permissionAsked", true) }
            permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            locationStatusId = R.string.permission_prompt
            return
        }
        if (!locations.isLocationEnabled) {
            locationStatusId = R.string.location_disabled
            showMessage(getString(R.string.enable_location), getString(R.string.enable_location_body), getString(R.string.open_location_settings)) {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            return
        }
        locating = true
        locationStatusId = R.string.locating
        var requested = false
        try {
            val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            for (provider in arrayOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                if (provider == LocationManager.GPS_PROVIDER && !fine) continue
                if (locations.isProviderEnabled(provider)) {
                    locations.requestLocationUpdates(provider, 1000L, 0f, locationListener, Looper.getMainLooper())
                    requested = true
                }
            }
        } catch (_: SecurityException) {
            stopLocation()
            locationStatusId = R.string.permission_disabled
            return
        }
        if (requested) handler.postDelayed(locationTimeout, 20_000L)
        else { locating = false; locationStatusId = R.string.location_unavailable }
    }
    private fun stopLocation() {
        if (::locations.isInitialized) locations.removeUpdates(locationListener)
        locating = false
        handler.removeCallbacks(locationTimeout)
    }

    override fun onPlace(feature: MapData.Feature) {
        stopLocation()
        locationStatusId = null
        choose(feature)
    }
    override fun onMapPoint(lon: Double, lat: Double) {
        stopLocation()
        locationStatusId = null
        // The reach of a tap shrinks with the zoom, so an empty-ground tap never
        // grabs a seat that is far outside the viewport.
        val span = map?.viewport()?.get(2) ?: 1.0
        val radius = minOf(NEARBY_SEAT_KM, TAP_REACH * span * MapData.EARTH_RADIUS_M / 1000 * Math.PI / 180 *
            kotlin.math.cos(Math.toRadians(lat)))
        val loaded = data ?: return
        // Empty ground keeps the current selection; the map still drops its marker.
        if (loaded.localAreaAt(lon, lat) == null && loaded.nearestSeat(lon, lat, radius) == null) return
        showAt(lon, lat, false, radius)
    }

    private fun choose(feature: MapData.Feature) {
        selectedId = feature.id
        selectedFeature = feature
        map?.select(feature)
        model.clearStatus()
        updateLocalityDetails(feature)
    }

    private fun updateLocalityDetails(feature: MapData.Feature?) {
        if (feature == null) {
            distanceNote = null
            model.clearTrace()
            return
        }
        val here = current
        val anchorLon = MapData.latitude(feature.labelY).let { feature.labelLon }
        val anchorLat = MapData.latitude(feature.labelY)
        distanceNote = if (here != null) {
            val km = MapData.distance(here.longitude, here.latitude, anchorLon, anchorLat)
            if (km in 0.05..250.0) {
                val dirRes = when (MapData.cardinalIndex(MapData.bearing(here.longitude, here.latitude, anchorLon, anchorLat))) {
                    0 -> R.string.dir_n; 1 -> R.string.dir_ne; 2 -> R.string.dir_e; 3 -> R.string.dir_se
                    4 -> R.string.dir_s; 5 -> R.string.dir_sw; 6 -> R.string.dir_w; else -> R.string.dir_nw
                }
                val dir = getString(dirRes)
                if (km >= 1.0) getString(R.string.distance_dir_km, dir, km)
                else getString(R.string.distance_dir_m, dir, (km * 1000).roundToInt())
            } else null
        } else null
        val queryLon = if (here != null && !feature.point && feature.contains(here.longitude, MapData.mercator(here.latitude))) here.longitude else anchorLon
        val queryLat = if (here != null && !feature.point && feature.contains(here.longitude, MapData.mercator(here.latitude))) here.latitude else anchorLat
        model.updateTrace(feature.region, queryLon, queryLat)
    }

    private fun descriptionAt(lon: Double, lat: Double): String {
        val loaded = data ?: return getString(R.string.map_description)
        val area = loaded.localAreaAt(lon, lat)
        if (area != null) return getString(R.string.current_county_description, area.name)
        val seat = loaded.nearestSeat(lon, lat, NEARBY_SEAT_KM)
        if (seat != null) return getString(R.string.current_nearby_description, seat.name)
        return getString(R.string.current_unknown_description)
    }

    private fun showAt(lon: Double, lat: Double, move: Boolean, radiusKm: Double = NEARBY_SEAT_KM) {
        val loaded = data ?: return
        val area = loaded.localAreaAt(lon, lat)
        if (area != null) {
            choose(area)
            if (move) map?.focusCounty(lon, lat, area)
        } else {
            val seat = loaded.nearestSeat(lon, lat, radiusKm)
            selectedId = seat?.id
            selectedFeature = seat
            map?.select(seat)
            model.clearStatus()
            updateLocalityDetails(seat)
            if (move) {
                val view = map
                val aspect = if (view != null && view.height > 0) view.width.toDouble() / view.height else 1.0
                val extent = seat?.let { 2.6 * max(abs(lon - it.lon), abs(MapData.mercator(lat) - it.y) * aspect) } ?: 1.2
                view?.go(lon, lat, max(1.2, extent))
            }
        }
    }

    private fun searchResult(feature: MapData.Feature) {
        stopLocation()
        locationStatusId = null
        choose(feature)
        map?.go(feature.lon, feature.lat, if (feature.point) 1.2 else ((feature.maxX - feature.minX) * 1.4).coerceIn(0.2, 8.0))
        searchOpen = false
    }

    private fun showMessage(title: String, body: String, action: String, click: () -> Unit) {
        message = UiMessage(title, body, action) {
            message = null
            try { click() } catch (_: ActivityNotFoundException) { locationStatusId = R.string.location_unavailable }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putDoubleArray("viewport", restoredViewport ?: map?.takeIf { data != null }?.viewport())
        outState.putString("selected", selectedId)
        outState.putBoolean("search", searchOpen)
        outState.putInt("year", year)
        outState.putString("region", viewRegion)
        current?.let {
            outState.putDouble("longitude", it.longitude); outState.putDouble("latitude", it.latitude)
            if (it.hasAccuracy()) outState.putFloat("accuracy", it.accuracy)
        }
    }
    override fun onResume() {
        super.onResume()
        if (::locations.isInitialized && current == null && !locating && locations.isLocationEnabled && hasLocationPermission()) locate()
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }
    override fun onStop() {
        super.onStop()
        if (locating) locationStatusId = R.string.location_interrupted
        stopLocation()
        sensors?.unregisterListener(this)
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR && current != null) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuth = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()
            map?.setHeading(azimuth)
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onDestroy() {
        destroyed = true
        if (model.listener === this) model.listener = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
