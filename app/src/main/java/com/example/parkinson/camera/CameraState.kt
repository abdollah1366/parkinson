package com.example.parkinson.camera

sealed class CameraState {
    object PermissionRequired : CameraState()
    object PermissionGranted : CameraState()
    object PermissionDenied : CameraState()
    object PermissionPermanentlyDenied : CameraState()
    object CameraInitializing : CameraState()
    object CameraReady : CameraState()
    data class CameraError(val message: String) : CameraState()
}
