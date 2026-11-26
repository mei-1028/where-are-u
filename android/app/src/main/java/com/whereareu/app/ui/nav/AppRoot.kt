package com.whereareu.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.whereareu.app.ui.auth.LoginScreen
import com.whereareu.app.ui.auth.RegisterScreen
import com.whereareu.app.ui.family.FamilySetupScreen
import com.whereareu.app.ui.onboarding.OnboardingScreen
import com.whereareu.app.ui.splash.SplashScreen

@Composable
fun AppRoot() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onResolved = { destination ->
                    navController.navigate(destination) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                onAuthenticated = { destination ->
                    navController.navigate(destination) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenRegister = {
                    navController.navigate(Routes.REGISTER) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onAuthenticated = { destination ->
                    navController.navigate(destination) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.FAMILY_SETUP) {
            FamilySetupScreen(
                onNavigate = { destination ->
                    navController.navigate(destination) {
                        popUpTo(Routes.FAMILY_SETUP) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Routes.MAIN) {
            MainScaffold(
                onLeftFamily = {
                    navController.navigate(Routes.FAMILY_SETUP) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onSessionExpired = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
