package dev.yourname.instanceddimensions.api

sealed interface TravelEnterResult {
    data object Entered : TravelEnterResult
    data class Rejected(val reason: String) : TravelEnterResult
}
