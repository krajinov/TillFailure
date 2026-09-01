package com.delminiusapps.tillfailure.foundation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delminiusapps.tillfailure.foundation.ui.TillFailureFoundationTheme

@Composable
fun FoundationHomeScreen(
    state: FoundationHomeState,
    onEvent: (FoundationHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "TillFailure",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(text = "Every rep counts.")
        Spacer(Modifier.height(40.dp))
        Text(text = "Foundation Home", fontWeight = FontWeight.SemiBold)
        Text(text = "Home reps: ${state.repCount}")
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onEvent(FoundationHomeEvent.OnAddRepClick) },
        ) {
            Text("Add rep")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onEvent(FoundationHomeEvent.OnOpenDetailsClick) },
        ) {
            Text("Open foundation details")
        }
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onEvent(FoundationHomeEvent.OnShowMessageClick) },
        ) {
            Text("Send one-shot message")
        }
    }
}

@Preview
@Composable
private fun FoundationHomeScreenPreview() {
    TillFailureFoundationTheme {
        FoundationHomeScreen(
            state = FoundationHomeState(repCount = 3),
            onEvent = {},
        )
    }
}
