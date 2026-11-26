package com.whereareu.app.core.location

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import com.whereareu.app.BuildConfig
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.model.LocationFix
import com.whereareu.app.core.model.ReportFrequency
import com.whereareu.app.core.net.RuntimeConfig
import com.whereareu.app.core.net.ServerConfig
import com.whereareu.app.core.net.toUserMessage
import com.whereareu.app.core.notifications.Notifications
import com.whereareu.app.core.permissions.PermissionChecker
import com.whereareu.app.core.util.BatteryReader
import com.whereareu.app.core.util.TimeUtils
import com.whereareu.app.core.ws.DeviceSocket
import com.whereareu.app.core.ws.SocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Location foreground service.
 *
 * Responsibilities:
 *  - foreground notification (type=location, declared in the manifest)
 *  - adaptive reporting via [AdaptiveIntervalPolicy]
 *  - offline queue + retry through [LocationReporter]
 *  - device WebSocket: locate commands -> one-shot high accuracy fix
 *
 * Android 14/15 caveat: a location FGS can only be started while the app has
 * while-in-use location access (and Android 15 further restricts background
 * starts, including some BOOT_COMPLETED paths). Background starts may throw
 * ForegroundServiceStartNotAllowedException; the boot receiver therefore also
 * posts a notification asking the user to open the app.
 */
