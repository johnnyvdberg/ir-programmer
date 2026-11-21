package io.github.g20ir.buttons

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlin.concurrent.thread

/**
 * Works around a Shield boot problem seen on the owner's device: Android starts the button
 * service's process very early during boot, the process dies before attaching ("failed to
 * attach … start timeout"), and Android never retries. The switch stays on, but the service
 * sits in "Binding services" until the setting is applied again.
 *
 * Re-writing Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES makes Android bind again. That
 * needs WRITE_SECURE_SETTINGS, which adb can grant (the helper does it automatically).
 */
object ServiceRepair {
    const val PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS

    fun component(c: Context) = ComponentName(c, ButtonService::class.java).flattenToString()

    fun canRepair(c: Context) = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun isEnabledInSettings(c: Context): Boolean =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.equals(component(c), ignoreCase = true) } == true

    /**
     * Removes and re-adds every enabled accessibility service, so Android binds them again
     * (this also revives other apps' services stuck the same way). Blocks ~1 s.
     * Returns false without the permission.
     */
    fun rebind(c: Context): Boolean {
        if (!canRepair(c)) return false
        val cr = c.contentResolver
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        return try {
            val current = Settings.Secure.getString(cr, key).orEmpty()
            val mine = component(c)
            val list = current.split(':').filter { it.isNotBlank() }.toMutableList()
            if (list.none { it.equals(mine, ignoreCase = true) }) list += mine
            Settings.Secure.putString(cr, key, list.filterNot { it.equals(mine, ignoreCase = true) }.joinToString(":"))
            Thread.sleep(800)
            Settings.Secure.putString(cr, key, list.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i("G20IR", "accessibility services re-applied: $list")
            true
        } catch (e: Exception) {
            Log.w("G20IR", "re-applying accessibility services failed: ${e.message}")
            false
        }
    }
}

/** After boot (and after an app update), repairs the button service if Android left it stuck. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        if (!ServiceRepair.isEnabledInSettings(app)) return
        val pending = goAsync()
        thread(name = "g20-boot-repair") {
            try {
                // Give Android time to finish booting and bind on its own.
                for (attempt in 1..2) {
                    Thread.sleep(15_000)
                    if (ButtonStore.serviceRunning.value) break
                    Log.i("G20IR", "button service not running after boot (attempt $attempt), re-applying")
                    if (!ServiceRepair.rebind(app)) {
                        Log.i("G20IR", "no WRITE_SECURE_SETTINGS; turn the helper on once or grant it with adb")
                        break
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                pending.finish()
            }
        }
    }
}
