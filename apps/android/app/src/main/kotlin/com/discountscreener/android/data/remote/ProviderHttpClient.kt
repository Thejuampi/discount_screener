package com.discountscreener.android.data.remote

import okhttp3.ConnectionPool
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import java.net.CookieManager
import java.net.CookiePolicy
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The HTTP client every provider in this app shares, sized for a screen of hundreds of symbols.
 *
 * What the default builder left wrong on a phone:
 *
 * - **The connection pool held five idle connections.** With a window of up to
 *   [AdaptiveRequestWindow.DEFAULT_MAX_WINDOW] calls to one host, every call past the fifth found no
 *   free connection and opened one, which is a TCP handshake and a TLS handshake before a byte of
 *   the answer. Over mobile data that is a few hundred milliseconds paid again and again. The pool
 *   now holds a connection for every call the window can have in flight, on every host it uses.
 * - **Only the whole call was bounded, at twenty seconds.** A socket that connects and then says
 *   nothing held its permit for the full twenty. Connect and read are now bounded on their own, so
 *   a dead socket gives the permit back in seconds instead of tens of seconds.
 *
 * HTTP/2 is left on, so the hosts that speak it carry many requests over one connection and the
 * handshake is paid once.
 */
internal fun providerHttpClient(
    connectionsPerHost: Int = AdaptiveRequestWindow.DEFAULT_MAX_WINDOW,
    hosts: Int = DEFAULT_HOSTS,
): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(CONNECT_TIMEOUT)
    .readTimeout(READ_TIMEOUT)
    .writeTimeout(WRITE_TIMEOUT)
    .callTimeout(CALL_TIMEOUT)
    .connectionPool(
        ConnectionPool(
            maxIdleConnections = connectionsPerHost * hosts,
            keepAliveDuration = KEEP_ALIVE_MINUTES,
            timeUnit = TimeUnit.MINUTES,
        ),
    )
    .cookieJar(
        JavaNetCookieJar(
            CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) },
        ),
    )
    .addInterceptor(BROWSER_DEFAULT_HEADERS_INTERCEPTOR)
    .build()

/** query1, query2 and the finance.yahoo.com page, which is every host one load touches. */
private const val DEFAULT_HOSTS = 3

/** A phone that cannot reach a host in this long is not on a network worth waiting for. */
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(6)

/** A connected socket that says nothing for this long has lost the answer. */
private val READ_TIMEOUT: Duration = Duration.ofSeconds(12)
private val WRITE_TIMEOUT: Duration = Duration.ofSeconds(6)

/** The outer bound, so a call that keeps trickling bytes still gives its permit back. */
private val CALL_TIMEOUT: Duration = Duration.ofSeconds(20)

/** Yahoo keeps a connection well past this; the pool releases it when the load is over. */
private const val KEEP_ALIVE_MINUTES = 5L
