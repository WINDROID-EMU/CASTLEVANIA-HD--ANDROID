package com.castlevania.nes

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class AchievementItem(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val unlocked: Boolean,
    val badge: String
)

data class RaUserSummary(
    val username: String,
    val displayName: String,
    val score: Int,
    val scoreSoftcore: Int
)

object RetroAchievementsManager {

    private const val PREFS_NAME = "retroachievements_prefs"
    private const val KEY_USERNAME = "ra_username"
    private const val KEY_TOKEN = "ra_token"
    private const val KEY_HARDCORE = "ra_hardcore"
    private const val KEY_LOGGED_IN = "ra_logged_in"

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    var onAchievementUnlockedUiListener: ((title: String, description: String, points: Int, badge: String) -> Unit)? = null
    var onLoginUiListener: ((success: Boolean, message: String) -> Unit)? = null
    var onGameLoadedUiListener: ((success: Boolean, message: String) -> Unit)? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Wire native callbacks
        NativeBridge.onAchievementUnlockedListener = { id, title, description, points, badge ->
            Log.i("RAManager", "Achievement unlocked: $title ($points pts, badge: $badge)")
            mainHandler.post {
                onAchievementUnlockedUiListener?.invoke(title, description, points, badge)
            }
        }

        NativeBridge.onLoginResultListener = { success, errorMessage, token ->
            Log.i("RAManager", "Login result: success=$success, error=$errorMessage, tokenLen=${token.length}")
            if (success) {
                prefs.edit().apply {
                    if (token.isNotEmpty()) putString(KEY_TOKEN, token)
                    putBoolean(KEY_LOGGED_IN, true)
                    apply()
                }
                mainHandler.post {
                    loadGame(force = true)
                    onLoginUiListener?.invoke(true, errorMessage)
                }
            } else {
                prefs.edit().putBoolean(KEY_LOGGED_IN, false).apply()
                mainHandler.post {
                    onLoginUiListener?.invoke(false, errorMessage)
                }
            }
        }

        NativeBridge.onGameLoadedListener = { success, errorMessage ->
            Log.i("RAManager", "Game loaded in RetroAchievements: success=$success, error=$errorMessage")
            isLoadingGame = false
            mainHandler.post {
                onGameLoadedUiListener?.invoke(success, errorMessage)
            }
        }

        // Apply saved hardcore mode preference
        val isHardcore = prefs.getBoolean(KEY_HARDCORE, false)
        NativeBridge.nativeRaSetHardcoreEnabled(isHardcore)

        // If previously logged in, re-authenticate seamlessly with token
        val savedUser = prefs.getString(KEY_USERNAME, "") ?: ""
        val savedToken = prefs.getString(KEY_TOKEN, "") ?: ""
        val wasLoggedIn = prefs.getBoolean(KEY_LOGGED_IN, false)

        if (wasLoggedIn && savedUser.isNotEmpty() && savedToken.isNotEmpty()) {
            Log.i("RAManager", "Restoring previous RetroAchievements session for $savedUser...")
            NativeBridge.nativeRaLoginWithToken(savedUser, savedToken)
        }
    }

    private var isLoadingGame = false

    fun loadGame(force: Boolean = false) {
        if (isLoadingGame && !force) {
            Log.i("RAManager", "Game load already in progress, skipping")
            return
        }
        if (!isSessionActive()) {
            Log.w("RAManager", "Cannot load game: session not active yet")
            return
        }
        isLoadingGame = true
        NativeBridge.nativeRaLoadGame()
    }

    fun isGameLoading(): Boolean = isLoadingGame

    fun getGameTitle(): String = NativeBridge.nativeRaGetGameTitle()

    fun login(username: String, pass: String) {
        prefs.edit().putString(KEY_USERNAME, username).apply()
        NativeBridge.nativeRaLoginWithPassword(username, pass)
    }

    fun logout() {
        prefs.edit().apply {
            remove(KEY_TOKEN)
            putBoolean(KEY_LOGGED_IN, false)
            apply()
        }
        NativeBridge.nativeRaLogout()
    }

    fun isSessionActive(): Boolean {
        return NativeBridge.nativeRaIsLoggedIn()
    }

    fun isLoggedIn(): Boolean {
        return isSessionActive() || (if (::prefs.isInitialized) prefs.getBoolean(KEY_LOGGED_IN, false) else false)
    }

    fun getUsername(): String {
        return if (::prefs.isInitialized) prefs.getString(KEY_USERNAME, "") ?: "" else ""
    }

    fun setHardcore(enabled: Boolean) {
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_HARDCORE, enabled).apply()
        }
        NativeBridge.nativeRaSetHardcoreEnabled(enabled)
    }

    fun isHardcore(): Boolean {
        return NativeBridge.nativeRaGetHardcoreEnabled()
    }

    fun getUserSummary(): RaUserSummary? {
        return try {
            val jsonStr = NativeBridge.nativeRaGetUserInfoJson()
            if (jsonStr.isEmpty() || jsonStr == "{}") return null
            val obj = JSONObject(jsonStr)
            RaUserSummary(
                username = obj.optString("username", ""),
                displayName = obj.optString("display_name", ""),
                score = obj.optInt("score", 0),
                scoreSoftcore = obj.optInt("score_softcore", 0)
            )
        } catch (e: Exception) {
            Log.e("RAManager", "Error parsing user info JSON: ${e.message}")
            null
        }
    }

    fun getAchievements(): List<AchievementItem> {
        val list = mutableListOf<AchievementItem>()
        try {
            val jsonStr = NativeBridge.nativeRaGetAchievementsJson()
            if (jsonStr.isEmpty() || jsonStr == "[]") return list
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    AchievementItem(
                        id = obj.optInt("id", 0),
                        title = obj.optString("title", ""),
                        description = obj.optString("description", ""),
                        points = obj.optInt("points", 0),
                        unlocked = obj.optBoolean("unlocked", false),
                        badge = obj.optString("badge", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("RAManager", "Error parsing achievements JSON: ${e.message}")
        }
        return list
    }
}
