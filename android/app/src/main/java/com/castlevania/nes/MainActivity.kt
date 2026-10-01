package com.castlevania.nes

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.util.Log

class MainActivity : AppCompatActivity() {

    private lateinit var glView: NesGLSurfaceView
    private lateinit var controllerView: VirtualControllerView
    private lateinit var settingsView: SettingsOverlayView
    private lateinit var editorBar: ControllerEditorBar
    private lateinit var achievementPopup: AchievementPopupView
    private val audioPlayer = NesAudioPlayer()

    private var physicalGamepadMask = 0
    private var isMenuOpen = false
    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during gameplay
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 1. Prepare HD Pack assets in internal storage
        val hdDir = HdPackManager.setupHdPack(this)

        // 2. Initialize Mesen NES emulator with embedded Castlevania ROM
        NativeBridge.nativeInit(filesDir.absolutePath, hdDir.absolutePath)

        // 3. Initialize RetroAchievements manager & sessions
        RetroAchievementsManager.init(this)

        // 4. Setup views
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        glView = NesGLSurfaceView(this)
        rootLayout.addView(
            glView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        controllerView = VirtualControllerView(this)
        rootLayout.addView(
            controllerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Settings Overlay Menu
        settingsView = SettingsOverlayView(this).apply {
            visibility = View.GONE
        }
        rootLayout.addView(
            settingsView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Controller Editor Bar (top floating toolbar)
        editorBar = ControllerEditorBar(this).apply {
            visibility = View.GONE
        }
        rootLayout.addView(
            editorBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        // Achievement Popup Banner (top floating card)
        achievementPopup = AchievementPopupView(this)
        rootLayout.addView(
            achievementPopup,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = (16 * resources.displayMetrics.density).toInt()
            }
        )

        RetroAchievementsManager.onAchievementUnlockedUiListener = { title, desc, points, _ ->
            achievementPopup.show(title, desc, points)
        }

        setContentView(rootLayout)
        hideSystemUI()

        // 6. Wire up controller Select button to open Settings Menu
        controllerView.onMenuClickListener = {
            if (!isEditMode) {
                openSettingsMenu()
            }
        }

        // 7. Connect Settings View & Editor callbacks
        setupSettingsCallbacks()
        setupEditorCallbacks()

        // 7. Load and apply saved preferences
        applyInitialPreferences()

        // 8. Start audio output
        audioPlayer.start()
    }

    private fun setupSettingsCallbacks() {
        settingsView.onResumeGameListener = {
            resumeGame()
        }

        settingsView.onStateSavedListener = { slot ->
            NativeBridge.nativeSaveState(slot)
        }

        settingsView.onStateLoadedListener = { slot ->
            NativeBridge.nativeLoadState(slot)
        }

        settingsView.onResetGameListener = {
            NativeBridge.nativeReset()
        }

        settingsView.onHdPackChangedListener = { enabled ->
            NativeBridge.nativeSetHdPack(enabled)
        }

        settingsView.onSpriteLimitChangedListener = { enabled ->
            NativeBridge.nativeSetNoSpriteLimit(enabled)
        }

        settingsView.onVolumeChangedListener = { vol ->
            NativeBridge.nativeSetMasterVolume(vol)
        }

        settingsView.onHdBgmVolumeChangedListener = { vol ->
            NativeBridge.nativeSetHdBgmVolume(vol)
        }

        settingsView.onAudioMuteChangedListener = { muted ->
            if (muted) {
                audioPlayer.stop()
            } else {
                audioPlayer.start()
            }
        }

        settingsView.onFilterChangedListener = { smooth ->
            glView.setSmoothFilter(smooth)
        }

        settingsView.onFilterModeChangedListener = { mode ->
            glView.setFilterMode(mode)
        }

        settingsView.onStretchChangedListener = { stretch ->
            glView.setStretchToScreen(stretch)
        }

        settingsView.onRefreshRateChangedListener = { targetHz ->
            applyDisplayRefreshRate(targetHz)
        }

        settingsView.onControllerOpacityChangedListener = { opacity ->
            controllerView.controllerOpacity = opacity
        }

        settingsView.onVibrationChangedListener = { enabled ->
            controllerView.isVibrationEnabled = enabled
        }

        settingsView.onEditLayoutClickListener = {
            startControllerEditMode()
        }

        settingsView.onResetLayoutClickListener = {
            controllerView.resetLayout()
        }

        // Gameplay Options & Cheats
        settingsView.onInfiniteHealthChangedListener = { enabled ->
            NativeBridge.nativeSetInfiniteHealth(enabled)
        }

        settingsView.onInfiniteHeartsChangedListener = { enabled ->
            NativeBridge.nativeSetInfiniteHearts(enabled)
        }

        settingsView.onOneHitBossChangedListener = { enabled ->
            NativeBridge.nativeSetOneHitBoss(enabled)
        }

        settingsView.onDifficultyModeChangedListener = { mode ->
            NativeBridge.nativeSetDifficultyMode(mode)
        }

        settingsView.onDoubleJumpChangedListener = { enabled ->
            NativeBridge.nativeSetDoubleJump(enabled)
        }

        settingsView.onLatchStairsChangedListener = { enabled ->
            NativeBridge.nativeSetLatchStairs(enabled)
        }

        settingsView.onInfiniteLivesChangedListener = { enabled ->
            NativeBridge.nativeSetInfiniteLives(enabled)
        }

        settingsView.onMaxWhipChangedListener = { enabled ->
            NativeBridge.nativeSetMaxWhip(enabled)
        }

        settingsView.onTripleShotChangedListener = { enabled ->
            NativeBridge.nativeSetTripleShot(enabled)
        }

        settingsView.onSmartEnemyAiChangedListener = { enabled ->
            NativeBridge.nativeSetSmartEnemyAi(enabled)
        }

        settingsView.onSmartAiAggressionChangedListener = { level ->
            NativeBridge.nativeSetSmartAiAggression(level)
        }

        settingsView.onCrossHeartRecoveryChangedListener = { enabled ->
            NativeBridge.nativeSetCrossHeartRecovery(enabled)
        }
    }

    private fun setupEditorCallbacks() {
        editorBar.onSizeDecreaseListener = {
            controllerView.changeSelectedControlScale(-0.05f)
        }

        editorBar.onSizeIncreaseListener = {
            controllerView.changeSelectedControlScale(+0.05f)
        }

        editorBar.onToggleGridListener = {
            controllerView.isGridSnapEnabled = !controllerView.isGridSnapEnabled
            editorBar.updateGridStatus(controllerView.isGridSnapEnabled)
            controllerView.invalidate()
        }

        editorBar.onResetDefaultsListener = {
            controllerView.resetLayout()
            updateEditorBarStatus()
        }

        editorBar.onSaveListener = {
            finishControllerEditMode(save = true)
        }

        editorBar.onCancelListener = {
            finishControllerEditMode(save = false)
        }

        controllerView.onSelectedControlChanged = { _, _ ->
            updateEditorBarStatus()
        }
    }

    private fun startControllerEditMode() {
        if (isEditMode) return
        isEditMode = true
        isMenuOpen = false

        // Pause emulation and audio while editing layout
        NativeBridge.nativePause()
        audioPlayer.stop()

        settingsView.visibility = View.GONE
        controllerView.startEditMode()
        editorBar.visibility = View.VISIBLE
        updateEditorBarStatus()
    }

    private fun finishControllerEditMode(save: Boolean) {
        if (!isEditMode) return
        isEditMode = false

        if (save) {
            controllerView.saveLayout()
        } else {
            controllerView.discardChanges()
        }
        controllerView.exitEditMode()
        editorBar.visibility = View.GONE

        hideSystemUI()

        // Resume audio and emulation
        val prefs = getSharedPreferences("castlevania_settings", Context.MODE_PRIVATE)
        val isAudioEnabled = prefs.getBoolean("opt_audio_enabled", true)
        if (isAudioEnabled) {
            audioPlayer.start()
        }
        NativeBridge.nativeResume()
    }

    private fun updateEditorBarStatus() {
        val name = when (controllerView.selectedControl) {
            EditableControl.ANALOG -> "🕹️ Analógico"
            EditableControl.BTN_B -> "🗡️ Botão B (Ataque)"
            EditableControl.BTN_A -> "👢 Botão A (Pulo)"
            EditableControl.BTN_ITEM -> "⭐ Botão Item (Sub-arma)"
            EditableControl.MENU_START -> "⚙️ Select / Start"
            EditableControl.NONE -> "Toque em um botão"
        }
        val scale = when (controllerView.selectedControl) {
            EditableControl.ANALOG -> controllerView.analogScale
            EditableControl.BTN_B -> controllerView.btnBScale
            EditableControl.BTN_A -> controllerView.btnAScale
            EditableControl.BTN_ITEM -> controllerView.btnItemScale
            EditableControl.MENU_START -> controllerView.selStartScale
            EditableControl.NONE -> 1.0f
        }
        editorBar.updateSelectedControl(name, (scale * 100).toInt(), controllerView.isGridSnapEnabled)
    }

    private fun applyInitialPreferences() {
        val prefs = getSharedPreferences("castlevania_settings", Context.MODE_PRIVATE)

        // HD Pack
        val isHd = prefs.getBoolean("opt_hd_pack", true)
        NativeBridge.nativeSetHdPack(isHd)

        // Sprite limit
        val noLimit = prefs.getBoolean("opt_no_sprite_limit", false)
        NativeBridge.nativeSetNoSpriteLimit(noLimit)

        // Volume
        val vol = prefs.getInt("opt_volume", 100)
        NativeBridge.nativeSetMasterVolume(vol)

        // HD Mod Music Volume
        val hdBgmVol = prefs.getInt("opt_hd_bgm_volume", 65)
        NativeBridge.nativeSetHdBgmVolume(hdBgmVol)

        // Filter & Stretch
        val filterMode = prefs.getInt("opt_video_filter", NesRenderer.FILTER_MODE_FIDELITYFX)
        glView.setFilterMode(filterMode)
        glView.setStretchToScreen(prefs.getBoolean("opt_stretch", false))

        // Screen Refresh Rate (Default 120Hz)
        val targetHz = prefs.getFloat("opt_refresh_rate", 120.0f)
        applyDisplayRefreshRate(targetHz)

        // Controller Opacity & Vibration
        val opacity = prefs.getInt("opt_opacity", VirtualControllerView.DEFAULT_OPACITY_PERCENT) / 100.0f
        controllerView.controllerOpacity = opacity
        controllerView.isVibrationEnabled = prefs.getBoolean("opt_vibration", true)

        // Gameplay Options & Cheats
        val infHealth = prefs.getBoolean("opt_infinite_health", false)
        NativeBridge.nativeSetInfiniteHealth(infHealth)

        val infHearts = prefs.getBoolean("opt_infinite_hearts", false)
        NativeBridge.nativeSetInfiniteHearts(infHearts)

        val oneHitBoss = prefs.getBoolean("opt_one_hit_boss", false)
        NativeBridge.nativeSetOneHitBoss(oneHitBoss)

        val diffMode = prefs.getInt("opt_difficulty_mode", 0)
        NativeBridge.nativeSetDifficultyMode(diffMode)

        val doubleJump = prefs.getBoolean("opt_double_jump", true)
        NativeBridge.nativeSetDoubleJump(doubleJump)

        val latchStairs = prefs.getBoolean("opt_latch_stairs", true)
        NativeBridge.nativeSetLatchStairs(latchStairs)

        val infLives = prefs.getBoolean("opt_infinite_lives", false)
        NativeBridge.nativeSetInfiniteLives(infLives)

        val maxWhip = prefs.getBoolean("opt_max_whip", false)
        NativeBridge.nativeSetMaxWhip(maxWhip)

        val tripleShot = prefs.getBoolean("opt_triple_shot", false)
        NativeBridge.nativeSetTripleShot(tripleShot)

        val smartEnemyAi = prefs.getBoolean("opt_smart_enemy_ai", false)
        NativeBridge.nativeSetSmartEnemyAi(smartEnemyAi)

        val smartAiAggression = prefs.getInt("opt_smart_ai_aggression", 1)
        NativeBridge.nativeSetSmartAiAggression(smartAiAggression)

        val crossHeartRecovery = prefs.getBoolean("opt_cross_heart_recovery", true)
        NativeBridge.nativeSetCrossHeartRecovery(crossHeartRecovery)
    }

    fun applyDisplayRefreshRate(targetHz: Float = 120.0f) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val currentDisplay = display
                if (currentDisplay != null) {
                    val modes = currentDisplay.supportedModes
                    val bestMode = if (targetHz >= 110.0f) {
                        modes.filter { it.refreshRate >= 119.0f }.maxByOrNull { it.refreshRate }
                            ?: modes.filter { it.refreshRate >= 89.0f }.maxByOrNull { it.refreshRate }
                            ?: modes.maxByOrNull { it.refreshRate }
                    } else {
                        modes.filter { it.refreshRate in 59.0f..61.0f }.firstOrNull()
                            ?: modes.minByOrNull { it.refreshRate }
                    }

                    val params = window.attributes
                    if (bestMode != null) {
                        params.preferredDisplayModeId = bestMode.modeId
                    }
                    params.preferredRefreshRate = targetHz
                    window.attributes = params

                    Log.i("MainActivity", "120Hz display mode configured: modeId=${bestMode?.modeId}, rate=${bestMode?.refreshRate}Hz, preferred=${targetHz}Hz")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                val currentDisplay = windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                val modes = currentDisplay.supportedModes
                val bestMode = if (targetHz >= 110.0f) {
                    modes.filter { it.refreshRate >= 119.0f }.maxByOrNull { it.refreshRate }
                        ?: modes.filter { it.refreshRate >= 89.0f }.maxByOrNull { it.refreshRate }
                        ?: modes.maxByOrNull { it.refreshRate }
                } else {
                    modes.filter { it.refreshRate in 59.0f..61.0f }.firstOrNull()
                        ?: modes.minByOrNull { it.refreshRate }
                }

                val params = window.attributes
                if (bestMode != null) {
                    params.preferredDisplayModeId = bestMode.modeId
                }
                params.preferredRefreshRate = targetHz
                window.attributes = params
                Log.i("MainActivity", "Display mode configured (API 23+): modeId=${bestMode?.modeId}, rate=${bestMode?.refreshRate}Hz")
            }

            glView.setTargetRefreshRate(targetHz)
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not set display refresh rate: ${e.message}")
        }
    }

