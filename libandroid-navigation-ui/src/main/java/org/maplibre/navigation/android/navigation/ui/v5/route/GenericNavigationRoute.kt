package org.maplibre.navigation.android.navigation.ui.v5.route

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.maplibre.navigation.core.models.DirectionsResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit

class GenericNavigationRoute private constructor(
    private val requestUrl: String,
    private val headers: Map<String, String>,
    private val jsonPayload: String,
    private val retrofit: Retrofit
) {

    private val service: GenericRouteService by lazy {
        retrofit.create(GenericRouteService::class.java)
    }

    fun getRoute(callback: Callback<DirectionsResponse>) {
        getCall().enqueue(callback)
    }

    fun getCall(): Call<DirectionsResponse> {
        val upstreamCall = service.requestRoute(requestUrl, headers, buildRequestBody())
        return wrapCall(upstreamCall)
    }

    private fun buildRequestBody(): RequestBody = jsonPayload.toRequestBody(JSON_MEDIA_TYPE)

    private fun wrapCall(call: Call<ResponseBody>): Call<DirectionsResponse> {
        return object : Call<DirectionsResponse> {
            override fun enqueue(callback: Callback<DirectionsResponse>) {
                val thisCall = this
                call.enqueue(object : Callback<ResponseBody> {
                    override fun onResponse(
                        call: Call<ResponseBody>,
                        response: Response<ResponseBody>
                    ) {
                        callback.onResponse(thisCall, mapResponse(response))
                    }

                    override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                        callback.onFailure(thisCall, t)
                    }
                })
            }

            override fun execute(): Response<DirectionsResponse> {
                return mapResponse(call.execute())
            }

            override fun clone(): Call<DirectionsResponse> = wrapCall(call.clone())

            override fun isExecuted(): Boolean = call.isExecuted

            override fun cancel() {
                call.cancel()
            }

            override fun isCanceled(): Boolean = call.isCanceled

            override fun request() = call.request()

        }
    }

    private fun mapResponse(response: Response<ResponseBody>): Response<DirectionsResponse> {
        if (!response.isSuccessful) {
            val errorBody = response.errorBody() ?: UNKNOWN_ERROR_MESSAGE.toResponseBody(ERROR_MEDIA_TYPE)
            return Response.error(errorBody, response.raw())
        }

        val responseBody = response.body()
            ?: return Response.error(EMPTY_BODY_MESSAGE.toResponseBody(ERROR_MEDIA_TYPE), response.raw())

        return try {
            val json = responseBody.string()
            val parsed = DirectionsResponse.fromJson(json)
            Response.success(response.code(), parsed)
        } catch (t: Throwable) {
            Response.error((t.message ?: PARSE_ERROR_MESSAGE).toResponseBody(ERROR_MEDIA_TYPE), response.raw())
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ERROR_MEDIA_TYPE = "text/plain".toMediaType()
        private const val UNKNOWN_ERROR_MESSAGE = "Unable to fetch route"
        private const val EMPTY_BODY_MESSAGE = "Empty route response"
        private const val PARSE_ERROR_MESSAGE = "Unable to parse route"

        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder internal constructor() {
        private var requestUrl: String? = null
        private var jsonPayload: String? = null
        private val headers: MutableMap<String, String> = LinkedHashMap()
        private var retrofit: Retrofit? = null

        fun requestUrl(url: String) = apply { this.requestUrl = url }

        fun jsonPayload(payload: String) = apply { this.jsonPayload = payload }

        fun header(key: String, value: String) = apply { this.headers[key] = value }

        fun headers(headers: Map<String, String>) = apply { this.headers.putAll(headers) }

        fun retrofit(retrofit: Retrofit) = apply { this.retrofit = retrofit }

        fun build(): GenericNavigationRoute {
            val url = requireNotNull(requestUrl) { "requestUrl is required." }
            val payload = requireNotNull(jsonPayload) { "jsonPayload is required." }
            val retrofitInstance = retrofit ?: GenericRouteRetrofitProvider.retrofit
            return GenericNavigationRoute(url, headers.toMap(), payload, retrofitInstance)
        }
    }
}
