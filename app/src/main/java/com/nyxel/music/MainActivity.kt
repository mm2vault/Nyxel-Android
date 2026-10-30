package com.nyxel.music

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingTrack: Triple<Uri, String, String>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false

    private val islandPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startIslandService()
        else {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ISLAND_ENABLED, false).apply()
            sendIslandState(false)
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && scanPending) scanDeviceLibrary() else sendTracks(JSONArray())
        scanPending = false
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val player = mediaController
            if (player != null && ::webView.isInitialized) {
                val state = JSONObject()
                    .put("position", player.currentPosition.coerceAtLeast(0))
                    .put("duration", player.duration.coerceAtLeast(0))
                    .put("isPlaying", player.isPlaying)
                    .put("ended", player.playbackState == Player.STATE_ENDED)
                val payload = JSONObject.quote(state.toString())
                webView.evaluateJavascript("window.onNativePlayback && window.onNativePlayback($payload)", null)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 6, 18)
        window.navigationBarColor = android.graphics.Color.rgb(11, 6, 18)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(11, 6, 18))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(NyxelBridge(), "NyxelNative")
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
        connectMediaController()
        handler.post(progressRunnable)
        if (islandEnabled() && Settings.canDrawOverlays(this)) startIslandService()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) sendIslandState(islandEnabled() && Settings.canDrawOverlays(this))
    }

    private fun connectMediaController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            runCatching { controllerFuture?.get() }.getOrNull()?.let { controller ->
                mediaController = controller
                pendingTrack?.let { playTrack(it.first, it.second, it.third) }
                pendingTrack = null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun playTrack(uri: Uri, title: String, artist: String) {
        val controller = mediaController
        if (controller == null) {
            pendingTrack = Triple(uri, title, artist)
            return
        }
        val metadata = MediaMetadata.Builder().setTitle(title).setArtist(artist).build()
        controller.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build())
        controller.prepare()
        controller.play()
    }

    private fun islandEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ISLAND_ENABLED, false)

    private fun requestIsland() {
        if (Settings.canDrawOverlays(this)) startIslandService()
        else islandPermissionLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startIslandService() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ISLAND_ENABLED, true).apply()
        ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java))
        sendIslandState(true)
    }

    private fun stopIslandService() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ISLAND_ENABLED, false).apply()
        stopService(Intent(this, IslandOverlayService::class.java))
        sendIslandState(false)
    }

    private fun sendIslandState(enabled: Boolean) {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("window.onIslandState && window.onIslandState($enabled)", null)
    }

    inner class NyxelBridge {
        @JavascriptInterface fun playLocal(uriText: String, title: String, artist: String) {
            runOnUiThread {
                val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return@runOnUiThread
                playTrack(uri, title, artist)
            }
        }
        @JavascriptInterface fun pause() { runOnUiThread { mediaController?.pause() } }
        @JavascriptInterface fun scanDeviceMusic() {
            runOnUiThread {
                val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                    else Manifest.permission.READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED) {
                    scanDeviceLibrary()
                } else {
                    scanPending = true
                    permissionLauncher.launch(permission)
                }
            }
        }
        @JavascriptInterface fun enableIsland() { runOnUiThread { requestIsland() } }
        @JavascriptInterface fun disableIsland() { runOnUiThread { stopIslandService() } }
        @JavascriptInterface fun isIslandEnabled(): Boolean =
            islandEnabled() && Settings.canDrawOverlays(this@MainActivity)
    }

    private fun scanDeviceLibrary() {
        val tracks = JSONArray()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val sortOrder = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
        val cursor: Cursor? = contentResolver.query(collection, projection, selection, null, sortOrder)
        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                tracks.put(JSONObject()
                    .put("uri", Uri.withAppendedPath(collection, id.toString()).toString())
                    .put("name", it.getString(titleColumn) ?: "Bilinmeyen parça")
                    .put("artist", it.getString(artistColumn) ?: "Bilinmeyen sanatçı"))
            }
        }
        sendTracks(tracks)
    }

    private fun sendTracks(tracks: JSONArray) {
        if (!::webView.isInitialized) return
        val payload = JSONObject.quote(tracks.toString())
        webView.evaluateJavascript("window.onNativeTracks && window.onNativeTracks($payload)", null)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressRunnable)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("NyxelNative")
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "nyxel_settings"
        private const val KEY_ISLAND_ENABLED = "island_enabled"
    }
}
