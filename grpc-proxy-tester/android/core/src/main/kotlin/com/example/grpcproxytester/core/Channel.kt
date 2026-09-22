package com.example.grpcproxytester.core

import com.example.grpcproxytester.health.HealthCheckRequest
import com.example.grpcproxytester.health.HealthGrpcKt.HealthCoroutineStub
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ConnectivityState
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.HttpConnectProxiedSocketAddress
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

internal fun buildChannel(c: ConnectionConfig): ManagedChannel {
    val builder = OkHttpChannelBuilder.forTarget(c.address)
        .userAgent("grpc-proxy-tester-android")
        .maxInboundMessageSize(c.maxMessageSize)
    if (c.useTls) {
        builder.useTransportSecurity()
        if (c.skipTlsVerify) {
            builder.sslSocketFactory(trustAllSslContext().socketFactory)
            builder.hostnameVerifier { _, _ -> true }
        }
    } else {
        builder.usePlaintext()
    }
    if (c.authority.isNotEmpty()) {
        builder.overrideAuthority(c.authority)
    }
    if (c.connectProxy.isNotEmpty()) {
        val (host, port) = parseHostPort(c.connectProxy, "CONNECT-прокси")
        builder.proxyDetector { target ->
            HttpConnectProxiedSocketAddress.newBuilder()
                .setTargetAddress(target as InetSocketAddress)
                .setProxyAddress(InetSocketAddress(host, port))
                .build()
        }
    }
    val interceptors = mutableListOf<ClientInterceptor>(CaptureInterceptor)
    if (c.headers.isNotEmpty()) {
        interceptors += MetadataUtils.newAttachHeadersInterceptor(metadataOf(c.headers))
    }
    return builder.intercept(interceptors).build()
}

private fun metadataOf(headers: List<Header>): Metadata = Metadata().apply {
    for (h in headers) {
        if (h.key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
            put(Metadata.Key.of(h.key, Metadata.BINARY_BYTE_MARSHALLER), h.value.toByteArray())
        } else {
            put(Metadata.Key.of(h.key, Metadata.ASCII_STRING_MARSHALLER), h.value)
        }
    }
}

/** Доверяет любому сертификату — только для режима «не проверять сертификат». */
private fun trustAllSslContext(): SSLContext {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
}

/**
 * Устанавливает соединение с прокси заранее, чтобы при проблемах с сетью/TLS/HTTP2
 * показать одну понятную ошибку вместо провала всех проверок.
 */
internal suspend fun awaitReady(channel: ManagedChannel) {
    var state = channel.getState(true)
    while (state != ConnectivityState.READY) {
        if (state == ConnectivityState.TRANSIENT_FAILURE) {
            // Вызов без waitForReady сразу вернёт последнюю ошибку соединения.
            try {
                HealthCoroutineStub(channel).check(HealthCheckRequest.getDefaultInstance())
            } catch (e: Exception) {
                if (Status.fromThrowable(e).code == Status.Code.UNAVAILABLE) throw e
            }
        }
        val from = state
        suspendCancellableCoroutine { cont ->
            channel.notifyWhenStateChanged(from) { if (cont.isActive) cont.resume(Unit) }
        }
        state = channel.getState(true)
    }
}

/** Куда складывать заголовки и трейлеры ответа конкретного вызова. */
internal class CallCapture {
    @Volatile var headers: Metadata? = null
    @Volatile var trailers: Metadata? = null
}

internal val CAPTURE: CallOptions.Key<CallCapture> = CallOptions.Key.create("tester-capture")

/** Сохраняет заголовки и трейлеры ответа в [CallCapture], если он передан в CallOptions. */
internal object CaptureInterceptor : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val call = next.newCall(method, callOptions)
        val capture = callOptions.getOption(CAPTURE) ?: return call
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {
            override fun start(responseListener: ClientCall.Listener<RespT>, headers: Metadata) {
                val listener = object :
                    ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onHeaders(headers: Metadata) {
                        capture.headers = headers
                        super.onHeaders(headers)
                    }

                    override fun onClose(status: Status, trailers: Metadata) {
                        capture.trailers = trailers
                        super.onClose(status, trailers)
                    }
                }
                super.start(listener, headers)
            }
        }
    }
}
