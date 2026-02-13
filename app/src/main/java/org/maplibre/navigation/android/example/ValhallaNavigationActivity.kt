package org.maplibre.navigation.android.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import org.maplibre.geojson.Point
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.navigation.android.example.databinding.ActivityNavigationUiBinding
import org.maplibre.navigation.android.navigation.ui.v5.NavigationLauncher
import org.maplibre.navigation.android.navigation.ui.v5.NavigationLauncherOptions
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.navigation.*
import org.maplibre.turf.TurfConstants
import org.maplibre.turf.TurfMeasurement
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationMapRoute
import org.maplibre.navigation.android.navigation.ui.v5.route.GenericNavigationRoute
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import java.util.Locale

class ValhallaNavigationActivity :
    AppCompatActivity(),
    OnMapReadyCallback,
    MapLibreMap.OnMapClickListener {
    private lateinit var mapLibreMap: MapLibreMap

    // Navigation related variables
    private var language = Locale.getDefault().language
    private var route: DirectionsRoute? = null
    private var navigationMapRoute: NavigationMapRoute? = null
    private var destination: Point? = null
    private var locationComponent: LocationComponent? = null

    private lateinit var binding: ActivityNavigationUiBinding

    private var simulateRoute = false

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        binding = ActivityNavigationUiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mapView.apply {
            onCreate(savedInstanceState)
            getMapAsync(this@ValhallaNavigationActivity)
        }

        binding.startRouteButton.setOnClickListener {
            route?.let { route ->
                val userLocation = mapLibreMap.locationComponent.lastKnownLocation ?: return@let
                val options = NavigationLauncherOptions.builder()
                    .directionsRoute(route)
                    .shouldSimulateRoute(simulateRoute)
                    .initialMapCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(userLocation.latitude, userLocation.longitude)).build()
                    )
                    .lightThemeResId(R.style.TestNavigationViewLight)
                    .darkThemeResId(R.style.TestNavigationViewDark)
                    .build()
                NavigationLauncher.startNavigation(this@ValhallaNavigationActivity, options)
            }
        }

        binding.simulateRouteSwitch.setOnCheckedChangeListener { _, checked ->
            simulateRoute = checked
        }

        binding.clearPoints.setOnClickListener {
            if (::mapLibreMap.isInitialized) {
                mapLibreMap.markers.forEach {
                    mapLibreMap.removeMarker(it)
                }
            }
            destination = null
            it.visibility = View.GONE
            binding.startRouteLayout.visibility = View.GONE

            navigationMapRoute?.removeRoute()
        }
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        this.mapLibreMap = mapLibreMap
        mapLibreMap.setStyle(
            Style.Builder().fromUri(getString(R.string.map_style_light))
        ) { style ->
            enableLocationComponent(style)
            navigationMapRoute = NavigationMapRoute(binding.mapView, mapLibreMap)
            mapLibreMap.addOnMapClickListener(this)

            Snackbar.make(
                findViewById(R.id.container),
                "Tap map to place destination",
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        // Get an instance of the component
        locationComponent = mapLibreMap.locationComponent

        locationComponent?.let {
            // Activate with a built LocationComponentActivationOptions object
            it.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build(),
            )

            // Enable to make component visible
            it.isLocationComponentEnabled = true

            // Set the component's camera mode
            it.cameraMode = CameraMode.TRACKING_GPS_NORTH

            // Set the component's render mode
            it.renderMode = RenderMode.NORMAL
        }
    }

    override fun onMapClick(point: LatLng): Boolean {
        destination = Point.fromLngLat(point.longitude, point.latitude)

        mapLibreMap.addMarker(MarkerOptions().position(point))
        binding.clearPoints.visibility = View.VISIBLE
        calculateRoute()
        return true
    }

    private fun calculateRoute() {
        binding.startRouteLayout.visibility = View.GONE
        val userLocation = mapLibreMap.locationComponent.lastKnownLocation
        val destination = destination
        if (userLocation == null) {
            Timber.d("calculateRoute: User location is null, therefore, origin can't be set.")
            return
        }

        if (destination == null) {
            Timber.d("calculateRoute: destination is null, therefore, destination can't be set.")
            return
        }

        val origin = Point.fromLngLat(userLocation.longitude, userLocation.latitude)
        if (TurfMeasurement.distance(origin, destination, TurfConstants.UNIT_METERS) < 50) {
            Timber.d("calculateRoute: distance < 50 m")
            binding.startRouteButton.visibility = View.GONE
            return
        }

        // The full Valhalla API is documented here:
        // https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/

        // It would be better if there was a proper ValhallaService which uses retrofit to
        // generate the API call similar to the DirectionsService for the Mapbox API:
        // https://github.com/mapbox/mapbox-java/blob/main/services-directions/src/main/java/com/mapbox/api/directions/v5/DirectionsService.java
        // But this is the first step to show how the newly added banner_instructions
        // and voice_instructions of Valhalla can be used to generate directions directly:
        val requestBody = mapOf(
            "format" to "osrm",
            "costing" to "auto",
            "banner_instructions" to true,
            "voice_instructions" to true,
            "language" to language,
            "directions_options" to mapOf(
                "units" to "kilometers"
            ),
            "costing_options" to mapOf(
                "auto" to mapOf(
                    "top_speed" to 130
                )
            ),
            "locations" to listOf(
                mapOf(
                    "lon" to origin.longitude(),
                    "lat" to origin.latitude(),
                    "type" to "break"
                ),
                mapOf(
                    "lon" to destination.longitude(),
                    "lat" to destination.latitude(),
                    "type" to "break"
                )
            )
        )

        val requestBodyJson = Gson().toJson(requestBody)
        val requestUrl = buildValhallaRouteUrl()
        val genericNavigationRoute = GenericNavigationRoute.builder()
            .requestUrl(requestUrl)
            .jsonPayload(requestBodyJson)
            .header("User-Agent", "MapLibre Android Navigation SDK Demo App")
            .build()

        Timber.d("calculateRoute to %s enqueued requestBodyJson: %s", requestUrl, requestBodyJson)
        genericNavigationRoute.getRoute(object : Callback<DirectionsResponse> {
            override fun onResponse(
                call: Call<DirectionsResponse>,
                response: Response<DirectionsResponse>
            ) {
                val directionsResponse = response.body()
                Timber.d("response body: %s", directionsResponse)
                if (response.isSuccessful && directionsResponse != null) {
                    Timber.d(
                        "calculateRoute to Valhalla successful with status code: %s",
                        response.code()
                    )
                    val firstRoute = directionsResponse.routes.firstOrNull()
                    if (firstRoute == null) {
                        Timber.w("calculateRoute Valhalla response did not contain any routes")
                        return
                    }
                    this@ValhallaNavigationActivity.route = firstRoute
                    runOnUiThread {
                        navigationMapRoute?.addRoutes(directionsResponse.routes)
                        binding.startRouteLayout.visibility = View.VISIBLE
                    }
                } else {
                    Timber.e(
                        "calculateRoute request to Valhalla failed: code=%s message=%s",
                        response.code(),
                        response.errorBody()?.string()
                    )
                }
            }

            override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) {
                Timber.e(t, "calculateRoute Failed to get route from Valhalla")
            }
        })
    }

    private fun buildValhallaRouteUrl(): String {
        val baseUrl = getString(R.string.valhalla_url).trimEnd('/')
        return "$baseUrl/route"
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mapLibreMap.isInitialized) {
            mapLibreMap.removeOnMapClickListener(this)
        }
        binding.mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}