class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var provider: LocationProvider? = null
    private var socket: DeviceSocket? = null

    private var frequency: ReportFrequency = ReportFrequency.STANDARD
    private var currentIntervalMillis: Long = ReportFrequency.STANDARD.baseIntervalMillis
    private var lastFix: LocationFix? = null
    private var lastFixAtMillis: Long? = null

    @Volatile private var started = false
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> {
                serviceScope.launch {
                    // Server first (sharingPaused for the family), then local
                    // state + stop this service inside the controller.
                    AppGraph.sharingController.setSharingEnabled(false).onFailure { error ->
                        Log.w(TAG, "Pause from notification failed: ${error.message}")
                        updateNotification()
                        Notifications.showSharingActionFailed(
                            context = this@LocationTrackingService,
                            message = error.toUserMessage(),
                        )
                    }
                    // On success the controller stops this service; on failure
                    // the service keeps running (rollback), with an alert above.
                }
                return START_STICKY
            }

            else -> startTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        provider?.stop()
        provider = null
        socket?.stop()
        socket = null
        serviceScope.cancel()
        isRunning = false
        super.onDestroy()
    }

    private fun startTracking() {
        if (started) return
        if (!PermissionChecker.isForegroundLocationGranted(this)) {
            Log.w(TAG, "Location permission missing; not starting")
            Notifications.showRestoreNotification(this, "需要定位权限才能共享位置")
            stopSelf()
            return
        }

        started = true
        val initialNotification = Notifications.trackingNotification(
            context = this,
            contentText = "正在准备位置共享…",
            subText = null,
        )
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.TRACKING_NOTIFICATION_ID,
                initialNotification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                },
            )
            // Receipt for SharingController: only after startForeground succeeds.
            isRunning = true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            started = false
            isRunning = false
            Notifications.showRestoreNotification(this, "系统暂时不允许后台定位，请打开应用恢复")
            stopSelf()
            return
        }

        serviceScope.launch { runTracking() }
    }

    private suspend fun runTracking() {
        val initial = AppGraph.sessionStore.current()
        if (!initial.hasSession) {
            Notifications.showRestoreNotification(this, "请先登录并加入家庭")
            shutdown()
            return
        }
        if (initial.familyId.isNullOrBlank()) {
            // Do not report while the user has no family / has left one.
            Log.i(TAG, "No family, stopping tracking")
            shutdown()
            return
        }
        if (!initial.sharingEnabled) {
            Log.i(TAG, "Sharing disabled, stopping tracking")
            shutdown()
            return
        }

        // Seed the interceptor/websocket caches in case we were restarted from
        // BOOT_COMPLETED before the Application flow collector ran.
        RuntimeConfig.serverBaseUrl = initial.baseUrl
        RuntimeConfig.userToken = initial.userJwt
        RuntimeConfig.deviceToken = initial.deviceToken

        if (initial.deviceToken.isNullOrBlank()) {
            val registration = AppGraph.deviceManager.ensureRegistered()
            if (registration.isFailure) {
                Log.w(TAG, "Device registration failed: ${registration.exceptionOrNull()?.message}")
                Notifications.showRestoreNotification(this, "设备尚未注册，请打开应用恢复共享")
                shutdown()
                return
            }
        }

        val session = AppGraph.sessionStore.current()
        val deviceToken = session.deviceToken
        if (deviceToken.isNullOrBlank()) {
            shutdown()
            return
        }
        RuntimeConfig.deviceToken = deviceToken

        frequency = session.reportFrequency
        currentIntervalMillis = frequency.baseIntervalMillis

        val activeProvider = AppGraph.locationProviderFactory.create()
        provider = activeProvider
        activeProvider.start(currentIntervalMillis)
        updateNotification()

        val deviceSocket = DeviceSocket(
            client = AppGraph.webSocketClient,
            scope = serviceScope,
            urlProvider = { ServerConfig.websocketUrl(session.baseUrl) },
            // Read dynamically so a forced re-registration can restart the socket
            // with the fresh device token.
            tokenProvider = { RuntimeConfig.deviceToken },
            appVersion = BuildConfig.VERSION_NAME,
            batteryProvider = { BatteryReader.percent(this) },
        )
        socket = deviceSocket
        deviceSocket.start()

        serviceScope.launch {
            deviceSocket.events.collect { handleSocketEvent(it) }
        }
        serviceScope.launch {
            AppGraph.sessionStore.state
                .map { it.reportFrequency }
                .distinctUntilChanged()
                .collect { newFrequency ->
                    frequency = newFrequency
                    val speed = lastFix?.speed ?: 0f
                    applyInterval(
                        AdaptiveIntervalPolicy.intervalFor(
                            frequency = newFrequency,
                            speedMetersPerSecond = speed,
                            speedKnown = lastFix != null,
                        ),
                    )
                }
        }

        // Drain anything left from a previous offline period.
        AppGraph.locationReporter.flush()

        activeProvider.fixes.collect { fix ->
            onFix(fix)
        }
    }

    private suspend fun onFix(fix: LocationFix) {
        lastFix = fix
        lastFixAtMillis = System.currentTimeMillis()
        applyInterval(
            AdaptiveIntervalPolicy.intervalFor(
                frequency = frequency,
                speedMetersPerSecond = fix.speed,
                speedKnown = true,
            ),
        )
        updateNotification()
        AppGraph.locationReporter.submit(fix)
    }

    private fun handleSocketEvent(event: SocketEvent) {
        when (event) {
            is SocketEvent.Connected -> {
                Log.i(TAG, "Device socket connected")
                serviceScope.launch { AppGraph.locationReporter.flush() }
            }

            is SocketEvent.Disconnected -> {
                Log.i(TAG, "Device socket disconnected: ${event.reason}")
            }

            is SocketEvent.Locate -> {
                Log.i(TAG, "Locate command: ${event.commandId}")
                serviceScope.launch {
                    val commandReceivedAt = System.currentTimeMillis()
                    val oneShot = provider?.getOneShotHighAccuracyFix(timeoutMillis = 20_000L)
                    val selected = LocateFixPolicy.selectForCommand(
                        oneShot = oneShot,
                        latestContinuous = lastFix,
                        commandReceivedAt = commandReceivedAt,
                        now = System.currentTimeMillis(),
                    )
                    if (selected == null) {
                        // No fresh point exists; do not send a stale location
                        // pretending to be the locate result. Log and notify so
                        // this failure is visible (contract has no failure API).
                        Log.e(
                            TAG,
                            "Locate ${event.commandId} failed: no fresh fix " +
                                "(oneShot=$oneShot, lastFixAge=" +
                                (lastFix?.let { System.currentTimeMillis() - it.recordedAt }) + "ms)",
                        )
                        Notifications.showLocateFailed(
                            this@LocationTrackingService,
                            "无法获取当前位置，请检查系统定位和权限",
                        )
                    } else {
                        Log.i(
                            TAG,
                            "Locate ${event.commandId} reporting " +
                                "provider=${selected.provider} accuracy=${selected.accuracy} " +
                                "recordedAt=${selected.recordedAt} " +
                                "source=${if (selected === oneShot) "one-shot" else "continuous"}",
                        )
                        AppGraph.locationReporter.submit(selected, commandId = event.commandId)
                    }
                }
            }

            is SocketEvent.Unauthorized -> {
                Log.w(TAG, "Device WebSocket unauthorized; re-registering device")
                serviceScope.launch {
                    val result = AppGraph.deviceManager.ensureRegistered(force = true)
                    if (result.isSuccess) {
                        // DeviceSocket stopped itself; restart it with the new
                        // RuntimeConfig.deviceToken.
                        if (!stopping) socket?.start()
                    } else {
                        val error = result.exceptionOrNull()
                        Notifications.showRestoreNotification(
                            this@LocationTrackingService,
                            if (error is com.whereareu.app.core.net.ApiException && error.isUnauthorized) {
                                "登录已过期，请打开应用重新登录"
                            } else {
                                "设备认证失败，请打开应用恢复共享"
                            },
                        )
                        if (error is com.whereareu.app.core.net.ApiException && error.isUnauthorized) {
                            AppGraph.authRepository.logout()
                        }
                        shutdown()
                    }
                }
            }
        }
    }

    private fun applyInterval(intervalMillis: Long) {
        val normalized = intervalMillis.coerceAtLeast(ADAPTIVE_MIN_INTERVAL_MS)
        if (normalized == currentIntervalMillis) return
        currentIntervalMillis = normalized
        provider?.updateInterval(normalized)
        updateNotification()
    }

    private fun updateNotification() {
        if (stopping || !started) return
        val source = if (AppGraph.locationProviderFactory.usingFakeProvider) " · 模拟定位" else ""
        val text = "每 ${TimeUtils.intervalLabel(currentIntervalMillis)}更新$source"
        val subText = lastFixAtMillis?.let { "最后更新 ${TimeUtils.formatClock(it)}" }
        val notification = Notifications.trackingNotification(this, text, subText)
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(Notifications.TRACKING_NOTIFICATION_ID, notification)
        }
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        provider?.stop()
        provider = null
        socket?.stop()
        socket = null
        serviceScope.cancel()
        isRunning = false
        mainHandler.post {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        const val ACTION_START = "com.whereareu.app.action.START_TRACKING"
        const val ACTION_STOP = "com.whereareu.app.action.STOP_TRACKING"
        const val ACTION_PAUSE = "com.whereareu.app.action.PAUSE_SHARING"

        @Volatile
        var isRunning: Boolean = false
            private set

        private const val TAG = "LocationTrackingService"
        private const val ADAPTIVE_MIN_INTERVAL_MS = 60_000L
    }
}
