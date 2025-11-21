package com.mahmutalperenunal.fling.tv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import com.mahmutalperenunal.fling.tv.R
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.mahmutalperenunal.fling.tv.Direction
import com.mahmutalperenunal.fling.tv.PinSource
import com.mahmutalperenunal.fling.tv.TransferLog
import java.text.SimpleDateFormat
import java.util.*

/**
 * Root TV screen showing Nearby/LAN actions and the last transfers list.
 */
@Composable
fun TvHomeScreen(
    // State
    advertising: Boolean,
    lanRunning: Boolean,
    uiStatus: String,
    uiFolder: String,
    totalBytesExpected: Long,
    progressText: String,
    progressFloat: Float,
    filesReceived: Int,
    filesExpected: Int,
    logs: List<TransferLog>,

    // PIN dialog state
    showPinDialog: Boolean,
    pinValue: String?,
    pinSource: PinSource?,

    // Actions
    onToggleAdvertising: () -> Unit,
    onSendToPhone: () -> Unit,
    onToggleLanServer: () -> Unit,
    onPinInfoDismiss: () -> Unit,
    onPinDecision: (Boolean) -> Unit
) {
    var showLogs by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {

        Crossfade(
            targetState = showLogs,
            label = "tv_home_logs_crossfade"
        ) { logsVisible ->
            if (logsVisible) {
                // ================= Last Transfers Screen =================
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Button(onClick = { showLogs = false }) {
                            Text(stringResource(R.string.tv_home_back_to_home))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.tv_home_last_transfers_title),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.tv_home_last_transfers_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .heightIn(max = 420.dp)
                    ) {
                        val recent = logs.takeLast(100).asReversed()
                        items(recent) { item ->
                            TransferHistoryCard(item = item)
                        }
                    }
                }
            } else {
                // ================= Home Screen (cards) =================
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(24.dp)
                        .widthIn(max = 900.dp)
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.tv_home_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )

                    Spacer(Modifier.height(24.dp))

                    // Top row: receive from phone / send to phone
                    Row(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        HomeCard(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(2.1f)
                                .heightIn(max = 220.dp),
                            title = stringResource(R.string.tv_home_receive_from_phone_title),
                            subtitle = if (advertising)
                                stringResource(R.string.tv_home_receive_from_phone_sub_active)
                            else
                                stringResource(R.string.tv_home_receive_from_phone_sub_inactive),
                            icon = Icons.Filled.Tv
                        ) {
                            onToggleAdvertising()
                        }

                        HomeCard(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(2.1f)
                                .heightIn(max = 220.dp),
                            title = stringResource(R.string.tv_home_send_to_phone_title),
                            subtitle = if (filesExpected > 0)
                                stringResource(R.string.tv_home_send_to_phone_sub_connected)
                            else
                                stringResource(R.string.tv_home_send_to_phone_sub_not_connected),
                            icon = Icons.Filled.Upload,
                            enabled = true // MainActivity already checks conditions
                        ) {
                            onSendToPhone()
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Bottom row: LAN / history
                    Row(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        HomeCard(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(2.1f)
                                .heightIn(max = 220.dp),
                            title = if (lanRunning)
                                stringResource(R.string.tv_home_lan_stop)
                            else
                                stringResource(R.string.tv_home_lan_start),
                            subtitle = stringResource(R.string.tv_home_lan_subtitle),
                            icon = Icons.Filled.PhoneAndroid
                        ) {
                            onToggleLanServer()
                        }

                        HomeCard(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(2.1f)
                                .heightIn(max = 220.dp),
                            title = stringResource(R.string.tv_home_last_transfers_title),
                            subtitle = stringResource(R.string.tv_home_history_subtitle),
                            icon = Icons.Filled.History
                        ) {
                            showLogs = true
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // Progress + status
                    if (totalBytesExpected > 0L) {
                        Text(stringResource(R.string.tv_home_total_progress, progressText))
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progressFloat },
                            modifier = Modifier.fillMaxWidth(0.7f)
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.9f),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.tv_home_status, uiStatus))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.tv_home_folder, uiFolder),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(
                                    R.string.tv_home_received_count,
                                    filesReceived,
                                    filesExpected
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Shared PIN dialog (keep as-is)
        if (showPinDialog) {
            val pin = pinValue ?: "------"
            val mode = pinSource ?: PinSource.NEARBY

            when (mode) {
                PinSource.LAN_OUT -> {
                    AlertDialog(
                        onDismissRequest = { /* forced choice */ },
                        title = { Text(stringResource(R.string.pin_verify_title)) },
                        text = {
                            Text(stringResource(R.string.pin_verify_body, pin))
                        },
                        confirmButton = {
                            Button(onClick = { onPinDecision(true) }) {
                                Text(stringResource(R.string.pin_match))
                            }
                        },
                        dismissButton = {
                            Button(onClick = { onPinDecision(false) }) {
                                Text(stringResource(R.string.pin_mismatch))
                            }
                        }
                    )
                }

                PinSource.NEARBY, PinSource.LAN_IN -> {
                    val titleText = if (mode == PinSource.NEARBY)
                        stringResource(R.string.nearby_pin_title)
                    else
                        stringResource(R.string.lan_pin_title)

                    val body = if (mode == PinSource.NEARBY) {
                        stringResource(R.string.nearby_pin_body, pin)
                    } else {
                        stringResource(R.string.lan_pin_body, pin)
                    }
                    AlertDialog(
                        onDismissRequest = { onPinInfoDismiss() },
                        title = { Text(titleText) },
                        text = { Text(body) },
                        confirmButton = {
                            Button(onClick = onPinInfoDismiss) {
                                Text(stringResource(R.string.ok))
                            }
                        },
                        dismissButton = {}
                    )
                }
            }
        }
    }
}

