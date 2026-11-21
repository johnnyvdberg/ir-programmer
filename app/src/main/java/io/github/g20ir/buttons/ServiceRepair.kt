package io.github.g20ir.buttons

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Works around a Shield boot problem seen on the owner's device: Android starts the button
 * service's process very early during boot, the process dies before attaching ("failed to
 * attach … start timeout"), and Android never retries. The switch stays on, but the service
 * sits in "Binding services" until the setting is applied again.
 *
 * Re-writing Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES makes Android bind again. That
 * needs WRITE_SECURE_SETTINGS, which adb can grant (the helper does it automatically).
 *
 * Three safety nets call [repairIfNeeded]:
 *  1. after boot / app update: a chain of checks at 15 s, 30 s, 1, 2 and 5 min ([RepairJobService]);
 *  2. a persisted job every 15 minutes, which survives reboots even if the boot signal is lost;
 *  3. whenever the app is opened.
 */
object ServiceRepair {
    const val PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS
    private const val JOB_CHAIN = 2001
    private const val JOB_PERIODIC = 2002
    private val CHAIN_DELAYS_MS = longArrayOf(15_000, 30_000, 60_000, 120_000, 300_000)
    private const val PERIOD_MS = 15 * 60 * 1000L
    private const val TAG = "G20IR"

    fun component(c: Context) = ComponentName(c, ButtonService::class.java)

    fun canRepair(c: Context) = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun isEnabledInSettings(c: Context): Boolean =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.equals(component(c).flattenToString(), ignoreCase = true) } == true

    /** Bound and connected, according to the service itself or to Android. */
    fun isRunning(c: Context): Boolean {
        if (ButtonStore.serviceRunning.value) return true
        val am = c.getSystemService(AccessibilityManager::class.java) ?: return false
        val mine = component(c)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.let { si -> si.packageName == mine.packageName && si.name == mine.className } == true }
    }

    /**
     * Removes and re-adds our service in the enabled list (keeping the others), so Android
     * binds it again. Blocks ~1 s. Returns false without the permission.
     */
    fun rebind(c: Context): Boolean {
        if (!canRepair(c)) return false
        val cr = c.contentResolver
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        return try {
            val mine = component(c).flattenToString()
            val list = Settings.Secure.getString(cr, key).orEmpty().split(':').filter { it.isNotBlank() }.toMutableList()
            if (list.none { it.equals(mine, ignoreCase = true) }) list += mine
            Settings.Secure.putString(cr, key, list.filterNot { it.equals(mine, ignoreCase = true) }.joinToString(":"))
            Thread.sleep(800)
            Settings.Secure.putString(cr, key, list.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "accessibility services re-applied: $list")
            true
        } catch (e: Exception) {
            Log.w(TAG, "re-applying accessibility services failed: ${e.message}")
            false
        }
    }

    /** Repairs if the service is switched on but not running. Call off the main thread. */
    fun repairIfNeeded(c: Context, reason: String): Boolean {
        if (!isEnabledInSettings(c) || isRunning(c)) return false
        Log.i(TAG, "button service enabled but not running ($reason)")
        val ok = rebind(c)
        record(c, if (ok) "repaired ($reason)" else "needed a repair ($reason) but has no permission")
        return ok
    }

    private fun record(c: Context, what: String) {
        val time = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault()).format(Date())
        c.getSharedPreferences("repair", Context.MODE_PRIVATE).edit().putString("last", "$time: $what").apply()
    }

    fun lastRepair(c: Context): String? = c.getSharedPreferences("repair", Context.MODE_PRIVATE).getString("last", null)

    /** Schedules check [attempt] of the after-boot chain. */
    fun scheduleChain(c: Context, attempt: Int) {
        if (attempt >= CHAIN_DELAYS_MS.size) return
        val delay = CHAIN_DELAYS_MS[attempt]
        val job = JobInfo.Builder(JOB_CHAIN, ComponentName(c, RepairJobService::class.java))
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay + 10_000)
            .setExtras(PersistableBundle().apply { putInt("attempt", attempt) })
            .build()
        c.getSystemService(JobScheduler::class.java)?.schedule(job)
    }

    /** The persisted 15-minute check; scheduling again keeps the existing one. */
    fun ensurePeriodic(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_PERIODIC) != null) return
        val job = JobInfo.Builder(JOB_PERIODIC, ComponentName(c, RepairJobService::class.java))
            .setPeriodic(PERIOD_MS)
            .setPersisted(true)
            .build()
        js.schedule(job)
    }

    /** Safety net 3: when the app is opened. Waits a moment in case Android is binding right now. */
    fun onAppOpened(c: Context) {
        val app = c.applicationContext
        ensurePeriodic(app)
        thread(name = "g20-open-repair", isDaemon = true) {
            Thread.sleep(2_000)
            repairIfNeeded(app, "app opened")
        }
    }
}

/** Runs both the after-boot chain and the periodic check. */
class RepairJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val attempt = params.extras.getInt("attempt", -1)
        thread(name = "g20-repair-job") {
            try {
                if (attempt >= 0) {
                    ServiceRepair.repairIfNeeded(this, "check ${attempt + 1} after boot")
                    ServiceRepair.scheduleChain(this, attempt + 1)
                } else {
                    ServiceRepair.repairIfNeeded(this, "periodic check")
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

/** After boot (and after an app update): start the check chain and make sure the periodic job exists. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        ServiceRepair.ensurePeriodic(app)
        if (ServiceRepair.isEnabledInSettings(app)) ServiceRepair.scheduleChain(app, 0)
    }
}
