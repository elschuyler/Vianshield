package app.vian.vianshield.vpn

import android.content.Context
import app.vian.vianshield.logging.LogCatcher
import app.vian.vianshield.logging.LogLevel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe one-time initializer for the Gomobile runtime (`go.Seq`).
 *
 * Ensures `go.Seq.setContext(applicationContext)` is invoked safely and exactly once
 * before any Firestack or Go netstack API is executed.
 */
object GomobileRuntime {
  private const val TAG = "GomobileRuntime"
  private val isInitialized = AtomicBoolean(false)

  fun init(context: Context) {
    if (isInitialized.compareAndSet(false, true)) {
      try {
        go.Seq.setContext(context.applicationContext)
        LogCatcher.getInstance(context).log(
          LogLevel.INFO,
          TAG,
          "Gomobile runtime initialized successfully via go.Seq.setContext."
        )
      } catch (t: Throwable) {
        LogCatcher.getInstance(context).log(
          LogLevel.WARN,
          TAG,
          "Failed to initialize go.Seq context: ${t.message}"
        )
      }
    }
  }

  fun isInitialized(): Boolean = isInitialized.get()
}
