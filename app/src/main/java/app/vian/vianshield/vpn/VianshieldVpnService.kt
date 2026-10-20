package app.vian.vianshield.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.vian.vianshield.MainActivity
import app.vian.vianshield.R
import app.vian.vianshield.logging.LogCatcher
import app.vian.vianshield.logging.LogLevel
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.Proxy
import com.celzero.firestack.intra.Intra
import com.celzero.firestack.intra.Tunnel
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android VpnService implementation integrating the verified Firestack engine
 * for single-peer WireGuard connections.
 *
 * Implements:
 * 1. Truthful connection states (no claims of handshake or remote traffic verification).
 * 2. Safe one-time Gomobile runtime initialization via GomobileRuntime.
 * 3. Immediate synchronous startForeground() dispatch on service start.
 * 4. Thread-safe idempotency preventing duplicate connections and resource leaks.
 * 5. Safe socket protection failure handling with clean service abort preventing routing loops.
 */
class VianshieldVpnService : VpnService() {

  private enum class ServiceLifecycleState {
    IDLE,
    CONNECTING,
    ACTIVE,
    STOPPING
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lifecycleLock = Any()
  @Volatile private var lifecycleState = ServiceLifecycleState.IDLE

  private var connectJob: Job? = null
  private var monitorJob: Job? = null

  private var tunPfd: ParcelFileDescriptor? = null
  private var tunnel: Tunnel? = null
  private var wgProxy: Proxy? = null
  private var activeConfig: WireGuardConfig? = null

  private lateinit var logCatcher: LogCatcher
  private lateinit var notificationManager: NotificationManager

