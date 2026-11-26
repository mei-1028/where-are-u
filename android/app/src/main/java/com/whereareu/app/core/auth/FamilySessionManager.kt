package com.whereareu.app.core.auth

/**
 * Device/foreground-service lifecycle around family transitions.
 *
 * - create/join: force a fresh device registration so the server binds this
 *   device to the current family.
 * - leave / removed: stop location tracking and clear the device token + id
 *   (and family); the user stays logged in and can join another family.
 */
class FamilySessionManager(
    private val deviceRegistrar: DeviceRegistrar,
    private val clearDevice: suspend () -> Unit,
    private val clearFamily: suspend () -> Unit,
    private val stopTracking: () -> Unit,
) {
    suspend fun onJoinedFamily(): Result<String> =
        deviceRegistrar.ensureRegistered(force = true)

    suspend fun onLeftFamilyOrRemoved() {
        stopTracking()
        clearDevice()
        clearFamily()
    }
}
