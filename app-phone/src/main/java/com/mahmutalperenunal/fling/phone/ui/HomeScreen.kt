package com.mahmutalperenunal.fling.phone.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mahmutalperenunal.fling.phone.Direction
import com.mahmutalperenunal.fling.phone.R
import com.mahmutalperenunal.fling.phone.TransferLog
import java.util.Locale

/**
 * Phone main screen:
 * - Home: actions for nearby & LAN transfers
 * - Last transfers: recent history
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneHomeScreen(
    // State
    discovering: Boolean,
    lanServerRunning: Boolean,
    targetTreeUri: Uri?,
    lastMessage: String,
    totalBytesExpectedIn: Long,
    totalBytesCompletedIn: Long,
    filesExpectedIn: Int,
    filesReceivedIn: Int,
    phoneLogs: List<TransferLog>,

    // Actions
    onToggleNearbyDiscovery: () -> Unit,
    onSendLanFromPending: () -> Unit,
    onPickTargetFolder: () -> Unit,
    onToggleLanServer: () -> Unit
) {
    var showLogs by remember { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(
                targetState = showLogs,
                label = "phone_home_logs_crossfade"
            ) { logsVisible ->
                if (logsVisible) {
                    PhoneLastTransfersScreen(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        phoneLogs = phoneLogs,
                        onBackToHome = { showLogs = false }
                    )
                } else {
                    PhoneHomeMainScreen(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        discovering = discovering,
                        lanServerRunning = lanServerRunning,
                        targetTreeUri = targetTreeUri,
                        lastMessage = lastMessage,
                        totalBytesExpectedIn = totalBytesExpectedIn,
                        totalBytesCompletedIn = totalBytesCompletedIn,
                        filesExpectedIn = filesExpectedIn,
                        filesReceivedIn = filesReceivedIn,
                        onToggleNearbyDiscovery = onToggleNearbyDiscovery,
                        onSendLanFromPending = onSendLanFromPending,
                        onPickTargetFolder = onPickTargetFolder,
                        onToggleLanServer = onToggleLanServer,
                        onShowLogs = { showLogs = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneHomeMainScreen(
    modifier: Modifier = Modifier,
    discovering: Boolean,
    lanServerRunning: Boolean,
    targetTreeUri: Uri?,
    lastMessage: String,
    totalBytesExpectedIn: Long,
    totalBytesCompletedIn: Long,
    filesExpectedIn: Int,
    filesReceivedIn: Int,
    onToggleNearbyDiscovery: () -> Unit,
    onSendLanFromPending: () -> Unit,
    onPickTargetFolder: () -> Unit,
    onToggleLanServer: () -> Unit,
    onShowLogs: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.phone_home_title),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.phone_home_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PhoneHomeCard(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1.4f),
                title = if (discovering)
                    stringResource(R.string.phone_card_nearby_on)
                else
                    stringResource(R.string.phone_card_nearby_off),
                subtitle = if (discovering)
                    stringResource(R.string.phone_card_nearby_on_sub)
                else
                    stringResource(R.string.phone_card_nearby_off_sub),
                icon = Icons.Filled.Radio
            ) {
                onToggleNearbyDiscovery()
            }

            PhoneHomeCard(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1.4f),
                title = stringResource(R.string.phone_card_send_title),
                subtitle = stringResource(R.string.phone_card_send_subtitle),
                icon = Icons.Filled.CloudUpload,
                enabled = true
            ) {
                onSendLanFromPending()
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PhoneHomeCard(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1.4f),
                title = stringResource(R.string.phone_card_folder_title),
                subtitle = if (targetTreeUri == null)
                    stringResource(R.string.phone_card_folder_sub_none)
                else
                    stringResource(R.string.phone_card_folder_sub_ready),
                icon = Icons.Filled.Folder
            ) {
                onPickTargetFolder()
            }

            PhoneHomeCard(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1.4f),
                title = if (lanServerRunning)
                    stringResource(R.string.phone_card_lan_on)
                else
                    stringResource(R.string.phone_card_lan_off),
                subtitle = stringResource(R.string.phone_card_lan_subtitle),
                icon = Icons.Filled.Cable
            ) {
                onToggleLanServer()
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = onShowLogs) {
            Icon(Icons.Filled.History, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.phone_last_transfers_button))
        }

        Spacer(Modifier.height(20.dp))

        AnimatedVisibility(
            visible = totalBytesExpectedIn > 0L,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val progress = (totalBytesCompletedIn.toFloat() /
                        totalBytesExpectedIn.coerceAtLeast(1L).toFloat())
                    .coerceIn(0f, 1f)
                val pct = ((totalBytesCompletedIn * 100L) /
                        totalBytesExpectedIn.coerceAtLeast(1L)).toInt()

                Text(stringResource(R.string.phone_progress_label, pct))
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        val folderLabel = targetTreeUri?.toString()
            ?: stringResource(R.string.phone_target_none)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.phone_status_label, lastMessage),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.phone_target_folder_label,
                        folderLabel
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.phone_received_label,
                        filesReceivedIn,
                        filesExpectedIn
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PhoneLastTransfersScreen(
    modifier: Modifier = Modifier,
    phoneLogs: List<TransferLog>,
    onBackToHome: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBackToHome) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.phone_last_transfers_title),
                style = MaterialTheme.typography.titleLarge
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.phone_last_transfers_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
        ) {
            val recent = phoneLogs.takeLast(100).asReversed()
            items(recent) { item ->
                PhoneTransferHistoryCard(item = item)
            }
        }
    }
}

@Composable
fun PhoneTransferHistoryCard(
    item: TransferLog,
    modifier: Modifier = Modifier
) {
    val dirText = if (item.dir == Direction.IN)
        stringResource(R.string.phone_dir_in)
    else
        stringResource(R.string.phone_dir_out)

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

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = dirIcon,
                contentDescription = null,
                tint = dirColor,
                modifier = Modifier.size(24.dp)
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
                    text = stringResource(
                        R.string.phone_log_line,
                        dirText,
                        item.size
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatTime(item.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun PhoneHomeCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
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
        label = "home_card_bg"
    )

    val elevation by animateDpAsState(
        targetValue = if (focused || pressed) 10.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "home_card_elevation"
    )

    val scale by animateFloatAsState(
        targetValue = if (focused || pressed) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "home_card_scale"
    )

    Card(
        modifier = modifier
            .onFocusChanged { focusState -> focused = focusState.isFocused }
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
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ----- Local helpers -----
private fun formatTime(ts: Long): String {
    if (ts <= 0) return "—"
    val df = java.text.SimpleDateFormat("HH:mm  dd.MM.yyyy", Locale.getDefault())
    return df.format(java.util.Date(ts))
}