package app.vian.vianshield.logging

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogKeeperViewModel(application: Application) : AndroidViewModel(application) {

  private val logCatcher = LogCatcher.getInstance(application)
  val memoryDiagnostics = MemoryDiagnostics(logCatcher)

  private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
  val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

  val isArchivalPending: StateFlow<Boolean> = logCatcher.isArchivalPending
  val isMasterSwitchEnabled: StateFlow<Boolean> = logCatcher.isMasterSwitchEnabled
  val currentStorageBytes: StateFlow<Long> = logCatcher.currentStorageBytes
  val droppedRecordsCount: StateFlow<Long> = logCatcher.droppedRecordsCount
  val isMemoryTestRunning: StateFlow<Boolean> = memoryDiagnostics.isSamplingRunning
  val isMemorySessionEnabled: StateFlow<Boolean> = memoryDiagnostics.isUserSessionEnabled

  init {
    refreshLogs()
  }

  /**
   * Safely loads log entries on Dispatchers.IO so that parsing up to 2 MiB of logs
   * does not block the Main (UI) thread or cause ANRs.
   */
  fun refreshLogs() {
    viewModelScope.launch {
      val loaded = withContext(Dispatchers.IO) {
        try {
          logCatcher.readAllEntries().reversed()
        } catch (_: Exception) {
          emptyList()
        }
      }
      _entries.value = loaded
    }
  }

  fun setMasterSwitch(enabled: Boolean) {
    logCatcher.setMasterSwitch(enabled)
    memoryDiagnostics.onMasterSwitchChanged(enabled)
    refreshLogs()
  }

  fun clearLogs() {
    logCatcher.clearWorkingLogs()
    refreshLogs()
  }

  fun toggleMemoryTestSession() {
    memoryDiagnostics.toggleSession(viewModelScope)
    refreshLogs()
  }

  fun onAppForegroundChanged(inForeground: Boolean) {
    memoryDiagnostics.onAppForegroundChanged(inForeground, viewModelScope)
  }

  fun getExportFileName(): String {
    val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    val stamp = formatter.format(Date())
    return "Vianshield-Logs-$stamp.txt"
  }

  fun getExportContent(): String {
    return logCatcher.getExportPayload()
  }

  fun onExportSucceeded() {
    logCatcher.onExportSucceeded()
    refreshLogs()
  }

  fun onExportFailed(reason: String) {
    logCatcher.onExportFailed(reason)
    refreshLogs()
  }

  override fun onCleared() {
    super.onCleared()
    memoryDiagnostics.stopUserSession()
  }
}
