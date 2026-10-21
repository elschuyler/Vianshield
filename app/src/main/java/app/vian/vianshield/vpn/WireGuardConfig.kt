package app.vian.vianshield.vpn

import android.util.Base64
import java.util.Locale

/**
 * Representation of a validated single-peer WireGuard configuration for Vianshield.
 *
 * All cryptographic keys are maintained securely in memory and converted to standard 64-character
 * lowercase hexadecimal strings as required by the Firestack / WireGuard UAPI interface.
 */
data class WireGuardConfig(
  val interfaceAddresses: List<String>,
  val dnsServers: List<String>,
  val mtu: Int,
  val listenPort: Int?,
  val privateKeyHex: String,
  val peerPublicKeyHex: String,
  val peerPresharedKeyHex: String?,
  val peerEndpoint: String,
  val peerAllowedIps: List<String>,
  val peerPersistentKeepalive: Int?,
  val isIpv6Configured: Boolean,
  val originalFileName: String = "tunnel.conf"
) {

  /**
   * Generates the newline-delimited UAPI configuration string expected by Firestack's
   * wgIfConfigOf and device.IpcSet handlers.
   */
  fun toFirestackUapiConfig(): String {
    val sb = StringBuilder()
    sb.append("private_key=").append(privateKeyHex).append("\n")
    sb.append("replace_peers=true\n")
    sb.append("address=").append(interfaceAddresses.joinToString(",")).append("\n")
    if (dnsServers.isNotEmpty()) {
      sb.append("dns=").append(dnsServers.joinToString(",")).append("\n")
    }
    sb.append("mtu=").append(mtu).append("\n")
    if (listenPort != null && listenPort > 0) {
      sb.append("listen_port=").append(listenPort).append("\n")
    }
    sb.append("public_key=").append(peerPublicKeyHex).append("\n")
    if (!peerPresharedKeyHex.isNullOrBlank()) {
      sb.append("preshared_key=").append(peerPresharedKeyHex).append("\n")
    }
    sb.append("endpoint=").append(peerEndpoint).append("\n")
    for (allowedIp in peerAllowedIps) {
      sb.append("allowed_ip=").append(allowedIp).append("\n")
    }
    if (peerPersistentKeepalive != null && peerPersistentKeepalive > 0) {
      sb.append("persistent_keepalive_interval=").append(peerPersistentKeepalive).append("\n")
    }
    return sb.toString()
  }

  /**
   * Safe, non-sensitive summary for display and diagnostics.
   * NEVER logs or displays the private key or preshared key.
   */
  fun toSanitizedSummary(): String {
    val routingLabel = if (isIpv6Configured) "Dual-stack (IPv4 & IPv6)" else "IPv4 only (IPv6 unrouted)"
    val pubKeyFingerprint = if (peerPublicKeyHex.length >= 10) "${peerPublicKeyHex.substring(0, 10)}…" else "valid"
    return "Endpoint: $peerEndpoint | Routing: $routingLabel | Peer PubKey: $pubKeyFingerprint | MTU: $mtu"
  }
}

object WireGuardConfigParser {

  /** Maximum allowed size in bytes for a WireGuard text configuration (64 KiB). */
  const val MAX_CONFIG_SIZE_BYTES = 64 * 1024
  private const val KEY_LEN_BYTES = 32
  private const val KEY_HEX_CHARS = 64
  private const val DEFAULT_MTU = 1420

