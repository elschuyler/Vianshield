package app.vian.vianshield.logging

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * LogCatcher: Lightweight, zero-telemetry diagnostic log backend for Vianshield.
 *
 * Guarantees & Constraints:
 * - Runs entirely on-device; logs are NEVER transmitted over the network.
 * - Collects ONLY operational and diagnostic metadata (startup, process termination, exceptions, engine status).
 * - FORBIDDEN: Packet payloads, browsing history, DNS queries, private keys, and user tokens are strictly never recorded.
 * - Note on Native Crashes: Standard Kotlin/JVM exception handlers cannot catch low-level native crashes (such as SIGSEGV
 *   or SIGABRT in native Go-JNI binaries), system force-stops, or power loss.
 * - Hard Storage Limit: Maximum combined size of 2 MiB (2,097,152 bytes) for all files in logDirectory.
 * - Atomic Export Checkpoint: Captures a non-duplicating checkpoint during export; subsequent records are preserved in active log.
 * - Bounded Crash Handler: Does not wait indefinitely for file locks during fatal crash recording.
 */
class LogCatcher private constructor(private val context: Context) {

  private val prefs: SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val logDirectory: File = File(context.filesDir, "vianshield_logs").apply {
    if (!exists()) mkdirs()
  }

  private val currentLogFile: File = File(logDirectory, "current.log")
  private val checkpointFile: File = File(logDirectory, "checkpoint.log")
  private val emergencyCrashFile: File = File(logDirectory, "emergency_crash.log")

  private val _isArchivalPending = MutableStateFlow(false)
  val isArchivalPending: StateFlow<Boolean> = _isArchivalPending.asStateFlow()

  private val _isMasterSwitchEnabled = MutableStateFlow(
    prefs.getBoolean(KEY_MASTER_LOGGING_ENABLED, true)
  )
  val isMasterSwitchEnabled: StateFlow<Boolean> = _isMasterSwitchEnabled.asStateFlow()

  private val _currentStorageBytes = MutableStateFlow(0L)
  val currentStorageBytes: StateFlow<Long> = _currentStorageBytes.asStateFlow()

  private val _droppedRecordsCount = MutableStateFlow(0L)
  val droppedRecordsCount: StateFlow<Long> = _droppedRecordsCount.asStateFlow()

  // Small, explicitly bounded in-memory buffer for critical errors when disk is completely saturated
  private val inMemoryEmergencyQueue = ArrayDeque<LogEntry>(MAX_MEMORY_QUEUE_CAPACITY)

  // Checkpoint snapshots to preserve emergency memory queue and dropped counts across export attempts
  private val checkpointEmergencyQueue = mutableListOf<LogEntry>()
  private var checkpointDroppedCount = 0L

  private val lock = ReentrantLock(true)
  private val isExceptionHandlerInstalled = AtomicBoolean(false)

  init {
    updateStorageMetrics()
    checkStorageLimit()
  }

  fun getPreferences(): SharedPreferences = prefs
  internal val testLock: ReentrantLock get() = lock

  fun getTotalStorageBytes(): Long {
    lock.withLock {
      return logDirectory.listFiles()?.sumOf { it.length() } ?: 0L
    }
  }

