package ua.kadr.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Floating prompter: a small movable window with the script that stays on top of any app,
 * so you can record with TikTok, Instagram or the phone's own camera.
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTE_ID = 7
        const val ACTION_STOP = "ua.kadr.app.STOP_OVERLAY"
        @Volatile var running = false; private set

        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var root: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var scroll: ScrollView
    private lateinit var text: TextView
    private lateinit var playBtn: TextView
    private lateinit var speedLabel: TextView
    private var playing = false
    private var speed = 60f    // dp per second
    private var fontSp = 26f
    private var lastFrame = 0L
    private lateinit var header: View
    private lateinit var body: FrameLayout
    private val alphas = listOf(0x33, 0x66, 0x99, 0xCC)
    private var alphaIdx = 1
    private val hideHeader = Runnable { if (playing) header.animate().alpha(0f).setDuration(300).start() }
    private var carry = 0f

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!playing) return
            if (lastFrame != 0L) {
                carry += speed * resources.displayMetrics.density * (t - lastFrame) / 1_000_000_000f
                val px = carry.toInt()
                if (px > 0) { scroll.scrollBy(0, px); carry -= px }
                if (!scroll.canScrollVertically(1)) setPlaying(false)
            }
            lastFrame = t
            if (playing) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startAsForeground()
        if (root == null) {
            if (!canDraw(this)) { stopSelf(); return START_NOT_STICKY }
            prefs = Prefs(this)
            speed = prefs.speed; fontSp = prefs.overlayFont
            showWindow()
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        playing = false
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Суфлер поверх додатків", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Суфлер працює")
            .setContentText("Текст показується поверх інших додатків")
            .setContentIntent(open)
            .addAction(0, "Закрити суфлер", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTE_ID, note, type)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        minWidth = dp(40f)
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showWindow() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics

        val script = prefs.scripts.firstOrNull { it.id == prefs.currentScriptId } ?: prefs.scripts.first()

        // Header: drag handle and controls.
        playBtn = button("▶") { setPlaying(!playing) }
        speedLabel = TextView(this).apply { setTextColor(0xFF9AA0AE.toInt()); textSize = 12f; setPadding(dp(4f), 0, dp(4f), 0) }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC0B0E14.toInt())
            setPadding(dp(4f), 0, dp(4f), 0)
            addView(TextView(this@OverlayService).apply { text = "⠿"; setTextColor(0xFF9AA0AE.toInt()); textSize = 18f; setPadding(dp(6f), 0, dp(6f), 0) })
            addView(playBtn)
            addView(button("↺") { scroll.smoothScrollTo(0, 0); carry = 0f })
            addView(button("−") { changeSpeed(-10f) })
            addView(speedLabel)
            addView(button("+") { changeSpeed(10f) })
            addView(button("A−") { changeFont(-2f) })
            addView(button("A+") { changeFont(2f) })
            addView(button("◐") { alphaIdx = (alphaIdx + 1) % alphas.size; prefs.overlayAlpha = alphaIdx; applyAlpha() })
            addView(View(this@OverlayService), LinearLayout.LayoutParams(0, 1, 1f))
            addView(button("✕") { stopSelf() })
        }

        text = TextView(this).apply {
            this.text = script.text
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.3f)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Tap on the text toggles play/pause; drags still scroll it by hand.
        var downY = 0f; var downT = 0L
        scroll.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; downT = System.currentTimeMillis() }
                MotionEvent.ACTION_UP -> if (kotlin.math.abs(e.rawY - downY) < dp(8f) && System.currentTimeMillis() - downT < 300) {
                    // A tap brings the controls back; a second tap toggles play.
                    if (header.alpha < 0.5f) showHeader() else setPlaying(!playing)
                }
            }
            false
        }

        // Reading mark on the left, a third of the way down.
        val mark = View(this).apply { setBackgroundColor(0xFFFFD43B.toInt()) }
        body = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(mark, FrameLayout.LayoutParams(dp(5f), dp(30f)).apply { gravity = Gravity.START or Gravity.TOP })
        }

        // Resize handle in the bottom-right corner.
        val resize = TextView(this).apply {
            text = "◢"; setTextColor(0xFFFFD43B.toInt()); textSize = 20f; gravity = Gravity.CENTER
        }
        body.addView(resize, FrameLayout.LayoutParams(dp(36f), dp(36f)).apply { gravity = Gravity.END or Gravity.BOTTOM })

        // Close button that never hides, top-right corner of the text.
        val close = TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xB3000000.toInt()) }
            setOnClickListener { stopSelf() }
        }
        body.addView(close, FrameLayout.LayoutParams(dp(36f), dp(36f)).apply {
            gravity = Gravity.END or Gravity.TOP; topMargin = dp(6f); rightMargin = dp(6f)
        })

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.TRANSPARENT) }
            clipToOutline = true
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        params = WindowManager.LayoutParams(
            prefs.overlayW.takeIf { it > 0 } ?: (metrics.widthPixels * 0.94f).toInt(),
            prefs.overlayH.takeIf { it > 0 } ?: (metrics.heightPixels * 0.32f).toInt(),
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.overlayX.takeIf { it >= 0 } ?: (metrics.widthPixels * 0.03f).toInt()
            y = prefs.overlayY.takeIf { it >= 0 } ?: dp(60f)
        }

        // Drag the window by its header.
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (sx + (e.rawX - tx)).toInt(); params.y = (sy + (e.rawY - ty)).toInt()
                    wm.updateViewLayout(container, params); true
                }
                MotionEvent.ACTION_UP -> { savePlace(); false }
                else -> false
            }
        }
        // Resize from the corner.
        var sw = 0; var sh = 0
        resize.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sw = params.width; sh = params.height; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (sw + (e.rawX - tx)).toInt().coerceIn(dp(220f), metrics.widthPixels)
                    params.height = (sh + (e.rawY - ty)).toInt().coerceIn(dp(140f), metrics.heightPixels)
                    wm.updateViewLayout(container, params); true
                }
                MotionEvent.ACTION_UP -> { savePlace(); true }
                else -> false
            }
        }

        root = container
        wm.addView(container, params)
        alphaIdx = prefs.overlayAlpha.coerceIn(0, alphas.size - 1)
        applyFont(); updateSpeedLabel(); applyAlpha()
        container.post { padForReading(body.height) }
        body.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) padForReading(bottom - top)
        }
    }

    /** Text starts at the reading mark and can scroll until its last line reaches it. */
    private fun padForReading(h: Int) {
        val markTop = (h * 0.3f).toInt()
        text.setPadding(dp(16f), markTop, dp(16f), h)
        (root as? ViewGroup)?.let { r ->
            val body = r.getChildAt(1) as FrameLayout
            val mark = body.getChildAt(1)
            (mark.layoutParams as FrameLayout.LayoutParams).topMargin = markTop
            mark.requestLayout()
        }
    }

    private fun savePlace() {
        prefs.overlayX = params.x; prefs.overlayY = params.y
        prefs.overlayW = params.width; prefs.overlayH = params.height
    }

    private fun applyAlpha() { body.setBackgroundColor(alphas[alphaIdx] shl 24) }

    /** Controls fade out 3 s after the text starts moving and come back on a tap. */
    private fun showHeader() {
        header.removeCallbacks(hideHeader)
        header.animate().alpha(1f).setDuration(150).start()
        header.postDelayed(hideHeader, 3000)
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        playBtn.text = if (p) "❚❚" else "▶"
        showHeader()
        if (p) { lastFrame = 0L; Choreographer.getInstance().postFrameCallback(frame) }
    }

    private fun changeSpeed(d: Float) {
        speed = (speed + d).coerceIn(10f, 250f); prefs.speed = speed; updateSpeedLabel()
    }

    private fun updateSpeedLabel() { speedLabel.text = speed.toInt().toString() }

    private fun changeFont(d: Float) {
        fontSp = (fontSp + d).coerceIn(14f, 56f); prefs.overlayFont = fontSp; applyFont()
    }

    private fun applyFont() { text.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp) }
}
