package app.vian.vianshield.logging

import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Diagnostic Memory Test Session:
 * Samples process heap and PSS memory metrics every 15 seconds.
 *
 * Guarantees:
 * - Sampling is active ONLY when user enabled it AND app is in the foreground AND master logging is enabled.
 * - Pauses automatically when app moves to background (onStop) and resumes when returning to foreground (onStart).
 * - Cancels immediately when the user disables it or when master switch is disabled.
 * - Collects ONLY JVM and process memory metrics; no device identifiers, location, or private data.
 */
class MemoryDiagnostics(private val logCatcher: LogCatcher) {

  private var sessionJob: Job? = null
  private var parentScope: CoroutineScope? = null

  private val _isUserSessionEnabled = MutableStateFlow(false)
  val isUserSessionEnabled: StateFlow<Boolean> = _isUserSessionEnabled.asStateFlow()

  private val _isSamplingRunning = MutableStateFlow(false)
  val isSamplingRunning: StateFlow<Boolean> = _isSamplingRunning.asStateFlow()

  private var isAppInForeground = true

  fun toggleSession(scope: CoroutineScope) {
    if (_isUserSessionEnabled.value) {
      stopUserSession()
    } else {
      startUserSession(scope)
    }
  }

  fun startUserSession(scope: CoroutineScope) {
    parentScope = scope
    _isUserSessionEnabled.value = true
    logCatcher.logInfo("MemoryDiagnostics", "Diagnostic memory test session enabled by user.")
    evaluateSamplingState()
  }

  fun stopUserSession() {
    _isUserSessionEnabled.value = false
    logCatcher.logInfo("MemoryDiagnostics", "Diagnostic memory test session stopped by user.")
    stopSampling()
  }

  fun onAppForegroundChanged(inForeground: Boolean, scope: CoroutineScope? = null) {
    isAppInForeground = inForeground
    if (scope != null) parentScope = scope
    evaluateSamplingState()
  }

  fun onMasterSwitchChanged(masterEnabled: Boolean) {
    if (!masterEnabled) {
      stopSampling()
    } else {
      evaluateSamplingState()
    }
  }

  private fun evaluateSamplingState() {
    val shouldRun = _isUserSessionEnabled.value &&
      isAppInForeground &&
      logCatcher.isMasterSwitchEnabled.value

    if (shouldRun) {
      if (sessionJob == null || sessionJob?.isActive == false) {
        startSampling()
      }
    } else {
      stopSampling()
    }
  }

  private fun startSampling() {
    val scope = parentScope ?: return
    _isSamplingRunning.value = true

    sessionJob = scope.launch(Dispatchers.Default) {
      while (isActive && isAppInForeground && logCatcher.isMasterSwitchEnabled.value && _isUserSessionEnabled.value) {
        try {
          val runtime = Runtime.getRuntime()
          val totalMemory = runtime.totalMemory()
          val freeMemory = runtime.freeMemory()
          val usedMemory = totalMemory - freeMemory
          val maxMemory = runtime.maxMemory()

          val usedMb = usedMemory / (1024 * 1024)
          val totalMb = totalMemory / (1024 * 1024)
          val maxMb = maxMemory / (1024 * 1024)

          val pssKb = Debug.getPss()
          val pssMb = pssKb / 1024

          logCatcher.logInfo(
            "MemoryDiagnostics",
            "Process Memory: Used=${usedMb}MB, Allocated=${totalMb}MB, MaxHeap=${maxMb}MB, PSS=${pssMb}MB"
          )
        } catch (_: Exception) {
          // Prevent sampler crash
        }

        delay(INTERVAL_MS)
      }
      _isSamplingRunning.value = false
    }
  }

  private fun stopSampling() {
    sessionJob?.cancel()
    sessionJob = null
    _isSamplingRunning.value = false
  }

  companion object {
    private const val INTERVAL_MS = 15_000L // 15 seconds
  }
}
