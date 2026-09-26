package com.castlevania.nes

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ControllerEditorBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onSizeDecreaseListener: (() -> Unit)? = null
    var onSizeIncreaseListener: (() -> Unit)? = null
    var onToggleGridListener: (() -> Unit)? = null
    var onResetDefaultsListener: (() -> Unit)? = null
    var onSaveListener: (() -> Unit)? = null
    var onCancelListener: (() -> Unit)? = null

    private val txtTitle: TextView
    private val txtStatus: TextView
    private val btnGrid: Button
    private val btnSizeMinus: Button
    private val btnSizePlus: Button

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val dp1 = resources.displayMetrics.density

        // Dark gothic translucent background with crimson bottom border
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F010121C"))
            setStroke((2 * dp1).toInt(), Color.parseColor("#8B0000"))
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 16 * dp1, 16 * dp1, 16 * dp1, 16 * dp1)
        }
        setPadding((16 * dp1).toInt(), (6 * dp1).toInt(), (16 * dp1).toInt(), (8 * dp1).toInt())

        // 1. Title & Selected element info
        val infoCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f)
        }

        txtTitle = TextView(context).apply {
            text = "📐 MODO DE EDIÇÃO DO CONTROLE"
            setTextColor(Color.parseColor("#D4AF37")) // Gold
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        infoCol.addView(txtTitle)

        txtStatus = TextView(context).apply {
            text = "Toque em um botão para mover ou redimensionar"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT
        }
        infoCol.addView(txtStatus)
        addView(infoCol)

        // 2. Size Controls (-) and (+)
        val sizeGroup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (12 * dp1).toInt()
            }
        }

        val txtSizeLabel = TextView(context).apply {
            text = "Tamanho: "
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 11.5f
            setPadding(0, 0, (4 * dp1).toInt(), 0)
        }
        sizeGroup.addView(txtSizeLabel)

        btnSizeMinus = Button(context).apply {
            text = "➖"
            textSize = 12f
            val btnSize = (32 * dp1).toInt()
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                marginEnd = (4 * dp1).toInt()
            }
            background = createRoundButtonBg(dp1, "#262838", "#444455")
            setPadding(0, 0, 0, 0)
            setOnClickListener { onSizeDecreaseListener?.invoke() }
        }
        sizeGroup.addView(btnSizeMinus)

        btnSizePlus = Button(context).apply {
            text = "➕"
            textSize = 12f
            val btnSize = (32 * dp1).toInt()
            layoutParams = LayoutParams(btnSize, btnSize)
            background = createRoundButtonBg(dp1, "#262838", "#444455")
            setPadding(0, 0, 0, 0)
            setOnClickListener { onSizeIncreaseListener?.invoke() }
        }
        sizeGroup.addView(btnSizePlus)
        addView(sizeGroup)

        // 3. Grid Snap Toggle
        btnGrid = Button(context).apply {
            text = "🧲 Grade: ON"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (32 * dp1).toInt()).apply {
                marginEnd = (12 * dp1).toInt()
            }
            background = createRoundButtonBg(dp1, "#1B5E20", "#2E7D32")
            setPadding((10 * dp1).toInt(), 0, (10 * dp1).toInt(), 0)
            setOnClickListener { onToggleGridListener?.invoke() }
        }
        addView(btnGrid)

        // 4. Reset Defaults Button
        val btnReset = Button(context).apply {
            text = "🔄 Padrão"
            setTextColor(Color.parseColor("#FFCDD2"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (32 * dp1).toInt()).apply {
                marginEnd = (12 * dp1).toInt()
            }
            background = createRoundButtonBg(dp1, "#3E1C1F", "#7F2B32")
            setPadding((10 * dp1).toInt(), 0, (10 * dp1).toInt(), 0)
            setOnClickListener { onResetDefaultsListener?.invoke() }
        }
        addView(btnReset)

        // 5. Save Button
        val btnSave = Button(context).apply {
            text = "💾 SALVAR"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (32 * dp1).toInt()).apply {
                marginEnd = (10 * dp1).toInt()
            }
            background = createRoundButtonBg(dp1, "#9B111E", "#D4AF37")
            setPadding((14 * dp1).toInt(), 0, (14 * dp1).toInt(), 0)
            setOnClickListener { onSaveListener?.invoke() }
        }
        addView(btnSave)

        // 6. Cancel Button
        val btnCancel = Button(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            val btnSize = (32 * dp1).toInt()
            layoutParams = LayoutParams(btnSize, btnSize)
            background = createRoundButtonBg(dp1, "#262838", "#444455")
            setPadding(0, 0, 0, 0)
            setOnClickListener { onCancelListener?.invoke() }
        }
        addView(btnCancel)
    }

    fun updateSelectedControl(name: String, scalePercent: Int, isGridOn: Boolean) {
        txtStatus.text = "$name • Tamanho: $scalePercent%"
        updateGridStatus(isGridOn)
    }

    fun updateGridStatus(isGridOn: Boolean) {
        val dp1 = resources.displayMetrics.density
        btnGrid.text = if (isGridOn) "🧲 Grade: ON" else "🧲 Grade: OFF"
        btnGrid.background = if (isGridOn) {
            createRoundButtonBg(dp1, "#1B5E20", "#2E7D32")
        } else {
            createRoundButtonBg(dp1, "#33333E", "#555566")
        }
    }

    private fun createRoundButtonBg(dp1: Float, bgColor: String, strokeColor: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgColor))
            cornerRadius = 8 * dp1
            setStroke((1 * dp1).toInt(), Color.parseColor(strokeColor))
        }
    }
}
