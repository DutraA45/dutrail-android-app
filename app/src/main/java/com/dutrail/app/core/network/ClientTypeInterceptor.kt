package com.dutrail.app.core.network

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Adiciona `X-Client-Type: mobile` às rotas de `/auth`, como a API exige do app. */
class ClientTypeInterceptor(
    baseUrl: HttpUrl,
    private val logClientErrors: Boolean,
) : Interceptor {

    private val authPathPrefix = baseUrl.encodedPath + "auth/"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.startsWith(authPathPrefix)) return chain.proceed(request)

        val response = chain.proceed(request.newBuilder().header(HEADER, VALUE).build())
        // Um 400 em /auth costuma ser bug do cliente: deixa rastro no log de debug.
        if (response.code == 400 && logClientErrors) {
            Log.w(TAG, "400 em ${request.url.encodedPath}: ${response.peekBody(2048).string()}")
        }
        return response
    }

    companion object {
        const val HEADER = "X-Client-Type"
        const val VALUE = "mobile"
        private const val TAG = "ClientType"
    }
}
