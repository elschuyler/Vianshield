package app.vian.vianshield.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Severity level for Vianshield diagnostic events.
 */
enum class LogLevel {
  INFO,
  WARN,
  ERROR
}

/**
 * Structured diagnostic event captured by LogCatcher.
 * Strictly limited to operational metadata; contains no PII, DNS queries, packet bytes, or credentials.
 */
data class LogEntry(
  val timestamp: Long,
  val isoTime: String,
  val level: LogLevel,
  val component: String,
  val message: String,
  val throwableSummary: String? = null
) {
  fun toFormattedLine(): String {
    val base = "[$isoTime] [${level.name}] [$component]: $message"
    return if (throwableSummary != null) {
      "$base | Exception: $throwableSummary"
    } else {
      base
    }
  }

  companion object {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }

    fun create(
      level: LogLevel,
      component: String,
      message: String,
      throwable: Throwable? = null
    ): LogEntry {
      val now = System.currentTimeMillis()
      val formatted = synchronized(isoFormat) { isoFormat.format(Date(now)) }
      val summary = throwable?.let {
        "${it.javaClass.simpleName}: ${it.message ?: "No message"}"
      }
      return LogEntry(
        timestamp = now,
        isoTime = formatted,
        level = level,
        component = component,
        message = message,
        throwableSummary = summary
      )
    }

    fun fromFormattedLine(line: String): LogEntry? {
      if (line.isBlank() || !line.startsWith("[")) return null
      return try {
        val parts = line.split("] [", limit = 3)
        if (parts.size < 3) return null
        val time = parts[0].removePrefix("[")
        val levelStr = parts[1]
        val rest = parts[2]
        val componentEnd = rest.indexOf("]: ")
        if (componentEnd == -1) return null
        val component = rest.substring(0, componentEnd)
        val msgAndEx = rest.substring(componentEnd + 3)

        val exIndex = msgAndEx.indexOf(" | Exception: ")
        val (message, exception) = if (exIndex != -1) {
          msgAndEx.substring(0, exIndex) to msgAndEx.substring(exIndex + 14)
        } else {
          msgAndEx to null
        }

        val level = try {
          LogLevel.valueOf(levelStr)
        } catch (_: Exception) {
          LogLevel.INFO
        }

        LogEntry(
          timestamp = 0L,
          isoTime = time,
          level = level,
          component = component,
          message = message,
          throwableSummary = exception
        )
      } catch (_: Exception) {
        null
      }
    }
  }
}
