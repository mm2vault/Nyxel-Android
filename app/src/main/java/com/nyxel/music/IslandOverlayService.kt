package com.nyxel.music

import android.app.*
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject

/** Floating Nyxel island. The supplied HTML remains the source of truth for its appearance. */
class IslandOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: WebView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pageReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val progressTicker = object : Runnable {
        override fun run() {
            updatePlaybackState()
            if (controller?.isPlaying == true) mainHandler.postDelayed(this, 1000L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlaybackState()
            mainHandler.removeCallbacks(progressTicker)
            if (isPlaying) mainHandler.post(progressTicker)
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updatePlaybackState()
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = updatePlaybackState()
        override fun onPlaybackStateChanged(playbackState: Int) = updatePlaybackState()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
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
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Nyxel")
            .setContentText("Nyxel yüzen kapsülü etkin")
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            runCatching { controllerFuture?.get() }.getOrNull()?.let {
                controller = it
                it.addListener(playerListener)
                updatePlaybackState()
                if (it.isPlaying) mainHandler.post(progressTicker)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createIslandView() {
        val web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            addJavascriptInterface(OverlayBridge(), "NyxelOverlay")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageReady = true
                    resizeOverlay(false)
                    updatePlaybackState()
                }
            }
        }
        val params = WindowManager.LayoutParams(
            dp(130), dp(38),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
            alpha = 1f
        }
        overlay = web
        layoutParams = params
        runCatching {
            windowManager.addView(web, params)
            web.loadUrl("file:///android_asset/overlay.html")
        }.onFailure { stopSelf() }
    }

    private fun resizeOverlay(open: Boolean) {
        val web = overlay ?: return
        val params = layoutParams ?: return
        params.width = dp(if (open) 360 else 130)
        params.height = dp(if (open) 270 else 38)
        runCatching { windowManager.updateViewLayout(web, params) }
    }

    private fun updatePlaybackState() {
        val p = controller
        val metadata = p?.mediaMetadata
        val duration = p?.duration?.takeIf { it > 0 } ?: 0L
        val position = p?.currentPosition?.coerceAtLeast(0) ?: 0L
        val playing = p?.isPlaying == true
        val payload = JSONObject()
            .put("title", metadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Nyxel")
            .put("artist", metadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Müzik hazır")
            .put("playing", playing)
            .put("status", if (playing) "Şimdi çalıyor" else "Seni dinliyorum...")
            .put("position", formatTime(position))
            .put("duration", formatTime(duration))
            .put("progress", if (duration > 0) ((position * 100) / duration).toInt().coerceIn(0, 100) else 0)
        if (pageReady) overlay?.evaluateJavascript("window.nyxelNativeRender&&window.nyxelNativeRender($payload)", null)
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000).toInt()
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun runOnMain(action: () -> Unit) {
        ContextCompat.getMainExecutor(this).execute(action)
    }

    private inner class OverlayBridge {
        @JavascriptInterface fun setExpanded(open: Boolean) { runOnMain { resizeOverlay(open) } }
        @JavascriptInterface fun togglePlayback() {
            runOnMain {
                controller?.let { if (it.isPlaying) it.pause() else it.play() }
                updatePlaybackState()
            }
        }
        @JavascriptInterface fun previous() { runOnMain { controller?.seekToPreviousMediaItem(); updatePlaybackState() } }
        @JavascriptInterface fun next() { runOnMain { controller?.seekToNextMediaItem(); updatePlaybackState() } }
        @JavascriptInterface fun seekTo(ratio: Float) {
            runOnMain {
                controller?.let { player ->
                    val duration = player.duration
                    if (duration > 0) player.seekTo((duration * ratio.coerceIn(0f, 1f)).toLong())
                    updatePlaybackState()
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressTicker)
        overlay?.let { runCatching { windowManager.removeView(it) }; it.destroy() }
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        overlay = null
        pageReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "nyxel_island"
        private const val NOTIFICATION_ID = 2401
    }
}
