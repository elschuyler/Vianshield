package app.vian.vianshield.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Truthful WireGuard connection states:
 * - DISCONNECTED: Service is stopped, no tunnel active.
 * - CONNECTING: Android TUN created, Gomobile and Firestack proxy starting.
 * - TUNNEL_ACTIVE_UNVERIFIED: TUN interface active and Firestack netstack running,
 *   but WireGuard peer communication is unverified (local packet activity only).
 * - CONNECTION_UNCONFIRMED: Tunnel initialized, but no packets processed by local TUN
 *   or peer is unresponsive after startup window.
 * - ERROR: Failed during setup or runtime with specific failure details.
 */
enum class VpnConnectionState {
  DISCONNECTED,
  CONNECTING,
  TUNNEL_ACTIVE_UNVERIFIED,
  CONNECTION_UNCONFIRMED,
  ERROR
}

data class VpnUiState(
  val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
  val config: WireGuardConfig? = null,
  val localTunTxPackets: Long = 0,
  val localTunRxPackets: Long = 0,
  val errorMessage: String? = null
)

object VpnStateHolder {
  private val _state = MutableStateFlow(VpnUiState())
  val state: StateFlow<VpnUiState> = _state.asStateFlow()

  fun updateConfig(config: WireGuardConfig?) {
    _state.value = _state.value.copy(config = config)
  }

  fun updateState(
    connectionState: VpnConnectionState,
    errorMessage: String? = null
  ) {
    _state.value = _state.value.copy(
      connectionState = connectionState,
      errorMessage = errorMessage
    )
  }

  fun updateStats(tx: Long, rx: Long) {
    val currentState = _state.value.connectionState
    val nextState = when (currentState) {
      VpnConnectionState.CONNECTING -> VpnConnectionState.TUNNEL_ACTIVE_UNVERIFIED
      else -> currentState
    }
    _state.value = _state.value.copy(
      connectionState = nextState,
      localTunTxPackets = tx,
      localTunRxPackets = rx
    )
  }

  fun resetToDisconnected() {
    _state.value = _state.value.copy(
      connectionState = VpnConnectionState.DISCONNECTED,
      localTunTxPackets = 0,
      localTunRxPackets = 0,
      errorMessage = null
    )
  }
}
