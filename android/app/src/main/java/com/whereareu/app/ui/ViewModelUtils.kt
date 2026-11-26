package com.whereareu.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Small factory helper: ViewModels are constructed from the AppGraph service
 * locator without adding a DI framework for M1.
 */
inline fun <reified VM : ViewModel> viewModelFactoryFor(
    noinline create: () -> VM,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { create() }
    }
