package org.maplibre.navigation.android.navigation.ui.v5.route

import okhttp3.OkHttpClient
import retrofit2.Retrofit

internal object GenericRouteRetrofitProvider {
    private const val DEFAULT_BASE_URL = "https://localhost/"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(DEFAULT_BASE_URL)
            .client(okHttpClient)
            .build()
    }
}