    fun getActiveRefreshRate(): Float {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.refreshRate ?: 120.0f
            } else {
                @Suppress("DEPRECATION")
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.refreshRate
            }
        } catch (e: Exception) {
            120.0f
        }
    }

    private fun openSettingsMenu() {
        if (isMenuOpen) return
        isMenuOpen = true

        // Pause emulation and audio while menu is open
        NativeBridge.nativePause()
        audioPlayer.stop()

        settingsView.updateActiveDisplayStats(getActiveRefreshRate(), glView.renderer.measuredFps)
        settingsView.showMenu()
    }

    private fun resumeGame() {
        if (!isMenuOpen) return
        isMenuOpen = false

        hideSystemUI()

        // Resume audio and emulation
        val prefs = getSharedPreferences("castlevania_settings", Context.MODE_PRIVATE)
        val isAudioEnabled = prefs.getBoolean("opt_audio_enabled", true)
        if (isAudioEnabled) {
            audioPlayer.start()
        }
        NativeBridge.nativeResume()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()

        val prefs = getSharedPreferences("castlevania_settings", Context.MODE_PRIVATE)
        val targetHz = prefs.getFloat("opt_refresh_rate", 120.0f)
        applyDisplayRefreshRate(targetHz)

        glView.onResume()
        if (!isMenuOpen && !isEditMode) {
            audioPlayer.start()
            NativeBridge.nativeResume()
        }
    }

    override fun onPause() {
        super.onPause()
        NativeBridge.nativePause()
        audioPlayer.stop()
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.stop()
        NativeBridge.nativeDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (ignored: Exception) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    // Physical Gamepad Support (Bluetooth / USB controllers)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN

        // If Settings Menu is open, handle Back key to close it
        if (isMenuOpen && isDown && event.keyCode == KeyEvent.KEYCODE_BACK) {
            settingsView.hideMenu()
            return true
        }

        // Physical Gamepad SELECT button opens Settings Menu
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT && isDown) {
            if (isMenuOpen) {
                settingsView.hideMenu()
            } else {
                openSettingsMenu()
            }
            return true
        }

        var bit = 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> bit = NativeBridge.BTN_A
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X -> bit = NativeBridge.BTN_B
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L1 -> bit = NativeBridge.BTN_ITEM
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> bit = NativeBridge.BTN_START
            KeyEvent.KEYCODE_DPAD_UP -> bit = NativeBridge.BTN_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> bit = NativeBridge.BTN_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> bit = NativeBridge.BTN_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> bit = NativeBridge.BTN_RIGHT
        }

        if (bit != 0) {
            if (isDown) {
                physicalGamepadMask = physicalGamepadMask or bit
            } else {
                physicalGamepadMask = physicalGamepadMask and bit.inv()
            }
            NativeBridge.nativeSetInput(physicalGamepadMask)
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isMenuOpen) return true

        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val axisX = event.getAxisValue(MotionEvent.AXIS_X).takeIf { it != 0f }
                ?: event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val axisY = event.getAxisValue(MotionEvent.AXIS_Y).takeIf { it != 0f }
                ?: event.getAxisValue(MotionEvent.AXIS_HAT_Y)

            var stickMask = 0
            val threshold = 0.5f

            if (axisX < -threshold) stickMask = stickMask or NativeBridge.BTN_LEFT
            if (axisX > threshold) stickMask = stickMask or NativeBridge.BTN_RIGHT
            if (axisY < -threshold) stickMask = stickMask or NativeBridge.BTN_UP
            if (axisY > threshold) stickMask = stickMask or NativeBridge.BTN_DOWN

            val stickClear = (NativeBridge.BTN_LEFT or NativeBridge.BTN_RIGHT or NativeBridge.BTN_UP or NativeBridge.BTN_DOWN).inv()
            physicalGamepadMask = (physicalGamepadMask and stickClear) or stickMask
            NativeBridge.nativeSetInput(physicalGamepadMask)
            return true
        }

        return super.onGenericMotionEvent(event)
    }
}
