package com.whereareu.app.core

import android.content.Context
import android.util.Log
import com.whereareu.app.core.auth.ApiDeviceApi
import com.whereareu.app.core.auth.AuthRepository
import com.whereareu.app.core.auth.DeviceManager
import com.whereareu.app.core.auth.FamilySessionManager
import com.whereareu.app.core.auth.SessionStore
import com.whereareu.app.core.data.AlertsRepository
import com.whereareu.app.core.data.FamilyRepository
import com.whereareu.app.core.data.HistoryRepository
import com.whereareu.app.core.location.LocationFlushScheduler
import com.whereareu.app.core.location.LocationProviderFactory
import com.whereareu.app.core.location.LocationReporter
import com.whereareu.app.core.location.LocationTrackingService
import com.whereareu.app.core.location.OfflineLocationQueue
import com.whereareu.app.core.location.SharingController
import com.whereareu.app.core.location.SharingSyncWorker
import com.whereareu.app.core.location.TrackingManager
import com.whereareu.app.core.location.TrackingStartReceipt
import com.whereareu.app.core.net.ApiClientFactory
import com.whereareu.app.core.net.ApiException
import com.whereareu.app.core.net.ApiService
import com.whereareu.app.core.net.RuntimeConfig
import com.whereareu.app.core.util.AddressResolver
import com.whereareu.app.core.util.AndroidAddressResolver
import com.whereareu.app.core.util.BatteryReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File

/**
 * Minimal manual DI. There is only one process and one app module, so a
 * service locator keeps the scaffold readable without adding Hilt to M1.
 */
object AppGraph {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * UI intents raised outside the Compose tree (notification taps). The map /
     * scaffold consumes and clears them.
     */
    val pendingMemberFocus = MutableStateFlow<String?>(null)
    val pendingAlertsOpen = MutableStateFlow(false)
    /** In-memory unread badge; refreshed by the inbox and the device socket. */
    val unreadAlerts = MutableStateFlow(0)

    fun requestMemberFocus(userId: String) {
        pendingMemberFocus.value = userId
    }

    fun requestAlertsOpen() {
        pendingAlertsOpen.value = true
    }

    fun bumpUnreadAlerts(delta: Int = 1) {
        unreadAlerts.update { current -> (current + delta).coerceAtLeast(0) }
    }

    lateinit var appContext: Context
        private set
    lateinit var sessionStore: SessionStore
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var deviceManager: DeviceManager
        private set
    lateinit var familySessionManager: FamilySessionManager
        private set
    lateinit var familyRepository: FamilyRepository
        private set
    lateinit var historyRepository: HistoryRepository
        private set
    lateinit var alertsRepository: AlertsRepository
        private set
    lateinit var addressResolver: AddressResolver
        private set
    lateinit var offlineQueue: OfflineLocationQueue
        private set
    lateinit var locationReporter: LocationReporter
        private set
    lateinit var locationProviderFactory: LocationProviderFactory
        private set
    lateinit var sharingController: SharingController
        private set
    lateinit var userApi: ApiService
        private set
    lateinit var deviceApi: ApiService
        private set
    lateinit var webSocketClient: OkHttpClient
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            appContext = app
            sessionStore = SessionStore(app)

            val clients = ApiClientFactory()
            userApi = clients.userApi
            deviceApi = clients.deviceApi
            webSocketClient = clients.webSocketClient

            deviceManager = DeviceManager(ApiDeviceApi(userApi), sessionStore)
            offlineQueue = OfflineLocationQueue(
                file = File(app.filesDir, OfflineLocationQueue.FILE_NAME),
            )
            authRepository = AuthRepository(
                api = userApi,
                store = sessionStore,
                deviceManager = deviceManager,
                stopTracking = { TrackingManager.stop(app) },
                clearOfflineQueue = { offlineQueue.clear() },
            )
            familySessionManager = FamilySessionManager(
                deviceRegistrar = deviceManager,
                clearDevice = { sessionStore.clearDevice() },
                clearFamily = { sessionStore.clearFamily() },
                stopTracking = { TrackingManager.stop(app) },
            )
            familyRepository = FamilyRepository(userApi, sessionStore)
            historyRepository = HistoryRepository(userApi)
            alertsRepository = AlertsRepository(userApi)
            addressResolver = AndroidAddressResolver(app)
            val reauthMutex = Mutex()
            locationReporter = LocationReporter(
                api = { request -> deviceApi.reportLocations(request) },
                queue = offlineQueue,
                batteryProvider = { BatteryReader.percent(app) },
                scheduleFlush = { LocationFlushScheduler.schedule(app) },
                onUnauthorized = { failedToken ->
                    reauthMutex.withLock {
                        if (failedToken.isNullOrBlank() || RuntimeConfig.deviceToken == failedToken) {
                            val result = deviceManager.ensureRegistered(force = true)
                            if (result.isFailure) {
                                val error = result.exceptionOrNull()
                                if (error is ApiException && error.isUnauthorized) {
                                    authRepository.logout()
                                    offlineQueue.clear()
                                }
                            }
                        }
                    }
                },
            )
            locationProviderFactory = LocationProviderFactory(app)
            sharingController = SharingController(
                setRemotePaused = { paused -> familyRepository.setSharingPaused(paused) },
                readLocalSharingEnabled = { sessionStore.current().sharingEnabled },
                saveLocalSharingEnabled = { enabled -> sessionStore.setSharingEnabled(enabled) },
                startTracking = { TrackingManager.start(app) },
                awaitTrackingRunning = {
                    TrackingStartReceipt.awaitRunning(
                        isRunning = { LocationTrackingService.isRunning },
                    )
                },
                stopTracking = { TrackingManager.stop(app) },
                enqueueServerPauseRetry = {
                    sessionStore.setPendingSharingPause(true)
                    SharingSyncWorker.enqueue(app)
                },
                clearServerSyncPending = { sessionStore.setPendingSharingPause(false) },
                hasPendingServerPause = { sessionStore.current().pendingSharingPause },
            )

            appScope.launch {
                sessionStore.state.collect { session ->
                    RuntimeConfig.serverBaseUrl = session.baseUrl
                    RuntimeConfig.userToken = session.userJwt
                    RuntimeConfig.deviceToken = session.deviceToken
                }
            }

            initialized = true
            Log.i(TAG, "AppGraph initialized")
        }
    }

    private const val TAG = "AppGraph"
}