  /**
   * Parses and validates a standard WireGuard .conf file.
   *
   * @throws IllegalArgumentException if the configuration is invalid or unsupported.
   */
  fun parse(content: String, fileName: String = "tunnel.conf"): WireGuardConfig {
    // 1. Validate file extension preliminary check
    if (!fileName.endsWith(".conf", ignoreCase = true)) {
      throw IllegalArgumentException("Unsupported file — choose a WireGuard .conf file.")
    }

    // 2. Reject empty or oversized configurations
    if (content.isBlank()) {
      throw IllegalArgumentException("Unsupported file — choose a WireGuard .conf file.")
    }
    if (content.length > MAX_CONFIG_SIZE_BYTES) {
      throw IllegalArgumentException("File exceeds maximum size of 64 KB — choose a valid WireGuard .conf file.")
    }

    // 3. Reject binary documents (e.g. .slm, .bin, .so)
    if (content.contains('\u0000')) {
      throw IllegalArgumentException("Unsupported file — binary data detected. Choose a valid WireGuard .conf file.")
    }

    // 4. Validate essential WireGuard section headers
    if (!content.contains("[Interface]", ignoreCase = true) || !content.contains("[Peer]", ignoreCase = true)) {
      throw IllegalArgumentException("Unsupported file — choose a WireGuard .conf file.")
    }

    var inInterface = false
    var inPeer = false
    var peerCount = 0

    var privateKeyRaw: String? = null
    val addresses = mutableListOf<String>()
    val dnsList = mutableListOf<String>()
    var mtu: Int? = null
    var listenPort: Int? = null

    var peerPublicKeyRaw: String? = null
    var peerPresharedKeyRaw: String? = null
    var peerEndpoint: String? = null
    val allowedIps = mutableListOf<String>()
    var persistentKeepalive: Int? = null

    val lines = content.lines()
    for (rawLine in lines) {
      // Strip comments starting with # or ;
      val commentIdx = rawLine.indexOfAny(charArrayOf('#', ';'))
      val line = (if (commentIdx >= 0) rawLine.substring(0, commentIdx) else rawLine).trim()
      if (line.isEmpty()) continue

      if (line.equals("[Interface]", ignoreCase = true)) {
        inInterface = true
        inPeer = false
        continue
      } else if (line.equals("[Peer]", ignoreCase = true)) {
        inInterface = false
        inPeer = true
        peerCount++
        if (peerCount > 1) {
          throw IllegalArgumentException("Multiple peers are not supported in this milestone. Please import a single-peer configuration.")
        }
        continue
      }

      val eqIdx = line.indexOf('=')
      if (eqIdx == -1) continue
      val key = line.substring(0, eqIdx).trim()
      val value = line.substring(eqIdx + 1).trim()
      if (key.isEmpty() || value.isEmpty()) continue

      if (inInterface) {
        when (key.lowercase(Locale.ROOT)) {
          "privatekey" -> privateKeyRaw = value
          "address" -> {
            for (addr in value.split(",")) {
              val trimmed = addr.trim()
              if (trimmed.isNotEmpty()) addresses.add(trimmed)
            }
          }
          "dns" -> {
            for (dns in value.split(",")) {
              val trimmed = dns.trim()
              if (trimmed.isNotEmpty()) dnsList.add(trimmed)
            }
          }
          "mtu" -> mtu = value.toIntOrNull()
          "listenport" -> listenPort = value.toIntOrNull()
        }
      } else if (inPeer) {
        when (key.lowercase(Locale.ROOT)) {
          "publickey" -> peerPublicKeyRaw = value
          "presharedkey" -> peerPresharedKeyRaw = value
          "endpoint" -> peerEndpoint = value
          "allowedips" -> {
            for (cidr in value.split(",")) {
              val trimmed = cidr.trim()
              if (trimmed.isNotEmpty()) allowedIps.add(trimmed)
            }
          }
          "persistentkeepalive" -> persistentKeepalive = value.toIntOrNull()
        }
      }
    }

    if (addresses.isEmpty()) {
      throw IllegalArgumentException("Missing or empty [Interface] Address.")
    }
    if (privateKeyRaw.isNullOrBlank()) {
      throw IllegalArgumentException("Missing [Interface] PrivateKey.")
    }
    if (peerCount == 0 || peerPublicKeyRaw.isNullOrBlank()) {
      throw IllegalArgumentException("Missing [Peer] section or PublicKey.")
    }
    if (peerEndpoint.isNullOrBlank()) {
      throw IllegalArgumentException("Missing [Peer] Endpoint (host:port).")
    }

    // Validate and convert Base64 keys to 64-character lowercase hex
    val privateKeyHex = keyToHex(privateKeyRaw, "PrivateKey")
    val publicKeyHex = keyToHex(peerPublicKeyRaw, "PublicKey")
    val presharedKeyHex = if (!peerPresharedKeyRaw.isNullOrBlank()) {
      keyToHex(peerPresharedKeyRaw, "PresharedKey")
    } else null

    // Determine if any IPv6 addresses are configured
    val hasIpv6 = addresses.any { it.contains(":") }

    val effectiveAllowedIps = if (allowedIps.isEmpty()) {
      listOf("0.0.0.0/0")
    } else {
      allowedIps
    }

    val finalMtu = mtu?.takeIf { it in 576..9000 } ?: DEFAULT_MTU

    return WireGuardConfig(
      interfaceAddresses = addresses,
      dnsServers = dnsList,
      mtu = finalMtu,
      listenPort = listenPort,
      privateKeyHex = privateKeyHex,
      peerPublicKeyHex = publicKeyHex,
      peerPresharedKeyHex = presharedKeyHex,
      peerEndpoint = peerEndpoint,
      peerAllowedIps = effectiveAllowedIps,
      peerPersistentKeepalive = persistentKeepalive,
      isIpv6Configured = hasIpv6,
      originalFileName = fileName
    )
  }

  /**
   * Converts a Base64-encoded WireGuard key (or already hex-encoded key) to a 64-char lowercase hex string.
   */
  fun keyToHex(rawKey: String, keyName: String): String {
    val trimmed = rawKey.trim()
    if (trimmed.length == KEY_HEX_CHARS && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
      return trimmed.lowercase(Locale.ROOT)
    }

    return try {
      val bytes = Base64.decode(trimmed, Base64.DEFAULT)
      if (bytes.size != KEY_LEN_BYTES) {
        throw IllegalArgumentException("Invalid $keyName byte length: expected $KEY_LEN_BYTES bytes, got ${bytes.size}")
      }
      bytesToHex(bytes)
    } catch (e: Exception) {
      if (e is IllegalArgumentException) throw e
      throw IllegalArgumentException("Invalid $keyName format: could not decode Base64: ${e.message}")
    }
  }

  private fun bytesToHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
      val intVal = b.toInt() and 0xFF
      if (intVal < 16) sb.append('0')
      sb.append(Integer.toHexString(intVal))
    }
    return sb.toString().lowercase(Locale.ROOT)
  }
}
