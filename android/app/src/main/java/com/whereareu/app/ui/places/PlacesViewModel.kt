package com.whereareu.app.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whereareu.app.core.data.PlacesRepository
import com.whereareu.app.core.net.PlaceDto
import com.whereareu.app.core.net.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlacesUiState(
    val loading: Boolean = true,
    val places: List<PlaceDto> = emptyList(),
    val error: String? = null,
)

class PlacesViewModel(private val repository: PlacesRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(PlacesUiState())
    val uiState: StateFlow<PlacesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            repository.places()
                .onSuccess { places ->
                    _uiState.update { it.copy(loading = false, places = places, error = null) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(loading = false, error = error.toUserMessage()) }
                }
        }
    }

    fun upsert(place: PlaceDto) {
        _uiState.update { state ->
            val exists = state.places.any { it.id == place.id }
            state.copy(
                places = if (exists) {
                    state.places.map { if (it.id == place.id) place else it }
                } else {
                    state.places + place
                },
            )
        }
    }

    fun remove(placeId: String) {
        _uiState.update { state ->
            state.copy(places = state.places.filter { it.id != placeId })
        }
    }
}
