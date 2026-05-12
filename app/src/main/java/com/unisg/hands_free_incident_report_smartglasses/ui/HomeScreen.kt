package com.unisg.hands_free_incident_report_smartglasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Preview
@Composable
fun HomeScreen(
    background: MutableState<Color> = androidx.compose.runtime.mutableStateOf(Color.White),
    isConnected: MutableState<Boolean> = androidx.compose.runtime.mutableStateOf(false),
    isRecording: MutableState<Boolean> = androidx.compose.runtime.mutableStateOf(false),
    onStopClick: () -> Unit = {}
) {
    val textColor = if (background.value.luminance() > 0.5f) Color.Black else Color.White
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background.value)
            .size(250.dp)
            .clickable(onClick = onStopClick),
        contentAlignment = Alignment.Center
    ) {
        val recordingText = if (isRecording.value) "Recording: ON" else "Recording: OFF"
        val connectionText = if (isConnected.value) "Glasses: Connected" else "Glasses: Disconnected"
        Text(
            text = "$connectionText\n$recordingText\nSTOP RECORDING",
            color = textColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}