/**
 * Reusable home-card that works with DPAD focus and click, with animation.
 */
@Composable
private fun HomeCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (focused || pressed)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        label = "tv_home_card_bg"
    )

    val elevation by animateDpAsState(
        targetValue = if (focused || pressed) 12.dp else 4.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "tv_home_card_elevation"
    )

    val scale by animateFloatAsState(
        targetValue = if (focused || pressed) 1.03f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "tv_home_card_scale"
    )

    Card(
        modifier = modifier
            .padding(8.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Single card in the "Last Transfers" list.
 */
@Composable
private fun TransferHistoryCard(
    item: TransferLog,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    val dirText = if (item.dir == Direction.IN)
        stringResource(R.string.dir_tv_from_phone)
    else
        stringResource(R.string.dir_tv_to_phone)
    val dirIcon =
        if (item.dir == Direction.IN) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward
    val dirColor = if (item.dir == Direction.IN)
        MaterialTheme.colorScheme.tertiary
    else
        MaterialTheme.colorScheme.primary

    val statusIcon = if (item.success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline
    val statusColor = if (item.success)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.error

    val containerColor =
        if (focused) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (focused) 8.dp else 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = dirIcon,
                contentDescription = null,
                tint = dirColor,
                modifier = Modifier.size(32.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "$dirText • ${humanSize(item.size)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatTime(item.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(12.dp))

            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

// ----- Local helpers for UI formatting -----

private fun humanSize(size: Long): String {
    if (size <= 0) return "—"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        size >= gb -> String.format(Locale.US, "%.2f GB", size / gb)
        size >= mb -> String.format(Locale.US, "%.2f MB", size / mb)
        size >= kb -> String.format(Locale.US, "%.2f KB", size / kb)
        else -> "$size B"
    }
}

private fun formatTime(ts: Long): String {
    if (ts <= 0) return "—"
    val df = SimpleDateFormat("HH:mm  dd.MM.yyyy", Locale.getDefault())
    return df.format(Date(ts))
}