package org.meshtastic.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.meshtastic.feature.settings.EmergencyWipeHandler
import java.io.File

class EmergencyWipeManager(private val context: Context) : EmergencyWipeHandler {

    companion object {
        private const val WIPE_FLAG_FILENAME = "wipe.flag"

        fun isWiped(context: Context): Boolean {
            return File(context.filesDir.parentFile, WIPE_FLAG_FILENAME).exists()
        }
    }

    override suspend fun executeEmergencyWipe() {
        withContext(Dispatchers.IO) {
            try {
                // Clear all databases
                context.databaseList().forEach { dbName ->
                    context.deleteDatabase(dbName)
                }

                // Clear all SharedPreferences
                val prefsDir = context.filesDir.parentFile?.resolve("shared_prefs")
                prefsDir?.listFiles()?.forEach { it.delete() }

                // Clear DataStore files
                context.filesDir.listFiles()?.forEach { it.deleteRecursively() }

                // Clear cache
                context.cacheDir.deleteRecursively()
                context.externalCacheDir?.deleteRecursively()

                // Clear external files
                context.getExternalFilesDir(null)?.deleteRecursively()

                // Write the wipe flag AFTER clearing everything
                // It goes in the parent of filesDir so it survives the filesDir wipe above
                val wipeFlag = File(context.filesDir.parentFile, WIPE_FLAG_FILENAME)
                wipeFlag.createNewFile()

                android.util.Log.d("EmergencyWipe", "Wipe complete")

            } catch (e: Exception) {
                android.util.Log.e("EmergencyWipe", "Wipe error: ${e.message}")
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}