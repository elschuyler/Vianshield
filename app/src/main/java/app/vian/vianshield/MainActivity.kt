package app.vian.vianshield

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicInteger
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vian.vianshield.logging.LogCatcher
import app.vian.vianshield.logging.LogKeeperViewModel
import app.vian.vianshield.logging.LogLevel
import app.vian.vianshield.ui.LogKeeperScreen
import app.vian.vianshield.ui.theme.VianshieldTheme
import app.vian.vianshield.vpn.VianshieldVpnService
import app.vian.vianshield.vpn.VpnConnectionState
import app.vian.vianshield.vpn.VpnStateHolder
import app.vian.vianshield.vpn.VpnUiState
import app.vian.vianshield.vpn.WireGuardConfigParser
import app.vian.vianshield.vpn.WireGuardConfigStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

enum class AppDestination {
  HOME,
  LOG_KEEPER
}

class MainActivity : ComponentActivity() {

  private val connectAttemptCounter = AtomicInteger(0)
  @VisibleForTesting
  internal var currentConsentAttempt = 0
  @VisibleForTesting
  internal val isConsentPendingState = mutableStateOf(false)

  @Suppress("DEPRECATION")
  @VisibleForTesting
  public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_CODE_VPN_CONSENT) {
      handleConsentResult(resultCode)
    }
  }

  @VisibleForTesting
  internal fun handleConsentResult(resultCode: Int) {
    val attempt = currentConsentAttempt
    isConsentPendingState.value = false
    LogCatcher.getInstance(this).log(
      LogLevel.INFO,
      TAG,
      "VPN consent activity-result received: attempt=#$attempt, resultCode=$resultCode."
    )
    if (resultCode == Activity.RESULT_OK) {
      LogCatcher.getInstance(this).log(
        LogLevel.INFO,
        TAG,
        "Connect request #$attempt: VPN consent granted by user (RESULT_OK). Starting VianshieldVpnService."
      )
      VianshieldVpnService.start(this)
    } else {
      val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
      val isVpnDisallowed = userManager?.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN) ?: false
      LogCatcher.getInstance(this).log(
        LogLevel.WARN,
        TAG,
        "Connect request #$attempt: VPN consent not granted or dismissed (resultCode=$resultCode, disallowConfigVpn=$isVpnDisallowed). " +
          "Note: If another VPN has Always-on VPN enabled in Android Settings, the system dialog dismisses automatically."
      )
      val message = if (isVpnDisallowed) {
        "VPN configuration is disallowed on this device."
      } else {
        getString(R.string.vpn_permission_denied)
      }
      Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
  }

  @VisibleForTesting
  internal fun handleConnectRequested() {
    val attempt = connectAttemptCounter.incrementAndGet()
    LogCatcher.getInstance(this).log(
      LogLevel.INFO,
      TAG,
      "Connect request #$attempt received from user."
    )

    if (isConsentPendingState.value) {
      LogCatcher.getInstance(this).log(
        LogLevel.WARN,
        TAG,
        "Connect request #$attempt rejected: already awaiting VPN consent."
      )
      return
    }

    val currentVpnState = VpnStateHolder.state.value.connectionState
    if (currentVpnState == VpnConnectionState.CONNECTING || currentVpnState == VpnConnectionState.TUNNEL_ACTIVE_UNVERIFIED) {
      LogCatcher.getInstance(this).log(
        LogLevel.WARN,
        TAG,
        "Connect request #$attempt rejected: VPN tunnel is already $currentVpnState."
      )
      return
    }

    val prepareIntent = try {
      VpnService.prepare(this)
    } catch (e: Exception) {
      LogCatcher.getInstance(this).log(
        LogLevel.ERROR,
        TAG,
        "Connect request #$attempt: exception calling VpnService.prepare(): ${e.message}"
      )
      null
    }

    if (prepareIntent == null) {
      LogCatcher.getInstance(this).log(
        LogLevel.INFO,
        TAG,
        "Connect request #$attempt: VpnService.prepare() returned null (already prepared/granted). Starting VianshieldVpnService directly."
      )
      VianshieldVpnService.start(this)
    } else {
      val targetComponent = prepareIntent.component?.flattenToShortString() ?: "system default"
      LogCatcher.getInstance(this).log(
        LogLevel.INFO,
        TAG,
        "Connect request #$attempt: VpnService.prepare() returned Intent (target=$targetComponent)."
      )

      // Diagnostic checks: system restrictions and dialog component resolvability
      val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
      val isVpnDisallowed = userManager?.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN) ?: false
      if (isVpnDisallowed) {
        LogCatcher.getInstance(this).log(
          LogLevel.WARN,
          TAG,
          "Connect request #$attempt: UserManager.DISALLOW_CONFIG_VPN is TRUE on this device. System dialog will be blocked."
        )
      }

      val resolveInfo = packageManager.resolveActivity(prepareIntent, 0)
      val resolvedComponent = resolveInfo?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "NONE"
      LogCatcher.getInstance(this).log(
        LogLevel.INFO,
        TAG,
        "Connect request #$attempt: resolveActivity=$resolvedComponent (exported=${resolveInfo?.activityInfo?.exported})"
      )

      try {
        val appInfo = packageManager.getApplicationInfo("com.android.vpndialogs", 0)
        LogCatcher.getInstance(this).log(
          LogLevel.INFO,
          TAG,
          "Connect request #$attempt: com.android.vpndialogs enabled=${appInfo.enabled}"
        )
      } catch (e: Exception) {
        LogCatcher.getInstance(this).log(
          LogLevel.WARN,
          TAG,
          "Connect request #$attempt: package com.android.vpndialogs status: ${e.message}"
        )
      }

      currentConsentAttempt = attempt
      isConsentPendingState.value = true
      try {
        LogCatcher.getInstance(this).log(
          LogLevel.INFO,
          TAG,
          "Connect request #$attempt: immediately before startActivityForResult(requestCode=$REQUEST_CODE_VPN_CONSENT)."
        )
        @Suppress("DEPRECATION")
        startActivityForResult(prepareIntent, REQUEST_CODE_VPN_CONSENT)
        LogCatcher.getInstance(this).log(
          LogLevel.INFO,
          TAG,
          "Connect request #$attempt: startActivityForResult launched successfully."
        )
      } catch (e: ActivityNotFoundException) {
        isConsentPendingState.value = false
        LogCatcher.getInstance(this).log(
          LogLevel.ERROR,
          TAG,
          "Connect request #$attempt: ActivityNotFoundException launching VPN consent: ${e.message}"
        )
        Toast.makeText(this, "VPN dialog activity not found on device.", Toast.LENGTH_LONG).show()
      } catch (e: Exception) {
        isConsentPendingState.value = false
        LogCatcher.getInstance(this).log(
          LogLevel.ERROR,
          TAG,
          "Connect request #$attempt: exception thrown while launching consent activity: ${e.message}"
        )
        Toast.makeText(this, "Could not open VPN permission dialog: ${e.message}", Toast.LENGTH_SHORT).show()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // If waiting for consent and user granted it outside or returning with grant:
    if (isConsentPendingState.value && VpnService.prepare(this) == null) {
      LogCatcher.getInstance(this).log(
        LogLevel.INFO,
        TAG,
        "onResume: VPN permission verified as granted while awaiting consent."
      )
      isConsentPendingState.value = false
      VianshieldVpnService.start(this)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Initialize LogCatcher lifecycle and process monitoring
    LogCatcher.getInstance(this).initializeProcess()

    // Initialize Gomobile runtime context safely
    app.vian.vianshield.vpn.GomobileRuntime.init(this)

    // Load persisted configuration from private internal storage
    val initialConfig = WireGuardConfigStore(this).loadConfig()
    if (initialConfig != null) {
      VpnStateHolder.updateConfig(initialConfig)
    }

    setContent {
      VianshieldTheme {
        val logKeeperViewModel: LogKeeperViewModel = viewModel()
        var currentDestination by remember { mutableStateOf(AppDestination.HOME) }
        val context = LocalContext.current
        val vpnUiState by VpnStateHolder.state.collectAsState()

        val exportLauncher = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.CreateDocument("text/plain")
        ) { uri: Uri? ->
          if (uri != null) {
            try {
              context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                  writer.write(logKeeperViewModel.getExportContent())
                  writer.flush()
                }
              }
              logKeeperViewModel.onExportSucceeded()
              Toast.makeText(context, "Logs successfully exported and working buffer archived.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
              try {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
              } catch (_: Exception) {
                // Provider may not support delete
              }
              logKeeperViewModel.onExportFailed(e.message ?: "Write failed")
              Toast.makeText(context, "Failed to export logs: ${e.message}", Toast.LENGTH_SHORT).show()
            }
          } else {
            logKeeperViewModel.onExportFailed("User canceled document creation")
          }
        }

        val isConsentPending by isConsentPendingState

        // Document picker launcher for standard WireGuard .conf file
        val importConfLauncher = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
          if (uri != null) {
            try {
              var fileName = "tunnel.conf"
              var reportedSize: Long = -1L
              context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
              )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                  if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex) ?: "tunnel.conf"
                  }
                  if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    reportedSize = cursor.getLong(sizeIndex)
                  }
                }
              }

              // Fast preliminary extension check before reading any stream bytes
              if (!fileName.endsWith(".conf", ignoreCase = true)) {
                throw IllegalArgumentException("Unsupported file — choose a WireGuard .conf file.")
              }

              // Fast preliminary size check from content provider if reported
              if (reportedSize > WireGuardConfigParser.MAX_CONFIG_SIZE_BYTES) {
                throw IllegalArgumentException("File exceeds maximum size of 64 KB — choose a valid WireGuard .conf file.")
              }

              // Bounded streaming read (strictly capped at 64 KB) to prevent OutOfMemoryError
              val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
                val buffer = CharArray(1024)
                val sb = StringBuilder()
                var totalChars = 0
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                  totalChars += charsRead
                  if (totalChars > WireGuardConfigParser.MAX_CONFIG_SIZE_BYTES) {
                    throw IllegalArgumentException("File exceeds maximum size of 64 KB — choose a valid WireGuard .conf file.")
                  }
                  sb.append(buffer, 0, charsRead)
                }
                sb.toString()
              } ?: throw IllegalArgumentException("Could not read selected document.")

              // Strict parsing and validation
              val parsedConfig = WireGuardConfigParser.parse(content, fileName)

              // Only persist and update state after complete validation passes (preserves previous valid profile on any error)
              WireGuardConfigStore(context).saveConfig(content, fileName)
              VpnStateHolder.updateConfig(parsedConfig)

              LogCatcher.getInstance(context).log(
                LogLevel.INFO,
                "MainActivity",
                "Imported WireGuard configuration: ${parsedConfig.toSanitizedSummary()}"
              )
              Toast.makeText(context, "Imported $fileName successfully.", Toast.LENGTH_SHORT).show()
            } catch (e: OutOfMemoryError) {
              LogCatcher.getInstance(context).log(
                LogLevel.ERROR,
                "MainActivity",
                "OutOfMemoryError intercepted while importing document. Previous profile preserved."
              )
              Toast.makeText(context, "Unsupported file — file too large to process.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
              LogCatcher.getInstance(context).log(
                LogLevel.ERROR,
                "MainActivity",
                "Failed to import WireGuard configuration: ${e.message}"
              )
              Toast.makeText(context, e.message ?: "Unsupported file — choose a WireGuard .conf file.", Toast.LENGTH_LONG).show()
            }
          }
        }

        // Observe foreground/background lifecycle to pause memory diagnostics in background
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
          val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
              androidx.lifecycle.Lifecycle.Event.ON_START -> logKeeperViewModel.onAppForegroundChanged(true)
              androidx.lifecycle.Lifecycle.Event.ON_STOP -> logKeeperViewModel.onAppForegroundChanged(false)
              else -> {}
            }
          }
          lifecycleOwner.lifecycle.addObserver(observer)
          onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
          }
        }

        when (currentDestination) {
          AppDestination.HOME -> {
            VianshieldHomeScreen(
              vpnState = vpnUiState,
              isConsentPending = isConsentPending,
              onOpenLogKeeper = { currentDestination = AppDestination.LOG_KEEPER },
              onExportRequested = {
                exportLauncher.launch(logKeeperViewModel.getExportFileName())
              },
              onImportConfRequested = {
                importConfLauncher.launch(arrayOf("*/*", "application/octet-stream", "text/plain"))
              },
              onConnectRequested = {
                handleConnectRequested()
              },
              onDisconnectRequested = {
                VianshieldVpnService.stop(context)
              },
              logKeeperViewModel = logKeeperViewModel
            )
          }
          AppDestination.LOG_KEEPER -> {
            BackHandler {
              currentDestination = AppDestination.HOME
            }
            LogKeeperScreen(
              viewModel = logKeeperViewModel,
              onNavigateBack = { currentDestination = AppDestination.HOME },
              onRequestExport = {
                exportLauncher.launch(logKeeperViewModel.getExportFileName())
              }
            )
          }
        }
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    LogCatcher.getInstance(this).markCleanShutdown()
  }

  companion object {
    private const val TAG = "MainActivity"
    const val REQUEST_CODE_VPN_CONSENT = 100
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VianshieldHomeScreen(
  vpnState: VpnUiState = VpnUiState(),
  isConsentPending: Boolean = false,
  onOpenLogKeeper: () -> Unit = {},
  onExportRequested: () -> Unit = {},
  onImportConfRequested: () -> Unit = {},
  onConnectRequested: () -> Unit = {},
  onDisconnectRequested: () -> Unit = {},
  logKeeperViewModel: LogKeeperViewModel? = null,
  modifier: Modifier = Modifier
) {
  val isArchivalPending = logKeeperViewModel?.isArchivalPending?.collectAsState()?.value ?: false

  Scaffold(
    modifier = modifier
      .fillMaxSize()
      .testTag("vianshield_root_scaffold"),
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
          ) {
            Icon(
              imageVector = Icons.Default.Shield,
              contentDescription = stringResource(id = R.string.title_vianshield),
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = stringResource(id = R.string.title_vianshield),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.testTag("app_title_text")
            )
          }
        },
        actions = {
          IconButton(
            onClick = onOpenLogKeeper,
            modifier = Modifier.testTag("btn_top_log_keeper")
          ) {
            Icon(
              imageVector = Icons.Default.Description,
              contentDescription = stringResource(id = R.string.action_open_log_keeper),
              tint = if (isArchivalPending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface
        )
      )
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(horizontal = 24.dp),
      contentAlignment = Alignment.Center
    ) {
      Column(
        modifier = Modifier
          .widthIn(max = 480.dp)
          .fillMaxWidth()
          .testTag("main_content_column"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        // App Identity Shield Badge
        Surface(
          modifier = Modifier
            .size(72.dp)
            .testTag("app_shield_badge"),
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          tonalElevation = 2.dp
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.Shield,
              contentDescription = "Shield Icon",
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(40.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = stringResource(id = R.string.title_vianshield),
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.ExtraBold,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("hero_title_text")
        )

        Spacer(modifier = Modifier.height(6.dp))

        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        ) {
          Text(
            text = "Phase 4 • Minimal WireGuard VPN",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
          )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // WireGuard VPN Card
        WireGuardVpnCard(
          vpnState = vpnState,
          isConsentPending = isConsentPending,
          onImportConfRequested = onImportConfRequested,
          onConnectRequested = onConnectRequested,
          onDisconnectRequested = onDisconnectRequested
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Diagnostic & Archival Status Card (Log Keeper)
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .testTag("diagnostic_status_card"),
          colors = CardDefaults.cardColors(
            containerColor = if (isArchivalPending) {
              MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            } else {
              MaterialTheme.colorScheme.surface
            }
          ),
          shape = RoundedCornerShape(16.dp)
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = if (isArchivalPending) Icons.Default.Warning else Icons.Default.Description,
                  contentDescription = null,
                  tint = if (isArchivalPending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Log Keeper",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface
                )
              }
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isArchivalPending) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
              ) {
                Text(
                  text = if (isArchivalPending) "Archival Pending" else "Active (Cap: 2 MiB)",
                  style = MaterialTheme.typography.labelSmall,
                  color = if (isArchivalPending) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
              text = if (isArchivalPending) {
                "Internal log buffer has reached the 2 MiB limit. Export to Downloads is pending."
              } else {
                "Lightweight on-device diagnostic capture active. Zero telemetry or network transmissions."
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(
                onClick = onOpenLogKeeper,
                modifier = Modifier
                  .weight(1f)
                  .testTag("btn_view_logs")
              ) {
                Text("View Logs")
              }
              if (isArchivalPending) {
                Button(
                  onClick = onExportRequested,
                  modifier = Modifier
                    .weight(1f)
                    .testTag("btn_home_export_now")
                ) {
                  Text("Export Now")
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun WireGuardVpnCard(
  vpnState: VpnUiState,
  isConsentPending: Boolean = false,
  onImportConfRequested: () -> Unit,
  onConnectRequested: () -> Unit,
  onDisconnectRequested: () -> Unit,
  modifier: Modifier = Modifier
) {
  val config = vpnState.config
  val state = vpnState.connectionState

  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("status_card"),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant
    ),
    shape = RoundedCornerShape(16.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(18.dp)
    ) {
      // Header: Title + Routing Label
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.VpnKey,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = stringResource(id = R.string.vpn_section_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        if (config != null) {
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (config.isIpv6Configured) {
              MaterialTheme.colorScheme.primaryContainer
            } else {
              MaterialTheme.colorScheme.tertiaryContainer
            }
          ) {
            Text(
              text = if (config.isIpv6Configured) "Dual-stack" else "IPv4 only",
              style = MaterialTheme.typography.labelSmall,
              color = if (config.isIpv6Configured) {
                MaterialTheme.colorScheme.onPrimaryContainer
              } else {
                MaterialTheme.colorScheme.onTertiaryContainer
              },
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      if (config == null) {
        // Empty State: Prompt user to import configuration
        Text(
          text = stringResource(id = R.string.no_config_loaded),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("firewall_status_text")
        )

        Spacer(modifier = Modifier.height(14.dp))

        Button(
          onClick = onImportConfRequested,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("btn_import_conf")
        ) {
          Icon(
            imageVector = Icons.Default.FileOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(id = R.string.action_import_conf))
        }
      } else {
        // Configured State
        Text(
          text = "Config: ${config.originalFileName} (${config.peerEndpoint})",
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Connection State Badge
        val (badgeColor, textColor, stateText, icon) = when (state) {
          VpnConnectionState.DISCONNECTED -> Quad(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.onSurface,
            stringResource(id = R.string.status_disconnected),
            Icons.Default.Shield
          )
          VpnConnectionState.CONNECTING -> Quad(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            stringResource(id = R.string.status_connecting),
            Icons.Default.HourglassEmpty
          )
          VpnConnectionState.TUNNEL_ACTIVE_UNVERIFIED -> Quad(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            stringResource(id = R.string.status_tunnel_active_unverified),
            Icons.Default.CheckCircle
          )
          VpnConnectionState.CONNECTION_UNCONFIRMED -> Quad(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(id = R.string.status_connection_unconfirmed),
            Icons.Default.Warning
          )
          VpnConnectionState.ERROR -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            vpnState.errorMessage ?: stringResource(id = R.string.status_error),
            Icons.Default.Error
          )
        }

        Surface(
          shape = RoundedCornerShape(8.dp),
          color = badgeColor,
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = icon,
              contentDescription = null,
              tint = textColor,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = stateText,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = textColor,
              modifier = Modifier.testTag("firewall_status_text")
            )
          }
        }

        if (state == VpnConnectionState.TUNNEL_ACTIVE_UNVERIFIED ||
          state == VpnConnectionState.CONNECTING ||
          state == VpnConnectionState.CONNECTION_UNCONFIRMED
        ) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(id = R.string.traffic_stats, vpnState.localTunTxPackets, vpnState.localTunRxPackets),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("traffic_stats_text")
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Action Buttons
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          if (state == VpnConnectionState.DISCONNECTED || state == VpnConnectionState.ERROR) {
            Button(
              onClick = onConnectRequested,
              enabled = !isConsentPending && state != VpnConnectionState.CONNECTING,
              modifier = Modifier
                .weight(1f)
                .testTag("btn_vpn_connect")
            ) {
              Text(
                when {
                  isConsentPending -> stringResource(id = R.string.awaiting_vpn_permission)
                  state == VpnConnectionState.ERROR -> "Retry"
                  else -> stringResource(id = R.string.action_connect)
                }
              )
            }
            OutlinedButton(
              onClick = onImportConfRequested,
              modifier = Modifier
                .weight(1f)
                .testTag("btn_change_conf")
            ) {
              Text(stringResource(id = R.string.action_change_conf))
            }
          } else {
            FilledTonalButton(
              onClick = onDisconnectRequested,
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
              ),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_vpn_disconnect")
            ) {
              Text(stringResource(id = R.string.action_disconnect))
            }
          }
        }
      }
    }
  }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Preview(showBackground = true)
@Composable
fun VianshieldHomeScreenPreview() {
  VianshieldTheme {
    VianshieldHomeScreen()
  }
}
