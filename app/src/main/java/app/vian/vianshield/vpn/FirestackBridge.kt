package app.vian.vianshield.vpn

import android.net.VpnService
import app.vian.vianshield.logging.LogCatcher
import app.vian.vianshield.logging.LogLevel
import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.backend.Gostr
import com.celzero.firestack.backend.ServerSummary
import com.celzero.firestack.backend.Tab
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark
import com.celzero.firestack.intra.SocketSummary

/**
 * Concrete implementation of the Firestack Bridge interface.
 * Connects the Go netstack runtime to Android VpnService socket protection,
 * routes safe diagnostic logs to LogCatcher, and supplies clean pass-through
 * defaults for packet flow and DNS resolution.
 */
class FirestackBridge(
  private val vpnService: VpnService,
  private val logCatcher: LogCatcher,
  private val onProtectionFailed: ((path: String?, fd: Long) -> Unit)? = null
) : Bridge {

  /**
   * Protect outbound sockets from looping back into the VPN TUN interface.
   *
   * Note on callback contract: Bridge.protect(String, Long) returns void and does not declare
   * exceptions. Throwing unchecked exceptions across Gomobile JNI terminates the process or aborts.
   * If VpnService.protect(fd) returns false, we log an ERROR and invoke onProtectionFailed
   * to trigger a safe, graceful service disconnect preventing socket loops.
   */
  override fun protect(path: String?, fd: Long) {
    if (fd <= 0) {
      logCatcher.log(
        LogLevel.WARN,
        "FirestackBridge",
        "Invalid socket fd for protection: $fd (path: $path)"
      )
      return
    }
    val success = vpnService.protect(fd.toInt())
    if (!success) {
      logCatcher.log(
        LogLevel.ERROR,
        "FirestackBridge",
        "CRITICAL: Failed to protect socket fd $fd (path: $path). Socket traffic would loop into TUN."
      )
      onProtectionFailed?.invoke(path, fd)
    }
  }

  override fun bind4(ip: String?, proto: String?, port: Long) {
    // Client-side WireGuard does not require port binding callbacks
  }

  override fun bind6(ip: String?, proto: String?, port: Long) {
    // Client-side WireGuard does not require port binding callbacks
  }

  override fun log(level: Int, msg: Gostr?) {
    val message = msg?.string() ?: return
    // Strict credential exclusion: never log keys or config blocks
    if (message.contains("private_key") || message.contains("preshared_key")) {
      return
    }
    val mappedLevel = when (level) {
      0 -> LogLevel.ERROR
      1 -> LogLevel.WARN
      else -> LogLevel.INFO
    }
    logCatcher.log(mappedLevel, "FirestackEngine", message)
  }

  override fun flow(
    protocol: Int,
    pid: Int,
    src: Gostr?,
    dst: Gostr?,
    srcPort: Gostr?,
    dstPort: Gostr?,
    uid: Gostr?,
    pkg: Gostr?
  ): Mark {
    return Mark()
  }

  override fun inflow(protocol: Int, pid: Int, src: Gostr?, dst: Gostr?): Mark {
    return Mark()
  }

  override fun preflow(protocol: Int, pid: Int, src: Gostr?, dst: Gostr?): PreMark {
    return PreMark()
  }

  override fun postFlow(mark: Mark?) {
    // No-op
  }

  override fun onSocketClosed(summary: SocketSummary?) {
    // No-op
  }

  override fun onDNSAdded(name: Gostr?) {
    // No-op
  }

  override fun onDNSRemoved(name: Gostr?) {
    // No-op
  }

  override fun onDNSStopped() {
    // No-op
  }

  override fun onQuery(qname: Gostr?, qtype: Gostr?, id: Long): DNSOpts {
    val opts = DNSOpts()
    opts.noblock = true
    return opts
  }

  override fun onResponse(summary: DNSSummary?) {
    // No-op
  }

  override fun onUpstreamAnswer(summary: DNSSummary?, server: Gostr?): DNSOpts {
    val opts = DNSOpts()
    opts.noblock = true
    return opts
  }

  override fun onProxyAdded(id: Gostr?) {
    logCatcher.log(LogLevel.INFO, "FirestackBridge", "Proxy added: ${id?.string()}")
  }

  override fun onProxyRemoved(id: Gostr?) {
    logCatcher.log(LogLevel.INFO, "FirestackBridge", "Proxy removed: ${id?.string()}")
  }

  override fun onProxyStopped(id: Gostr?) {
    logCatcher.log(LogLevel.INFO, "FirestackBridge", "Proxy stopped: ${id?.string()}")
  }

  override fun onProxiesStopped() {
    logCatcher.log(LogLevel.INFO, "FirestackBridge", "All proxies stopped")
  }

  override fun onSvcComplete(summary: ServerSummary?) {
    // No-op
  }

  override fun svcRoute(
    src: String?,
    dst: String?,
    proto: String?,
    uid: String?,
    pkg: String?
  ): Tab {
    val tab = Tab()
    tab.block = false
    return tab
  }
}
