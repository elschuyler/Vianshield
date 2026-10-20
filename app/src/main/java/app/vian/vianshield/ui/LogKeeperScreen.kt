package app.vian.vianshield.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vian.vianshield.R
import app.vian.vianshield.logging.LogEntry
import app.vian.vianshield.logging.LogLevel
import app.vian.vianshield.logging.LogKeeperViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogKeeperScreen(
  viewModel: LogKeeperViewModel,
  onNavigateBack: () -> Unit,
  onRequestExport: () -> Unit,
  modifier: Modifier = Modifier
) {
  val entries by viewModel.entries.collectAsState()
  val isArchivalPending by viewModel.isArchivalPending.collectAsState()
  val isMasterSwitchEnabled by viewModel.isMasterSwitchEnabled.collectAsState()
  val currentStorageBytes by viewModel.currentStorageBytes.collectAsState()
  val isMemoryTestRunning by viewModel.isMemoryTestRunning.collectAsState()
  val droppedRecordsCount by viewModel.droppedRecordsCount.collectAsState()

  val clipboardManager = LocalClipboardManager.current
  val context = LocalContext.current

  Scaffold(
    modifier = modifier
      .fillMaxSize()
      .testTag("log_keeper_scaffold"),
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = stringResource(id = R.string.log_keeper_title),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.testTag("log_keeper_title_text")
            )
            Text(
              text = stringResource(id = R.string.log_keeper_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.testTag("btn_back_home")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back to home"
            )
          }
        },
        actions = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 8.dp)
          ) {
            Text(
              text = "Active",
              style = MaterialTheme.typography.labelMedium,
              color = if (isMasterSwitchEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.width(4.dp))
            Switch(
              checked = isMasterSwitchEnabled,
              onCheckedChange = { viewModel.setMasterSwitch(it) },
              modifier = Modifier.testTag("switch_master_logging")
            )
          }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface
        )
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(horizontal = 16.dp)
    ) {
      // Storage and Archival Pending Card
      if (isArchivalPending) {
        Card(
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("archival_pending_banner")
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.Warning,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(id = R.string.archival_pending_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
              )
              Text(
                text = stringResource(id = R.string.archival_pending_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = onRequestExport,
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
              ),
              modifier = Modifier.testTag("btn_archival_export_now")
            ) {
              Text("Export")
            }
          }
        }
      }

      // Action & Stats Bar
      Card(
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp)
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          val storageKb = currentStorageBytes / 1024
          val storagePercent = (currentStorageBytes.toDouble() / (2 * 1024 * 1024) * 100).coerceAtMost(100.0)
          val formattedSize = String.format(Locale.US, "%.2f KiB (%.1f%%)", storageKb.toDouble(), storagePercent)

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text(
                text = stringResource(id = R.string.storage_usage_label, formattedSize),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              if (droppedRecordsCount > 0L) {
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                  shape = RoundedCornerShape(4.dp),
                  color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                  Text(
                    text = "$droppedRecordsCount dropped (cap reached)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    fontSize = 10.sp
                  )
                }
              }
            }

            FilterChip(
              selected = isMemoryTestRunning,
              onClick = { viewModel.toggleMemoryTestSession() },
              label = {
                Text(
                  if (isMemoryTestRunning) stringResource(id = R.string.memory_test_session_active)
                  else stringResource(id = R.string.memory_test_session_idle),
                  fontSize = 11.sp
                )
              },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Default.Memory,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp)
                )
              },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
              ),
              modifier = Modifier.testTag("chip_memory_test")
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = onRequestExport,
              modifier = Modifier
                .weight(1f)
                .testTag("btn_export_logs")
            ) {
              Icon(
                imageVector = Icons.Default.FileDownload,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(id = R.string.action_export_logs))
            }

            OutlinedButton(
              onClick = {
                val text = viewModel.getExportContent()
                clipboardManager.setText(AnnotatedString(text))
                Toast.makeText(context, "Copied logs to clipboard", Toast.LENGTH_SHORT).show()
              },
              modifier = Modifier
                .weight(1f)
                .testTag("btn_copy_logs")
            ) {
              Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(id = R.string.action_copy_logs))
            }

            IconButton(
              onClick = { viewModel.clearLogs() },
              modifier = Modifier.testTag("btn_clear_logs")
            ) {
              Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = stringResource(id = R.string.action_clear_logs),
                tint = MaterialTheme.colorScheme.outline
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Logs List
      if (entries.isEmpty()) {
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = stringResource(id = R.string.empty_logs_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .testTag("logs_list_column"),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          items(entries) { entry ->
            LogEntryCard(entry = entry)
          }
        }
      }

      // Disclaimer footer
      Text(
        text = stringResource(id = R.string.privacy_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 8.dp)
      )
    }
  }
}

@Composable
fun LogEntryCard(entry: LogEntry, modifier: Modifier = Modifier) {
  val (containerColor, badgeColor, badgeTextColor) = when (entry.level) {
    LogLevel.INFO -> Triple(
      MaterialTheme.colorScheme.surface,
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer
    )
    LogLevel.WARN -> Triple(
      MaterialTheme.colorScheme.surface,
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.onTertiaryContainer
    )
    LogLevel.ERROR -> Triple(
      MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
      MaterialTheme.colorScheme.error,
      MaterialTheme.colorScheme.onError
    )
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = containerColor),
    shape = RoundedCornerShape(8.dp),
    modifier = modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = badgeColor
          ) {
            Text(
              text = entry.level.name,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = badgeTextColor,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = entry.component,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
          )
        }
        Text(
          text = entry.isoTime,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp
        )
      }

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = entry.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      if (entry.throwableSummary != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(
              MaterialTheme.colorScheme.surfaceVariant,
              RoundedCornerShape(4.dp)
            )
            .padding(6.dp)
        ) {
          Text(
            text = entry.throwableSummary,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
            fontSize = 10.sp
          )
        }
      }
    }
  }
}
