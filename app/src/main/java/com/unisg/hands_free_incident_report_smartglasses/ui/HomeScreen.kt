package com.unisg.hands_free_incident_report_smartglasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Preview
@Composable
fun HomeScreen(onStopClick: () -> Unit = {}) {
    // Added a default empty lambda to onStopClick to prevent NullPointerException during Preview rendering.
    // Parameters in Composables annotated with @Preview must have default values or be provided by a wrapper Preview function.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Red)
            .size(250.dp)
            .clickable(onClick = onStopClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "STOP RECORDING",
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}