  override fun onCreate() {
    super.onCreate()
    logCatcher = LogCatcher.getInstance(this)
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    try {
      createNotificationChannel()
    } catch (e: Exception) {
      logCatcher.log(LogLevel.ERROR, TAG, "Failed to create notification channel: ${e.message}")
    }
    // Critical Fix 2: Initialize Gomobile runtime once with application context
    GomobileRuntime.init(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action ?: ACTION_CONNECT

    when (action) {
      ACTION_DISCONNECT -> {
        logCatcher.log(LogLevel.INFO, TAG, "Disconnect requested by user.")
        handleDisconnect()
        return START_NOT_STICKY
      }
      ACTION_CONNECT -> {
        // Critical Fix 3: Enter foreground state synchronously before async tunnel initialization
        startForegroundNotification(getString(R.string.status_connecting))
        handleConnect()
        return START_STICKY
      }
      else -> {
        return START_NOT_STICKY
      }
    }
  }

  /**
   * Critical Fix 4: Prevent duplicate connections and enforce idempotent startup.
   */
  private fun handleConnect() {
    synchronized(lifecycleLock) {
      if (lifecycleState == ServiceLifecycleState.CONNECTING || lifecycleState == ServiceLifecycleState.ACTIVE) {
        logCatcher.log(
          LogLevel.WARN,
          TAG,
          "Ignored duplicate connect request: service is already $lifecycleState."
        )
        val currentUiState = VpnStateHolder.state.value
        updateNotificationContent(getNotificationTextForState(
          currentUiState.connectionState,
          currentUiState.localTunTxPackets,
          currentUiState.localTunRxPackets
        ))
        return
      }
      if (lifecycleState == ServiceLifecycleState.STOPPING) {
        logCatcher.log(
          LogLevel.WARN,
          TAG,
          "Cannot connect: service is currently stopping. Disconnect must complete first."
        )
        return
      }
      lifecycleState = ServiceLifecycleState.CONNECTING
    }

    val configStore = WireGuardConfigStore(this)
    val config = configStore.loadConfig()
    if (config == null) {
      logCatcher.log(LogLevel.ERROR, TAG, "Cannot connect: no configuration found.")
      VpnStateHolder.updateState(VpnConnectionState.ERROR, "No configuration loaded.")
      synchronized(lifecycleLock) { lifecycleState = ServiceLifecycleState.IDLE }
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return
    }

    activeConfig = config
    VpnStateHolder.updateConfig(config)
    VpnStateHolder.updateState(VpnConnectionState.CONNECTING)

    connectJob?.cancel()
    connectJob = serviceScope.launch {
      startVpn(config)
    }
  }

  private suspend fun startVpn(config: WireGuardConfig) {
    try {
      logCatcher.log(LogLevel.INFO, TAG, "Starting WireGuard VPN: ${config.toSanitizedSummary()}")

      // 1. Configure and establish Android TUN interface
      val builder = Builder()
      builder.setSession("Vianshield WireGuard")
      builder.setMtu(config.mtu)

      for (addrStr in config.interfaceAddresses) {
        val parts = addrStr.split("/")
        val ipStr = parts[0].trim()
        val prefix = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: if (ipStr.contains(":")) 128 else 32 else if (ipStr.contains(":")) 128 else 32
        builder.addAddress(InetAddress.getByName(ipStr), prefix)
      }

      for (dnsStr in config.dnsServers) {
        try {
          builder.addDnsServer(InetAddress.getByName(dnsStr.trim()))
        } catch (e: Exception) {
          logCatcher.log(LogLevel.WARN, TAG, "Invalid DNS entry skipped: $dnsStr")
        }
      }

      for (allowedIpStr in config.peerAllowedIps) {
        val parts = allowedIpStr.split("/")
        val ipStr = parts[0].trim()
        val prefix = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: if (ipStr.contains(":")) 128 else 32 else if (ipStr.contains(":")) 128 else 32
        builder.addRoute(InetAddress.getByName(ipStr), prefix)
      }

      builder.setBlocking(false)
      val pfd = builder.establish()
      if (pfd == null) {
        throw IllegalStateException("VpnService.Builder.establish() returned null. TUN interface creation failed.")
      }
      tunPfd = pfd
      val fd = pfd.fd.toLong()

      // 2. Initialize Firestack Go netstack and WireGuard protocol
      Intra.experimentalWireGuard(true)

      // Critical Fix 5: Pass protection failure listener to handle socket protect errors gracefully
      val bridge = FirestackBridge(
        vpnService = this@VianshieldVpnService,
        logCatcher = logCatcher,
        onProtectionFailed = { path, failedFd ->
          logCatcher.log(
            LogLevel.ERROR,
            TAG,
            "Socket protection failed for fd $failedFd (path: $path). Aborting VPN to prevent routing loop."
          )
          VpnStateHolder.updateState(
            VpnConnectionState.ERROR,
            "Socket protection failed (fd $failedFd). Disconnected to prevent routing loop."
          )
          handleDisconnect()
        }
      )

      val createdTunnel = Intra.connect3(fd, config.mtu.toLong(), bridge)
      tunnel = createdTunnel

      // 3. Add WireGuard Proxy to netstack
      val uapi = config.toFirestackUapiConfig()
      val proxies = createdTunnel.proxies
      val proxy = proxies.addProxy(Backend.strOf(Backend.WG), Backend.strOf(uapi))
      wgProxy = proxy

      synchronized(lifecycleLock) {
        if (lifecycleState != ServiceLifecycleState.CONNECTING) {
          // Cancelled during startup
          cleanupResourcesInternal()
          return
        }
        lifecycleState = ServiceLifecycleState.ACTIVE
      }

      logCatcher.log(LogLevel.INFO, TAG, "WireGuard proxy registered with Firestack. Local TUN active.")

      // Critical Fix 1: State is truthfully TUNNEL_ACTIVE_UNVERIFIED
      VpnStateHolder.updateState(VpnConnectionState.TUNNEL_ACTIVE_UNVERIFIED)
      updateNotificationContent(getString(R.string.status_tunnel_active_unverified) + " (Local TUN TX: 0 / RX: 0)")

      // 4. Start local TUN packet telemetry polling
      startTrafficMonitoring(createdTunnel)

    } catch (e: Exception) {
      logCatcher.log(LogLevel.ERROR, TAG, "Failed to start VPN: ${e.message}")
      VpnStateHolder.updateState(VpnConnectionState.ERROR, e.message ?: "VPN connection failed")
      stopVpnInternal()
    }
  }

  /**
   * Critical Fix 1: Truthful telemetry polling.
   * IPStat rcv and snd are local TUN counters; they do not prove a WireGuard handshake
   * or peer connectivity.
   */
  private fun startTrafficMonitoring(activeTunnel: Tunnel) {
    monitorJob?.cancel()
    monitorJob = serviceScope.launch {
      val startTimeMs = System.currentTimeMillis()

      while (isActive) {
        delay(1500)
        try {
          val netStat = activeTunnel.stat()
          val ipStat = netStat.ip()
          val rcv = ipStat.rcv
          val snd = ipStat.snd

          VpnStateHolder.updateStats(tx = snd, rx = rcv)

          val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000
          if (rcv == 0L && elapsedSec >= 10) {
            VpnStateHolder.updateState(
              VpnConnectionState.CONNECTION_UNCONFIRMED,
              "No packets received through local TUN after ${elapsedSec}s. Peer unreachable or key mismatch."
            )
            updateNotificationContent(getString(R.string.status_connection_unconfirmed) + " (Local TUN TX: $snd / RX: 0)")
          } else {
            updateNotificationContent(getString(R.string.status_tunnel_active_unverified) + " (Local TUN TX: $snd / RX: $rcv)")
          }
        } catch (e: Exception) {
          logCatcher.log(LogLevel.WARN, TAG, "Error polling tunnel telemetry: ${e.message}")
        }
      }
    }
  }

  private fun handleDisconnect() {
    synchronized(lifecycleLock) {
      if (lifecycleState == ServiceLifecycleState.IDLE || lifecycleState == ServiceLifecycleState.STOPPING) {
        logCatcher.log(LogLevel.INFO, TAG, "Ignored disconnect request: service is already $lifecycleState.")
        return
      }
      lifecycleState = ServiceLifecycleState.STOPPING
    }
    serviceScope.launch {
      stopVpnInternal()
    }
  }

  private fun stopVpnInternal() {
    synchronized(lifecycleLock) {
      connectJob?.cancel()
      connectJob = null
      cleanupResourcesInternal()
      lifecycleState = ServiceLifecycleState.IDLE
    }
    VpnStateHolder.resetToDisconnected()
    try {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } catch (e: Exception) {
      logCatcher.log(LogLevel.WARN, TAG, "Error stopping foreground: ${e.message}")
    }
    stopSelf()
  }

  /**
   * Release all VPN resources safely and idempotently.
   */
  private fun cleanupResourcesInternal() {
    monitorJob?.cancel()
    monitorJob = null

    try {
      wgProxy?.stop()
    } catch (e: Exception) {
      logCatcher.log(LogLevel.WARN, TAG, "Error stopping proxy: ${e.message}")
    }
    wgProxy = null

    try {
      tunnel?.disconnect()
      tunnel?.unlink()
    } catch (e: Exception) {
      logCatcher.log(LogLevel.WARN, TAG, "Error disconnecting tunnel: ${e.message}")
    }
    tunnel = null

    try {
      tunPfd?.close()
    } catch (e: Exception) {
      logCatcher.log(LogLevel.WARN, TAG, "Error closing TUN file descriptor: ${e.message}")
    }
    tunPfd = null

    logCatcher.log(LogLevel.INFO, TAG, "VPN resources fully cleaned up.")
  }

  private fun startForegroundNotification(statusText: String) {
    val notification = buildNotification(statusText)
    try {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
      )
    } catch (e: Exception) {
      logCatcher.log(LogLevel.ERROR, TAG, "Failed to start foreground service: ${e.message}")
    }
  }

