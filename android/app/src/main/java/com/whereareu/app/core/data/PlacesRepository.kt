package com.whereareu.app.core.data

import com.whereareu.app.core.net.ApiService
import com.whereareu.app.core.net.CreatePlaceRequest
import com.whereareu.app.core.net.DeletePlaceResponse
import com.whereareu.app.core.net.PlaceDto
import com.whereareu.app.core.net.PlacesResponse
import com.whereareu.app.core.net.UpdatePlaceRequest
import com.whereareu.app.core.net.safeApiCall

/** Family places (home/school/company) used by geofence rules. */
class PlacesRepository(private val api: ApiService) {

    suspend fun places(): Result<List<PlaceDto>> =
        safeApiCall { api.places().places }

    suspend fun create(
        name: String,
        type: String,
        lat: Double,
        lng: Double,
        radiusMeters: Int,
    ): Result<PlaceDto> = safeApiCall {
        api.createPlace(
            CreatePlaceRequest(
                name = name,
                type = type,
                lat = lat,
                lng = lng,
                radiusMeters = radiusMeters,
            ),
        ).place
    }

    suspend fun update(
        placeId: String,
        name: String? = null,
        type: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        radiusMeters: Int? = null,
    ): Result<PlaceDto> = safeApiCall {
        api.updatePlace(
            placeId,
            UpdatePlaceRequest(
                name = name,
                type = type,
                lat = lat,
                lng = lng,
                radiusMeters = radiusMeters,
            ),
        ).place
    }

    suspend fun delete(placeId: String): Result<DeletePlaceResponse> =
        safeApiCall { api.deletePlace(placeId) }
}
