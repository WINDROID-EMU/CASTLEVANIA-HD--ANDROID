package com.castlevania.nes

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

class SettingsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val prefs: SharedPreferences = context.getSharedPreferences("castlevania_settings", Context.MODE_PRIVATE)

    var onResumeGameListener: (() -> Unit)? = null
    var onStateSavedListener: ((slot: Int) -> Boolean)? = null
    var onStateLoadedListener: ((slot: Int) -> Boolean)? = null
    var onResetGameListener: (() -> Unit)? = null
    var onHdPackChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onSpriteLimitChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onVolumeChangedListener: ((volume: Int) -> Unit)? = null
    var onHdBgmVolumeChangedListener: ((volume: Int) -> Unit)? = null
    var onAchievementVolumeChangedListener: ((volume: Int) -> Unit)? = null
    var onAudioMuteChangedListener: ((muted: Boolean) -> Unit)? = null
    var onFilterChangedListener: ((smooth: Boolean) -> Unit)? = null
    var onFilterModeChangedListener: ((mode: Int) -> Unit)? = null
    var onStretchChangedListener: ((stretch: Boolean) -> Unit)? = null
    var onRefreshRateChangedListener: ((targetHz: Float) -> Unit)? = null
    var onControllerOpacityChangedListener: ((opacity: Float) -> Unit)? = null
    var onVibrationChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onEditLayoutClickListener: (() -> Unit)? = null
    var onResetLayoutClickListener: (() -> Unit)? = null

    // Gameplay Cheats & Features Listeners
    var onInfiniteHealthChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onInfiniteHeartsChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onOneHitBossChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onDifficultyModeChangedListener: ((mode: Int) -> Unit)? = null
    var onDoubleJumpChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onLatchStairsChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onInfiniteLivesChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onMaxWhipChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onTripleShotChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onSmartEnemyAiChangedListener: ((enabled: Boolean) -> Unit)? = null
    var onSmartAiAggressionChangedListener: ((level: Int) -> Unit)? = null
    var onCrossHeartRecoveryChangedListener: ((enabled: Boolean) -> Unit)? = null

    private var refreshRateBadge: TextView? = null
    private var detectedDisplayHzText: TextView? = null
    private var videoFpsBadge: TextView? = null

    private var soundPreviewPlayer: MediaPlayer? = null

    private var currentTab = 0
    private var selectedSlot = 1

    private val tabButtons = mutableListOf<Button>()
    private val tabContents = mutableListOf<View>()
    private var refreshAchievementsAction: (() -> Unit)? = null

    private val statusMessageView: TextView
    private val mainContainer: LinearLayout

    init {
        // Dark translucent background with click blocker
        setBackgroundColor(Color.parseColor("#E608080C"))
        isClickable = true
        isFocusable = true

        val displayMetrics = resources.displayMetrics
        val dp1 = displayMetrics.density

        // Outer wrapper to center content
        val outerLayout = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isClickable = true
        }
        addView(outerLayout)

        // Main Dialog Card
        mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val maxWidthPx = (700 * dp1).toInt()
            val maxHeightPx = (displayMetrics.heightPixels * 0.90f).toInt()
            layoutParams = LayoutParams(
                maxWidthPx.coerceAtMost((displayMetrics.widthPixels * 0.94f).toInt()),
                maxHeightPx
            ).apply {
                gravity = Gravity.CENTER
            }

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#15161E"))
                setStroke((2 * dp1).toInt(), Color.parseColor("#8B0000")) // Crimson border
                cornerRadius = 18 * dp1
            }
            setPadding((16 * dp1).toInt(), (14 * dp1).toInt(), (16 * dp1).toInt(), (12 * dp1).toInt())
        }
        outerLayout.addView(mainContainer)

        // --- 1. HEADER ---
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp1).toInt()
            }
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val txtTitle = TextView(context).apply {
            text = "🦇 CONFIGURAÇÕES"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        titleCol.addView(txtTitle)

        val txtSub = TextView(context).apply {
            text = "Castlevania NES • HD"
            setTextColor(Color.parseColor("#9E9EAF"))
            textSize = 11f
        }
        titleCol.addView(txtSub)
        headerLayout.addView(titleCol)

        // Close Button (X)
        val btnClose = Button(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            val btnSize = (32 * dp1).toInt()
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#262838"))
                cornerRadius = 16 * dp1
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { hideMenu() }
        }
        headerLayout.addView(btnClose)
        mainContainer.addView(headerLayout)

        // --- 2. TAB BAR ---
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (38 * dp1).toInt()
            ).apply {
                bottomMargin = (10 * dp1).toInt()
            }
        }

        val tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0C0D13"))
                cornerRadius = 8 * dp1
            }
            setPadding((3 * dp1).toInt(), (3 * dp1).toInt(), (3 * dp1).toInt(), (3 * dp1).toInt())
        }
        tabScroll.addView(tabBar)

        val tabTitles = listOf("🕹️ Gameplayer", "💾 Estados", "🖥️ Vídeo", "🎧 Áudio", "🎮 Controles", "🏆 Conquistas")
        for (i in tabTitles.indices) {
            val btnTab = Button(context).apply {
                text = tabTitles[i]
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setPadding((12 * dp1).toInt(), 0, (12 * dp1).toInt(), 0)
                setOnClickListener { selectTab(i) }
            }
            tabButtons.add(btnTab)
            tabBar.addView(btnTab)
        }
        mainContainer.addView(tabScroll)

        // Status banner
        statusMessageView = TextView(context).apply {
            text = ""
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#4CAF50"))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * dp1).toInt()
            }
        }
        mainContainer.addView(statusMessageView)

        // --- 3. TAB CONTENTS CONTAINER ---
        val contentContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }
        mainContainer.addView(contentContainer)

        // Create 6 tab views
        val tabGameplayer = createGameplayerTab(dp1)
        val tabSave = createSaveLoadTab(dp1)
        val tabVideo = createVideoTab(dp1)
        val tabAudio = createAudioTab(dp1)
        val tabControls = createControlsTab(dp1)
        val tabAchievements = createAchievementsTab(dp1)

        tabContents.addAll(listOf(tabGameplayer, tabSave, tabVideo, tabAudio, tabControls, tabAchievements))
        for (t in tabContents) {
            contentContainer.addView(t)
        }

        // Initially select tab 0 (Gameplayer)
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        currentTab = index
        val dp1 = resources.displayMetrics.density

        for (i in tabButtons.indices) {
            val isSelected = (i == index)
            val btn = tabButtons[i]
            btn.setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#888899"))
            btn.background = GradientDrawable().apply {
                setColor(if (isSelected) Color.parseColor("#2C1014") else Color.TRANSPARENT)
                cornerRadius = 6 * dp1
                if (isSelected) {
                    setStroke((1 * dp1).toInt(), Color.parseColor("#B22222"))
                }
            }
        }

        for (i in tabContents.indices) {
            val isCurrent = (i == index)
            tabContents[i].visibility = if (isCurrent) View.VISIBLE else View.GONE
            if (isCurrent) {
                (tabContents[i] as? ScrollView)?.scrollTo(0, 0)
            }
        }

        if (index == 5) {
            refreshAchievementsAction?.invoke()
        }
    }

    private fun showStatus(msg: String, isError: Boolean = false) {
        statusMessageView.text = msg
        statusMessageView.setTextColor(if (isError) Color.parseColor("#FF5252") else Color.parseColor("#69F0AE"))
        statusMessageView.visibility = View.VISIBLE
        statusMessageView.removeCallbacks(hideStatusRunnable)
        statusMessageView.postDelayed(hideStatusRunnable, 3000)
    }

    private val hideStatusRunnable = Runnable {
        statusMessageView.visibility = View.GONE
    }

    // =========================================================================
    // TAB 0: GAMEPLAYER (TRAPAÇAS, HABILIDADES & DIFICULDADE)
    // =========================================================================
    private fun createGameplayerTab(dp1: Float): View {
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding((4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt())
        }
        scroll.addView(layout)

        // Hardcore warning notice if active
        if (RetroAchievementsManager.isHardcore()) {
            val hcWarning = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * dp1).toInt()
                }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2A1215"))
                    cornerRadius = 8 * dp1
                    setStroke((1 * dp1).toInt(), Color.parseColor("#E53935"))
                }
                setPadding((12 * dp1).toInt(), (10 * dp1).toInt(), (12 * dp1).toInt(), (10 * dp1).toInt())
            }

            val iconWarn = TextView(context).apply {
                text = "⚠️"
                textSize = 16f
                setPadding(0, 0, (8 * dp1).toInt(), 0)
            }
            hcWarning.addView(iconWarn)

            val txtWarn = TextView(context).apply {
                text = "Modo Hardcore do RetroAchievements ativo: Funções de assistência e trapaças ficam pausadas para proteger seu ranking oficial. Para ativá-las livremente, alterne para o Modo Softcore na aba Conquistas."
                setTextColor(Color.parseColor("#FFCDD2"))
                textSize = 10.5f
            }
            hcWarning.addView(txtWarn)
            layout.addView(hcWarning)
        }

        // --- SEÇÃO 1: FACILIDADES & TRAPAÇAS ---
        val secCheatsHeader = TextView(context).apply {
            text = "🛡️ FACILIDADES & TRAPAÇAS CLÁSSICAS"
            setTextColor(Color.parseColor("#E5C158"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (6 * dp1).toInt())
        }
        layout.addView(secCheatsHeader)

        // 1. Vida Infinita (God Mode)
        val isInfHealth = prefs.getBoolean("opt_infinite_health", false)
        val swHealth = createSwitchRow(
            dp1,
            "❤️ Vida Infinita (God Mode)",
            "Mantém a vida de Simon no máximo (64 HP). Imune a danos de monstros e golpes",
            isInfHealth
        ) { checked ->
            prefs.edit().putBoolean("opt_infinite_health", checked).apply()
            onInfiniteHealthChangedListener?.invoke(checked)
            showStatus(if (checked) "Vida Infinita ATIVADA!" else "Vida Infinita desativada")
        }
        layout.addView(swHealth)

        // 2. Corações Infinitos (99 Corações)
        val isInfHearts = prefs.getBoolean("opt_infinite_hearts", false)
        val swHearts = createSwitchRow(
            dp1,
            "💎 Corações Infinitos (Munição Ilimitada)",
            "Trava o contador em 99 corações. Use sub-armas à vontade sem esgotar",
            isInfHearts
        ) { checked ->
            prefs.edit().putBoolean("opt_infinite_hearts", checked).apply()
            onInfiniteHeartsChangedListener?.invoke(checked)
            showStatus(if (checked) "Corações Infinitos ATIVADOS (99)!" else "Corações Infinitos desativados")
        }
        layout.addView(swHearts)

        // 3. Matar o Boss com só 1 Golpe
        val isOneHitBoss = prefs.getBoolean("opt_one_hit_boss", false)
        val swBoss = createSwitchRow(
            dp1,
            "⚡ Matar Chefão com 1 Golpe (One-Hit Boss)",
            "O primeiro golpe acertado em qualquer boss elimina sua barra de vida na hora",
            isOneHitBoss
        ) { checked ->
            prefs.edit().putBoolean("opt_one_hit_boss", checked).apply()
            onOneHitBossChangedListener?.invoke(checked)
            showStatus(if (checked) "Matar Boss com 1 Golpe ATIVADO!" else "Matar Boss com 1 Golpe desativado")
        }
        layout.addView(swBoss)

        // --- SEÇÃO 2: HABILIDADE ESPECIAL ---
        val secSkillHeader = TextView(context).apply {
            text = "🦘 HABILIDADE ESPECIAL DE MOVIMENTO"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (6 * dp1).toInt(), 0, (6 * dp1).toInt())
        }
        layout.addView(secSkillHeader)

        // 4. Pulo Duplo (Double Jump)
        val isDoubleJump = prefs.getBoolean("opt_double_jump", true)
        val swDoubleJump = createSwitchRow(
            dp1,
            "🦘 Pulo Duplo no Ar (Double Jump)",
            "Aperte o botão de Pulo (A) no ar para realizar um segundo salto com impulso e controle de direção",
            isDoubleJump
        ) { checked ->
            prefs.edit().putBoolean("opt_double_jump", checked).apply()
            onDoubleJumpChangedListener?.invoke(checked)
            showStatus(if (checked) "Pulo Duplo ATIVADO!" else "Pulo Duplo desativado")
        }
        layout.addView(swDoubleJump)

        // 4.1 Subir na Escada no Ar (Mid-Air Stair Grab / Latch)
        val isLatchStairs = prefs.getBoolean("opt_latch_stairs", true)
        val swLatchStairs = createSwitchRow(
            dp1,
            "🪜 Agarrar Escadas no Ar (Subir no Salto)",
            "Pule em qualquer altura ou metade da escada segurando CIMA para se agarrar e subir como se viesse do início. Pressione Pulo (A) na escada para saltar fora dela",
            isLatchStairs
        ) { checked ->
            prefs.edit().putBoolean("opt_latch_stairs", checked).apply()
            onLatchStairsChangedListener?.invoke(checked)
            showStatus(if (checked) "Agarrar Escadas no Ar ATIVADO!" else "Agarrar Escadas no Ar desativado")
        }
        layout.addView(swLatchStairs)

        // --- SEÇÃO 3: AUMENTAR A DIFICULDADE DO JOGO ---
        val diffSection = createDifficultySection(dp1)
        layout.addView(diffSection)

        // --- SEÇÃO 4: INTELIGÊNCIA ARTIFICIAL DOS INIMIGOS ---
        val secAiHeader = TextView(context).apply {
            text = "🧠 INTELIGÊNCIA ARTIFICIAL DOS INIMIGOS (SMART AI)"
            setTextColor(Color.parseColor("#B388FF"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (6 * dp1).toInt(), 0, (6 * dp1).toInt())
        }
        layout.addView(secAiHeader)

        val isSmartAi = prefs.getBoolean("opt_smart_enemy_ai", false)
        val swSmartAi = createSwitchRow(
            dp1,
            "🧠 IA dos Inimigos Mais Esperta (Smart AI)",
            "Inimigos esquivam de chicotadas pulando, rastreiam e perseguem Simon pelas costas, aumentam cadência de ataque e emboscam",
            isSmartAi
        ) { checked ->
            prefs.edit().putBoolean("opt_smart_enemy_ai", checked).apply()
            onSmartEnemyAiChangedListener?.invoke(checked)
            showStatus(if (checked) "IA Inteligente dos Inimigos ATIVADA!" else "IA dos Inimigos restaurada ao padrão original")
        }
        layout.addView(swSmartAi)

        val aiAggressionSection = createAiAggressionSection(dp1)
        layout.addView(aiAggressionSection)

        // --- SEÇÃO 5: BÔNUS & ARMAS ---
        val secBonusHeader = TextView(context).apply {
            text = "⚔️ UPGRADES E POTENCIALIZADORES"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (6 * dp1).toInt(), 0, (6 * dp1).toInt())
        }
        layout.addView(secBonusHeader)

        // 5. Chicote Máximo
        val isMaxWhip = prefs.getBoolean("opt_max_whip", false)
        val swWhip = createSwitchRow(
            dp1,
            "⛓️ Chicote Máximo (Morning Star)",
            "Chicote longo de corrente mais forte habilitado permanentemente",
            isMaxWhip
        ) { checked ->
            prefs.edit().putBoolean("opt_max_whip", checked).apply()
            onMaxWhipChangedListener?.invoke(checked)
            showStatus(if (checked) "Chicote Máximo ATIVADO!" else "Chicote Máximo desativado")
        }
        layout.addView(swWhip)

        // 6. Disparo Triplo
        val isTripleShot = prefs.getBoolean("opt_triple_shot", false)
        val swTriple = createSwitchRow(
            dp1,
            "🔥 Disparo Triplo Permanente (Triple Shot)",
            "Permite lançar até 3 sub-armas simultaneamente na tela",
            isTripleShot
        ) { checked ->
            prefs.edit().putBoolean("opt_triple_shot", checked).apply()
            onTripleShotChangedListener?.invoke(checked)
            showStatus(if (checked) "Disparo Triplo ATIVADO!" else "Disparo Triplo desativado")
        }
        layout.addView(swTriple)

        // 7. Vidas Infinitas
        val isInfLives = prefs.getBoolean("opt_infinite_lives", false)
        val swLives = createSwitchRow(
            dp1,
            "👑 Vidas Infinitas (9 Vidas)",
            "Trava as vidas em 9 para que nunca haja Game Over",
            isInfLives
        ) { checked ->
            prefs.edit().putBoolean("opt_infinite_lives", checked).apply()
            onInfiniteLivesChangedListener?.invoke(checked)
            showStatus(if (checked) "Vidas Infinitas ATIVADAS!" else "Vidas Infinitas desativadas")
        }
        layout.addView(swLives)

        // 8. Recuperar Coração com a Cruz Bumerangue
        val isCrossRecovery = prefs.getBoolean("opt_cross_heart_recovery", true)
        val swCrossRecovery = createSwitchRow(
            dp1,
            "🪃 Recuperar Coração ao Pegar a Cruz Bumerangue",
            "Ao lançar a cruz e interceptá-la de volta no retorno, recupera automaticamente o coração que foi gasto ao jogá-la",
            isCrossRecovery
        ) { checked ->
            prefs.edit().putBoolean("opt_cross_heart_recovery", checked).apply()
            onCrossHeartRecoveryChangedListener?.invoke(checked)
            showStatus(if (checked) "Recuperação de Coração da Cruz ATIVADA!" else "Recuperação de Coração desativada")
        }
        layout.addView(swCrossRecovery)

        return scroll
    }

    private data class DifficultyOption(
        val mode: Int,
        val icon: String,
        val title: String,
        val tag: String?,
        val tagColor: String?,
        val description: String
    )

    private fun createDifficultySection(dp1: Float): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * dp1).toInt()
                topMargin = (6 * dp1).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141622"))
                cornerRadius = 10 * dp1
                setStroke((1 * dp1).toInt(), Color.parseColor("#2A2D40"))
            }
            setPadding((12 * dp1).toInt(), (10 * dp1).toInt(), (12 * dp1).toInt(), (10 * dp1).toInt())
        }

        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp1).toInt()
            }
        }

        val titleView = TextView(context).apply {
            text = "💀 DIFICULDADE DO JOGO"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        headerLayout.addView(titleView)
        container.addView(headerLayout)

        val subDesc = TextView(context).apply {
            text = "Aumente o nível de desafio e a penalidade de dano sofrido por Simon:"
            setTextColor(Color.parseColor("#9E9EAF"))
            textSize = 10.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp1).toInt()
            }
        }
        container.addView(subDesc)

        val options = listOf(
            DifficultyOption(
                mode = 0,
                icon = "🛡️",
                title = "Normal (Padrão Original)",
                tag = "ORIGINAL",
                tagColor = "#81C784",
                description = "Dano clássico do Castlevania de NES. Equilíbrio padrão do jogo original."
            ),
            DifficultyOption(
                mode = 1,
                icon = "⚔️",
                title = "Difícil (Dano 2x Dobrado)",
                tag = "DESAFIO",
                tagColor = "#FFB300",
                description = "Simon Belmont perde o dobro de vida a cada ataque, inimigo ou projétil sofrido. Exige cautela redobrada."
            ),
            DifficultyOption(
                mode = 2,
                icon = "💀",
                title = "Pesadelo (Morte com 1 Golpe)",
                tag = "EXTREMO",
                tagColor = "#FF1744",
                description = "Qualquer dano recebido é instantaneamente fatal! Desafio máximo estilo 'Hitless / One-Hit Death'."
            )
        )

        var selectedDiff = prefs.getInt("opt_difficulty_mode", 0)
        val optionViews = mutableListOf<Triple<View, TextView, TextView>>()

        fun updateDiffSelection(mode: Int) {
            selectedDiff = mode
            prefs.edit().putInt("opt_difficulty_mode", mode).apply()
            onDifficultyModeChangedListener?.invoke(mode)

            for (i in options.indices) {
                val opt = options[i]
                val (card, checkView, titleV) = optionViews[i]
                val isSel = opt.mode == mode

                card.background = GradientDrawable().apply {
                    if (isSel) {
                        setColor(Color.parseColor("#28161A"))
                        cornerRadius = 8 * dp1
                        setStroke((1.5f * dp1).toInt(), Color.parseColor("#FF5252"))
                    } else {
                        setColor(Color.parseColor("#0F111A"))
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#1F2232"))
                    }
                }

                if (isSel) {
                    checkView.text = "✔"
                    checkView.setTextColor(Color.parseColor("#FF5252"))
                    titleV.setTextColor(Color.parseColor("#FF8A80"))
                } else {
                    checkView.text = "○"
                    checkView.setTextColor(Color.parseColor("#44475A"))
                    titleV.setTextColor(Color.parseColor("#DDDDDD"))
                }
            }
        }

        for (opt in options) {
            val isSel = opt.mode == selectedDiff

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * dp1).toInt()
                }
                isClickable = true
                isFocusable = true
                setPadding((10 * dp1).toInt(), (8 * dp1).toInt(), (10 * dp1).toInt(), (8 * dp1).toInt())
                background = GradientDrawable().apply {
                    if (isSel) {
                        setColor(Color.parseColor("#28161A"))
                        cornerRadius = 8 * dp1
                        setStroke((1.5f * dp1).toInt(), Color.parseColor("#FF5252"))
                    } else {
                        setColor(Color.parseColor("#0F111A"))
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#1F2232"))
                    }
                }
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val iconView = TextView(context).apply {
                text = opt.icon
                textSize = 14f
                setPadding(0, 0, (6 * dp1).toInt(), 0)
            }
            topRow.addView(iconView)

            val titleV = TextView(context).apply {
                text = opt.title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSel) Color.parseColor("#FF8A80") else Color.parseColor("#DDDDDD"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            topRow.addView(titleV)

            if (opt.tag != null) {
                val tagV = TextView(context).apply {
                    text = opt.tag
                    textSize = 8.5f
                    typeface = Typeface.DEFAULT_BOLD
                    val colorHex = opt.tagColor ?: "#FF5252"
                    setTextColor(Color.parseColor(colorHex))
                    setPadding((5 * dp1).toInt(), (1 * dp1).toInt(), (5 * dp1).toInt(), (1 * dp1).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#201215"))
                        cornerRadius = 3 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor(colorHex))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = (8 * dp1).toInt()
                    }
                }
                topRow.addView(tagV)
            }

            val checkView = TextView(context).apply {
                text = if (isSel) "✔" else "○"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSel) Color.parseColor("#FF5252") else Color.parseColor("#44475A"))
            }
            topRow.addView(checkView)
            card.addView(topRow)

            val descV = TextView(context).apply {
                text = opt.description
                setTextColor(Color.parseColor("#A0A0B2"))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (3 * dp1).toInt()
                    leftMargin = (22 * dp1).toInt()
                }
            }
            card.addView(descV)

            card.setOnClickListener {
                updateDiffSelection(opt.mode)
                showStatus("Dificuldade definida: ${opt.title}")
            }

            optionViews.add(Triple(card, checkView, titleV))
            container.addView(card)
        }

        return container
    }

    private fun createAiAggressionSection(dp1: Float): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * dp1).toInt()
                topMargin = (2 * dp1).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151224"))
                cornerRadius = 10 * dp1
                setStroke((1 * dp1).toInt(), Color.parseColor("#322550"))
            }
            setPadding((12 * dp1).toInt(), (10 * dp1).toInt(), (12 * dp1).toInt(), (10 * dp1).toInt())
        }

        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp1).toInt()
            }
        }

        val titleView = TextView(context).apply {
            text = "⚡ COMPORTAMENTO TÁTICO DA IA"
            setTextColor(Color.parseColor("#B388FF"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        headerLayout.addView(titleView)
        container.addView(headerLayout)

        val subDesc = TextView(context).apply {
            text = "Selecione o nível de agressividade e inteligência tática dos monstros:"
            setTextColor(Color.parseColor("#9E9EAF"))
            textSize = 10.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp1).toInt()
            }
        }
        container.addView(subDesc)

        val options = listOf(
            DifficultyOption(
                mode = 1,
                icon = "🎯",
                title = "Tática Avançada (Equilibrada)",
                tag = "INTELIGENTE",
                tagColor = "#B388FF",
                description = "Inimigos detectam chicotadas e saltam para esquivar, viram para perseguir Simon se ele pular por cima e aumentam o ritmo de disparo."
            ),
            DifficultyOption(
                mode = 2,
                icon = "🔥",
                title = "Implacável (Predatória)",
                tag = "INSANO",
                tagColor = "#FF4081",
                description = "Reflexos instantâneos de salto duplo, perseguição veloz em dobro quando Simon está por perto, cadência máxima de projéteis e emboscadas com saltos contínuos."
            )
        )

        var selectedAggro = prefs.getInt("opt_smart_ai_aggression", 1)
        val optionViews = mutableListOf<Triple<View, TextView, TextView>>()

        fun updateAggroSelection(level: Int) {
            selectedAggro = level
            prefs.edit().putInt("opt_smart_ai_aggression", level).apply()
            onSmartAiAggressionChangedListener?.invoke(level)

            for (i in options.indices) {
                val opt = options[i]
                val (card, checkView, titleV) = optionViews[i]
                val isSel = opt.mode == level

                card.background = GradientDrawable().apply {
                    if (isSel) {
                        setColor(Color.parseColor("#261536"))
                        cornerRadius = 8 * dp1
                        setStroke((1.5f * dp1).toInt(), Color.parseColor("#B388FF"))
                    } else {
                        setColor(Color.parseColor("#0F111A"))
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#1F2232"))
                    }
                }

                if (isSel) {
                    checkView.text = "✔"
                    checkView.setTextColor(Color.parseColor("#B388FF"))
                    titleV.setTextColor(Color.parseColor("#D1C4E9"))
                } else {
                    checkView.text = "○"
                    checkView.setTextColor(Color.parseColor("#44475A"))
                    titleV.setTextColor(Color.parseColor("#DDDDDD"))
                }
            }
        }

        for (opt in options) {
            val isSel = opt.mode == selectedAggro

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * dp1).toInt()
                }
                isClickable = true
                isFocusable = true
                setPadding((10 * dp1).toInt(), (8 * dp1).toInt(), (10 * dp1).toInt(), (8 * dp1).toInt())
                background = GradientDrawable().apply {
                    if (isSel) {
                        setColor(Color.parseColor("#261536"))
                        cornerRadius = 8 * dp1
                        setStroke((1.5f * dp1).toInt(), Color.parseColor("#B388FF"))
                    } else {
                        setColor(Color.parseColor("#0F111A"))
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#1F2232"))
                    }
                }
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val iconView = TextView(context).apply {
                text = opt.icon
                textSize = 14f
                setPadding(0, 0, (6 * dp1).toInt(), 0)
            }
            topRow.addView(iconView)

            val titleV = TextView(context).apply {
                text = opt.title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSel) Color.parseColor("#D1C4E9") else Color.parseColor("#DDDDDD"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            topRow.addView(titleV)

            if (opt.tag != null) {
                val tagV = TextView(context).apply {
                    text = opt.tag
                    textSize = 8.5f
                    typeface = Typeface.DEFAULT_BOLD
                    val colorHex = opt.tagColor ?: "#B388FF"
                    setTextColor(Color.parseColor(colorHex))
                    setPadding((5 * dp1).toInt(), (1 * dp1).toInt(), (5 * dp1).toInt(), (1 * dp1).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#20122E"))
                        cornerRadius = 3 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor(colorHex))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = (8 * dp1).toInt()
                    }
                }
                topRow.addView(tagV)
            }

            val checkView = TextView(context).apply {
                text = if (isSel) "✔" else "○"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSel) Color.parseColor("#B388FF") else Color.parseColor("#44475A"))
            }
            topRow.addView(checkView)
            card.addView(topRow)

            val descV = TextView(context).apply {
                text = opt.description
                setTextColor(Color.parseColor("#A0A0B2"))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (3 * dp1).toInt()
                    leftMargin = (22 * dp1).toInt()
                }
            }
            card.addView(descV)

            card.setOnClickListener {
                updateAggroSelection(opt.mode)
                showStatus("Agressividade da IA: ${opt.title}")
            }

            optionViews.add(Triple(card, checkView, titleV))
            container.addView(card)
        }

        return container
    }

    // =========================================================================
    // TAB 1: SALVAR / CARREGAR PROGRESSO (SAVE & LOAD STATE)
    // =========================================================================
    private fun createSaveLoadTab(dp1: Float): View {
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding((4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt())
        }
        scroll.addView(layout)

        val slotLabel = TextView(context).apply {
            text = "Selecione o Slot de Salvamento:"
            setTextColor(Color.parseColor("#D0D0E0"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (6 * dp1).toInt())
        }
        layout.addView(slotLabel)

        // Slot buttons row
        val slotRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * dp1).toInt()
            ).apply {
                bottomMargin = (12 * dp1).toInt()
            }
        }

        val slotBtns = mutableListOf<Button>()
        for (slot in 1..3) {
            val btn = Button(context).apply {
                text = "Slot $slot"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                    if (slot < 3) rightMargin = (8 * dp1).toInt()
                }
                setOnClickListener {
                    selectedSlot = slot
                    updateSlotButtons(slotBtns, dp1)
                }
            }
            slotBtns.add(btn)
            slotRow.addView(btn)
        }
        updateSlotButtons(slotBtns, dp1)
        layout.addView(slotRow)

        // Action Buttons Row: Save & Load
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * dp1).toInt()
            ).apply {
                bottomMargin = (12 * dp1).toInt()
            }
        }

        val btnSave = Button(context).apply {
            text = "💾 Salvar Ponto"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f).apply {
                rightMargin = (8 * dp1).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B5E20")) // Forest Green
                cornerRadius = 8 * dp1
            }
            setOnClickListener {
                val ok = onStateSavedListener?.invoke(selectedSlot) ?: false
                if (ok) {
                    showStatus("✓ Progresso salvo no Slot $selectedSlot com sucesso!")
                } else {
                    showStatus("Falha ao salvar no Slot $selectedSlot.", true)
                }
            }
        }
        actionsRow.addView(btnSave)

        val btnLoad = Button(context).apply {
            text = "📂 Carregar Ponto"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D47A1")) // Deep Blue
                cornerRadius = 8 * dp1
            }
            setOnClickListener {
                val ok = onStateLoadedListener?.invoke(selectedSlot) ?: false
                if (ok) {
                    showStatus("✓ Progresso do Slot $selectedSlot carregado!")
                } else {
                    showStatus("Nenhum salvamento encontrado no Slot $selectedSlot.", true)
                }
            }
        }
        actionsRow.addView(btnLoad)
        layout.addView(actionsRow)

        // Reset Button
        val btnReset = Button(context).apply {
            text = "⚠️ Reiniciar Jogo (Reset)"
            setTextColor(Color.parseColor("#FFCDD2"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * dp1).toInt()
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3E1C1F"))
                cornerRadius = 8 * dp1
                setStroke((1 * dp1).toInt(), Color.parseColor("#7F2B32"))
            }
            setOnClickListener {
                onResetGameListener?.invoke()
                showStatus("Jogo reiniciado!")
            }
        }
        layout.addView(btnReset)

        return scroll
    }

    private fun updateSlotButtons(buttons: List<Button>, dp1: Float) {
        for (i in buttons.indices) {
            val isSel = (i + 1 == selectedSlot)
            buttons[i].setTextColor(if (isSel) Color.WHITE else Color.parseColor("#AAAAAA"))
            buttons[i].background = GradientDrawable().apply {
                setColor(if (isSel) Color.parseColor("#6A1B29") else Color.parseColor("#1C1E2B"))
                cornerRadius = 8 * dp1
                if (isSel) {
                    setStroke((2 * dp1).toInt(), Color.parseColor("#E53935"))
                }
            }
        }
    }

    // =========================================================================
    // TAB 2: VÍDEO & HD PACK
    // =========================================================================
    private fun createVideoTab(dp1: Float): View {
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding((4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt())
        }
        scroll.addView(layout)

        // HD Pack Switch
        val isHdActive = prefs.getBoolean("opt_hd_pack", true)
        val swHd = createSwitchRow(
            dp1,
            "Gráficos HD (HD Pack )",
            "Texturas em 4x de alta definição e HUD remasterizado",
            isHdActive
        ) { checked ->
            prefs.edit().putBoolean("opt_hd_pack", checked).apply()
            onHdPackChangedListener?.invoke(checked)
            showStatus(if (checked) "Gráficos HD ativados!" else "Gráficos originais do NES ativados!")
        }
        layout.addView(swHd)

        // Aspect Ratio Switch
        val isStretch = prefs.getBoolean("opt_stretch", false)
        val swStretch = createSwitchRow(
            dp1,
            "Esticar Tela (Widescreen)",
            "Desativado = 4:3 Original com bordas; Ativado = Tela cheia",
            isStretch
        ) { checked ->
            prefs.edit().putBoolean("opt_stretch", checked).apply()
            onStretchChangedListener?.invoke(checked)
        }
        layout.addView(swStretch)

        // Screen Refresh Rate (120 Hz by default)
        val refreshRateSection = createRefreshRateSection(dp1)
        layout.addView(refreshRateSection)

        // Video Filter & Upscaling Engine Selector
        val filterSection = createVideoFilterSection(dp1)
        layout.addView(filterSection)

        // Remove Sprite Limit
        val isNoLimit = prefs.getBoolean("opt_no_sprite_limit", false)
        val swLimit = createSwitchRow(
            dp1,
            "Remover Limite de 8 Sprites",
            "Elimina o piscar/flicker clássico de personagens e morcegos",
            isNoLimit
        ) { checked ->
            prefs.edit().putBoolean("opt_no_sprite_limit", checked).apply()
            onSpriteLimitChangedListener?.invoke(checked)
        }
        layout.addView(swLimit)

        return scroll
    }

    private data class RefreshRateOption(
        val hz: Float,
        val icon: String,
        val title: String,
        val tag: String?,
        val tagColor: String?,
        val description: String
    )

    private fun createRefreshRateSection(dp1: Float): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * dp1).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141622"))
                cornerRadius = 10 * dp1
                setStroke((1 * dp1).toInt(), Color.parseColor("#2A2D40"))
            }
            setPadding((12 * dp1).toInt(), (10 * dp1).toInt(), (12 * dp1).toInt(), (10 * dp1).toInt())
        }

        // Section Title Header
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp1).toInt()
            }
        }

        val titleView = TextView(context).apply {
            text = "⚡ TAXA DE ATUALIZAÇÃO (REFRESH RATE)"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val badge = TextView(context).apply {
            text = "120 HZ ATIVO"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * dp1).toInt(), (2 * dp1).toInt(), (6 * dp1).toInt(), (2 * dp1).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0C2433"))
                cornerRadius = 4 * dp1
                setStroke((1 * dp1).toInt(), Color.parseColor("#00B0FF"))
            }
        }
        refreshRateBadge = badge

        headerLayout.addView(titleView)
        headerLayout.addView(badge)
        container.addView(headerLayout)

        val subDesc = TextView(context).apply {
            text = "Taxa de atualização de tela e sincronia vertical (VSync) do motor gráfico:"
            setTextColor(Color.parseColor("#9E9EAF"))
            textSize = 10.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp1).toInt()
            }
        }
        container.addView(subDesc)

        val options = listOf(
            RefreshRateOption(
                hz = 120.0f,
                icon = "🚀",
                title = "120 Hz (Ultra Fluidez & Menor Latência)",
                tag = "PADRÃO • RECOMENDADO",
                tagColor = "#00E5FF",
                description = "Sincroniza o motor gráfico a 120 Hz com pulldown 2:2 perfeito (60 FPS NES). Corta o atraso de toque pela metade (8.3ms) e zera micro-travamentos."
            ),
            RefreshRateOption(
                hz = 60.0f,
                icon = "🔋",
                title = "60 Hz (Padrão Antigo / Economia)",
                tag = "ECONOMIA",
                tagColor = "#81C784",
                description = "Taxa clássica de 60 Hz. Recomendada apenas se desejar economizar o consumo de bateria em telas antigas."
            )
        )

        var selectedHz = prefs.getFloat("opt_refresh_rate", 120.0f)
        val optionViews = mutableListOf<Triple<View, TextView, TextView>>()

        fun updateHzSelection(hz: Float) {
            selectedHz = hz
            prefs.edit().putFloat("opt_refresh_rate", hz).apply()
            val is120 = hz >= 110.0f
            badge.text = if (is120) "120 HZ ATIVO" else "60 HZ ATIVO"
            badge.setTextColor(if (is120) Color.parseColor("#00E5FF") else Color.parseColor("#81C784"))
            badge.background = GradientDrawable().apply {
                val c = if (is120) "#0C2433" else "#1B2E20"
                val s = if (is120) "#00B0FF" else "#2E7D32"
                setColor(Color.parseColor(c))
                cornerRadius = 4 * dp1
                setStroke((1 * dp1).toInt(), Color.parseColor(s))
            }

            videoFpsBadge?.text = if (is120) "⚡ 120 HZ • 60 FPS NES" else "60 FPS NATIVO"

            onRefreshRateChangedListener?.invoke(hz)

            for (i in options.indices) {
                val opt = options[i]
                val (card, checkView, titleV) = optionViews[i]
                val isSel = Math.abs(opt.hz - hz) < 1.0f

                card.background = GradientDrawable().apply {
                    if (isSel) {
                        setColor(Color.parseColor("#152438"))
                        cornerRadius = 8 * dp1
                        setStroke((1.5f * dp1).toInt(), Color.parseColor("#00E5FF"))
                    } else {
                        setColor(Color.parseColor("#0F111A"))
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#1F2232"))
                    }
                }

                if (isSel) {
                    checkView.text = "✔"
                    checkView.setTextColor(Color.parseColor("#00E5FF"))
                    titleV.setTextColor(Color.parseColor("#80D8FF"))
                } else {
                    checkView.text = "○"
                    checkView.setTextColor(Color.parseColor("#44475A"))
                    titleV.setTextColor(Color.parseColor("#DDDDDD"))
                }
            }
        }

        for (opt in options) {
            val isSel = Math.abs(opt.hz - selectedHz) < 1.0f

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * dp1).toInt()
                }
                isClickable = true
                isFocusable = true
                setPadding((10 * dp1).toInt(), (8 * dp1).toInt(), (10 * dp1).toInt(), (8 * dp1).toInt())
                background = GradientDrawable().apply {
                    if (isSel) {
                        setColor(Color.parseColor("#152438"))
                        cornerRadius = 8 * dp1
                        setStroke((1.5f * dp1).toInt(), Color.parseColor("#00E5FF"))
                    } else {
                        setColor(Color.parseColor("#0F111A"))
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#1F2232"))
                    }
                }
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val iconView = TextView(context).apply {
                text = opt.icon
                textSize = 14f
                setPadding(0, 0, (6 * dp1).toInt(), 0)
            }
            topRow.addView(iconView)

            val titleV = TextView(context).apply {
                text = opt.title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSel) Color.parseColor("#80D8FF") else Color.parseColor("#DDDDDD"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            topRow.addView(titleV)

            if (opt.tag != null) {
                val tagV = TextView(context).apply {
                    text = opt.tag
                    textSize = 8.5f
                    typeface = Typeface.DEFAULT_BOLD
                    val colorHex = opt.tagColor ?: "#00E5FF"
                    setTextColor(Color.parseColor(colorHex))
                    setPadding((5 * dp1).toInt(), (1 * dp1).toInt(), (5 * dp1).toInt(), (1 * dp1).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#112433"))
                        cornerRadius = 3 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor(colorHex))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = (8 * dp1).toInt()
                    }
                }
                topRow.addView(tagV)
            }

            val checkView = TextView(context).apply {
                text = if (isSel) "✔" else "○"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSel) Color.parseColor("#00E5FF") else Color.parseColor("#44475A"))
            }
            topRow.addView(checkView)
            card.addView(topRow)

            val descV = TextView(context).apply {
                text = opt.description
                setTextColor(Color.parseColor("#A0A0B2"))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (3 * dp1).toInt()
                    leftMargin = (22 * dp1).toInt()
                }
            }
            card.addView(descV)

            card.setOnClickListener {
                updateHzSelection(opt.hz)
                showStatus("Taxa definida para ${opt.hz.toInt()} Hz")
            }

            optionViews.add(Triple(card, checkView, titleV))
            container.addView(card)
        }

        // Info footer
        val detectedText = TextView(context).apply {
            text = "ℹ️ Motor gráfico otimizado com VSync a 120Hz para respostas ultrarrápidas de toque e gamepad."
            setTextColor(Color.parseColor("#78909C"))
            textSize = 9.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * dp1).toInt()
            }
        }
        detectedDisplayHzText = detectedText
        container.addView(detectedText)

        return container
    }

    private data class FilterOption(
        val mode: Int,
        val icon: String,
        val title: String,
        val tag: String?,
        val tagColor: String?,
        val description: String
    )

    private fun createVideoFilterSection(dp1: Float): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * dp1).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141622"))
                cornerRadius = 10 * dp1
                setStroke((1 * dp1).toInt(), Color.parseColor("#2A2D40"))
            }
            setPadding((12 * dp1).toInt(), (10 * dp1).toInt(), (12 * dp1).toInt(), (10 * dp1).toInt())
        }

        // Section Title Header
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp1).toInt()
            }
        }

        val titleView = TextView(context).apply {
            text = "✨ FILTRO DE VÍDEO & UPSCALE"
            setTextColor(Color.parseColor("#E5C158"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        val fpsBadge = TextView(context).apply {
            val is120 = prefs.getFloat("opt_refresh_rate", 120.0f) >= 110.0f
            text = if (is120) "⚡ 120 HZ • 60 FPS NES" else "60 FPS NATIVO"
            setTextColor(if (is120) Color.parseColor("#00E5FF") else Color.parseColor("#81C784"))
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((6 * dp1).toInt(), (2 * dp1).toInt(), (6 * dp1).toInt(), (2 * dp1).toInt())
            background = GradientDrawable().apply {
                val c = if (is120) "#0C2433" else "#1B2E20"
                val s = if (is120) "#00B0FF" else "#2E7D32"
                setColor(Color.parseColor(c))
                cornerRadius = 4 * dp1
                setStroke((1 * dp1).toInt(), Color.parseColor(s))
            }
        }
        videoFpsBadge = fpsBadge
        headerLayout.addView(titleView)
        headerLayout.addView(fpsBadge)
        container.addView(headerLayout)

        val subDesc = TextView(context).apply {
            text = "Selecione o motor de pós-processamento gráfico em tempo real:"
            setTextColor(Color.parseColor("#9E9EAF"))
            textSize = 10.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp1).toInt()
            }
        }
        container.addView(subDesc)

        val options = listOf(
            FilterOption(
                mode = NesRenderer.FILTER_MODE_FIDELITYFX,
                icon = "⚡",
                title = "AMD FidelityFX™ (FSR / CAS)",
                tag = "RECOMENDADO",
                tagColor = "#FFD700",
                description = "Upscaling adaptativo e nitidez extrema de contraste da AMD. Gráficos em altíssima definição, contornos nítidos e zero borrões a 60 FPS."
            ),
            FilterOption(
                mode = NesRenderer.FILTER_MODE_IA_XBR,
                icon = "🧠",
                title = "IA Upscale (xBR Smart Curves)",
                tag = "VETORIAL HD",
                tagColor = "#00E5FF",
                description = "Reconstrução procedural geométrica de bordas. Elimina o serrilhado de blocos e suaviza contornos em curvas vetoriais HD."
            ),
            FilterOption(
                mode = NesRenderer.FILTER_MODE_CRT,
                icon = "📺",
                title = "CRT Arcade Realista",
                tag = "TUBO RETRÔ",
                tagColor = "#FF80AB",
                description = "Experiência autêntica de TV de tubo: Scanlines analógicas, grade de fósforo RGB (Trinitron) e brilho quente de arcade."
            ),
            FilterOption(
                mode = NesRenderer.FILTER_MODE_NORMAL,
                icon = "👾",
                title = "Pixel Art Puro (Padrão)",
                tag = "ORIGINAL 1:1",
                tagColor = "#B0BEC5",
                description = "Pixels clássicos nítidos sem pós-processamento, exatamente como no hardware original do NES."
            ),
            FilterOption(
                mode = NesRenderer.FILTER_MODE_BILINEAR,
                icon = "🌫️",
                title = "Suave (Bilinear)",
                tag = null,
                tagColor = null,
                description = "Interpolação linear clássica para aspecto visual suave."
            )
        )

        var selectedMode = prefs.getInt("opt_video_filter", NesRenderer.FILTER_MODE_FIDELITYFX)
        val optionViews = mutableListOf<Triple<View, TextView, TextView>>()

        fun updateSelection(mode: Int) {
            selectedMode = mode
            prefs.edit().putInt("opt_video_filter", mode).apply()
            onFilterModeChangedListener?.invoke(mode)
            onFilterChangedListener?.invoke(mode == NesRenderer.FILTER_MODE_BILINEAR)

            for (i in options.indices) {
                val opt = options[i]
                val (card, checkView, titleV) = optionViews[i]
                val isSel = opt.mode == mode

                card.background = GradientDrawable().apply {
                    if (isSel) {
                        setColor(Color.parseColor("#261726"))
                        cornerRadius = 8 * dp1
                        setStroke((1.5f * dp1).toInt(), Color.parseColor("#D4AF37"))
                    } else {
                        setColor(Color.parseColor("#0F111A"))
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#1F2232"))
                    }
                }

                if (isSel) {
                    checkView.text = "✔"
                    checkView.setTextColor(Color.parseColor("#FFD700"))
                    titleV.setTextColor(Color.parseColor("#FFF176"))
                } else {
                    checkView.text = "○"
                    checkView.setTextColor(Color.parseColor("#44475A"))
                    titleV.setTextColor(Color.parseColor("#DDDDDD"))
                }
            }
        }

        for (opt in options) {
            val isSel = opt.mode == selectedMode

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * dp1).toInt()
                }
                isClickable = true
                isFocusable = true
                setPadding((10 * dp1).toInt(), (8 * dp1).toInt(), (10 * dp1).toInt(), (8 * dp1).toInt())
                background = GradientDrawable().apply {
                    if (isSel) {
                        setColor(Color.parseColor("#261726"))
                        cornerRadius = 8 * dp1
                        setStroke((1.5f * dp1).toInt(), Color.parseColor("#D4AF37"))
                    } else {
                        setColor(Color.parseColor("#0F111A"))
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#1F2232"))
                    }
                }
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val iconView = TextView(context).apply {
                text = opt.icon
                textSize = 14f
                setPadding(0, 0, (6 * dp1).toInt(), 0)
            }
            topRow.addView(iconView)

            val titleV = TextView(context).apply {
                text = opt.title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSel) Color.parseColor("#FFF176") else Color.parseColor("#DDDDDD"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            topRow.addView(titleV)

            if (opt.tag != null) {
                val tagV = TextView(context).apply {
                    text = opt.tag
                    textSize = 8.5f
                    typeface = Typeface.DEFAULT_BOLD
                    val colorHex = opt.tagColor ?: "#FFD700"
                    setTextColor(Color.parseColor(colorHex))
                    setPadding((5 * dp1).toInt(), (1 * dp1).toInt(), (5 * dp1).toInt(), (1 * dp1).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1F1E28"))
                        cornerRadius = 3 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor(colorHex))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = (8 * dp1).toInt()
                    }
                }
                topRow.addView(tagV)
            }

            val checkView = TextView(context).apply {
                text = if (isSel) "✔" else "○"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSel) Color.parseColor("#FFD700") else Color.parseColor("#44475A"))
            }
            topRow.addView(checkView)
            card.addView(topRow)

            val descV = TextView(context).apply {
                text = opt.description
                setTextColor(Color.parseColor("#A0A0B2"))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (3 * dp1).toInt()
                    leftMargin = (22 * dp1).toInt()
                }
            }
            card.addView(descV)

            card.setOnClickListener {
                updateSelection(opt.mode)
                showStatus("Filtro ativado: ${opt.title}")
            }

            optionViews.add(Triple(card, checkView, titleV))
            container.addView(card)
        }

        return container
    }

    // =========================================================================
    // TAB 3: ÁUDIO
    // =========================================================================
    private fun createAudioTab(dp1: Float): View {
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding((4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt())
        }
        scroll.addView(layout)

        // Audio Mute Switch
        val isAudioEnabled = prefs.getBoolean("opt_audio_enabled", true)
        val swMute = createSwitchRow(
            dp1,
            "Áudio Ativado",
            "Trilha sonora e efeitos sonoros do Castlevania NES",
            isAudioEnabled
        ) { checked ->
            prefs.edit().putBoolean("opt_audio_enabled", checked).apply()
            onAudioMuteChangedListener?.invoke(!checked)
        }
        layout.addView(swMute)

        // Volume Slider (Master)
        val currentVol = prefs.getInt("opt_volume", 100)
        val volHeader = TextView(context).apply {
            text = "Volume Geral: $currentVol%"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (10 * dp1).toInt(), 0, (6 * dp1).toInt())
        }
        layout.addView(volHeader)

        val volBar = SeekBar(context).apply {
            max = 100
            progress = currentVol
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * dp1).toInt()
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    volHeader.text = "Volume Geral: $progress%"
                    if (fromUser) {
                        prefs.edit().putInt("opt_volume", progress).apply()
                        onVolumeChangedListener?.invoke(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(volBar)

        // HD Mod Music Volume Slider
        val currentHdBgmVol = prefs.getInt("opt_hd_bgm_volume", 65)
        val hdBgmHeader = TextView(context).apply {
            text = "Música do Mod HD: $currentHdBgmVol%"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (4 * dp1).toInt(), 0, (2 * dp1).toInt())
        }
        layout.addView(hdBgmHeader)

        val hdBgmSub = TextView(context).apply {
            text = "Volume da trilha sonora orquestrada de fundo (OGG)"
            setTextColor(Color.parseColor("#9090A0"))
            textSize = 10.5f
            setPadding(0, 0, 0, (6 * dp1).toInt())
        }
        layout.addView(hdBgmSub)

        val hdBgmBar = SeekBar(context).apply {
            max = 100
            progress = currentHdBgmVol
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * dp1).toInt()
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    hdBgmHeader.text = "Música do Mod HD: $progress%"
                    if (fromUser) {
                        prefs.edit().putInt("opt_hd_bgm_volume", progress).apply()
                        onHdBgmVolumeChangedListener?.invoke(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(hdBgmBar)

        // Achievement Sound Volume Slider
        val currentAchVol = prefs.getInt("opt_achievement_sound_volume", 100)
        val achVolHeader = TextView(context).apply {
            text = "Som de Conquistas: $currentAchVol%"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (4 * dp1).toInt(), 0, (2 * dp1).toInt())
        }
        layout.addView(achVolHeader)

        val achVolSub = TextView(context).apply {
            text = "Volume do efeito sonoro ao desbloquear conquistas (RetroAchievements)"
            setTextColor(Color.parseColor("#9090A0"))
            textSize = 10.5f
            setPadding(0, 0, 0, (6 * dp1).toInt())
        }
        layout.addView(achVolSub)

        val achVolBar = SeekBar(context).apply {
            max = 100
            progress = currentAchVol
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * dp1).toInt()
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    achVolHeader.text = "Som de Conquistas: $progress%"
                    if (fromUser) {
                        prefs.edit().putInt("opt_achievement_sound_volume", progress).apply()
                        onAchievementVolumeChangedListener?.invoke(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val vol = (seekBar?.progress ?: 0) / 100f
                    if (vol > 0f) {
                        try {
                            soundPreviewPlayer?.stop()
                            soundPreviewPlayer?.release()
                            soundPreviewPlayer = MediaPlayer.create(context, R.raw.fx)?.apply {
                                setVolume(vol, vol)
                                isLooping = false
                                setOnCompletionListener { mp ->
                                    try { mp.release() } catch (_: Exception) {}
                                    if (soundPreviewPlayer === mp) soundPreviewPlayer = null
                                }
                                start()
                            }
                        } catch (_: Exception) {}
                    }
                }
            })
        }
        layout.addView(achVolBar)

        val audioInfo = TextView(context).apply {
            text = "Output: 48.000 Hz Estéreo • Baixa Latência PCM 16-bit"
            setTextColor(Color.parseColor("#888899"))
            textSize = 11f
            setPadding(0, (4 * dp1).toInt(), 0, 0)
        }
        layout.addView(audioInfo)

        return scroll
    }

    // =========================================================================
    // TAB 4: CONTROLES
    // =========================================================================
    private fun createControlsTab(dp1: Float): View {
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding((4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt())
        }
        scroll.addView(layout)

        // Vibration Switch
        val isVibration = prefs.getBoolean("opt_vibration", true)
        val swVib = createSwitchRow(
            dp1,
            "Vibração Tátil (Haptic Feedback)",
            "Vibra suavemente ao pressionar botões virtuais",
            isVibration
        ) { checked ->
            prefs.edit().putBoolean("opt_vibration", checked).apply()
            onVibrationChangedListener?.invoke(checked)
        }
        layout.addView(swVib)

        // Controller Opacity Slider
        val currentOpacity = prefs.getInt("opt_opacity", VirtualControllerView.DEFAULT_OPACITY_PERCENT)
        val opacityHeader = TextView(context).apply {
            text = "Opacidade dos Controles: $currentOpacity%"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (10 * dp1).toInt(), 0, (6 * dp1).toInt())
        }
        layout.addView(opacityHeader)

        val opacityBar = SeekBar(context).apply {
            max = 100
            progress = currentOpacity
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * dp1).toInt()
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val actualProg = progress.coerceAtLeast(15)
                    opacityHeader.text = "Opacidade dos Botões: $actualProg%"
                    if (fromUser) {
                        prefs.edit().putInt("opt_opacity", actualProg).apply()
                        onControllerOpacityChangedListener?.invoke(actualProg / 100.0f)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(opacityBar)

        // Custom Layout Buttons (Edit position/size with grid & Reset)
        val btnEditLayout = Button(context).apply {
            text = "✏️ Personalizar Posição e Tamanho (Editor com Grade)"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * dp1).toInt()
            ).apply {
                topMargin = (4 * dp1).toInt()
                bottomMargin = (8 * dp1).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#801313")) // Gothic crimson
                cornerRadius = 8 * dp1
                setStroke((1.5f * dp1).toInt(), Color.parseColor("#D4AF37")) // Gold border
            }
            setOnClickListener {
                hideMenu()
                onEditLayoutClickListener?.invoke()
            }
        }
        layout.addView(btnEditLayout)

        val btnResetLayout = Button(context).apply {
            text = "🔄 Restaurar Posição e Tamanho Padrão"
            setTextColor(Color.parseColor("#FFCDD2"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * dp1).toInt()
            ).apply {
                bottomMargin = (12 * dp1).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#261418"))
                cornerRadius = 8 * dp1
                setStroke((1 * dp1).toInt(), Color.parseColor("#5C1616"))
            }
            setOnClickListener {
                onResetLayoutClickListener?.invoke()
                showStatus("Layout dos controles restaurado ao padrão!")
            }
        }
        layout.addView(btnResetLayout)

        val gamepadNote = TextView(context).apply {
            text = "💡 Controles Físicos (Bluetooth / USB):\nControles de Xbox, PS4/PS5 ou Ipega funcionam automaticamente quando conectados."
            setTextColor(Color.parseColor("#9E9EAF"))
            textSize = 11.5f
            setPadding((8 * dp1).toInt(), (8 * dp1).toInt(), (8 * dp1).toInt(), (8 * dp1).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10121C"))
                cornerRadius = 8 * dp1
            }
        }
        layout.addView(gamepadNote)

        return scroll
    }

    private fun createSwitchRow(
        dp1: Float,
        title: String,
        subtitle: String,
        initialValue: Boolean,
        onChanged: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * dp1).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B1D28"))
                cornerRadius = 8 * dp1
            }
            setPadding((12 * dp1).toInt(), (8 * dp1).toInt(), (12 * dp1).toInt(), (8 * dp1).toInt())
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val txtTitle = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        textCol.addView(txtTitle)

        val txtSub = TextView(context).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9090A0"))
            textSize = 10.5f
        }
        textCol.addView(txtSub)
        row.addView(textCol)

        val sw = Switch(context).apply {
            isChecked = initialValue
            setOnCheckedChangeListener { _, isChecked ->
                onChanged(isChecked)
            }
        }
        row.addView(sw)

        return row
    }

    // =========================================================================
    // TAB 5: RETROACHIEVEMENTS (CONQUISTAS DO RETROARCH)
    // =========================================================================
    private fun createAchievementsTab(dp1: Float): View {
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding((4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt(), (4 * dp1).toInt())
        }
        scroll.addView(container)

        fun refreshAchievementsTab() {
            container.removeAllViews()

            val isSessionActive = RetroAchievementsManager.isSessionActive()
            val isLoggedIn = RetroAchievementsManager.isLoggedIn()
            val userSummary = RetroAchievementsManager.getUserSummary()

            if (!isSessionActive && !isLoggedIn) {
                // --- 1. CARD DE LOGIN ---
                val loginCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1C1E2A"))
                        cornerRadius = 12 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#383B52"))
                    }
                    setPadding((16 * dp1).toInt(), (14 * dp1).toInt(), (16 * dp1).toInt(), (14 * dp1).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (12 * dp1).toInt()
                    }
                }

                val titleLogin = TextView(context).apply {
                    text = "🔐 Login no RetroAchievements"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, (6 * dp1).toInt())
                }
                loginCard.addView(titleLogin)

                val descLogin = TextView(context).apply {
                    text = "Conecte sua conta do retroachievements.org para acompanhar e desbloquear conquistas em tempo real durante a gameplay."
                    setTextColor(Color.parseColor("#A0A0B0"))
                    textSize = 12f
                    setPadding(0, 0, 0, (12 * dp1).toInt())
                }
                loginCard.addView(descLogin)

                val edtUser = EditText(context).apply {
                    hint = "Nome de usuário (Username)"
                    setHintTextColor(Color.parseColor("#666677"))
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#12131C"))
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#444760"))
                    }
                    setPadding((12 * dp1).toInt(), (10 * dp1).toInt(), (12 * dp1).toInt(), (10 * dp1).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (10 * dp1).toInt()
                    }
                    setText(RetroAchievementsManager.getUsername())
                }
                loginCard.addView(edtUser)

                val edtPass = EditText(context).apply {
                    hint = "Senha da conta"
                    setHintTextColor(Color.parseColor("#666677"))
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#12131C"))
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#444760"))
                    }
                    setPadding((12 * dp1).toInt(), (10 * dp1).toInt(), (12 * dp1).toInt(), (10 * dp1).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (12 * dp1).toInt()
                    }
                }
                loginCard.addView(edtPass)

                val btnLogin = Button(context).apply {
                    text = "ENTRAR COM RETROACHIEVEMENTS"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (42 * dp1).toInt()
                    )
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#9B111E")) // Crimson
                        cornerRadius = 8 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#D4AF37")) // Gold
                    }
                    setOnClickListener {
                        val u = edtUser.text.toString().trim()
                        val p = edtPass.text.toString().trim()
                        if (u.isEmpty() || p.isEmpty()) {
                            showStatus("Preencha usuário e senha", true)
                            return@setOnClickListener
                        }
                        isEnabled = false
                        text = "CONECTANDO..."
                        RetroAchievementsManager.onLoginUiListener = { success, msg ->
                            isEnabled = true
                            text = "ENTRAR COM RETROACHIEVEMENTS"
                            if (success) {
                                showStatus("Login efetuado com sucesso!")
                                refreshAchievementsTab()
                            } else {
                                val friendlyMsg = if (msg.contains("1015")) {
                                    "Muitas requisições (Cloudflare 1015). Aguarde 1 minuto."
                                } else if (msg.contains("Invalid", ignoreCase = true) || msg.contains("credenciais", ignoreCase = true)) {
                                    "Usuário ou senha incorretos no RetroAchievements."
                                } else if (msg.contains("No response", ignoreCase = true)) {
                                    "Sem resposta do servidor. Verifique usuário, senha ou conexão."
                                } else {
                                    "Erro no login: $msg"
                                }
                                showStatus(friendlyMsg, true)
                            }
                        }
                        RetroAchievementsManager.login(u, p)
                    }
                }
                loginCard.addView(btnLogin)

                val romHashView = TextView(context).apply {
                    val hash = NativeBridge.nativeGetRomHash()
                    text = "Hash da ROM (NES #1004): $hash"
                    setTextColor(Color.parseColor("#666677"))
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setPadding(0, (10 * dp1).toInt(), 0, 0)
                }
                loginCard.addView(romHashView)

                container.addView(loginCard)
            } else if (!isSessionActive && isLoggedIn) {
                // Sessão salva autenticando em segundo plano
                val connectingCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1C1E2A"))
                        cornerRadius = 12 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#383B52"))
                    }
                    setPadding((16 * dp1).toInt(), (16 * dp1).toInt(), (16 * dp1).toInt(), (16 * dp1).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (12 * dp1).toInt()
                    }
                }

                val txtConnecting = TextView(context).apply {
                    text = "🔄 Autenticando sessão com o RetroAchievements...\nAguarde alguns instantes ou faça login novamente."
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, (6 * dp1).toInt(), 0, (12 * dp1).toInt())
                }
                connectingCard.addView(txtConnecting)

                val btnRelogin = Button(context).apply {
                    text = "Trocar de Conta / Novo Login"
                    setTextColor(Color.parseColor("#FFAAAA"))
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (36 * dp1).toInt()
                    )
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#2A1215"))
                        cornerRadius = 6 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#772222"))
                    }
                    setOnClickListener {
                        RetroAchievementsManager.logout()
                        refreshAchievementsTab()
                    }
                }
                connectingCard.addView(btnRelogin)
                container.addView(connectingCard)
            } else {
                // --- 2. CARD DO PERFIL DO USUÁRIO LOGADO ---
                val profileCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1C1E2A"))
                        cornerRadius = 12 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#D4AF37")) // Gold
                    }
                    setPadding((14 * dp1).toInt(), (12 * dp1).toInt(), (14 * dp1).toInt(), (12 * dp1).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (10 * dp1).toInt()
                    }
                }

                val rowUser = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val txtAvatar = TextView(context).apply {
                    text = "👤"
                    textSize = 24f
                    setPadding(0, 0, (10 * dp1).toInt(), 0)
                }
                rowUser.addView(txtAvatar)

                val userCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }

                val username = userSummary?.username ?: RetroAchievementsManager.getUsername()
                val score = userSummary?.score ?: 0
                val txtUsername = TextView(context).apply {
                    text = username
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                }
                userCol.addView(txtUsername)

                val txtScore = TextView(context).apply {
                    text = "Pontuação: $score pontos"
                    setTextColor(Color.parseColor("#FFD700"))
                    textSize = 12f
                }
                userCol.addView(txtScore)
                rowUser.addView(userCol)

                val btnLogout = Button(context).apply {
                    text = "Sair"
                    setTextColor(Color.parseColor("#FF8888"))
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams((65 * dp1).toInt(), (32 * dp1).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#2A1215"))
                        cornerRadius = 6 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#772222"))
                    }
                    setOnClickListener {
                        RetroAchievementsManager.logout()
                        showStatus("Desconectado do RetroAchievements")
                        refreshAchievementsTab()
                    }
                }
                rowUser.addView(btnLogout)
                profileCard.addView(rowUser)

                // Hardcore Mode Switch Row
                val rowHardcore = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (10 * dp1).toInt(), 0, 0)
                }
                val hcCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                val txtHcTitle = TextView(context).apply {
                    text = "Modo Hardcore (Rankings Oficiais)"
                    setTextColor(Color.parseColor("#EEEEEE"))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                }
                hcCol.addView(txtHcTitle)
                val txtHcSub = TextView(context).apply {
                    text = "Desativa Save/Load States conforme regras oficiais"
                    setTextColor(Color.parseColor("#888899"))
                    textSize = 10f
                }
                hcCol.addView(txtHcSub)
                rowHardcore.addView(hcCol)

                val swHardcore = Switch(context).apply {
                    isChecked = RetroAchievementsManager.isHardcore()
                    setOnCheckedChangeListener { _, isChecked ->
                        RetroAchievementsManager.setHardcore(isChecked)
                        showStatus(if (isChecked) "Modo Hardcore ATIVADO (Save States bloqueados)" else "Modo Softcore ATIVADO")
                    }
                }
                rowHardcore.addView(swHardcore)
                profileCard.addView(rowHardcore)

                container.addView(profileCard)

                // --- 3. LISTA DE CONQUISTAS ---
                val achievements = RetroAchievementsManager.getAchievements()
                val unlockedCount = achievements.count { it.unlocked }
                val totalCount = achievements.size
                val gameTitle = RetroAchievementsManager.getGameTitle().ifEmpty { "Castlevania NES" }

                val rowHeader = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (6 * dp1).toInt(), 0, (8 * dp1).toInt())
                }

                val txtListHeader = TextView(context).apply {
                    text = if (totalCount > 0) "🏆 $gameTitle ($unlockedCount / $totalCount)" else "🏆 $gameTitle"
                    setTextColor(Color.parseColor("#D4AF37"))
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                rowHeader.addView(txtListHeader)

                val btnSync = Button(context).apply {
                    text = if (RetroAchievementsManager.isGameLoading()) "..." else "🔄 Sincronizar"
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        (100 * dp1).toInt(),
                        (30 * dp1).toInt()
                    )
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1B2A1E"))
                        cornerRadius = 6 * dp1
                        setStroke((1 * dp1).toInt(), Color.parseColor("#388E3C"))
                    }
                    setOnClickListener {
                        isEnabled = false
                        text = "..."
                        RetroAchievementsManager.loadGame(force = true)
                    }
                }
                rowHeader.addView(btnSync)
                container.addView(rowHeader)

                if (achievements.isEmpty()) {
                    val txtEmpty = TextView(context).apply {
                        text = if (RetroAchievementsManager.isGameLoading()) {
                            "Carregando conquistas do Castlevania no servidor...\nAguarde alguns instantes."
                        } else {
                            "Nenhuma conquista carregada ainda para este jogo.\nToque no botão abaixo para carregar as conquistas de Castlevania NES."
                        }
                        setTextColor(Color.parseColor("#AAAAAA"))
                        textSize = 12f
                        setPadding((8 * dp1).toInt(), (16 * dp1).toInt(), (8 * dp1).toInt(), (16 * dp1).toInt())
                        gravity = Gravity.CENTER
                    }
                    container.addView(txtEmpty)

                    val btnLoad = Button(context).apply {
                        text = if (RetroAchievementsManager.isGameLoading()) "CARREGANDO..." else "CARREGAR CONQUISTAS DE CASTLEVANIA"
                        isEnabled = !RetroAchievementsManager.isGameLoading()
                        setTextColor(Color.WHITE)
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (42 * dp1).toInt()
                        ).apply {
                            topMargin = (6 * dp1).toInt()
                            bottomMargin = (14 * dp1).toInt()
                        }
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#2E7D32")) // Green
                            cornerRadius = 8 * dp1
                            setStroke((1 * dp1).toInt(), Color.parseColor("#81C784"))
                        }
                        setOnClickListener {
                            isEnabled = false
                            text = "CARREGANDO..."
                            RetroAchievementsManager.loadGame(force = true)
                        }
                    }
                    container.addView(btnLoad)
                } else {
                    for (ach in achievements) {
                        val achCard = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            val isUnl = ach.unlocked
                            background = GradientDrawable().apply {
                                setColor(if (isUnl) Color.parseColor("#1B261D") else Color.parseColor("#161722"))
                                cornerRadius = 8 * dp1
                                setStroke((1 * dp1).toInt(), if (isUnl) Color.parseColor("#388E3C") else Color.parseColor("#292A3A"))
                            }
                            setPadding((10 * dp1).toInt(), (8 * dp1).toInt(), (10 * dp1).toInt(), (8 * dp1).toInt())
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = (6 * dp1).toInt()
                            }
                        }

                        val txtIcon = TextView(context).apply {
                            text = if (ach.unlocked) "🏆" else "🔒"
                            textSize = 20f
                            setPadding(0, 0, (10 * dp1).toInt(), 0)
                        }
                        achCard.addView(txtIcon)

                        val infoCol = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                        }

                        val txtTitle = TextView(context).apply {
                            text = ach.title
                            setTextColor(if (ach.unlocked) Color.parseColor("#FFD700") else Color.WHITE)
                            textSize = 13f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        infoCol.addView(txtTitle)

                        val txtDesc = TextView(context).apply {
                            text = ach.description
                            setTextColor(Color.parseColor("#AAAAAA"))
                            textSize = 11f
                        }
                        infoCol.addView(txtDesc)
                        achCard.addView(infoCol)

                        val txtPoints = TextView(context).apply {
                            text = "${ach.points} pts"
                            setTextColor(if (ach.unlocked) Color.parseColor("#81C784") else Color.parseColor("#888899"))
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding((6 * dp1).toInt(), 0, 0, 0)
                        }
                        achCard.addView(txtPoints)

                        container.addView(achCard)
                    }
                }
            }
        }

        refreshAchievementsAction = {
            post { refreshAchievementsTab() }
        }

        RetroAchievementsManager.onGameLoadedUiListener = { success, msg ->
            if (success) {
                showStatus("Conquistas sincronizadas com sucesso!")
            } else {
                showStatus("Erro ao sincronizar: $msg", true)
            }
            refreshAchievementsAction?.invoke()
        }

        refreshAchievementsTab()
        return scroll
    }

    fun showMenu() {
        if (currentTab == 5) {
            refreshAchievementsAction?.invoke()
        }
        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 0.95f
        scaleY = 0.95f
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(160)
            .setListener(null)
            .start()
    }

    fun hideMenu() {
        animate()
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(130)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                    onResumeGameListener?.invoke()
                }
            })
            .start()
    }

    fun updateActiveDisplayStats(displayHz: Float, renderFps: Float) {
        val hzInt = displayHz.toInt()
        val fpsInt = renderFps.toInt()
        detectedDisplayHzText?.text = "ℹ️ Painel detectado: ${hzInt} Hz | Motor gráfico: ~${fpsInt} FPS"
        val is120 = prefs.getFloat("opt_refresh_rate", 120.0f) >= 110.0f
        if (is120) {
            refreshRateBadge?.text = "${hzInt} HZ ATIVO"
            videoFpsBadge?.text = "⚡ ${hzInt} HZ • 60 FPS NES"
        } else {
            refreshRateBadge?.text = "60 HZ ATIVO"
            videoFpsBadge?.text = "60 FPS NATIVO"
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Intercept all touches while settings menu is visible
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            soundPreviewPlayer?.stop()
            soundPreviewPlayer?.release()
            soundPreviewPlayer = null
        } catch (_: Exception) {}
    }
}
