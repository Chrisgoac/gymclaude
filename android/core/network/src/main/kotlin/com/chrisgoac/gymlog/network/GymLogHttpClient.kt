package com.chrisgoac.gymlog.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

const val CONNECT_TIMEOUT_MILLIS = 15_000L
const val REQUEST_TIMEOUT_MILLIS = 60_000L // push route maxDuration is 60 s
const val SOCKET_TIMEOUT_MILLIS = 30_000L

/**
 * Builds the client shared by the sync API. No Logging plugin is installed on purpose:
 * Authorization headers and request/response bodies must never reach logs.
 * Retries are deliberately absent; backoff lives in the repository/WorkManager layer.
 */
internal fun createGymLogHttpClient(engine: HttpClientEngine = OkHttp.create()): HttpClient =
    HttpClient(engine) { configureGymLog() }

internal fun HttpClientConfig<*>.configureGymLog() {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
    }
}
