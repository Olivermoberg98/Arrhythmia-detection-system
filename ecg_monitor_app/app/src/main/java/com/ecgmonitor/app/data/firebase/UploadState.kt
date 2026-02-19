package com.ecgmonitor.app.data.firebase

sealed class UploadState {
    object Idle             : UploadState()
    object Uploading        : UploadState()
    object NotAuthenticated : UploadState()
    data class Success(val sessionId: String) : UploadState()
    data class Error(val message: String)     : UploadState()
}