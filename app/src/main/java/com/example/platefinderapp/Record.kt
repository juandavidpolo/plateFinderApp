package com.example.platefinderapp

import android.graphics.Bitmap

data class AppRecord(
    val plate: String,
    val isReported: Boolean,
    val image: Bitmap,
    val location: String
)