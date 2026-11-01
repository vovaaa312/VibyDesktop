package com.viby.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viby.app.model.Track
import com.viby.app.state.AppState
import com.viby.app.state.DemoData
import com.viby.app.state.SidePanel
import com.viby.app.theme.VibyColors

@Composable
fun RightPanel(state: AppState, modifier: Modifier = Modifier) {
    Panel(modifier.width(340.dp).fillMaxHeight()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab("Далее", state.sidePanel == SidePanel.QUEUE) { state.sidePanel = SidePanel.QUEUE }
                Spacer(Modifier.width(20.dp))
                Tab("Текст", state.sidePanel == SidePanel.LYRICS) { state.sidePanel = SidePanel.LYRICS }
                Spacer(Modifier.weight(1f))
                IconAction(Icons.Rounded.Close, "Закрыть", { state.sidePanel = SidePanel.NONE })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(VibyColors.Divider))
            when (state.sidePanel) {
                SidePanel.QUEUE -> QueueList(state)
                SidePanel.LYRICS -> Lyrics(state)
                SidePanel.NONE -> Unit
            }
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) VibyColors.TextPrimary else VibyColors.TextMuted,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Box(
            Modifier.width(36.dp).height(2.dp)
                .background(if (selected) VibyColors.TextPrimary else Color.Transparent)
        )
    }
}

@Composable
private fun QueueList(state: AppState) {
    val player = state.player
    val listState = rememberLazyListState()
    // keep the playing track at the top when it changes, like the Android queue panel
    LaunchedEffect(player.index) {
        if (player.index >= 0) listState.animateScrollToItem(player.index)
    }
    if (player.queue.isEmpty()) {
        EmptyHint("Очередь пуста", "Запустите плейлист или трек — здесь появится то, что играет дальше.")
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
        itemsIndexed(player.queue, key = { index, track -> "$index-${track.id}" }) { index, track ->
            QueueItem(
                track = track,
                current = index == player.index,
                past = index < player.index,
                onClick = { player.jumpTo(index) },
                onRemove = { player.removeFromQueue(index) },
            )
        }
    }
}

@Composable
private fun QueueItem(track: Track, current: Boolean, past: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    current -> VibyColors.Selected
                    hovered -> VibyColors.Hover
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(track.id, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    current -> VibyColors.AccentBright
                    past -> VibyColors.TextMuted
                    else -> VibyColors.TextPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = VibyColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hovered && !current) {
            IconAction(Icons.Rounded.Close, "Убрать из очереди", onRemove, size = 28.dp, iconSize = 18.dp)
        }
    }
}

@Composable
private fun Lyrics(state: AppState) {
    val track = state.player.current
    if (track == null) {
        EmptyHint("Ничего не играет", "Текст песни появится здесь во время воспроизведения.")
        return
    }
    val (top, bottom) = coverColors(track.id)
    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(top.copy(alpha = 0.85f), bottom)))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(track.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(12.dp))
        Text(
            DemoData.lyrics,
            color = Color.White,
            fontSize = 20.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun EmptyHint(title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = VibyColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = VibyColors.TextSecondary)
    }
}
