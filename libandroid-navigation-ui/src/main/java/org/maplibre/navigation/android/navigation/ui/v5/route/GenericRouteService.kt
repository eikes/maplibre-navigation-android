package org.maplibre.navigation.android.navigation.ui.v5.route

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

internal interface GenericRouteService {
    @POST
    fun requestRoute(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody
    ): Call<ResponseBody>
}
