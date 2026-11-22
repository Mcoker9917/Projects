package dev.duolauncher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.View
import java.util.concurrent.Executors

class AppInfo(val label: String, val component: ComponentName, val user: UserHandle, val icon: Bitmap) {
    val key: String get() = component.flattenToString()
    val pkg: String get() = component.packageName
}

/** Every launchable app on the phone, with iOS-style rounded-square icons. Keeps itself up to date. */
object AppsRepo {

    @Volatile var apps: List<AppInfo> = emptyList()
        private set
    val loaded get() = apps.isNotEmpty()

    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var watching = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun load(ctx: Context) {
        val app = ctx.applicationContext
        val la = app.getSystemService(LauncherApps::class.java) ?: return
        if (!watching) {
            watching = true
            la.registerCallback(object : LauncherApps.Callback() {
                override fun onPackageRemoved(packageName: String, user: UserHandle) = load(app)
                override fun onPackageAdded(packageName: String, user: UserHandle) = load(app)
                override fun onPackageChanged(packageName: String, user: UserHandle) = load(app)
                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = load(app)
                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = load(app)
            }, main)
        }
        exec.execute {
            val density = app.resources.displayMetrics.densityDpi
            val size = (64 * app.resources.displayMetrics.density).toInt()
            val list = la.profiles.flatMap { u -> la.getActivityList(null, u) }
                .filter { it.componentName.packageName != app.packageName }
                .map { AppInfo(it.label.toString(), it.componentName, it.user, render(it.getIcon(density), size)) }
                .sortedBy { it.label.lowercase() }
            main.post {
                apps = list
                listeners.toList().forEach { it() }
            }
        }
    }

    fun find(key: String): AppInfo? = apps.firstOrNull { it.key == key }
    fun byPackage(pkg: String): AppInfo? = apps.firstOrNull { it.pkg == pkg }

    /** Apps whose name contains [q], names that start with it first. */
    fun search(q: String): List<AppInfo> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        return apps.filter { it.label.contains(t, ignoreCase = true) }
            .sortedBy { if (it.label.startsWith(t, ignoreCase = true)) 0 else 1 }
    }

    fun launch(ctx: Context, app: AppInfo, from: View?) {
        val la = ctx.getSystemService(LauncherApps::class.java) ?: return
        var bounds: Rect? = null
        var opts: ActivityOptions? = null
        if (from != null) {
            val loc = IntArray(2)
            from.getLocationOnScreen(loc)
            bounds = Rect(loc[0], loc[1], loc[0] + from.width, loc[1] + from.height)
            opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height)
        }
        try {
            la.startMainActivity(app.component, app.user, bounds, opts?.toBundle())
        } catch (e: Exception) {
            // App went away between listing and tapping; the list will refresh itself.
        }
    }

    fun appInfo(ctx: Context, app: AppInfo) {
        ctx.getSystemService(LauncherApps::class.java)?.startAppDetailsActivity(app.component, app.user, null, null)
    }

    fun uninstall(ctx: Context, app: AppInfo) {
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Draw an icon as an iOS-style rounded square. */
    private fun render(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size * 0.225f
        c.clipPath(Path().apply { addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, Path.Direction.CW) })
        if (d is AdaptiveIconDrawable) {
            // Adaptive icon layers are 108dp with the visible part in the middle 72dp.
            val pad = size / 4
            val bg = d.background
            if (bg != null) {
                bg.setBounds(-pad, -pad, size + pad, size + pad)
                bg.draw(c)
            } else {
                c.drawColor(Color.WHITE)
            }
            d.foreground?.let {
                it.setBounds(-pad, -pad, size + pad, size + pad)
                it.draw(c)
            }
        } else {
            c.drawColor(Color.WHITE)
            val inset = (size * 0.14f).toInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(c)
        }
        return bmp
    }
}
