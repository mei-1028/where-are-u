package com.whereareu.app.core.net

import com.whereareu.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Rewrites the placeholder Retrofit origin while keeping /api/... paths. */
class BaseUrlInterceptor(private val baseUrlProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val base = baseUrlProvider().toHttpUrlOrNull()
            ?: return chain.proceed(request)
        val rewritten = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}

class BearerInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: return chain.proceed(chain.request())
        return chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build(),
        )
    }
}

/**
 * Builds two independent Retrofit stacks so a request can never accidentally
 * send the user JWT to a device-token endpoint (or vice versa).
 */
class ApiClientFactory {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }

    private val baseUrlInterceptor = BaseUrlInterceptor { RuntimeConfig.serverBaseUrl }

    private fun buildClient(tokenProvider: () -> String?): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(baseUrlInterceptor)
            .addInterceptor(BearerInterceptor(tokenProvider))
            .addInterceptor(loggingInterceptor)
            .build()

    private fun buildApi(client: OkHttpClient): ApiService =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)

    /** User JWT endpoints: auth, family, history, locate commands. */
    val userApi: ApiService = buildApi(buildClient { RuntimeConfig.userToken })

    /** Device token endpoints: POST /locations and device metadata. */
    val deviceApi: ApiService = buildApi(buildClient { RuntimeConfig.deviceToken })

    /** WebSocket shares connection settings but must not impose a read timeout. */
    val webSocketClient: OkHttpClient = buildClient { null }
        .newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val PLACEHOLDER_BASE_URL = "http://localhost/api/"
    }
}
