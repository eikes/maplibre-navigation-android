package org.maplibre.navigation.android.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import org.maplibre.geojson.model.Point
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
import org.maplibre.geojson.turf.TurfUnit
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationMapRoute
import org.maplibre.navigation.android.navigation.ui.v5.route.GenericNavigationRoute
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import java.util.Locale

class GraphHopperNavigationActivity :
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
            getMapAsync(this@GraphHopperNavigationActivity)
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
                NavigationLauncher.startNavigation(this@GraphHopperNavigationActivity, options)
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
        destination = Point(point.longitude, point.latitude)

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

        val origin = Point(userLocation.longitude, userLocation.latitude)
        if (org.maplibre.geojson.turf.TurfMeasurement.distance(origin, destination, TurfUnit.METRES) < 50) {
            Timber.d("calculateRoute: distance < 50 m")
            binding.startRouteButton.visibility = View.GONE
            return
        }

        // The full GraphHopper API is documented here:
        // https://docs.graphhopper.com/openapi/routing
        val requestBody = mapOf(
            "type" to "mapbox",
            "profile" to "car",
            "locale" to language,
            "points" to listOf(
                listOf(origin.longitude, origin.latitude),
                listOf(destination.longitude, destination.latitude)
            )
        )

        val requestBodyJson = Gson().toJson(requestBody)
        val genericNavigationRoute = GenericNavigationRoute.builder()
            .requestUrl(getString(R.string.graphhopper_url))
            .jsonPayload(requestBodyJson)
            .header("User-Agent", "MapLibre Android Navigation SDK Demo App")
            .build()

        Timber.d("calculateRoute enqueued requestBodyJson: %s", requestBodyJson)
        genericNavigationRoute.getRoute(object : Callback<DirectionsResponse> {
            override fun onResponse(
                call: Call<DirectionsResponse>,
                response: Response<DirectionsResponse>
            ) {
                val directionsResponse = response.body()
                if (response.isSuccessful && directionsResponse != null) {
                    Timber.d(
                        "calculateRoute to GraphHopper successful with status code: %s",
                        response.code()
                    )
                    val firstRoute = directionsResponse.routes.firstOrNull()
                    if (firstRoute == null) {
                        Timber.w("calculateRoute GraphHopper response did not contain any routes")
                        return
                    }
                    this@GraphHopperNavigationActivity.route = firstRoute
                    runOnUiThread {
                        navigationMapRoute?.addRoutes(directionsResponse.routes)
                        binding.startRouteLayout.visibility = View.VISIBLE
                    }
                } else {
                    Timber.e(
                        "calculateRoute request to GraphHopper failed: code=%s message=%s",
                        response.code(),
                        response.errorBody()?.string()
                    )
                }
            }

            override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) {
                Timber.e(t, "calculateRoute Failed to get route from GraphHopper")
            }
        })
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
