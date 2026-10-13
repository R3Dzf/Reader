package com.r3dzf.reader

data class DriveFile(
    val id: String,
    val name: String,
    val extension: String
)

data class ReaderUiState(
    val microsoftConfigured: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val status: String? = null,
    val files: List<DriveFile> = emptyList()
)
