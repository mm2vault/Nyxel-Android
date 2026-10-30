package com.nyxel.music.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Minimal chat message shared by cloud and future on-device providers. */
data class AiMessage(
    val role: String,
    val content: String
) {
    init {
        require(role in setOf("system", "user", "assistant")) { "Unsupported AI message role." }
    }
}

data class AiReply(
    val text: String,
    val model: String,
    val provider: String
)

fun interface AiCallback {
    fun onComplete(reply: AiReply?, error: String?)
}

class AiCall internal constructor(private val cancelAction: () -> Unit) {
    fun cancel() = cancelAction()
}

/**
 * Provider contract: UI and assistant logic must not depend on a specific model vendor.
 * Local LiteRT-LM and additional cloud providers can implement this same contract later.
 */
interface AiProvider {
    val id: String
    fun send(messages: List<AiMessage>, callback: AiCallback): AiCall
}

/**
 * Encrypts the user's own provider key with an AES key held by Android Keystore.
 * No API key is hard-coded, committed to GitHub, or written to logs.
 */
class AiSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun saveApiKey(value: String) {
        val key = value.trim()
        require(key.isNotEmpty()) { "API anahtarı boş olamaz." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        check(preferences.edit().putString(KEY_API, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "API anahtarı güvenli depolamaya yazılamadı."
        }
    }

    @Synchronized
    fun readApiKey(): String? {
        val packedText = preferences.getString(KEY_API, null) ?: return null
        return try {
            val packed = Base64.decode(packedText, Base64.NO_WRAP)
            require(packed.size > IV_LENGTH) { "Stored API key is invalid." }
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val encrypted = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clearApiKey() {
        preferences.edit().remove(KEY_API).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "nyxel_ai_secure"
        const val KEY_API = "openrouter_api_key"
        const val KEY_ALIAS = "nyxel_ai_api_key_aes"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}

/**
 * OpenRouter-compatible chat provider. Use modelId="openrouter/free" for its
 * free-model router, or pass a specific model ID selected by the user.
 *
 * This client is intentionally keyless until the user adds their own key in Nyxel.
 */
class OpenRouterProvider(
    context: Context,
    private val modelId: String = "openrouter/free"
) : AiProvider {
    override val id: String = "openrouter"
    private val appContext = context.applicationContext
    private val secretStore = AiSecretStore(appContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun saveApiKey(apiKey: String) = secretStore.saveApiKey(apiKey)
    fun hasApiKey(): Boolean = !secretStore.readApiKey().isNullOrBlank()
    fun removeApiKey() = secretStore.clearApiKey()

    override fun send(messages: List<AiMessage>, callback: AiCallback): AiCall {
        val cancelled = AtomicBoolean(false)
        val connectionRef = AtomicReference<HttpURLConnection?>(null)
        val futureRef = AtomicReference<Future<*>?>(null)

        val future = executor.submit {
            try {
                val apiKey = secretStore.readApiKey()
                    ?: throw IllegalStateException("Önce Nyxel AI ayarlarından OpenRouter API anahtarını eklemelisin.")
                require(messages.isNotEmpty()) { "Mesaj geçmişi boş." }

                val messageArray = JSONArray()
                messages.forEach { message ->
                    messageArray.put(
                        JSONObject()
                            .put("role", message.role)
                            .put("content", message.content)
                    )
                }
                val body = JSONObject()
                    .put("model", modelId)
                    .put("messages", messageArray)
                    .put("temperature", 0.7)
                    .put("max_tokens", 1200)

                val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("HTTP-Referer", "https://github.com/mm2vault/Nyxel-Android")
                    setRequestProperty("X-Title", "Nyxel Android")
                }
                connectionRef.set(connection)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val message = runCatching {
                        JSONObject(responseText).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    throw IllegalStateException(message.ifBlank { "AI sağlayıcısı HTTP $status yanıtı verdi." })
                }

                val root = JSONObject(responseText)
                val choices = root.optJSONArray("choices")
                    ?: throw IllegalStateException("AI yanıtında choices alanı bulunamadı.")
                val choice = choices.optJSONObject(0)
                    ?: throw IllegalStateException("AI boş yanıt döndürdü.")
                val answer = choice.optJSONObject("message")?.optString("content").orEmpty().trim()
                if (answer.isBlank()) throw IllegalStateException("AI boş bir mesaj döndürdü.")

                val reply = AiReply(
                    text = answer,
                    model = root.optString("model", modelId),
                    provider = id
                )
                if (!cancelled.get()) mainHandler.post { if (!cancelled.get()) callback.onComplete(reply, null) }
            } catch (error: Exception) {
                val message = error.message ?: "AI isteği tamamlanamadı."
                if (!cancelled.get()) mainHandler.post { if (!cancelled.get()) callback.onComplete(null, message) }
            } finally {
                connectionRef.getAndSet(null)?.disconnect()
            }
        }
        futureRef.set(future)

        return AiCall {
            cancelled.set(true)
            connectionRef.getAndSet(null)?.disconnect()
            futureRef.getAndSet(null)?.cancel(true)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private companion object {
        const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
    }
}
