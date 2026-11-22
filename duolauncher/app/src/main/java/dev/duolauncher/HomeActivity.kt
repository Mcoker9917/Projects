package dev.duolauncher

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.graphics.drawable.ColorDrawable
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home screen. Three layouts, picked from how the phone is held:
 *  - FOLDED: iOS-style app grid, ask bar and dock on the outer screen
 *  - UNFOLDED: the same on the left half, a live panel (Claude, calendar, notifications,
 *    widgets) on the right half
 *  - TABLETOP (half folded, hinge across the middle): clock and what's playing on top,
 *    media controls and dock on the bottom
 */
class HomeActivity : Activity() {

    private enum class Shape { FOLDED, UNFOLDED, TABLETOP }

    private val prefs by lazy { getSharedPreferences("duo", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private lateinit var root: FrameLayout
    private var shape: Shape? = null
    private var insets: Insets = Insets.NONE

    // Fold posture
    private var tabletop = false
    private var hingeTop = 0
    private var tracker: WindowInfoTrackerCallbackAdapter? = null
    private val layoutListener = Consumer<WindowLayoutInfo> { info ->
        val f = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        tabletop = f != null && f.state == FoldingFeature.State.HALF_OPENED &&
            f.orientation == FoldingFeature.Orientation.HORIZONTAL
        hingeTop = f?.bounds?.top ?: 0
        rebuild(force = false)
    }

    // Home screen pieces
    private var search: EditText? = null
    private var homeScroll: ScrollView? = null
    private var resultsBox: LinearLayout? = null
    private var homeColumns = 4
    private var drawer: View? = null

    // Panel pieces
    private var calendarBox: LinearLayout? = null
    private var notifBox: LinearLayout? = null

    // Tabletop media
    private var mediaTitle: TextView? = null
    private var mediaPlay: TextView? = null
    private val mediaTick = object : Runnable {
        override fun run() {
            refreshMedia()
            handler.postDelayed(this, 1500)
        }
    }

    // Widgets
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var widgetMgr: AppWidgetManager
    private var pendingWidget = -1

    private val appsChanged: () -> Unit = {
        ensureDefaults()
        rebuild(force = true)
    }
    private val notifsChanged: () -> Unit = { refreshNotifications() }

    // ---- lifecycle -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        root = FrameLayout(this)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, wi ->
            val i = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            if (i != insets) {
                insets = i
                rebuild(force = true)
            }
            WindowInsets.CONSUMED
        }
        widgetHost = AppWidgetHost(this, 1024)
        widgetMgr = AppWidgetManager.getInstance(this)
        AppsRepo.addListener(appsChanged)
        AppsRepo.load(this)
        rebuild(force = true)
    }

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
        try {
            tracker = WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this)).also {
                it.addWindowLayoutInfoListener(this, mainExecutor, layoutListener)
            }
        } catch (e: Exception) {
            tracker = null
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
            // Already stopped.
        }
        tracker?.removeWindowLayoutInfoListener(layoutListener)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        NotifService.addListener(notifsChanged)
        refreshCalendar()
        refreshNotifications()
        if (shape == Shape.TABLETOP) handler.post(mediaTick)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        NotifService.removeListener(notifsChanged)
        handler.removeCallbacks(mediaTick)
    }

    override fun onDestroy() {
        AppsRepo.removeListener(appsChanged)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuild(force = true)
    }

    /** Pressing Home while already home: close whatever is open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeDrawer()
        clearSearch()
        homeScroll?.smoothScrollTo(0, 0)
    }

    @Deprecated("Back on the home screen just closes things")
    override fun onBackPressed() {
        if (drawer != null) closeDrawer() else clearSearch()
    }

    // ---- layout ------------------------------------------------------------------------------

    private fun currentShape(): Shape = when {
        tabletop -> Shape.TABLETOP
        resources.configuration.smallestScreenWidthDp >= 600 -> Shape.UNFOLDED
        else -> Shape.FOLDED
    }

    private fun rebuild(force: Boolean) {
        val s = currentShape()
        if (!force && s == shape) return
        shape = s
        handler.removeCallbacks(mediaTick)
        root.removeAllViews()
        drawer = null
        calendarBox = null
        notifBox = null
        mediaTitle = null
        mediaPlay = null
        ensureDefaults()
        when (s) {
            Shape.FOLDED -> {
                val home = buildHome(columns = 4)
                home.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                root.addView(home)
            }
            Shape.UNFOLDED -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(insets.left, insets.top, insets.right, insets.bottom)
                }
                row.addView(buildHome(columns = 5), LinearLayout.LayoutParams(0, MATCH, 1f))
                row.addView(buildPanel(), LinearLayout.LayoutParams(0, MATCH, 1f))
                root.addView(row)
            }
            Shape.TABLETOP -> {
                root.addView(buildTabletop())
                if (resumed) handler.post(mediaTick)
            }
        }
        refreshCalendar()
        refreshNotifications()
    }

    // ---- home: ask bar, grid, dock -----------------------------------------------------------

    private fun buildHome(columns: Int): View {
        homeColumns = columns
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(askBar(), lp(MATCH, WRAP).margins(16f, 10f, 16f, 6f))
        if (!isDefaultHome()) {
            col.addView(pillButton("Make Duo your home screen") { requestHomeRole() }, lp(WRAP, WRAP).margins(16f, 4f, 16f, 4f))
        }

        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        col.addView(results, lp(MATCH, WRAP))
        resultsBox = results

        val grid = AppGrid(this, columns, 98f)
        homeApps().forEach { grid.addView(icon(it, 60f, true)) }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(grid)
        }
        col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).margins(8f, 8f, 8f, 0f))
        homeScroll = scroll

        col.addView(text("⌃  All apps", 13f, Color.WHITE, onWallpaper = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dpi(6f), 0, dpi(6f))
            setOnClickListener { openDrawer() }
        }, lp(MATCH, WRAP))
        col.addView(buildDock(), lp(MATCH, WRAP).margins(12f, 2f, 12f, 10f))

        return SwipeFrame(
            this, up = true,
            canSwipe = { drawer == null && search?.text.isNullOrEmpty() && !scroll.canScrollVertically(1) },
            onSwipe = { openDrawer() },
        ).apply { addView(col) }
    }

    private fun buildDock(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = dockBg()
            setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f))
        }
        val apps = dockApps()
        if (apps.isEmpty()) {
            row.addView(text("Long-press an app and choose “Add to Dock”", 13f, Color.WHITE, onWallpaper = true)
                .apply { gravity = Gravity.CENTER }, lp(MATCH, dpi(60f)))
        }
        apps.forEach { row.addView(icon(it, 58f, false), LinearLayout.LayoutParams(0, dpi(68f), 1f)) }
        return row
    }

    private fun icon(app: AppInfo, size: Float, label: Boolean, labelColor: Int = Color.WHITE) =
        IconView(this, app, size, label, labelColor) { showAppMenu(it) }

    private fun askBar(): EditText = EditText(this).apply {
        hint = "Search apps or ask Claude"
        setHintTextColor(dimText)
        setTextColor(cardText)
        textSize = 16f
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_GO
        background = pillBg()
        setPadding(dpi(18f), dpi(12f), dpi(18f), dpi(12f))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = showResults(s?.toString().orEmpty())
        })
        setOnEditorActionListener { v, _, _ ->
            submit(v.text.toString())
            true
        }
        search = this
    }

    /** While typing in the ask bar: matching apps, plus "Ask Claude". */
    private fun showResults(q: String) {
        val box = resultsBox ?: return
        box.removeAllViews()
        if (q.isBlank()) {
            box.visibility = View.GONE
            homeScroll?.visibility = View.VISIBLE
            return
        }
        box.visibility = View.VISIBLE
        homeScroll?.visibility = View.GONE
        val matches = AppsRepo.search(q).take(homeColumns * 2)
        if (matches.isNotEmpty()) {
            val grid = AppGrid(this, homeColumns, 98f)
            matches.forEach { grid.addView(icon(it, 60f, true)) }
            box.addView(grid, lp(MATCH, WRAP).margins(8f, 4f, 8f, 4f))
        }
        box.addView(text("✦  Ask Claude: “${q.trim()}”", 16f, cardText, bold = true).apply {
            background = cardBg(22f)
            setPadding(dpi(18f), dpi(14f), dpi(18f), dpi(14f))
            setOnClickListener {
                Claude.ask(this@HomeActivity, q.trim())
                clearSearch()
            }
        }, lp(MATCH, WRAP).margins(16f, 6f, 16f, 6f))
    }

    /** Enter in the ask bar: open the app if the name matches exactly, otherwise ask Claude. */
    private fun submit(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        val exact = AppsRepo.apps.firstOrNull { it.label.equals(t, ignoreCase = true) }
        if (exact != null) AppsRepo.launch(this, exact, null) else Claude.ask(this, t)
        clearSearch()
    }

    private fun clearSearch() {
        search?.let {
            if (it.text.isNotEmpty()) it.setText("")
            it.clearFocus()
        }
        hideKeyboard()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ---- all-apps drawer ---------------------------------------------------------------------

    private fun openDrawer() {
        if (drawer != null) return
        hideKeyboard()
        val cols = if (shape == Shape.FOLDED) 4 else 6
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(if (isDark) 0xF2141416.toInt() else 0xF2F2F2F7.toInt())
            setPadding(insets.left, insets.top + dpi(8f), insets.right, insets.bottom)
        }
        val grid = AppGrid(this, cols, 98f)
        fun fill(q: String) {
            grid.removeAllViews()
            val list = if (q.isBlank()) AppsRepo.apps else AppsRepo.search(q)
            list.forEach { grid.addView(icon(it, 58f, true, cardText)) }
        }
        val field = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(dimText)
            setTextColor(cardText)
            textSize = 16f
            isSingleLine = true
            background = pillBg(if (isDark) 0xFF2C2C2E.toInt() else 0xFFE3E3E8.toInt())
            setPadding(dpi(18f), dpi(12f), dpi(18f), dpi(12f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = fill(s?.toString().orEmpty())
            })
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, _, _ ->
                AppsRepo.search(v.text.toString()).firstOrNull()?.let { AppsRepo.launch(this@HomeActivity, it, null) }
                true
            }
        }
        box.addView(field, lp(MATCH, WRAP).margins(16f, 8f, 16f, 8f))
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(grid)
        }
        box.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        fill("")

        val frame = SwipeFrame(this, up = false, canSwipe = { !scroll.canScrollVertically(-1) }, onSwipe = { closeDrawer() })
        frame.addView(box)
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.translationY = root.height.toFloat()
        frame.animate().translationY(0f).setDuration(260).start()
        drawer = frame
    }

    private fun closeDrawer() {
        val d = drawer ?: return
        drawer = null
        hideKeyboard()
        d.animate().translationY(root.height.toFloat()).setDuration(220).withEndAction { root.removeView(d) }.start()
    }

    // ---- long-press app menu -----------------------------------------------------------------

    private fun showAppMenu(v: IconView) {
        val app = v.app
        val home = keys("home")
        val dock = keys("dock")
        val menu = PopupMenu(this, v)
        val inHome = app.key in home
        val inDock = app.key in dock
        menu.menu.add(0, 1, 0, if (inHome) "Remove from Home" else "Add to Home")
        if (inDock) menu.menu.add(0, 2, 1, "Remove from Dock")
        else if (dock.size < 5) menu.menu.add(0, 2, 1, "Add to Dock")
        menu.menu.add(0, 3, 2, "App info")
        menu.menu.add(0, 4, 3, "Uninstall")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { if (inHome) home.remove(app.key) else home.add(app.key); saveKeys("home", home); rebuild(true) }
                2 -> { if (inDock) dock.remove(app.key) else dock.add(app.key); saveKeys("dock", dock); rebuild(true) }
                3 -> AppsRepo.appInfo(this, app)
                4 -> AppsRepo.uninstall(this, app)
            }
            true
        }
        menu.show()
    }

    // ---- which apps go where -----------------------------------------------------------------

    private fun keys(name: String): MutableList<String> =
        prefs.getString(name, "").orEmpty().split("|").filter { it.isNotEmpty() }.toMutableList()

    private fun saveKeys(name: String, list: List<String>) {
        prefs.edit().putString(name, list.joinToString("|")).apply()
    }

    private fun homeApps() = keys("home").mapNotNull { AppsRepo.find(it) }
    private fun dockApps() = keys("dock").mapNotNull { AppsRepo.find(it) }

    /** First run: a sensible dock and home screen from the apps on a Pixel. */
    private fun ensureDefaults() {
        if (!AppsRepo.loaded) return
        if (!prefs.contains("dock")) {
            saveKeys("dock", DOCK.mapNotNull { AppsRepo.byPackage(it)?.key })
        }
        if (!prefs.contains("home")) {
            val dock = keys("dock").toSet()
            val picks = HOME.mapNotNull { AppsRepo.byPackage(it)?.key }.filter { it !in dock }.toMutableList()
            for (a in AppsRepo.apps) {
                if (picks.size >= 16) break
                if (a.key !in picks && a.key !in dock) picks += a.key
            }
            saveKeys("home", picks)
        }
    }

    // ---- unfolded: the right-hand panel ------------------------------------------------------

    private fun buildPanel(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(8f), dpi(10f), dpi(16f), dpi(16f))
        }
        box.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 64f
            setTextColor(Color.WHITE)
            setShadowLayer(dp(4f), 0f, dp(1f), 0x80000000.toInt())
        }, lp(WRAP, WRAP).margins(8f, 0f, 0f, 0f))
        box.addView(TextClock(this).apply {
            format12Hour = "EEEE, MMMM d"
            format24Hour = "EEEE, MMMM d"
            textSize = 18f
            setTextColor(Color.WHITE)
            setShadowLayer(dp(3f), 0f, dp(1f), 0x80000000.toInt())
        }, lp(WRAP, WRAP).margins(10f, 0f, 0f, 14f))

        box.addView(claudeCard())
        box.addView(card("NEXT UP").also { calendarBox = it })
        box.addView(card("NOTIFICATIONS").also { notifBox = it })
        box.addView(widgetsCard())

        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(box)
        }
    }

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBg()
        setPadding(dpi(18f), dpi(14f), dpi(18f), dpi(16f))
        layoutParams = lp(MATCH, WRAP).margins(0f, 0f, 0f, 12f)
        addView(text(title, 12f, dimText, bold = true), lp(WRAP, WRAP).margins(0f, 0f, 0f, 8f))
    }

    /** Remove everything in a card except its title. */
    private fun LinearLayout.clearBody() {
        while (childCount > 1) removeViewAt(1)
    }

    private fun claudeCard(): View = card("CLAUDE").apply {
        val field = EditText(this@HomeActivity).apply {
            hint = "Message Claude…"
            setHintTextColor(dimText)
            setTextColor(cardText)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = pillBg(if (isDark) 0x33FFFFFF else 0x14000000)
            setPadding(dpi(16f), dpi(11f), dpi(16f), dpi(11f))
            setOnEditorActionListener { v, _, _ ->
                val t = v.text.toString().trim()
                if (t.isNotEmpty()) {
                    Claude.ask(this@HomeActivity, t)
                    v.text = ""
                    hideKeyboard()
                }
                true
            }
        }
        addView(field, lp(MATCH, WRAP))
        val buttons = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pillButton("New chat") { Claude.open(this@HomeActivity) }, lp(WRAP, WRAP).margins(0f, 0f, 8f, 0f))
        buttons.addView(pillButton("Claude Code") { Claude.code(this@HomeActivity) }, lp(WRAP, WRAP))
        addView(buttons, lp(WRAP, WRAP).margins(0f, 10f, 0f, 0f))
    }

    private fun refreshCalendar() {
        val box = calendarBox ?: return
        box.clearBody()
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            box.addView(text("See your next events here.", 15f, cardText))
            box.addView(pillButton("Allow calendar") {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CALENDAR)
            }, lp(WRAP, WRAP).margins(0f, 8f, 0f, 0f))
            return
        }
        val events = upcomingEvents(4)
        if (events.isEmpty()) {
            box.addView(text("Nothing coming up this week", 15f, dimText))
            return
        }
        for (e in events) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dpi(5f), 0, dpi(5f))
                setOnClickListener { openCalendarAt(e.begin) }
            }
            row.addView(text(whenLabel(e), 14f, BLUE, bold = true).apply { minWidth = dpi(110f) })
            row.addView(text(e.title, 15f, cardText), LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(row)
        }
    }

    private class Event(val title: String, val begin: Long, val allDay: Boolean)

    private fun upcomingEvents(max: Int): List<Event> {
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now)
        ContentUris.appendId(builder, now + 7 * DateUtils.DAY_IN_MILLIS)
        val out = ArrayList<Event>()
        try {
            contentResolver.query(
                builder.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    out += Event(c.getString(0) ?: "(No title)", c.getLong(1), c.getInt(2) == 1)
                }
            }
        } catch (e: Exception) {
            // Calendar provider unavailable.
        }
        return out
    }

    private fun whenLabel(e: Event): String {
        val day = when {
            DateUtils.isToday(e.begin) -> "Today"
            DateUtils.isToday(e.begin - DateUtils.DAY_IN_MILLIS) -> "Tomorrow"
            else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(e.begin))
        }
        if (e.allDay) return day
        val time = android.text.format.DateFormat.getTimeFormat(this).format(Date(e.begin))
        return "$day $time"
    }

    private fun openCalendarAt(time: Long) {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, time) }.build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            // No calendar app.
        }
    }

    private fun refreshNotifications() {
        val box = notifBox ?: return
        box.clearBody()
        if (!NotifService.hasAccess(this)) {
            box.addView(text("See your latest notifications here.", 15f, cardText))
            box.addView(pillButton("Allow notification access") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }, lp(WRAP, WRAP).margins(0f, 8f, 0f, 0f))
            return
        }
        val list = NotifService.recent(5)
        if (list.isEmpty()) {
            box.addView(text("You're all caught up", 15f, dimText))
            return
        }
        for (sbn in list) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                ?: AppsRepo.byPackage(sbn.packageName)?.label ?: sbn.packageName
            val body = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpi(6f), 0, dpi(6f))
                setOnClickListener { NotifService.open(this@HomeActivity, sbn) }
            }
            AppsRepo.byPackage(sbn.packageName)?.let { app ->
                row.addView(ImageView(this).apply { setImageBitmap(app.icon) }, lp(dpi(30f), dpi(30f)).margins(0f, 0f, 12f, 0f))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(title, 15f, cardText, bold = true).apply { maxLines = 1 })
            if (body.isNotEmpty()) texts.addView(text(body, 14f, dimText))
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(row)
        }
    }

    // ---- widgets -----------------------------------------------------------------------------

    private fun widgetIds(): List<Int> = keys("widgets").mapNotNull { it.toIntOrNull() }

    private fun widgetsCard(): View = card("WIDGETS").apply {
        for (id in widgetIds()) {
            val info = widgetMgr.getAppWidgetInfo(id) ?: continue
            val frame = FrameLayout(this@HomeActivity)
            val hv = widgetHost.createView(applicationContext, id, info)
            frame.addView(hv, FrameLayout.LayoutParams(MATCH, maxOf(info.minHeight, dpi(110f))))
            frame.addView(text("✕", 14f, cardText, bold = true).apply {
                gravity = Gravity.CENTER
                background = pillBg(if (isDark) 0x99000000.toInt() else 0x99FFFFFF.toInt())
                setOnClickListener { removeWidget(id) }
            }, FrameLayout.LayoutParams(dpi(28f), dpi(28f), Gravity.END or Gravity.TOP))
            addView(frame, lp(MATCH, WRAP).margins(0f, 0f, 0f, 10f))
        }
        addView(pillButton("Add widget") { pickWidget() }, lp(WRAP, WRAP))
    }

    private fun pickWidget() {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidget = id
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                REQ_PICK_WIDGET,
            )
        } catch (e: Exception) {
            widgetHost.deleteAppWidgetId(id)
            Toast.makeText(this, "This phone doesn't offer a widget picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addWidget(id: Int) {
        saveKeys("widgets", (widgetIds() + id).map { it.toString() })
        rebuild(force = true)
    }

    private fun removeWidget(id: Int) {
        widgetHost.deleteAppWidgetId(id)
        saveKeys("widgets", widgetIds().filter { it != id }.map { it.toString() })
        rebuild(force = true)
    }

    @Deprecated("Activity results for the widget picker and home role")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget) ?: pendingWidget
        when (requestCode) {
            REQ_PICK_WIDGET -> {
                if (resultCode != RESULT_OK) {
                    widgetHost.deleteAppWidgetId(id)
                    return
                }
                val info = widgetMgr.getAppWidgetInfo(id)
                if (info?.configure != null) {
                    try {
                        widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG_WIDGET, null)
                    } catch (e: Exception) {
                        addWidget(id)
                    }
                } else {
                    addWidget(id)
                }
            }
            REQ_CONFIG_WIDGET -> if (resultCode == RESULT_OK) addWidget(id) else widgetHost.deleteAppWidgetId(id)
            REQ_HOME_ROLE -> rebuild(force = true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALENDAR) refreshCalendar()
    }

    // ---- tabletop ----------------------------------------------------------------------------

    private fun buildTabletop(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(insets.left, insets.top, insets.right, 0)
        }
        top.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 96f
            setTextColor(Color.WHITE)
            setShadowLayer(dp(4f), 0f, dp(1f), 0x80000000.toInt())
        })
        top.addView(TextClock(this).apply {
            format12Hour = "EEEE, MMMM d"
            format24Hour = "EEEE, MMMM d"
            textSize = 20f
            setTextColor(Color.WHITE)
            setShadowLayer(dp(3f), 0f, dp(1f), 0x80000000.toInt())
        })
        top.addView(text("", 17f, Color.WHITE, onWallpaper = true).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(24f), dpi(14f), dpi(24f), 0)
            mediaTitle = this
        }, lp(MATCH, WRAP))
        col.addView(top, if (hingeTop > 0) lp(MATCH, hingeTop) else LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(insets.left, 0, insets.right, insets.bottom)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun control(glyph: String, action: () -> Unit) = text(glyph, 38f, Color.WHITE, onWallpaper = true).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(22f), dpi(8f), dpi(22f), dpi(8f))
            setOnClickListener {
                action()
                handler.postDelayed({ refreshMedia() }, 300)
            }
        }
        controls.addView(control("⏮") { NotifService.media(this)?.transportControls?.skipToPrevious() })
        controls.addView(control("⏯") {
            val m = NotifService.media(this)
            if (m != null) {
                if (m.playbackState?.state == PlaybackState.STATE_PLAYING) m.transportControls.pause()
                else m.transportControls.play()
            }
        }.also { mediaPlay = it })
        controls.addView(control("⏭") { NotifService.media(this)?.transportControls?.skipToNext() })
        bottom.addView(controls)
        bottom.addView(buildDock(), lp(MATCH, WRAP).margins(16f, 12f, 16f, 12f))
        col.addView(bottom, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun refreshMedia() {
        val title = mediaTitle ?: return
        if (!NotifService.hasAccess(this)) {
            title.text = "Allow notification access in the unfolded panel to see what's playing"
            return
        }
        val m = NotifService.media(this)
        val meta = m?.metadata
        val song = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        title.text = when {
            song == null -> "Nothing playing"
            artist.isNullOrEmpty() -> song
            else -> "$song — $artist"
        }
        mediaPlay?.text = if (m?.playbackState?.state == PlaybackState.STATE_PLAYING) "⏸" else "▶"
    }

    // ---- home role ---------------------------------------------------------------------------

    private fun isDefaultHome(): Boolean =
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    private fun requestHomeRole() {
        val rm = getSystemService(RoleManager::class.java) ?: return
        @Suppress("DEPRECATION")
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE)
    }

    // ---- small layout helpers ----------------------------------------------------------------

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun <T : ViewGroup.MarginLayoutParams> T.margins(l: Float, t: Float, r: Float, b: Float): T = apply {
        setMargins(dpi(l), dpi(t), dpi(r), dpi(b))
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REQ_CALENDAR = 1
        const val REQ_PICK_WIDGET = 2
        const val REQ_CONFIG_WIDGET = 3
        const val REQ_HOME_ROLE = 4

        val DOCK = listOf(
            "com.google.android.dialer", "com.google.android.apps.messaging",
            "com.android.chrome", "com.google.android.GoogleCamera",
        )
        val HOME = listOf(
            "com.anthropic.claude", "com.github.android", "com.google.android.gm",
            "com.google.android.apps.photos", "com.google.android.apps.maps", "com.google.android.youtube",
            "com.google.android.calendar", "com.google.android.deskclock", "com.google.android.apps.youtube.music",
            "com.spotify.music", "com.google.android.keep", "com.google.android.apps.docs",
            "com.android.vending", "com.google.android.apps.weather", "com.google.android.calculator",
            "com.android.settings",
        )
    }
}
