package com.wji.meditationplayer.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wji.meditationplayer.ui.formatDuration

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTrack: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.openPicked(uri, onOpenTrack)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("冥想播放器", style = MaterialTheme.typography.headlineSmall)

        Button(
            onClick = { picker.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("選擇音檔")
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (recent.isNotEmpty()) {
            Text("最近播放", style = MaterialTheme.typography.titleSmall)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recent, key = { it.fileKey }) { track ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.openExisting(track.fileKey, onOpenTrack) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(track.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = buildString {
                                append("原始 ${formatDuration(track.originalDurationMs)}")
                                if (track.enabledGapCount > 0) {
                                    append(" · ${track.enabledGapCount} 個插入點")
                                    append(" · 總長 ${formatDuration(track.totalDurationMs)}")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
