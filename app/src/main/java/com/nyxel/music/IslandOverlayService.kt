package com.nyxel.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class IslandOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var islandView: LinearLayout? = null
    private var detailsPanel: LinearLayout? = null
    private var titleView: TextView? = null
    private var stateView: TextView? = null
    private var playView: TextView? = null
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var expanded = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlaybackState()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updatePlaybackState()
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = updatePlaybackState()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createIslandView()
        connectPlayer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Nyxel Dynamic Island", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Nyxel Dynamic Island")
            .setContentText("Yüzen ada etkin · Kapatmak için Nyxel'i aç")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            runCatching { controllerFuture?.get() }.getOrNull()?.let {
                controller = it
                it.addListener(playerListener)
                updatePlaybackState()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createIslandView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = rounded(Color.rgb(5, 4, 10), dp(28), Color.argb(190, 139, 92, 246))
            elevation = dp(14).toFloat()
        }
        val capsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(Color.BLACK, dp(24), Color.argb(90, 244, 114, 182))
        }
        val orb = TextView(this).apply {
            text = "✦"
            textSize = 19f
            setTextColor(Color.rgb(244, 114, 182))
            gravity = Gravity.CENTER
            setPadding(dp(3), 0, dp(8), 0)
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            text = "Nyxel"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        stateView = TextView(this).apply {
            text = "Hazır"
            textSize = 9f
            setTextColor(Color.LTGRAY)
            maxLines = 1
        }
        labels.addView(titleView)
        labels.addView(stateView)
        val pulse = TextView(this).apply {
            text = "●"
            textSize = 10f
            setTextColor(Color.rgb(167, 139, 250))
            setPadding(dp(12), 0, dp(3), 0)
        }
        capsule.addView(orb)
        capsule.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        capsule.addView(pulse)
        root.addView(capsule, LinearLayout.LayoutParams(dp(174), dp(38)))

        detailsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(12), 0, dp(2))
        }
        detailsPanel?.addView(View(this).apply {
            setBackgroundColor(Color.argb(55, 255, 255, 255))
        }, LinearLayout.LayoutParams(dp(220), dp(1)))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        val previous = actionButton("‹") { controller?.seekToPreviousMediaItem() }
        playView = actionButton("▶") {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
            updatePlaybackState()
        }
        val next = actionButton("›") { controller?.seekToNextMediaItem() }
        val open = actionButton("Aç") {
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null) startActivity(launch)
        }
        val close = actionButton("×") {\n            getSharedPreferences("nyxel_settings", MODE_PRIVATE).edit().putBoolean("island_enabled", false).apply()\n            stopSelf()\n        }
        listOf(previous, playView!!, next, open, close).forEach { actionRow.addView(it) }
        detailsPanel?.addView(actionRow)
        root.addView(detailsPanel)

        capsule.setOnClickListener {
            expanded = !expanded
            detailsPanel?.visibility = if (expanded) View.VISIBLE else View.GONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
        }
        islandView = root
        runCatching { windowManager.addView(root, params) }.onFailure { stopSelf() }
    }

    private fun actionButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(45, 255, 255, 255), dp(15), Color.TRANSPARENT)
            setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(dp(38), dp(36))
            lp.setMargins(dp(3), 0, dp(3), 0)
            layoutParams = lp
        }

    private fun updatePlaybackState() {
        val player = controller ?: return
        val title = player.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Nyxel"
        titleView?.text = title
        stateView?.text = if (player.isPlaying) "Şimdi çalıyor" else "Duraklatıldı"
        playView?.text = if (player.isPlaying) "Ⅱ" else "▶"
    }

    private fun rounded(color: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        islandView?.let { view -> runCatching { windowManager.removeView(view) } }
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        islandView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "nyxel_island"
        private const val NOTIFICATION_ID = 2401
    }
}
