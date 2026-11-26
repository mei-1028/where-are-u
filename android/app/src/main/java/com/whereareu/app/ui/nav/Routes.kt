package com.whereareu.app.ui.nav

import android.net.Uri

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FAMILY_SETUP = "family_setup"
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val INVITE = "invite"
    const val HISTORY = "history?deviceId={deviceId}&name={name}"

    fun history(deviceId: String, name: String): String =
        "history?deviceId=${Uri.encode(deviceId)}&name=${Uri.encode(name)}"
}
