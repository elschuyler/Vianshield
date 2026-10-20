package app.vian.vianshield.vpn

import android.content.Context
import java.io.File

/**
 * Handles private internal storage of the active WireGuard configuration.
 * Uses app-private files directory (/data/user/0/app.vian.vianshield/files/wg_active.conf),
 * never external storage or world-readable locations.
 */
class WireGuardConfigStore(private val context: Context) {

  private val configFile: File
    get() = File(context.filesDir, CONFIG_FILE_NAME)

  private val metaFile: File
    get() = File(context.filesDir, META_FILE_NAME)

  fun saveConfig(content: String, originalFileName: String) {
    configFile.writeText(content, Charsets.UTF_8)
    metaFile.writeText(originalFileName, Charsets.UTF_8)
  }

  fun loadConfig(): WireGuardConfig? {
    if (!configFile.exists()) return null
    return try {
      val content = configFile.readText(Charsets.UTF_8)
      val fileName = if (metaFile.exists()) metaFile.readText(Charsets.UTF_8) else "tunnel.conf"
      WireGuardConfigParser.parse(content, fileName)
    } catch (_: Exception) {
      null
    }
  }

  fun clear() {
    if (configFile.exists()) configFile.delete()
    if (metaFile.exists()) metaFile.delete()
  }

  companion object {
    private const val CONFIG_FILE_NAME = "wg_active.conf"
    private const val META_FILE_NAME = "wg_active.meta"
  }
}
