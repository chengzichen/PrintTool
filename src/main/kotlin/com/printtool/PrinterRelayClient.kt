package com.printtool

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.prefs.Preferences
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class RemotePrintJob(
    val jobId: Long,
    val type: String,
    val format: String,
    val fileName: String,
    val content: String,
    val paperWidthMm: Float = 80f,
    val paperHeightMm: Float = 130f,
    val labelsPerPage: Int = 3,
    val paperTemplate: String = "80x130",
    val autoPrint: Boolean = true
)

class PrinterRelayClient(private val prefs: Preferences) {
    companion object {
        private const val DEFAULT_SERVER_URL = "http://www.hommo.cn:8080"
        private const val SERVER_URL_KEY = "serverUrl"
        private const val DEVICE_ID_KEY = "cloudDeviceId"
        private const val DEVICE_TOKEN_KEY = "cloudDeviceToken"
        private const val DEVICE_FINGERPRINT_KEY = "deviceFingerprint"
    }

    private val gson = Gson()
    // Keep the WebSocket alive through idle periods. Gorilla WebSocket on the
    // server responds to these control pings automatically.
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val reconnectScheduler = Executors.newSingleThreadScheduledExecutor()
    private var socket: WebSocket? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var reconnectAttempt = 0
    private var connectionGeneration = 0L
    @Volatile private var manualClose = false

    var onJob: ((RemotePrintJob) -> Unit)? = null
    var onState: ((String) -> Unit)? = null

    fun serverUrl(): String = prefs.get(SERVER_URL_KEY, DEFAULT_SERVER_URL).trimEnd('/')
    fun deviceId(): String? = prefs.get(DEVICE_ID_KEY, null)
    fun isPaired(): Boolean = !deviceId().isNullOrBlank() && !prefs.get(DEVICE_TOKEN_KEY, null).isNullOrBlank()

    private fun machineName(): String = System.getenv("COMPUTERNAME")
        ?: System.getenv("HOSTNAME")
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-computer")

    /**
     * This is the cloud relay/client name, not the physical printer name.
     * A computer can have multiple local printers; the actual printer remains
     * selected independently in the desktop application.
     */
    fun displayName(): String = "打印客户端-${machineName()}"

    private fun platform(): String = "${System.getProperty("os.name", "unknown")} ${System.getProperty("os.arch", "unknown")}"

    private fun deviceFingerprint(): String {
        prefs.get(DEVICE_FINGERPRINT_KEY, null)?.let { return it }
        val macs = buildList {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val address = interfaces.nextElement().hardwareAddress
                if (address != null && address.isNotEmpty()) add(address.joinToString("") { byte -> "%02x".format(byte) })
            }
        }.sorted().joinToString(",")
        val source = listOf(machineName(), platform(), System.getProperty("user.name", "unknown"), macs).joinToString("|")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        prefs.put(DEVICE_FINGERPRINT_KEY, fingerprint)
        return fingerprint
    }

    fun pair(code: String, name: String): Result<String> {
        return try {
            val body = RequestBody.create(
                "application/json; charset=utf-8".toMediaType(),
                gson.toJson(mapOf(
                    "code" to code.trim(),
                    "name" to name.trim(),
                    "deviceFingerprint" to deviceFingerprint(),
                    "machineName" to machineName(),
                    "platform" to platform()
                ))
            )
            val request = Request.Builder().url("${serverUrl()}/printer/pair").post(body).build()
            http.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) return Result.failure(IllegalStateException(responseBody.ifBlank { "配对失败: HTTP ${response.code}" }))
                val data = gson.fromJson(responseBody, JsonObject::class.java).getAsJsonObject("data")
                val id = data.get("deviceId").asString
                val token = data.get("token").asString
                prefs.put(DEVICE_ID_KEY, id)
                prefs.put(DEVICE_TOKEN_KEY, token)
                Result.success(id)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Synchronized
    fun connect() {
        manualClose = false
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        val id = deviceId()
        val token = prefs.get(DEVICE_TOKEN_KEY, null)
        if (id.isNullOrBlank() || token.isNullOrBlank()) {
            onState?.invoke("未绑定打印设备")
            return
        }
        // A stale socket can still invoke onClosing/onFailure after it is
        // replaced. Generation checks below ensure it cannot start another
        // reconnect cycle or overwrite the current connection state.
        val generation = ++connectionGeneration
        val previousSocket = socket
        socket = null
        previousSocket?.close(1000, "replaced")
        val wsUrl = serverUrl().replaceFirst("^http://".toRegex(), "ws://").replaceFirst("^https://".toRegex(), "wss://")
        val request = Request.Builder()
            .url("$wsUrl/ws/printer?deviceId=${java.net.URLEncoder.encode(id, StandardCharsets.UTF_8)}")
            .header("Authorization", "Bearer $token")
            .build()
        onState?.invoke("正在连接服务器")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            private fun isCurrent(webSocket: WebSocket): Boolean =
                generation == connectionGeneration && socket === webSocket

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(webSocket)) {
                    webSocket.close(1000, "stale connection")
                    return
                }
                reconnectAttempt = 0
                reconnectFuture?.cancel(false)
                reconnectFuture = null
                onState?.invoke("已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(webSocket)) return
                try {
                    val root = gson.fromJson(text, JsonObject::class.java)
                    if (root.get("type")?.asString != "print.job") return
                    val job = root.getAsJsonObject("job")
                    val data = job.getAsJsonObject("data")
                    onJob?.invoke(RemotePrintJob(
                        job.get("id").asLong,
                        data.get("type")?.asString.orEmpty(),
                        data.get("format")?.asString.orEmpty(),
                        data.get("fileName")?.asString.orEmpty(),
                        data.get("content")?.asString.orEmpty(),
                        data.get("paperWidthMm")?.asFloat ?: 80f,
                        data.get("paperHeightMm")?.asFloat ?: 130f,
                        data.get("labelsPerPage")?.asInt ?: 3,
                        data.get("paperTemplate")?.asString ?: "80x130",
                        data.get("autoPrint")?.asBoolean ?: true
                    ))
                } catch (e: Exception) {
                    onState?.invoke("任务解析失败: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(webSocket)) return
                onState?.invoke("连接断开")
                scheduleReconnect(generation)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(webSocket)) return
                onState?.invoke("连接失败，将自动重试: ${t.message ?: "未知错误"}")
                scheduleReconnect(generation)
            }
        })
    }

    @Synchronized
    private fun scheduleReconnect(generation: Long) {
        if (generation != connectionGeneration) return
        if (manualClose || !isPaired() || reconnectFuture?.isDone == false) return
        val delaySeconds = minOf(30L, 2L shl minOf(reconnectAttempt, 4))
        reconnectAttempt++
        onState?.invoke("连接断开，${delaySeconds}秒后重试")
        reconnectFuture = reconnectScheduler.schedule({
            if (generation == connectionGeneration) connect()
        }, delaySeconds, TimeUnit.SECONDS)
    }

    fun sendStatus(jobId: Long, type: String, errorMessage: String? = null) {
        val data = mutableMapOf<String, Any>("type" to type, "jobId" to jobId)
        if (!errorMessage.isNullOrBlank()) data["errorMessage"] = errorMessage.take(512)
        socket?.send(gson.toJson(data))
    }

    @Synchronized
    fun close() {
        manualClose = true
        connectionGeneration++
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        val currentSocket = socket
        socket = null
        currentSocket?.close(1000, "application exit")
        socket = null
        reconnectScheduler.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
