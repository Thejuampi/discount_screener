package com.discountscreener.android.data.remote

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * An [OkHttpClient] that answers one URL fragment with one body and refuses every other call.
 *
 * A client that streams its answer cannot be tested at the string seam: the whole point of the
 * stream is that no string of the whole body exists. So the double sits under the client, at the
 * only place the body is still a body. Anything the test did not plan for lands as an
 * [AssertionError], for the reason [offlineHttpClient] gives.
 */
internal fun cannedHttpClient(fragment: String, body: String): OkHttpClient =
    cannedHttpClient(listOf(fragment to body))

/**
 * The same double over several routes. The first fragment the URL holds wins.
 *
 * A lookup crosses six SEC endpoints. Naming each one keeps the test honest about which answer
 * feeds which step, and an unplanned seventh call still lands as an [AssertionError].
 */
internal fun cannedHttpClient(routes: List<Pair<String, String>>): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(
        Interceptor { chain ->
            var request = chain.request()
            var body = routes.firstOrNull { request.url.toString().contains(it.first) }?.second
                ?: throw AssertionError("A unit test reached the network: ${request.method} ${request.url}")
            if (request.header("Accept-Encoding") != "identity") {
                throw AssertionError(
                    "The request asked SEC for a compressed body: ${request.url}" + "\n" +
                        "  The sieve reads the response as it arrives. A gzip frame costs the phone " +
                        "the decode of 4 MB it does not keep.",
                )
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        },
    )
    .build()