  private fun updateNotificationContent(statusText: String) {
    try {
      val notification = buildNotification(statusText)
      notificationManager.notify(NOTIFICATION_ID, notification)
    } catch (e: Exception) {
      logCatcher.log(LogLevel.WARN, TAG, "Failed to update notification: ${e.message}")
    }
  }

  private fun getNotificationTextForState(state: VpnConnectionState, tx: Long, rx: Long): String {
    return when (state) {
      VpnConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
      VpnConnectionState.CONNECTING -> getString(R.string.status_connecting)
      VpnConnectionState.TUNNEL_ACTIVE_UNVERIFIED -> getString(R.string.status_tunnel_active_unverified) + " (Local TUN TX: $tx / RX: $rx)"
      VpnConnectionState.CONNECTION_UNCONFIRMED -> getString(R.string.status_connection_unconfirmed) + " (Local TUN TX: $tx / RX: 0)"
      VpnConnectionState.ERROR -> getString(R.string.status_error)
    }
  }

  private fun buildNotification(statusText: String): Notification {
    val openActivityIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val openPendingIntent = PendingIntent.getActivity(
      this,
      0,
      openActivityIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val disconnectIntent = Intent(this, VianshieldVpnService::class.java).apply {
      action = ACTION_DISCONNECT
    }
    val disconnectPendingIntent = PendingIntent.getService(
      this,
      1,
      disconnectIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(getString(R.string.vpn_notification_title))
      .setContentText(statusText)
      .setContentIntent(openPendingIntent)
      .addAction(
        android.R.drawable.ic_menu_close_clear_cancel,
        getString(R.string.action_disconnect),
        disconnectPendingIntent
      )
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .build()
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.vpn_notification_channel),
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = "Ongoing WireGuard tunnel connection status"
      setShowBadge(false)
    }
    notificationManager.createNotificationChannel(channel)
  }

  override fun onDestroy() {
    super.onDestroy()
    cleanupResourcesInternal()
    serviceScope.cancel()
    logCatcher.log(LogLevel.INFO, TAG, "VianshieldVpnService destroyed.")
  }

  companion object {
    const val TAG = "VianshieldVpnService"
    const val ACTION_CONNECT = "app.vian.vianshield.vpn.CONNECT"
    const val ACTION_DISCONNECT = "app.vian.vianshield.vpn.DISCONNECT"
    private const val CHANNEL_ID = "vianshield_vpn_channel"
    private const val NOTIFICATION_ID = 2001

    fun start(context: Context) {
      val intent = Intent(context, VianshieldVpnService::class.java).apply {
        action = ACTION_CONNECT
      }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, VianshieldVpnService::class.java).apply {
        action = ACTION_DISCONNECT
      }
      context.startService(intent)
    }
  }
}
