package com.delminiusapps.tillfailure.foundation.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.delminiusapps.tillfailure.foundation.ui.TillFailureFoundationTheme

@Composable
fun FoundationDetailsScreen(
    state: FoundationDetailsState,
    onEvent: (FoundationDetailsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Foundation Details", fontWeight = FontWeight.Bold)
        Text(text = "Details ViewModel #${state.instanceNumber}")
        Text(text = "Previously released: ${state.previouslyReleasedCount}")
        Text(text = "Detail reps: ${state.detailRepCount}")
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onEvent(FoundationDetailsEvent.OnAddRepClick) },
        ) {
            Text("Add detail rep")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onEvent(FoundationDetailsEvent.OnBackClick) },
        ) {
            Text("Back to foundation home")
        }
    }
}

@Preview
@Composable
private fun FoundationDetailsScreenPreview() {
    TillFailureFoundationTheme {
        FoundationDetailsScreen(
            state = FoundationDetailsState(
                instanceNumber = 2,
                previouslyReleasedCount = 1,
                detailRepCount = 1,
            ),
            onEvent = {},
        )
    }
}
