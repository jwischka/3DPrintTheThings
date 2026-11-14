package com.u1.slicer.network

sealed interface MoonrakerUploadResult {
    data object Success : MoonrakerUploadResult
    data class Failure(val message: String) : MoonrakerUploadResult
}
