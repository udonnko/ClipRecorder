package com.example.cliprecorder.video

import android.net.Uri

data class ClipItem(
    val uri: Uri,
    val name: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val selected: Boolean = false,
)
