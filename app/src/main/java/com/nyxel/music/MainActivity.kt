package com.nyxel.music

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.nyxel.music.ai.AiMessage
import com.nyxel.music.ai.AiCall
import com.nyxel.music.ai.AiSecretStore
import com.nyxel.music.ai.OpenRouterProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingTrack: Triple<Uri, String, String>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updateExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var updateDownloadUrl: String? = null
    @Volatile private var pendingUpdateFile: File? = null
    private var scanPending = false
    private val aiSecretStore by lazy { AiSecretStore(this) }
    @Volatile private var aiProvider: OpenRouterProvider? = null
    @Volatile private var aiModelId: String = "openrouter/free"
    @Volatile private var activeAiCall: AiCall? = null

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
        pendingUpdateFile?.let { file ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                pendingUpdateFile = null
                installUpdateFile(file)
            }
        }
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

    private fun sendUpdateScript(script: String) {
        if (::webView.isInitialized) webView.evaluateJavascript(script, null)
    }

    private fun checkForUpdates() {
        updateExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Nyxel-Android-Updater")
                }
                if (connection.responseCode !in 200..299) error("GitHub yanıtı: HTTP ${connection.responseCode}")
                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val body = release.optString("body")
                val versionCodeMatch = Regex("NYXEL_VERSION_CODE=(\\d+)").find(body)
                    ?: error("Sürüm bilgisi Release notlarında bulunamadı.")
                val remoteCode = versionCodeMatch.groupValues[1].toInt()
                val assets = release.optJSONArray("assets") ?: JSONArray()
                var apkAsset: JSONObject? = null
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    if (asset.optString("name") == "Nyxel.apk") {
                        apkAsset = asset
                        break
                    }
                }
                val asset = apkAsset ?: error("Nyxel.apk dosyası bu Release içinde bulunamadı.")
                updateDownloadUrl = asset.optString("browser_download_url")
                val current = packageManager.getPackageInfo(packageName, 0)
                val currentCode = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode.toInt() else current.versionCode
                val remoteName = release.optString("tag_name").removePrefix("v")
                val info = JSONObject()
                    .put("available", remoteCode > currentCode)
                    .put("versionName", remoteName)
                    .put("versionCode", remoteCode)
                    .put("currentVersion", current.versionName ?: "2.0.0")
                    .put("size", asset.optLong("size"))
                    .put("notes", body)
                runOnUiThread {
                    sendUpdateScript("window.onUpdateCheck && window.onUpdateCheck(${JSONObject.quote(info.toString())})")
                }
            } catch (error: Exception) {
                val message = error.message ?: "Güncelleme kontrol edilemedi."
                runOnUiThread {
                    sendUpdateScript("window.onUpdateError && window.onUpdateError(${JSONObject.quote(message)})")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun downloadUpdate() {
        val url = updateDownloadUrl
        if (url.isNullOrBlank()) {
            checkForUpdates()
            return
        }
        updateExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Nyxel-Android-Updater")
                }
                if (connection.responseCode !in 200..299) error("APK indirilemedi: HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong.coerceAtLeast(0L)
                val directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "updates")
                if (!directory.exists()) directory.mkdirs()
                val apkFile = File(directory, "Nyxel-update.apk")
                var downloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1
                            runOnUiThread {
                                sendUpdateScript("window.onUpdateProgress && window.onUpdateProgress($percent,$downloaded,$total)")
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (!apkFile.exists() || apkFile.length() < 1024L) error("İndirilen APK dosyası geçersiz görünüyor.")
                val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                    ?: error("İndirilen dosya geçerli bir APK değil.")
                if (archiveInfo.packageName != packageName) error("APK paket kimliği Nyxel ile eşleşmiyor.")
                pendingUpdateFile = apkFile
                runOnUiThread {
                    sendUpdateScript("window.onUpdateDownloaded && window.onUpdateDownloaded()")
                    installUpdateFile(apkFile)
                }
            } catch (error: Exception) {
                val message = error.message ?: "Güncelleme indirilemedi."
                runOnUiThread {
                    sendUpdateScript("window.onUpdateError && window.onUpdateError(${JSONObject.quote(message)})")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun installUpdateFile(file: File) {
        if (!file.exists()) {
            pendingUpdateFile = null
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingUpdateFile = file
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
            pendingUpdateFile = null
        } catch (error: Exception) {
            val message = error.message ?: "Android kurulum ekranı açılamadı."
            sendUpdateScript("window.onUpdateError && window.onUpdateError(${JSONObject.quote(message)})")
        }
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
        @JavascriptInterface fun checkForUpdates() { this@MainActivity.checkForUpdates() }
        @JavascriptInterface fun downloadUpdate() { this@MainActivity.downloadUpdate() }
        @JavascriptInterface fun hasAiKey(): Boolean = aiSecretStore.readApiKey()?.isNotBlank() == true
        @JavascriptInterface fun saveAiKey(value: String): String = try {
            aiSecretStore.saveApiKey(value)
            "OK"
        } catch (error: Exception) {
            error.message ?: "API anahtarı kaydedilemedi."
        }
        @JavascriptInterface fun removeAiKey() {
            aiSecretStore.clearApiKey()
            activeAiCall?.cancel()
        }
        @JavascriptInterface fun sendAiMessage(historyJson: String, requestedModel: String) {
            val model = requestedModel.trim().ifBlank { "openrouter/free" }
            if (!model.matches(Regex("[A-Za-z0-9._:/-]{3,120}"))) {
                sendAiResult(null, "Model kimliği geçersiz.")
                return
            }
            val messages = try {
                val array = JSONArray(historyJson)
                val parsed = mutableListOf<AiMessage>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    val content = item.optString("content").trim()
                    if (role in setOf("user", "assistant") && content.isNotBlank()) {
                        parsed.add(AiMessage(role, content.take(8000)))
                    }
                }
                parsed.takeLast(20)
            } catch (_: Exception) {
                sendAiResult(null, "Sohbet geçmişi okunamadı.")
                return
            }
            if (messages.isEmpty()) {
                sendAiResult(null, "Önce bir mesaj yaz.")
                return
            }
            val provider = synchronized(this@MainActivity) {
                if (aiProvider == null || aiModelId != model) {
                    aiProvider?.shutdown()
                    aiModelId = model
                    aiProvider = OpenRouterProvider(this@MainActivity, model)
                }
                aiProvider!!
            }
            val system = AiMessage(
                "system",
                "Sen Nyxel'in Türkçe konuşan, samimi, güvenli ve pratik Android asistanısın. Kullanıcının diline uyum sağla. Bilmediğin şeyi uydurma. Cihaz ayarlarını, dosyaları veya başka uygulamaları doğrudan değiştirdiğini iddia etme; şu an yalnızca sohbet edebildiğini açıkça belirt. Zararlı veya izinsiz işlemlere yardımcı olma. Yanıtları anlaşılır ve gereksiz uzatmadan ver."
            )
            activeAiCall?.cancel()
            activeAiCall = provider.send(listOf(system) + messages) { reply, error ->
                if (reply != null) sendAiResult(reply.text, null)
                else sendAiResult(null, error ?: "AI yanıt veremedi.")
            }
        }
    }

    private fun sendAiResult(answer: String?, error: String?) {
        if (!::webView.isInitialized) return
        val payload = JSONObject().put("answer", answer ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)
        webView.evaluateJavascript("window.onNyxelAiReply && window.onNyxelAiReply(${JSONObject.quote(payload.toString())})", null)
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
        updateExecutor.shutdownNow()
        activeAiCall?.cancel()
        aiProvider?.shutdown()
        aiProvider = null
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
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/mm2vault/Nyxel-Android/releases/latest"
    }
}
