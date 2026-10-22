package app.vian.vianshield

import app.vian.vianshield.vpn.VpnConnectionState
import app.vian.vianshield.vpn.VpnStateHolder
import app.vian.vianshield.vpn.WireGuardConfigParser
import app.vian.vianshield.vpn.WireGuardConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WireGuardConfigTest {

  // Valid 32-byte Base64 WireGuard test keys
  // Raw 32 bytes of 0x01 = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE= -> 64 hex '01'
  private val validBase64Key1 = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
  private val expectedHexKey1 = "0101010101010101010101010101010101010101010101010101010101010101"

  // Raw 32 bytes of 0x02 = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI= -> 64 hex '02'
  private val validBase64Key2 = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
  private val expectedHexKey2 = "0202020202020202020202020202020202020202020202020202020202020202"

  @Test
  fun `parse valid single peer IPv4 configuration`() {
    val confContent = """
      [Interface]
      PrivateKey = $validBase64Key1
      Address = 10.0.0.2/32
      DNS = 1.1.1.1, 1.0.0.1
      MTU = 1420
      ListenPort = 51820

      [Peer]
      PublicKey = $validBase64Key2
      Endpoint = 198.51.100.1:51820
      AllowedIPs = 0.0.0.0/0
      PersistentKeepalive = 25
    """.trimIndent()

    val config = WireGuardConfigParser.parse(confContent, "wg0.conf")

    assertEquals(listOf("10.0.0.2/32"), config.interfaceAddresses)
    assertEquals(listOf("1.1.1.1", "1.0.0.1"), config.dnsServers)
    assertEquals(1420, config.mtu)
    assertEquals(51820, config.listenPort)
    assertEquals(expectedHexKey1, config.privateKeyHex)
    assertEquals(expectedHexKey2, config.peerPublicKeyHex)
    assertEquals("198.51.100.1:51820", config.peerEndpoint)
    assertEquals(listOf("0.0.0.0/0"), config.peerAllowedIps)
    assertEquals(25, config.peerPersistentKeepalive)
    assertFalse(config.isIpv6Configured)

    val uapi = config.toFirestackUapiConfig()
    assertTrue(uapi.contains("private_key=$expectedHexKey1"))
    assertTrue(uapi.contains("replace_peers=true"))
    assertTrue(uapi.contains("address=10.0.0.2/32"))
    assertTrue(uapi.contains("dns=1.1.1.1,1.0.0.1"))
    assertTrue(uapi.contains("mtu=1420"))
    assertTrue(uapi.contains("listen_port=51820"))
    assertTrue(uapi.contains("public_key=$expectedHexKey2"))
    assertTrue(uapi.contains("endpoint=198.51.100.1:51820"))
    assertTrue(uapi.contains("allowed_ip=0.0.0.0/0"))
    assertTrue(uapi.contains("persistent_keepalive_interval=25"))
  }

  @Test
  fun `parse valid dual-stack IPv4 and IPv6 configuration`() {
    val confContent = """
      [Interface]
      PrivateKey = $validBase64Key1
      Address = 10.0.0.2/32, fd00::2/128
      DNS = 1.1.1.1

      [Peer]
      PublicKey = $validBase64Key2
      Endpoint = 198.51.100.1:51820
      AllowedIPs = 0.0.0.0/0, ::/0
    """.trimIndent()

    val config = WireGuardConfigParser.parse(confContent, "dual.conf")

    assertEquals(2, config.interfaceAddresses.size)
    assertTrue(config.isIpv6Configured)
    assertEquals(listOf("0.0.0.0/0", "::/0"), config.peerAllowedIps)
  }

  @Test
  fun `reject configuration with multiple peers`() {
    val confContent = """
      [Interface]
      PrivateKey = $validBase64Key1
      Address = 10.0.0.2/32

      [Peer]
      PublicKey = $validBase64Key2
      Endpoint = 198.51.100.1:51820

      [Peer]
      PublicKey = $validBase64Key1
      Endpoint = 198.51.100.2:51820
    """.trimIndent()

    try {
      WireGuardConfigParser.parse(confContent)
      fail("Expected IllegalArgumentException for multiple peers")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("Multiple peers") == true)
    }
  }

  @Test
  fun `reject configuration missing interface address`() {
    val confContent = """
      [Interface]
      PrivateKey = $validBase64Key1

      [Peer]
      PublicKey = $validBase64Key2
      Endpoint = 198.51.100.1:51820
    """.trimIndent()

    try {
      WireGuardConfigParser.parse(confContent)
      fail("Expected IllegalArgumentException for missing Address")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("Address") == true)
    }
  }

  @Test
  fun `reject configuration missing private key`() {
    val confContent = """
      [Interface]
      Address = 10.0.0.2/32

      [Peer]
      PublicKey = $validBase64Key2
      Endpoint = 198.51.100.1:51820
    """.trimIndent()

    try {
      WireGuardConfigParser.parse(confContent)
      fail("Expected IllegalArgumentException for missing PrivateKey")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("PrivateKey") == true)
    }
  }

  @Test
  fun `reject configuration missing peer public key`() {
    val confContent = """
      [Interface]
      PrivateKey = $validBase64Key1
      Address = 10.0.0.2/32

      [Peer]
      Endpoint = 198.51.100.1:51820
    """.trimIndent()

    try {
      WireGuardConfigParser.parse(confContent)
      fail("Expected IllegalArgumentException for missing PublicKey")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("PublicKey") == true)
    }
  }

  @Test
  fun `reject invalid key format or length`() {
    val shortBase64 = "AQID" // only 3 bytes

    try {
      WireGuardConfigParser.keyToHex(shortBase64, "PrivateKey")
      fail("Expected IllegalArgumentException for short key")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("32 bytes") == true)
    }
  }

  @Test
  fun `sanitized summary never exposes private or preshared keys`() {
    val confContent = """
      [Interface]
      PrivateKey = $validBase64Key1
      Address = 10.0.0.2/32

      [Peer]
      PublicKey = $validBase64Key2
      PresharedKey = $validBase64Key1
      Endpoint = 198.51.100.1:51820
    """.trimIndent()

    val config = WireGuardConfigParser.parse(confContent)
    val summary = config.toSanitizedSummary()

    assertFalse("Summary must not contain raw private key", summary.contains(validBase64Key1))
    assertFalse("Summary must not contain hex private key", summary.contains(expectedHexKey1))
    assertTrue(summary.contains("Endpoint: 198.51.100.1:51820"))
    assertTrue(summary.contains("IPv4 only"))
  }

  @Test
  fun `vpn state holder truthfully tracks verification states`() {
    VpnStateHolder.resetToDisconnected()
    assertEquals(VpnConnectionState.DISCONNECTED, VpnStateHolder.state.value.connectionState)

    VpnStateHolder.updateState(VpnConnectionState.CONNECTING)
    assertEquals(VpnConnectionState.CONNECTING, VpnStateHolder.state.value.connectionState)

    // Local packet telemetry updates localTun packet counters and marks tunnel active unverified
    VpnStateHolder.updateStats(tx = 15, rx = 0)
    assertEquals(VpnConnectionState.TUNNEL_ACTIVE_UNVERIFIED, VpnStateHolder.state.value.connectionState)
    assertEquals(15L, VpnStateHolder.state.value.localTunTxPackets)
    assertEquals(0L, VpnStateHolder.state.value.localTunRxPackets)

    // Local packet counters do NOT claim handshake or traffic verified
    VpnStateHolder.updateStats(tx = 25, rx = 10)
    assertEquals(VpnConnectionState.TUNNEL_ACTIVE_UNVERIFIED, VpnStateHolder.state.value.connectionState)
    assertEquals(25L, VpnStateHolder.state.value.localTunTxPackets)
    assertEquals(10L, VpnStateHolder.state.value.localTunRxPackets)

    // Connection unconfirmed state when no RX received
    VpnStateHolder.updateState(VpnConnectionState.CONNECTION_UNCONFIRMED, "Awaiting RX packets")
    assertEquals(VpnConnectionState.CONNECTION_UNCONFIRMED, VpnStateHolder.state.value.connectionState)

    VpnStateHolder.resetToDisconnected()
    assertEquals(VpnConnectionState.DISCONNECTED, VpnStateHolder.state.value.connectionState)
    assertEquals(0L, VpnStateHolder.state.value.localTunTxPackets)
    assertEquals(0L, VpnStateHolder.state.value.localTunRxPackets)
  }

  @Test
  fun `gomobile runtime initializes safely and idempotently`() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    app.vian.vianshield.vpn.GomobileRuntime.init(context)
    assertTrue(app.vian.vianshield.vpn.GomobileRuntime.isInitialized())
    // Repeated call must be idempotent
    app.vian.vianshield.vpn.GomobileRuntime.init(context)
    assertTrue(app.vian.vianshield.vpn.GomobileRuntime.isInitialized())
  }

  @Test
  fun `firestack bridge triggers protection failure callback when protect returns false`() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val logCatcher = app.vian.vianshield.logging.LogCatcher.getInstance(context)

    // Mock/Stub VpnService that fails protect()
    var callbackInvoked = false
    var reportedFd = -1L
    var reportedPath: String? = null

    // Subclass VpnService with failing protect
    val dummyVpnService = object : android.net.VpnService() {
      override fun protect(socket: Int): Boolean = false
    }

    val bridge = app.vian.vianshield.vpn.FirestackBridge(
      vpnService = dummyVpnService,
      logCatcher = logCatcher,
      onProtectionFailed = { path, fd ->
        callbackInvoked = true
        reportedPath = path
        reportedFd = fd
      }
    )

    bridge.protect("udp", 42L)
    assertTrue("onProtectionFailed callback must be invoked when protect() returns false", callbackInvoked)
    assertEquals(42L, reportedFd)
    assertEquals("udp", reportedPath)
  }

  @Test
  fun `reject configuration with invalid file extension`() {
    val validConf = """
      [Interface]
      PrivateKey = $validBase64Key1
      Address = 10.0.0.2/32
      [Peer]
      PublicKey = $validBase64Key2
      Endpoint = 198.51.100.1:51820
    """.trimIndent()

    val invalidFileNames = listOf("model.slm", "config.txt", "tunnel.bin", "archive.zip", "test.conf.bak")
    for (invalidName in invalidFileNames) {
      try {
        WireGuardConfigParser.parse(validConf, invalidName)
        fail("Expected IllegalArgumentException for invalid extension: $invalidName")
      } catch (e: IllegalArgumentException) {
        assertTrue(e.message?.contains("Unsupported file — choose a WireGuard .conf file") == true)
      }
    }
  }

  @Test
  fun `reject empty or whitespace configuration`() {
    try {
      WireGuardConfigParser.parse("   \n\t  ", "tunnel.conf")
      fail("Expected IllegalArgumentException for empty configuration")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("Unsupported file") == true)
    }
  }

  @Test
  fun `reject configuration exceeding 64 KB size limit`() {
    val validBase = """
      [Interface]
      PrivateKey = $validBase64Key1
      Address = 10.0.0.2/32
      [Peer]
      PublicKey = $validBase64Key2
      Endpoint = 198.51.100.1:51820
    """.trimIndent()

    // Pad with comments to exceed 64 KB
    val oversizedConf = validBase + "\n" + "# oversized comment line ".repeat(3000)
    assertTrue("Oversized content must exceed 64 KB", oversizedConf.length > WireGuardConfigParser.MAX_CONFIG_SIZE_BYTES)

    try {
      WireGuardConfigParser.parse(oversizedConf, "oversized.conf")
      fail("Expected IllegalArgumentException for oversized configuration")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("exceeds maximum size") == true)
    }
  }

  @Test
  fun `reject arbitrary document missing WireGuard sections`() {
    val arbitraryText = "This is a random text document without wireguard sections.\nKey = Value\n"
    try {
      WireGuardConfigParser.parse(arbitraryText, "tunnel.conf")
      fail("Expected IllegalArgumentException for arbitrary document missing sections")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("Unsupported file") == true)
    }
  }

  @Test
  fun `reject binary content containing NUL bytes`() {
    val binaryContent = "[Interface]\u0000PrivateKey=$validBase64Key1\nAddress=10.0.0.2/32\n[Peer]\nPublicKey=$validBase64Key2\nEndpoint=1.1.1.1:51820"
    try {
      WireGuardConfigParser.parse(binaryContent, "binary.conf")
      fail("Expected IllegalArgumentException for binary content")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("binary data detected") == true)
    }
  }

  @Test
  fun `failed import preserves previous valid profile in store and state`() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val store = WireGuardConfigStore(context)
    store.clear()

    // 1. Establish initial valid configuration
    val initialConf = """
      [Interface]
      PrivateKey = $validBase64Key1
      Address = 10.0.0.2/32
      [Peer]
      PublicKey = $validBase64Key2
      Endpoint = 198.51.100.1:51820
    """.trimIndent()

    val initialConfig = WireGuardConfigParser.parse(initialConf, "primary.conf")
    store.saveConfig(initialConf, "primary.conf")
    VpnStateHolder.updateConfig(initialConfig)

    assertEquals("primary.conf", VpnStateHolder.state.value.config?.originalFileName)
    assertEquals("198.51.100.1:51820", VpnStateHolder.state.value.config?.peerEndpoint)
    assertEquals("primary.conf", store.loadConfig()?.originalFileName)

    // 2. Attempt invalid import (e.g. .slm model file or malformed text)
    val invalidSlmContent = "SLM_WEIGHTS_BINARY_BLOB"
    try {
      WireGuardConfigParser.parse(invalidSlmContent, "model.slm")
      fail("Must reject invalid .slm file")
    } catch (_: IllegalArgumentException) {
      // Expected failure: do not update store or state
    }

    // 3. Verify previous valid profile is strictly preserved
    val currentActiveConfig = VpnStateHolder.state.value.config
    assertNotNull("Previous valid profile must remain active in memory", currentActiveConfig)
    assertEquals("primary.conf", currentActiveConfig?.originalFileName)
    assertEquals("198.51.100.1:51820", currentActiveConfig?.peerEndpoint)

    val currentStoredConfig = store.loadConfig()
    assertNotNull("Previous valid profile must remain stored on disk", currentStoredConfig)
    assertEquals("primary.conf", currentStoredConfig?.originalFileName)
    assertEquals("198.51.100.1:51820", currentStoredConfig?.peerEndpoint)
  }

  @Test
  fun `consent state transitions and duplicate request rejection`() {
    val activityController = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = activityController.get()

    // 1. Initial state: not pending
    assertFalse("Consent pending must be false initially", activity.isConsentPendingState.value)
    assertEquals(0, activity.currentConsentAttempt)

    // 2. Simulate pending consent
    activity.isConsentPendingState.value = true
    activity.currentConsentAttempt = 1

    // 3. Duplicate connect attempt while consent is pending must be rejected
    val previousAttempt = activity.currentConsentAttempt
    activity.handleConnectRequested()
    assertEquals("Duplicate connect request must not advance consent attempt", previousAttempt, activity.currentConsentAttempt)
    assertTrue("Consent pending must remain true during pending request", activity.isConsentPendingState.value)

    // 4. Simulated consent result CANCELED
    activity.handleConsentResult(android.app.Activity.RESULT_CANCELED)
    assertFalse("Consent pending must be reset to false after cancellation", activity.isConsentPendingState.value)

    // 5. Simulated consent result OK
    activity.isConsentPendingState.value = true
    activity.currentConsentAttempt = 2
    activity.handleConsentResult(android.app.Activity.RESULT_OK)
    assertFalse("Consent pending must be reset to false after OK", activity.isConsentPendingState.value)
  }
}