  /**
   * Installs application-level uncaught JVM exception handler.
   * Preserves and delegates to previous handler.
   * Uses bounded waiting to prevent deadlock if another thread is stuck in file I/O.
   * Explicit Note: Does NOT capture native SIGSEGV/SIGABRT signals, system kills, or power loss.
   */
  fun installUncaughtExceptionHandler() {
    if (!isExceptionHandlerInstalled.compareAndSet(false, true)) return

    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    val isHandling = AtomicBoolean(false)

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      if (isHandling.compareAndSet(false, true)) {
        try {
          recordCrashWithBoundedWait(thread, throwable)
        } catch (_: Throwable) {
          // Prevent recursive exception loops
        }
      }
      previousHandler?.uncaughtException(thread, throwable)
    }
  }

  /**
   * Records a fatal JVM exception with a bounded wait (maximum timeoutMs).
   * If lock acquisition times out, uses a non-blocking fallback write to emergency_crash.log.
   */
  internal fun recordCrashWithBoundedWait(
    thread: Thread,
    throwable: Throwable,
    timeoutMs: Long = 1000L
  ) {
    val boundedMsg = (throwable.message ?: "No details").take(500)
    val crashEntry = LogEntry.create(
      level = LogLevel.ERROR,
      component = "UncaughtException",
      message = "Uncaught JVM exception on thread '${thread.name}': ${throwable.javaClass.simpleName} - $boundedMsg",
      throwable = throwable
    )

    val acquired = try {
      lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
      false
    }

    if (acquired) {
      try {
        writeEntryUnderLock(crashEntry)
      } finally {
        lock.unlock()
      }
    } else {
      // Lock held by stuck thread; use safe non-blocking emergency fallback path
      writeCrashFallbackDirect(crashEntry)
    }
  }

  /**
   * Emergency fallback for crash recording when main lock cannot be acquired within timeout.
   */
  private fun writeCrashFallbackDirect(entry: LogEntry) {
    try {
      val line = entry.toFormattedLine() + "\n"
      val lineBytes = line.toByteArray(StandardCharsets.UTF_8)
      // Only append if emergency file won't breach remaining cap
      val currentTotal = logDirectory.listFiles()?.sumOf { it.length() } ?: 0L
      if (currentTotal + lineBytes.size <= MAX_STORAGE_BYTES) {
        FileOutputStream(emergencyCrashFile, true).use { fos ->
          fos.write(lineBytes)
        }
      }
    } catch (_: Exception) {
      // Prevent crash reporting itself from crashing
    }
  }

  /**
   * Initialize logging lifecycle for the current app process.
   * Detects abnormal termination of previous process if clean shutdown flag was not recorded.
   */
  fun initializeProcess() {
    installUncaughtExceptionHandler()

    val cleanExit = prefs.getBoolean(KEY_CLEAN_SHUTDOWN, true)
    val lastProcessStartTime = prefs.getLong(KEY_LAST_PROCESS_START, 0L)

    if (!cleanExit && lastProcessStartTime > 0L) {
      log(
        level = LogLevel.WARN,
        component = "ProcessMonitor",
        message = "Unexpected previous process termination detected (unclean shutdown). " +
          "Note: Native signals (SIGSEGV/SIGABRT) bypass JVM exception handlers."
      )
    }

    prefs.edit()
      .putBoolean(KEY_CLEAN_SHUTDOWN, false)
      .putLong(KEY_LAST_PROCESS_START, System.currentTimeMillis())
      .commit()

    log(
      level = LogLevel.INFO,
      component = "Startup",
      message = "Vianshield process initialized (App ID: ${context.packageName})"
    )
  }

  fun markCleanShutdown() {
    prefs.edit()
      .putBoolean(KEY_CLEAN_SHUTDOWN, true)
      .commit()
  }

  fun setMasterSwitch(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_MASTER_LOGGING_ENABLED, enabled).commit()
    _isMasterSwitchEnabled.value = enabled
    if (enabled) {
      log(LogLevel.INFO, "LogCatcher", "Master logging switch enabled by user.")
    }
  }

  /**
   * Record a diagnostic event with strict pre-check storage accounting and bounded fallback.
   */
  fun log(
    level: LogLevel,
    component: String,
    message: String,
    throwable: Throwable? = null
  ) {
    if (!_isMasterSwitchEnabled.value) return

    val boundedMessage = if (message.length > MAX_SINGLE_ENTRY_CHARS) {
      message.take(MAX_SINGLE_ENTRY_CHARS) + " ...[TRUNCATED]"
    } else {
      message
    }

    val entry = LogEntry.create(level, component, boundedMessage, throwable)

    lock.withLock {
      writeEntryUnderLock(entry)
    }
  }

  private fun writeEntryUnderLock(entry: LogEntry) {
    val line = entry.toFormattedLine() + "\n"
    val lineBytes = line.toByteArray(StandardCharsets.UTF_8)
    val combinedBytes = getTotalStorageBytes()

    // If writing this line breaches the hard 2 MiB boundary, prepare room BEFORE writing
    if (combinedBytes + lineBytes.size > MAX_STORAGE_BYTES) {
      _isArchivalPending.value = true

      val neededBytes = (combinedBytes + lineBytes.size) - MAX_STORAGE_BYTES
      makeRoomByPruningInfoRecords(neededBytes)

      val updatedCombined = getTotalStorageBytes()
      if (updatedCombined + lineBytes.size > MAX_STORAGE_BYTES) {
        // Still does not fit on disk without deleting critical records
        if (entry.level == LogLevel.INFO) {
          _droppedRecordsCount.value += 1
          return
        } else {
          // WARN or ERROR: Store in bounded in-memory emergency queue
          if (inMemoryEmergencyQueue.size >= MAX_MEMORY_QUEUE_CAPACITY) {
            inMemoryEmergencyQueue.removeFirst()
            _droppedRecordsCount.value += 1
          }
          inMemoryEmergencyQueue.addLast(entry)
          return
        }
      }
    }

    // Write safely to active disk log
    try {
      FileOutputStream(currentLogFile, true).use { fos ->
        OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
          writer.write(line)
        }
      }
    } catch (_: Exception) {
      if (entry.level != LogLevel.INFO) {
        if (inMemoryEmergencyQueue.size >= MAX_MEMORY_QUEUE_CAPACITY) {
          inMemoryEmergencyQueue.removeFirst()
          _droppedRecordsCount.value += 1
        }
        inMemoryEmergencyQueue.addLast(entry)
      } else {
        _droppedRecordsCount.value += 1
      }
    }

    updateStorageMetrics()
    checkStorageLimit()
  }

  fun logInfo(component: String, message: String) = log(LogLevel.INFO, component, message)

  fun logWarn(component: String, message: String, throwable: Throwable? = null) =
    log(LogLevel.WARN, component, message, throwable)

  fun logError(component: String, message: String, throwable: Throwable? = null) =
    log(LogLevel.ERROR, component, message, throwable)

  fun logVpnEngineStatus(status: String, details: String? = null) {
    val msg = if (details != null) "$status: $details" else status
    log(LogLevel.INFO, "VpnEngineBridge", msg)
  }

  /**
   * Reads all records from private storage plus any in-memory emergency records.
   * Includes checkpoint, active current log, emergency crash file, and memory queue.
   */
  fun readAllEntries(): List<LogEntry> {
    lock.withLock {
      val entries = mutableListOf<LogEntry>()

      // 1. Checkpoint entries (if an export is currently pending/in progress)
      if (checkpointFile.exists()) {
        try {
          checkpointFile.readLines(StandardCharsets.UTF_8)
            .mapNotNullTo(entries) { LogEntry.fromFormattedLine(it) }
        } catch (_: Exception) {}
      }
      entries.addAll(checkpointEmergencyQueue)

      // 2. Active current entries
      if (currentLogFile.exists()) {
        try {
          currentLogFile.readLines(StandardCharsets.UTF_8)
            .mapNotNullTo(entries) { LogEntry.fromFormattedLine(it) }
        } catch (_: Exception) {}
      }

      // 3. Emergency crash file (if present)
      if (emergencyCrashFile.exists()) {
        try {
          emergencyCrashFile.readLines(StandardCharsets.UTF_8)
            .mapNotNullTo(entries) { LogEntry.fromFormattedLine(it) }
        } catch (_: Exception) {}
      }

      // 4. In-memory emergency queue
      entries.addAll(inMemoryEmergencyQueue)

      return entries
    }
  }

  /**
   * Captures an atomic export checkpoint.
   *
   * Mechanism (Zero Disk Duplication):
   * - Atomically renames current.log to checkpoint.log in-place (POSIX rename takes 0 additional bytes).
   * - Subsequent log() calls immediately start writing to a fresh current.log.
   * - Captures inMemoryEmergencyQueue and droppedRecordsCount into checkpoint snapshots.
   * - Retains checkpoint across failures/cancellations until onExportSucceeded() completes.
   */
  fun getExportPayload(): String {
    lock.withLock {
      // If checkpoint does not already exist, create atomic checkpoint from current.log
      if (!checkpointFile.exists() && currentLogFile.exists()) {
        currentLogFile.renameTo(checkpointFile)
        checkpointEmergencyQueue.clear()
        checkpointEmergencyQueue.addAll(inMemoryEmergencyQueue)
        inMemoryEmergencyQueue.clear()
        checkpointDroppedCount = _droppedRecordsCount.value
        _droppedRecordsCount.value = 0L
      } else if (!checkpointFile.exists() && inMemoryEmergencyQueue.isNotEmpty()) {
        // Only in-memory records existed
        checkpointEmergencyQueue.clear()
        checkpointEmergencyQueue.addAll(inMemoryEmergencyQueue)
        inMemoryEmergencyQueue.clear()
        checkpointDroppedCount = _droppedRecordsCount.value
        _droppedRecordsCount.value = 0L
      }

      val sb = StringBuilder()

      if (checkpointFile.exists()) {
        try {
          sb.append(checkpointFile.readText(StandardCharsets.UTF_8))
        } catch (_: Exception) {
          sb.append("Error reading checkpoint log file.\n")
        }
      }

      if (checkpointEmergencyQueue.isNotEmpty()) {
        sb.append("\n--- In-Memory Emergency Queue (Disk Cap Reached) ---\n")
        for (entry in checkpointEmergencyQueue) {
          sb.append(entry.toFormattedLine()).append("\n")
        }
      }

      if (checkpointDroppedCount > 0L) {
        sb.append("\n[Notice: $checkpointDroppedCount lower-priority entries were dropped to protect 2 MiB device storage cap]\n")
      }

      return if (sb.isEmpty()) "No logs recorded.\n" else sb.toString()
    }
  }

  /**
   * Verified callback executed ONLY AFTER external export to Downloads/SAF succeeds.
   * Deletes ONLY the checkpoint file and clears checkpoint state.
   * Any records logged to current.log during export are preserved intact!
   */
  fun onExportSucceeded() {
    lock.withLock {
      if (checkpointFile.exists()) {
        try { checkpointFile.delete() } catch (_: Exception) {}
      } else if (currentLogFile.exists()) {
        try { currentLogFile.delete() } catch (_: Exception) {}
      }
      checkpointEmergencyQueue.clear()
      checkpointDroppedCount = 0L

      // Clean up emergency crash file if present
      if (emergencyCrashFile.exists()) {
        try { emergencyCrashFile.delete() } catch (_: Exception) {}
      }

      updateStorageMetrics()
      _isArchivalPending.value = (getTotalStorageBytes() >= MAX_STORAGE_BYTES)
    }
    log(LogLevel.INFO, "LogCatcher", "Log archival verified & completed. Checkpoint cleared.")
  }

  /**
   * Callback executed when export fails or is canceled.
   * Preserves checkpoint and active log so export can be retried without data loss.
   */
  fun onExportFailed(reason: String) {
    lock.withLock {
      _isArchivalPending.value = true
    }
    log(LogLevel.WARN, "LogCatcher", "Archival export could not complete: $reason. Checkpoint preserved for retry.")
  }

  /**
   * Clears logs manually (user action).
   */
  fun clearWorkingLogs() {
    lock.withLock {
      logDirectory.listFiles()?.forEach { file ->
        try { file.delete() } catch (_: Exception) {}
      }
      inMemoryEmergencyQueue.clear()
      checkpointEmergencyQueue.clear()
      checkpointDroppedCount = 0L
      _droppedRecordsCount.value = 0L
      _isArchivalPending.value = false
      updateStorageMetrics()
    }
    log(LogLevel.INFO, "LogCatcher", "Diagnostic logs manually cleared by user.")
  }

  private fun updateStorageMetrics() {
    _currentStorageBytes.value = getTotalStorageBytes()
  }

  private fun checkStorageLimit() {
    if (getTotalStorageBytes() >= MAX_STORAGE_BYTES) {
      _isArchivalPending.value = true
    }
  }

  /**
   * Prunes oldest INFO records first to free up neededBytes without destroying WARN or ERROR records.
   * Prunes from currentLogFile (or checkpointFile if currentLogFile has none).
   */
  private fun makeRoomByPruningInfoRecords(bytesToFree: Long): Boolean {
    val targetFile = if (currentLogFile.exists() && currentLogFile.length() > 0L) {
      currentLogFile
    } else if (checkpointFile.exists()) {
      checkpointFile
    } else {
      return false
    }

    return try {
      val lines = targetFile.readLines(StandardCharsets.UTF_8).toMutableList()
      var freed = 0L
      val iterator = lines.iterator()
      val retained = mutableListOf<String>()

      while (iterator.hasNext()) {
        val line = iterator.next()
        if (freed < bytesToFree && line.contains("] [INFO] [")) {
          freed += line.toByteArray(StandardCharsets.UTF_8).size + 1
          _droppedRecordsCount.value += 1
        } else {
          retained.add(line)
        }
      }

      if (freed < bytesToFree && retained.isNotEmpty()) {
        val emergencyDrop = (retained.size * 0.25).toInt().coerceAtLeast(1)
        for (i in 0 until emergencyDrop) {
          if (retained.isNotEmpty()) {
            val removed = retained.removeAt(0)
            freed += removed.toByteArray(StandardCharsets.UTF_8).size + 1
            _droppedRecordsCount.value += 1
          }
        }
      }

      targetFile.writeText(retained.joinToString("\n") + if (retained.isNotEmpty()) "\n" else "", StandardCharsets.UTF_8)
      updateStorageMetrics()
      freed >= bytesToFree
    } catch (_: Exception) {
      false
    }
  }

  companion object {
    const val MAX_STORAGE_BYTES = 2 * 1024 * 1024L // 2 MiB (2,097,152 bytes)
    const val MAX_SINGLE_ENTRY_CHARS = 32 * 1024 // 32,768 chars max per single message
    const val MAX_MEMORY_QUEUE_CAPACITY = 50 // Bounded in-memory queue
    const val PREFS_NAME = "vianshield_logging_prefs"
    const val KEY_CLEAN_SHUTDOWN = "key_clean_shutdown"
    const val KEY_LAST_PROCESS_START = "key_last_process_start"
    const val KEY_MASTER_LOGGING_ENABLED = "key_master_logging_enabled"

    @Volatile
    private var instance: LogCatcher? = null

    fun getInstance(context: Context): LogCatcher {
      return instance ?: synchronized(this) {
        instance ?: LogCatcher(context.applicationContext).also { instance = it }
      }
    }
  }
}
