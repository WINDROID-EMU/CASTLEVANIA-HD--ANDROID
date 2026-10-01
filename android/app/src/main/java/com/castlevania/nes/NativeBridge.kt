package com.castlevania.nes

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object NativeBridge {
    init {
        System.loadLibrary("castlevania")
    }

    // Button bits
    const val BTN_A: Int = 1 shl 0
    const val BTN_B: Int = 1 shl 1
    const val BTN_SELECT: Int = 1 shl 2
    const val BTN_MENU: Int = BTN_SELECT
    const val BTN_START: Int = 1 shl 3
    const val BTN_UP: Int = 1 shl 4
    const val BTN_DOWN: Int = 1 shl 5
    const val BTN_LEFT: Int = 1 shl 6
    const val BTN_RIGHT: Int = 1 shl 7
    const val BTN_ITEM: Int = 1 shl 8

    // Thread pool for background HTTP network requests
    private val httpExecutor = Executors.newCachedThreadPool()

    // Callbacks for RetroAchievements
    var onAchievementUnlockedListener: ((id: Int, title: String, description: String, points: Int, badge: String) -> Unit)? = null
    var onLoginResultListener: ((success: Boolean, errorMessage: String, token: String) -> Unit)? = null
    var onGameLoadedListener: ((success: Boolean, errorMessage: String) -> Unit)? = null

    /**
     * Called from C++ rc_client_server_call_t to execute HTTP requests to RetroAchievements.org
     */
    @JvmStatic
    fun onServerCall(urlStr: String, postData: String?, contentType: String?, callbackPtr: Long, callbackDataPtr: Long) {
        httpExecutor.execute {
            var conn: HttpURLConnection? = null
            var responseCode = 0
            val responseBody = StringBuilder()
            var errorException: Exception? = null

            try {
                Log.i("NativeBridge", "HTTP Request starting -> URL: $urlStr | POST data length: ${postData?.length ?: 0}")
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "RetroAchievements-Mesen-Android/1.0")
                    setRequestProperty("Accept", "application/json, text/html, */*")
                    if (postData != null) {
                        requestMethod = "POST"
                        doOutput = true
                        val postBytes = postData.toByteArray(Charsets.UTF_8)
                        setFixedLengthStreamingMode(postBytes.size)
                        setRequestProperty("Content-Type", contentType ?: "application/x-www-form-urlencoded")
                        outputStream.use { os ->
                            os.write(postBytes)
                            os.flush()
                        }
                    } else {
                        requestMethod = "GET"
                    }
                }

                responseCode = conn.responseCode
                val rawStream = try {
                    if (responseCode in 200..299) conn.inputStream else conn.errorStream
                } catch (e: Exception) {
                    conn.errorStream
                }

                if (rawStream != null) {
                    val encoding = conn.contentEncoding ?: ""
                    val buffered = java.io.BufferedInputStream(rawStream)
                    buffered.mark(4)
                    val b1 = buffered.read()
                    val b2 = buffered.read()
                    buffered.reset()

                    val isGzip = encoding.contains("gzip", ignoreCase = true) || (b1 == 0x1f && b2 == 0x8b)
                    val decodedStream = if (isGzip) {
                        java.util.zip.GZIPInputStream(buffered)
                    } else {
                        buffered
                    }

                    decodedStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            responseBody.append(line).append("\n")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("NativeBridge", "HTTP Exception for $urlStr: ${e.javaClass.simpleName} - ${e.message}")
                errorException = e
            } finally {
                conn?.disconnect()
            }

            val finalBody = responseBody.toString().trim()
            Log.i("NativeBridge", "HTTP Response for $urlStr -> code: $responseCode, length: ${finalBody.length}, snippet: ${finalBody.take(120).replace("\n", " ")}")

            if (errorException != null || (responseCode == 0 && finalBody.isEmpty())) {
                val errMsg = if (errorException != null) {
                    "Erro de conexão (${errorException.javaClass.simpleName}): ${errorException.message}"
                } else {
                    "Servidor não respondeu (timeout ou sem internet)"
                }
                // Pass -1 (RC_API_SERVER_RESPONSE_CLIENT_ERROR) so rcheevos processes body as error_message
                nativeProcessServerResponse(callbackPtr, callbackDataPtr, -1, errMsg)
            } else {
                nativeProcessServerResponse(callbackPtr, callbackDataPtr, responseCode, finalBody)
            }
        }
    }

    @JvmStatic
    fun onAchievementUnlocked(id: Int, title: String, description: String, points: Int, badge: String) {
        onAchievementUnlockedListener?.invoke(id, title, description, points, badge)
    }

    @JvmStatic
    fun onLoginResult(success: Boolean, errorMessage: String, token: String) {
        onLoginResultListener?.invoke(success, errorMessage, token)
    }

    @JvmStatic
    fun onGameLoaded(success: Boolean, errorMessage: String) {
        onGameLoadedListener?.invoke(success, errorMessage)
    }

    external fun nativeInit(homeDir: String, hdPackDir: String): Boolean
    external fun nativePause()
    external fun nativeResume()
    external fun nativeSetInput(buttons: Int)
    external fun nativeGetCurrentSubweapon(): Int
    external fun nativeRender(textureId: Int)
    external fun nativeGetFrameWidth(): Int
    external fun nativeGetFrameHeight(): Int
    external fun nativeGetAudioSamples(outBuffer: ShortArray): Int
    external fun nativeSaveState(slot: Int): Boolean
    external fun nativeLoadState(slot: Int): Boolean
    external fun nativeReset()
    external fun nativeSetHdPack(enable: Boolean)
    external fun nativeGetHdPack(): Boolean
    external fun nativeSetNoSpriteLimit(enable: Boolean)
    external fun nativeGetNoSpriteLimit(): Boolean
    external fun nativeSetMasterVolume(volume: Int)
    external fun nativeGetMasterVolume(): Int
    external fun nativeSetHdBgmVolume(volume: Int)
    external fun nativeGetHdBgmVolume(): Int
    external fun nativeDestroy()

    // RetroAchievements Native API
    external fun nativeGetRomHash(): String
    external fun nativeRaLoginWithPassword(user: String, pass: String)
    external fun nativeRaLoginWithToken(user: String, token: String)
    external fun nativeRaLogout()
    external fun nativeRaIsLoggedIn(): Boolean
    external fun nativeRaGetGameTitle(): String
    external fun nativeRaLoadGame()
    external fun nativeRaSetHardcoreEnabled(enable: Boolean)
    external fun nativeRaGetHardcoreEnabled(): Boolean
    external fun nativeRaGetUserInfoJson(): String
    external fun nativeRaGetAchievementsJson(): String
    external fun nativeProcessServerResponse(callbackPtr: Long, callbackDataPtr: Long, httpStatus: Int, body: String)

    // Gameplay Features & Cheats
    external fun nativeSetInfiniteHealth(enable: Boolean)
    external fun nativeSetInfiniteHearts(enable: Boolean)
    external fun nativeSetOneHitBoss(enable: Boolean)
    external fun nativeSetDifficultyMode(mode: Int)
    external fun nativeSetDoubleJump(enable: Boolean)
    external fun nativeSetInfiniteLives(enable: Boolean)
    external fun nativeSetMaxWhip(enable: Boolean)
    external fun nativeSetTripleShot(enable: Boolean)
}
