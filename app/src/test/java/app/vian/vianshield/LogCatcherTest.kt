package app.vian.vianshield

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.vian.vianshield.logging.LogCatcher
import app.vian.vianshield.logging.LogEntry
import app.vian.vianshield.logging.LogLevel
import app.vian.vianshield.logging.MemoryDiagnostics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LogCatcherTest {

  private lateinit var context: Context
  private lateinit var logCatcher: LogCatcher

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    logCatcher = LogCatcher.getInstance(context)
    logCatcher.setMasterSwitch(true)
    logCatcher.clearWorkingLogs()
  }

  @Test
  fun test_log_entry_format_and_parse() {
    val entry = LogEntry.create(
      level = LogLevel.WARN,
      component = "VpnEngineBridge",
      message = "Engine state change detected",
      throwable = IllegalStateException("Sample error")
    )

    val line = entry.toFormattedLine()
    assertTrue(line.contains("[WARN]"))
    assertTrue(line.contains("[VpnEngineBridge]"))
    assertTrue(line.contains("Engine state change detected"))
    assertTrue(line.contains("IllegalStateException: Sample error"))

    val parsed = LogEntry.fromFormattedLine(line)
    assertNotNull(parsed)
    assertEquals(LogLevel.WARN, parsed?.level)
    assertEquals("VpnEngineBridge", parsed?.component)
    assertEquals("Engine state change detected", parsed?.message)
  }

  @Test
  fun test_master_switch_disables_logging() {
    logCatcher.setMasterSwitch(false)
    val entriesBefore = logCatcher.readAllEntries().size

    logCatcher.logInfo("TestComponent", "This should not be written")
    val entriesAfter = logCatcher.readAllEntries().size

    assertEquals(entriesBefore, entriesAfter)
    assertFalse(logCatcher.isMasterSwitchEnabled.value)
  }

  @Test
  fun test_unexpected_termination_detection() {
    val prefs = logCatcher.getPreferences()
    // Simulate previous unclean exit
    prefs.edit()
      .putBoolean(LogCatcher.KEY_CLEAN_SHUTDOWN, false)
      .putLong(LogCatcher.KEY_LAST_PROCESS_START, System.currentTimeMillis() - 10000)
      .commit()

    logCatcher.initializeProcess()

    val entries = logCatcher.readAllEntries()
    val hasCrashWarning = entries.any {
      it.component == "ProcessMonitor" && it.level == LogLevel.WARN && it.message.contains("Unexpected previous process termination")
    }
    assertTrue("Should log unexpected process termination warning", hasCrashWarning)
  }

  @Test
  fun test_clean_shutdown_prevents_warning() {
    logCatcher.clearWorkingLogs()
    logCatcher.markCleanShutdown()

    logCatcher.initializeProcess()

    val entries = logCatcher.readAllEntries()
    val hasCrashWarning = entries.any {
      it.component == "ProcessMonitor" && it.level == LogLevel.WARN
    }
    assertFalse("Clean shutdown should not trigger unexpected termination warning", hasCrashWarning)
  }

  @Test
  fun test_storage_cap_and_archival_pending() {
    // Generate large volume of logs to reach the 2 MiB boundary
    val chunk = "A".repeat(1024) // 1 KiB string
    for (i in 0 until 2200) {
      logCatcher.log(LogLevel.ERROR, "StressTest", "Stress block $i: $chunk")
    }

    val storageBytes = logCatcher.getTotalStorageBytes()
    assertTrue("Storage should be near 2 MiB cap", storageBytes > 1_500_000L)
    assertTrue("Combined storage must NEVER exceed 2 MiB hard limit (2097152 bytes)", storageBytes <= LogCatcher.MAX_STORAGE_BYTES)
    assertTrue("Archival pending must trigger when storage cap is reached", logCatcher.isArchivalPending.value)
  }

  @Test
  fun test_concurrent_logging_never_exceeds_cap() {
    val numThreads = 6
    val iterationsPerThread = 200
    val executor = Executors.newFixedThreadPool(numThreads)
    val latch = CountDownLatch(numThreads)
    val chunk = "C".repeat(2048) // 2 KiB

    for (t in 0 until numThreads) {
      executor.submit {
        try {
          for (i in 0 until iterationsPerThread) {
            val level = if (i % 2 == 0) LogLevel.INFO else LogLevel.ERROR
            logCatcher.log(level, "ThreadWorker$t", "Item $i: $chunk")
          }
        } finally {
          latch.countDown()
        }
      }
    }

    val completed = latch.await(30, TimeUnit.SECONDS)
    executor.shutdown()
    assertTrue("Concurrent workers should finish within timeout", completed)

    val totalBytes = logCatcher.getTotalStorageBytes()
    assertTrue("Combined storage under concurrency must NEVER exceed 2 MiB", totalBytes <= LogCatcher.MAX_STORAGE_BYTES)
    assertTrue("Archival pending should be active", logCatcher.isArchivalPending.value)
  }

  @Test
  fun test_oversized_record_is_truncated_and_does_not_breach_cap() {
    // Attempt to log an oversized 500 KB string
    val massivePayload = "M".repeat(500 * 1024)
    logCatcher.logError("OversizedComponent", massivePayload)

    val totalBytes = logCatcher.getTotalStorageBytes()
    assertTrue("Oversized record must not breach 2 MiB cap", totalBytes <= LogCatcher.MAX_STORAGE_BYTES)

    val entries = logCatcher.readAllEntries()
    val oversizedEntry = entries.find { it.component == "OversizedComponent" }
    assertNotNull(oversizedEntry)
    assertTrue("Message should be truncated", oversizedEntry?.message?.contains("[TRUNCATED]") == true)
  }

  @Test
  fun test_combined_directory_size_accounting() {
    val logDir = File(context.filesDir, "vianshield_logs")
    val dummyRotated = File(logDir, "rotated_archive.log")
    try {
      // Create a 1 MiB dummy rotated log file
      FileOutputStream(dummyRotated).use { fos ->
        fos.write(ByteArray(1024 * 1024) { 'R'.code.toByte() })
      }

      // Now log 1.5 MiB of entries into current.log
      val chunk = "X".repeat(1024)
      for (i in 0 until 1500) {
        logCatcher.logInfo("CombinedDirTest", "Line $i: $chunk")
      }

      val totalCombined = logCatcher.getTotalStorageBytes()
      assertTrue("Combined directory size must include rotated files and not exceed 2 MiB", totalCombined <= LogCatcher.MAX_STORAGE_BYTES)
      assertTrue("Total combined must be >= 1 MiB due to dummy file", totalCombined >= 1024 * 1024L)
    } finally {
      dummyRotated.delete()
    }
  }

  @Test
  fun test_uncaught_jvm_exception_handler() {
    logCatcher.installUncaughtExceptionHandler()
    val handler = Thread.getDefaultUncaughtExceptionHandler()
    assertNotNull(handler)

    val testException = NullPointerException("Forced test exception")
    handler?.uncaughtException(Thread.currentThread(), testException)

    val entries = logCatcher.readAllEntries()
    val caughtCrash = entries.any {
      it.component == "UncaughtException" && it.level == LogLevel.ERROR && it.message.contains("NullPointerException")
    }
    assertTrue("Uncaught exception must be captured in LogCatcher", caughtCrash)
  }

  @Test
  fun test_memory_diagnostics_lifecycle_pause_and_resume() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val testScope = TestScope(testDispatcher)
    val memoryDiagnostics = MemoryDiagnostics(logCatcher)

    // User starts session while app is in foreground
    memoryDiagnostics.startUserSession(testScope)
    assertTrue("User session should be marked enabled", memoryDiagnostics.isUserSessionEnabled.value)
    assertTrue("Sampling should run when in foreground", memoryDiagnostics.isSamplingRunning.value)

    // App goes to background
    memoryDiagnostics.onAppForegroundChanged(false)
    assertFalse("Sampling must pause when app is backgrounded", memoryDiagnostics.isSamplingRunning.value)
    assertTrue("User session intent remains active", memoryDiagnostics.isUserSessionEnabled.value)

    // App returns to foreground
    memoryDiagnostics.onAppForegroundChanged(true, testScope)
    assertTrue("Sampling must resume when app returns to foreground", memoryDiagnostics.isSamplingRunning.value)

    // Master switch disabled
    memoryDiagnostics.onMasterSwitchChanged(false)
    assertFalse("Sampling must stop when master switch is disabled", memoryDiagnostics.isSamplingRunning.value)

    // User explicitly stops session
    memoryDiagnostics.stopUserSession()
    assertFalse("User session should be disabled", memoryDiagnostics.isUserSessionEnabled.value)
    assertFalse("Sampling must remain stopped", memoryDiagnostics.isSamplingRunning.value)
  }

  @Test
  fun test_export_failure_preserves_logs() {
    logCatcher.logInfo("ComponentA", "Important diagnostic entry 1")
    logCatcher.logError("ComponentB", "Critical caught error 2")

    val payloadBefore = logCatcher.getExportPayload()
    assertTrue(payloadBefore.contains("Critical caught error 2"))

    // Simulate export failure
    logCatcher.onExportFailed("User canceled SAF picker")

    assertTrue("Archival pending should remain true on failure", logCatcher.isArchivalPending.value)
    val payloadAfter = logCatcher.getExportPayload()
    assertTrue("Logs must be preserved on export failure", payloadAfter.contains("Critical caught error 2"))
  }

  @Test
  fun test_export_success_clears_working_logs_and_resets_pending() {
    logCatcher.logInfo("ComponentA", "Diagnostic entry")
    logCatcher.log(LogLevel.ERROR, "ComponentB", "Error entry")

    logCatcher.onExportSucceeded()

    assertFalse("Archival pending must be false after success", logCatcher.isArchivalPending.value)
    val entries = logCatcher.readAllEntries()
    val containsOldEntry = entries.any { it.message == "Diagnostic entry" }
    assertFalse("Old working logs must be cleared after verified export", containsOldEntry)
  }

  @Test
  fun test_concurrent_log_during_export_survives_after_export_succeeds() {
    // 1. Initial state before export
    logCatcher.logInfo("PreExport", "Entry 1 - Before Export")

    // 2. Export requested: atomic checkpoint is taken
    val exportPayload = logCatcher.getExportPayload()
    assertTrue("Checkpoint payload must contain pre-export entry", exportPayload.contains("Entry 1 - Before Export"))

    // 3. Concurrent entry arrives while SAF document writing is in progress
    logCatcher.logError("ConcurrentLog", "Entry 2 - Arrived During Export Window")

    // 4. SAF export write completes and onExportSucceeded is called
    logCatcher.onExportSucceeded()

    // 5. Verify: Checkpoint was cleared, but the concurrent entry was NOT wiped
    val remainingEntries = logCatcher.readAllEntries()
    val hasPreExport = remainingEntries.any { it.message == "Entry 1 - Before Export" }
    val hasConcurrent = remainingEntries.any { it.message == "Entry 2 - Arrived During Export Window" }

    assertFalse("Exported checkpoint records must be cleared from working logs", hasPreExport)
    assertTrue("Records logged during export window MUST survive after export succeeds", hasConcurrent)
  }

  @Test
  fun test_export_failure_and_successful_retry() {
    logCatcher.logInfo("RetryTest", "Diagnostic record for retry test")

    // 1. First export attempt captures checkpoint
    val firstPayload = logCatcher.getExportPayload()
    assertTrue(firstPayload.contains("Diagnostic record for retry test"))

    // 2. Export canceled or failed
    logCatcher.onExportFailed("User canceled document creation")
    assertTrue("Archival pending must remain true on failure", logCatcher.isArchivalPending.value)

    // 3. User retries export: payload still contains original checkpoint records
    val retryPayload = logCatcher.getExportPayload()
    assertTrue("Retry payload must preserve original checkpoint records", retryPayload.contains("Diagnostic record for retry test"))

    // 4. Retry succeeds
    logCatcher.onExportSucceeded()
    assertFalse("Archival pending must be reset after successful retry", logCatcher.isArchivalPending.value)
  }

  @Test
  fun test_bounded_crash_handler_fallback_does_not_hang_when_lock_contended() {
    val lock = logCatcher.testLock
    val lockLatch = CountDownLatch(1)
    val releaseLatch = CountDownLatch(1)

    // Background thread simulates another thread stuck holding the lock
    val worker = Thread {
      lock.lock()
      try {
        lockLatch.countDown()
        releaseLatch.await(2, TimeUnit.SECONDS)
      } finally {
        lock.unlock()
      }
    }
    worker.start()

    assertTrue("Background thread must acquire lock", lockLatch.await(5, TimeUnit.SECONDS))

    val startTime = System.currentTimeMillis()
    try {
      // Main thread triggers uncaught crash handler with a bounded timeout of 150ms
      logCatcher.recordCrashWithBoundedWait(
        thread = Thread.currentThread(),
        throwable = RuntimeException("Forced crash with held lock"),
        timeoutMs = 150L
      )
    } finally {
      releaseLatch.countDown()
      worker.join(3000)
    }

    val elapsed = System.currentTimeMillis() - startTime
    assertTrue("Crash handler must return within bounded time (elapsed: ${elapsed}ms)", elapsed < 1500L)

    // Emergency crash file should have been written via fallback
    val entries = logCatcher.readAllEntries()
    val hasEmergencyCrash = entries.any {
      it.component == "UncaughtException" && it.message.contains("Forced crash with held lock")
    }
    assertTrue("Emergency crash entry must be recorded via fallback path", hasEmergencyCrash)
  }
}
