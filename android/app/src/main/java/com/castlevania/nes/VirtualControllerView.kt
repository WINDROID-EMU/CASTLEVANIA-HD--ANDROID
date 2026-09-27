package com.castlevania.nes

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

enum class EditableControl {
    NONE,
    ANALOG,
    BTN_B,
    BTN_A,
    BTN_ITEM,
    MENU_START
}

class VirtualControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val DEFAULT_ANALOG_SCALE = 0.6f
        const val DEFAULT_ANALOG_X_RATIO = 0.14385353f
        const val DEFAULT_ANALOG_Y_RATIO = 0.712963f

        const val DEFAULT_BTN_B_SCALE = 0.6f
        const val DEFAULT_BTN_B_X_RATIO = 0.79119444f
        const val DEFAULT_BTN_B_Y_RATIO = 0.8657407f

        const val DEFAULT_BTN_A_SCALE = 0.6f
        const val DEFAULT_BTN_A_X_RATIO = 0.9350479f
        const val DEFAULT_BTN_A_Y_RATIO = 0.8657407f

        const val DEFAULT_BTN_ITEM_SCALE = 0.6f
        const val DEFAULT_BTN_ITEM_X_RATIO = 0.8631212f
        const val DEFAULT_BTN_ITEM_Y_RATIO = 0.712963f

        const val DEFAULT_SEL_START_SCALE = 0.8f
        const val DEFAULT_SEL_START_X_RATIO = 0.52746296f
        const val DEFAULT_SEL_START_Y_RATIO = 0.9166667f

        const val DEFAULT_OPACITY_PERCENT = 49
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("castlevania_settings", Context.MODE_PRIVATE)

    // Standard Paints
    private val paintNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 25, 25, 32)
        style = Paint.Style.FILL
    }

    private val paintPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 220, 20, 60) // Crimson Castlevania red
        style = Paint.Style.FILL
    }

    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 220, 220, 220)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val paintBitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Editor Mode Paints
    private val paintGrid = Paint().apply {
        color = Color.argb(45, 212, 175, 55) // Translucent Gold
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val paintGridMajor = Paint().apply {
        color = Color.argb(90, 212, 175, 55) // Major grid line
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val paintSelection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700") // Gold outline
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }

    private val paintSelectionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 215, 0)
        style = Paint.Style.FILL
    }

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    var onMenuClickListener: (() -> Unit)? = null
    var isVibrationEnabled: Boolean = true

    var controllerOpacity: Float = DEFAULT_OPACITY_PERCENT / 100.0f
        set(value) {
            field = value.coerceIn(0.1f, 1.0f)
            paintNormal.color = Color.argb((200 * field).toInt(), 25, 25, 32)
            paintPressed.color = Color.argb((220 * field).toInt(), 220, 20, 60)
            paintBorder.color = Color.argb((200 * field).toInt(), 220, 220, 220)
            paintText.color = Color.argb((255 * field).toInt(), 255, 255, 255)
            paintBitmap.alpha = (255 * field).toInt()
            invalidate()
        }

    private var currentButtonsMask = 0

    // =========================================================================
    // EDIT MODE & GRID SNAP STATE
    // =========================================================================
    var isEditMode: Boolean = false
    var isGridSnapEnabled: Boolean = true
    var selectedControl: EditableControl = EditableControl.ANALOG
    var onSelectedControlChanged: ((control: EditableControl, scale: Float) -> Unit)? = null

    private var gridStepPx: Float = 48f
    private var activeEditPointerId = -1
    private var touchDragOffsetX = 0f
    private var touchDragOffsetY = 0f
    private var initialPinchDistance = 0f
    private var initialPinchScale = 1.0f

    // Scale factors (0.6f .. 1.8f)
    var analogScale: Float = DEFAULT_ANALOG_SCALE
    var btnBScale: Float = DEFAULT_BTN_B_SCALE
    var btnAScale: Float = DEFAULT_BTN_A_SCALE
    var btnItemScale: Float = DEFAULT_BTN_ITEM_SCALE
    var selStartScale: Float = DEFAULT_SEL_START_SCALE

    // Base Reference Dimensions (before scaling)
    private var baseSizeRef = 0f
    private var baseAnalogRadius = 0f
    private var baseBtnRadius = 0f
    private var baseSelStartW = 0f
    private var baseSelStartH = 0f

    // Actual Scaled & Positioned Dimensions
    // 1. Analog Stick
    private var analogCenterX = 0f
    private var analogCenterY = 0f
    private var analogRadius = 0f
    private var knobRadius = 0f
    private var maxKnobTravel = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var analogPointerId = -1

    private val rectAnalogBase = RectF()
    private val rectAnalogKnob = RectF()
    private var bitmapAnalogBase: Bitmap? = null
    private var bitmapAnalogKnob: Bitmap? = null

    // 2. Action Buttons (B: Attack, A: Jump)
    private var btnBX = 0f
    private var btnBY = 0f
    private var btnAX = 0f
    private var btnAY = 0f
    private var btnRadiusB = 0f
    private var btnRadiusA = 0f
    private val rectBtnA = RectF()
    private val rectBtnB = RectF()
    private var bitmapJump: Bitmap? = null
    private var bitmapAttack: Bitmap? = null

    // 3. Sub-Weapon / Item Button
    private var btnItemX = 0f
    private var btnItemY = 0f
    private var btnRadiusItem = 0f
    private val rectBtnItem = RectF()
    private var bitmapItemNone: Bitmap? = null
    private var bitmapItemDagger: Bitmap? = null
    private var bitmapItemAxe: Bitmap? = null
    private var bitmapItemHolyWater: Bitmap? = null
    private var bitmapItemCross: Bitmap? = null
    private var bitmapItemStopwatch: Bitmap? = null
    private var currentSubweaponId: Int = -1

    // 4. MENU & START
    private var selStartCenterX = 0f
    private var selStartCenterY = 0f
    private val rectSelect = RectF()
    private val rectStart = RectF()
    private val rectSelStartBounds = RectF()

    // Backup coordinates for discardChanges
    private var backupAnalogX = 0f
    private var backupAnalogY = 0f
    private var backupAnalogScale = 1.0f
    private var backupBtnBX = 0f
    private var backupBtnBY = 0f
    private var backupBtnBScale = 1.0f
    private var backupBtnAX = 0f
    private var backupBtnAY = 0f
    private var backupBtnAScale = 1.0f
    private var backupBtnItemX = 0f
    private var backupBtnItemY = 0f
    private var backupBtnItemScale = 1.0f
    private var backupSelStartX = 0f
    private var backupSelStartY = 0f
    private var backupSelStartScale = 1.0f

    private val subweaponPollRunnable = object : Runnable {
        override fun run() {
            val sw = try {
                NativeBridge.nativeGetCurrentSubweapon()
            } catch (e: Throwable) {
                0
            }
            if (sw != currentSubweaponId) {
                currentSubweaponId = sw
                invalidate()
            }
            postDelayed(this, 100)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(subweaponPollRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(subweaponPollRunnable)
    }

    private fun getCurrentSubweaponBitmap(): Bitmap? {
        val sw = try {
            NativeBridge.nativeGetCurrentSubweapon()
        } catch (e: Throwable) {
            currentSubweaponId
        }
        currentSubweaponId = sw
        return when (sw) {
            0x08 -> bitmapItemDagger ?: bitmapItemNone
            0x09 -> bitmapItemCross ?: bitmapItemNone
            0x0A, 0x0D -> bitmapItemAxe ?: bitmapItemNone
            0x0B -> bitmapItemHolyWater ?: bitmapItemNone
            0x0C, 0x0E -> bitmapItemStopwatch ?: bitmapItemNone
            else -> if (sw > 0) bitmapItemStopwatch ?: bitmapItemNone else bitmapItemNone
        }
    }

    init {
        try {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            bitmapJump = BitmapFactory.decodeResource(resources, R.drawable.btn_jump, opts)
            bitmapAttack = BitmapFactory.decodeResource(resources, R.drawable.btn_attack, opts)
            bitmapAnalogBase = BitmapFactory.decodeResource(resources, R.drawable.analog_base, opts)
            bitmapAnalogKnob = BitmapFactory.decodeResource(resources, R.drawable.analog_knob, opts)

            bitmapItemNone = BitmapFactory.decodeResource(resources, R.drawable.btn_item_none, opts)
            bitmapItemDagger = BitmapFactory.decodeResource(resources, R.drawable.btn_item_dagger, opts)
            bitmapItemAxe = BitmapFactory.decodeResource(resources, R.drawable.btn_item_axe, opts)
            bitmapItemHolyWater = BitmapFactory.decodeResource(resources, R.drawable.btn_item_holywater, opts)
            bitmapItemCross = BitmapFactory.decodeResource(resources, R.drawable.btn_item_cross, opts)
            bitmapItemStopwatch = BitmapFactory.decodeResource(resources, R.drawable.btn_item_stopwatch, opts)

            post(subweaponPollRunnable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val dp1 = resources.displayMetrics.density
        gridStepPx = 20 * dp1 // ~52px grid snap step

        baseSizeRef = Math.min(w, h).toFloat()
        baseAnalogRadius = baseSizeRef * 0.22f
        baseBtnRadius = baseSizeRef * 0.14f
        baseSelStartW = baseSizeRef * 0.18f
        baseSelStartH = baseSizeRef * 0.07f

        loadLayout(w, h)
        updateAllBounds()
        android.util.Log.d("VCView", "Layout sizes w=$w h=$h: Analog=($analogCenterX,$analogCenterY,r=$analogRadius), BtnB=($btnBX,$btnBY,r=$btnRadiusB), BtnA=($btnAX,$btnAY,r=$btnRadiusA), SelStart=($selStartCenterX,$selStartCenterY)")
    }

    fun loadLayout(w: Int = width, h: Int = height) {
        if (w <= 0 || h <= 0) return
        val hasCustom = prefs.getBoolean("ctrl_custom_layout", false)

        if (hasCustom) {
            analogScale = prefs.getFloat("ctrl_analog_scale", DEFAULT_ANALOG_SCALE).coerceIn(0.6f, 1.8f)
            btnBScale = prefs.getFloat("ctrl_btn_b_scale", DEFAULT_BTN_B_SCALE).coerceIn(0.6f, 1.8f)
            btnAScale = prefs.getFloat("ctrl_btn_a_scale", DEFAULT_BTN_A_SCALE).coerceIn(0.6f, 1.8f)
            btnItemScale = prefs.getFloat("ctrl_btn_item_scale", DEFAULT_BTN_ITEM_SCALE).coerceIn(0.6f, 1.8f)
            selStartScale = prefs.getFloat("ctrl_sel_start_scale", DEFAULT_SEL_START_SCALE).coerceIn(0.6f, 1.8f)

            analogCenterX = prefs.getFloat("ctrl_analog_x_ratio", DEFAULT_ANALOG_X_RATIO) * w
            analogCenterY = prefs.getFloat("ctrl_analog_y_ratio", DEFAULT_ANALOG_Y_RATIO) * h

            btnBX = prefs.getFloat("ctrl_btn_b_x_ratio", DEFAULT_BTN_B_X_RATIO) * w
            btnBY = prefs.getFloat("ctrl_btn_b_y_ratio", DEFAULT_BTN_B_Y_RATIO) * h

            btnAX = prefs.getFloat("ctrl_btn_a_x_ratio", DEFAULT_BTN_A_X_RATIO) * w
            btnAY = prefs.getFloat("ctrl_btn_a_y_ratio", DEFAULT_BTN_A_Y_RATIO) * h

            btnItemX = prefs.getFloat("ctrl_btn_item_x_ratio", DEFAULT_BTN_ITEM_X_RATIO) * w
            btnItemY = prefs.getFloat("ctrl_btn_item_y_ratio", DEFAULT_BTN_ITEM_Y_RATIO) * h

            selStartCenterX = prefs.getFloat("ctrl_sel_start_x_ratio", DEFAULT_SEL_START_X_RATIO) * w
            selStartCenterY = prefs.getFloat("ctrl_sel_start_y_ratio", DEFAULT_SEL_START_Y_RATIO) * h
        } else {
            resetToDefaultPositions(w, h)
        }
        updateAllBounds()
    }

    private fun resetToDefaultPositions(w: Int = width, h: Int = height) {
        analogScale = DEFAULT_ANALOG_SCALE
        btnBScale = DEFAULT_BTN_B_SCALE
        btnAScale = DEFAULT_BTN_A_SCALE
        btnItemScale = DEFAULT_BTN_ITEM_SCALE
        selStartScale = DEFAULT_SEL_START_SCALE

        analogRadius = baseAnalogRadius * analogScale
        analogCenterX = DEFAULT_ANALOG_X_RATIO * w
        analogCenterY = DEFAULT_ANALOG_Y_RATIO * h

        btnRadiusA = baseBtnRadius * btnAScale
        btnAX = DEFAULT_BTN_A_X_RATIO * w
        btnAY = DEFAULT_BTN_A_Y_RATIO * h

        btnRadiusB = baseBtnRadius * btnBScale
        btnBX = DEFAULT_BTN_B_X_RATIO * w
        btnBY = DEFAULT_BTN_B_Y_RATIO * h

        btnRadiusItem = baseBtnRadius * btnItemScale
        btnItemX = DEFAULT_BTN_ITEM_X_RATIO * w
        btnItemY = DEFAULT_BTN_ITEM_Y_RATIO * h

        selStartCenterX = DEFAULT_SEL_START_X_RATIO * w
        selStartCenterY = DEFAULT_SEL_START_Y_RATIO * h
    }

    private fun updateAllBounds() {
        if (width <= 0 || height <= 0) return

        // 1. Analog
        analogRadius = baseAnalogRadius * analogScale
        knobRadius = analogRadius * 0.54f
        maxKnobTravel = analogRadius * 0.58f
        if (!isEditMode && analogPointerId == -1) {
            knobX = analogCenterX
            knobY = analogCenterY
        }
        rectAnalogBase.set(
            analogCenterX - analogRadius,
            analogCenterY - analogRadius,
            analogCenterX + analogRadius,
            analogCenterY + analogRadius
        )

        // 2. Button B (Attack)
        btnRadiusB = baseBtnRadius * btnBScale
        rectBtnB.set(
            btnBX - btnRadiusB,
            btnBY - btnRadiusB,
            btnBX + btnRadiusB,
            btnBY + btnRadiusB
        )

        // 3. Button A (Jump)
        btnRadiusA = baseBtnRadius * btnAScale
        rectBtnA.set(
            btnAX - btnRadiusA,
            btnAY - btnRadiusA,
            btnAX + btnRadiusA,
            btnAY + btnRadiusA
        )

        // 4. Button Item (Sub-weapon)
        btnRadiusItem = baseBtnRadius * btnItemScale
        rectBtnItem.set(
            btnItemX - btnRadiusItem,
            btnItemY - btnRadiusItem,
            btnItemX + btnRadiusItem,
            btnItemY + btnRadiusItem
        )

        // 5. MENU & START Cluster
        val wBtn = baseSelStartW * selStartScale
        val hBtn = baseSelStartH * selStartScale
        val gap = 14f * resources.displayMetrics.density * selStartScale

        rectSelect.set(
            selStartCenterX - wBtn - gap / 2f,
            selStartCenterY - hBtn / 2f,
            selStartCenterX - gap / 2f,
            selStartCenterY + hBtn / 2f
        )
        rectStart.set(
            selStartCenterX + gap / 2f,
            selStartCenterY - hBtn / 2f,
            selStartCenterX + wBtn + gap / 2f,
            selStartCenterY + hBtn / 2f
        )
        rectSelStartBounds.set(
            rectSelect.left - 8f,
            rectSelect.top - 8f,
            rectStart.right + 8f,
            rectStart.bottom + 8f
        )
    }

    fun saveLayout() {
        if (width <= 0 || height <= 0) return
        prefs.edit()
            .putBoolean("ctrl_custom_layout", true)
            .putFloat("ctrl_analog_x_ratio", analogCenterX / width)
            .putFloat("ctrl_analog_y_ratio", analogCenterY / height)
            .putFloat("ctrl_analog_scale", analogScale)
            .putFloat("ctrl_btn_b_x_ratio", btnBX / width)
            .putFloat("ctrl_btn_b_y_ratio", btnBY / height)
            .putFloat("ctrl_btn_b_scale", btnBScale)
            .putFloat("ctrl_btn_a_x_ratio", btnAX / width)
            .putFloat("ctrl_btn_a_y_ratio", btnAY / height)
            .putFloat("ctrl_btn_a_scale", btnAScale)
            .putFloat("ctrl_btn_item_x_ratio", btnItemX / width)
            .putFloat("ctrl_btn_item_y_ratio", btnItemY / height)
            .putFloat("ctrl_btn_item_scale", btnItemScale)
            .putFloat("ctrl_sel_start_x_ratio", selStartCenterX / width)
            .putFloat("ctrl_sel_start_y_ratio", selStartCenterY / height)
            .putFloat("ctrl_sel_start_scale", selStartScale)
            .putBoolean("ctrl_grid_snap", isGridSnapEnabled)
            .apply()
    }

    fun resetLayout() {
        prefs.edit().putBoolean("ctrl_custom_layout", false).apply()
        resetToDefaultPositions()
        updateAllBounds()
        invalidate()
        notifyControlChanged()
    }

    fun discardChanges() {
        analogCenterX = backupAnalogX
        analogCenterY = backupAnalogY
        analogScale = backupAnalogScale

        btnBX = backupBtnBX
        btnBY = backupBtnBY
        btnBScale = backupBtnBScale

        btnAX = backupBtnAX
        btnAY = backupBtnAY
        btnAScale = backupBtnAScale

        btnItemX = backupBtnItemX
        btnItemY = backupBtnItemY
        btnItemScale = backupBtnItemScale

        selStartCenterX = backupSelStartX
        selStartCenterY = backupSelStartY
        selStartScale = backupSelStartScale

        updateAllBounds()
        invalidate()
    }

    fun startEditMode() {
        isEditMode = true
        // Store backup values in case user cancels
        backupAnalogX = analogCenterX
        backupAnalogY = analogCenterY
        backupAnalogScale = analogScale
        backupBtnBX = btnBX
        backupBtnBY = btnBY
        backupBtnBScale = btnBScale
        backupBtnAX = btnAX
        backupBtnAY = btnAY
        backupBtnAScale = btnAScale
        backupBtnItemX = btnItemX
        backupBtnItemY = btnItemY
        backupBtnItemScale = btnItemScale
        backupSelStartX = selStartCenterX
        backupSelStartY = selStartCenterY
        backupSelStartScale = selStartScale

        selectedControl = EditableControl.ANALOG
        notifyControlChanged()
        invalidate()
    }

    fun exitEditMode() {
        isEditMode = false
        activeEditPointerId = -1
        currentButtonsMask = 0
        NativeBridge.nativeSetInput(0)
        invalidate()
    }

    fun changeSelectedControlScale(delta: Float) {
        when (selectedControl) {
            EditableControl.ANALOG -> {
                analogScale = (analogScale + delta).coerceIn(0.6f, 1.8f)
            }
            EditableControl.BTN_B -> {
                btnBScale = (btnBScale + delta).coerceIn(0.6f, 1.8f)
            }
            EditableControl.BTN_A -> {
                btnAScale = (btnAScale + delta).coerceIn(0.6f, 1.8f)
            }
            EditableControl.BTN_ITEM -> {
                btnItemScale = (btnItemScale + delta).coerceIn(0.6f, 1.8f)
            }
            EditableControl.MENU_START -> {
                selStartScale = (selStartScale + delta).coerceIn(0.6f, 1.8f)
            }
            EditableControl.NONE -> {}
        }
        updateAllBounds()
        invalidate()
        notifyControlChanged()
    }

    fun notifyControlChanged() {
        val currentScale = when (selectedControl) {
            EditableControl.ANALOG -> analogScale
            EditableControl.BTN_B -> btnBScale
            EditableControl.BTN_A -> btnAScale
            EditableControl.BTN_ITEM -> btnItemScale
            EditableControl.MENU_START -> selStartScale
            EditableControl.NONE -> 1.0f
        }
        onSelectedControlChanged?.invoke(selectedControl, currentScale)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw Grid when in Edit Mode
        if (isEditMode) {
            drawGrid(canvas)
        }

        val mask = currentButtonsMask

        // 2. Draw Analog Stick
        drawAnalogStick(canvas)

        // 3. Draw Action Buttons (B: Attack, A: Jump)
        drawActionButtons(canvas, mask)

        // 4. Draw Select & Start
        drawSelectStart(canvas, mask)

        // 5. Draw Selection Highlights when in Edit Mode
        if (isEditMode) {
            drawSelectionHighlight(canvas)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val step = gridStepPx
        val w = width.toFloat()
        val h = height.toFloat()

        var count = 0
        var x = 0f
        while (x <= w) {
            val isMajor = (count % 4 == 0)
            canvas.drawLine(x, 0f, x, h, if (isMajor) paintGridMajor else paintGrid)
            x += step
            count++
        }

        count = 0
        var y = 0f
        while (y <= h) {
            val isMajor = (count % 4 == 0)
            canvas.drawLine(0f, y, w, y, if (isMajor) paintGridMajor else paintGrid)
            y += step
            count++
        }
    }

    private fun drawSelectionHighlight(canvas: Canvas) {
        val dp1 = resources.displayMetrics.density
        when (selectedControl) {
            EditableControl.ANALOG -> {
                val pad = 12f * dp1
                canvas.drawCircle(analogCenterX, analogCenterY, analogRadius + pad, paintSelectionFill)
                canvas.drawCircle(analogCenterX, analogCenterY, analogRadius + pad, paintSelection)
            }
            EditableControl.BTN_B -> {
                val pad = 10f * dp1
                canvas.drawCircle(btnBX, btnBY, btnRadiusB + pad, paintSelectionFill)
                canvas.drawCircle(btnBX, btnBY, btnRadiusB + pad, paintSelection)
            }
            EditableControl.BTN_A -> {
                val pad = 10f * dp1
                canvas.drawCircle(btnAX, btnAY, btnRadiusA + pad, paintSelectionFill)
                canvas.drawCircle(btnAX, btnAY, btnRadiusA + pad, paintSelection)
            }
            EditableControl.BTN_ITEM -> {
                val pad = 10f * dp1
                canvas.drawCircle(btnItemX, btnItemY, btnRadiusItem + pad, paintSelectionFill)
                canvas.drawCircle(btnItemX, btnItemY, btnRadiusItem + pad, paintSelection)
            }
            EditableControl.MENU_START -> {
                val pad = 10f * dp1
                val r = RectF(
                    rectSelStartBounds.left - pad,
                    rectSelStartBounds.top - pad,
                    rectSelStartBounds.right + pad,
                    rectSelStartBounds.bottom + pad
                )
                canvas.drawRoundRect(r, 14f * dp1, 14f * dp1, paintSelectionFill)
                canvas.drawRoundRect(r, 14f * dp1, 14f * dp1, paintSelection)
            }
            EditableControl.NONE -> {}
        }
    }

    private fun drawAnalogStick(canvas: Canvas) {
        val bmpBase = bitmapAnalogBase
        if (bmpBase != null) {
            canvas.drawBitmap(bmpBase, null, rectAnalogBase, paintBitmap)
        } else {
            canvas.drawCircle(analogCenterX, analogCenterY, analogRadius, paintNormal)
            canvas.drawCircle(analogCenterX, analogCenterY, analogRadius, paintBorder)
        }

        // Draw movable knob
        val bmpKnob = bitmapAnalogKnob
        val currentKnobX = if (isEditMode) analogCenterX else knobX
        val currentKnobY = if (isEditMode) analogCenterY else knobY

        rectAnalogKnob.set(
            currentKnobX - knobRadius,
            currentKnobY - knobRadius,
            currentKnobX + knobRadius,
            currentKnobY + knobRadius
        )
        if (bmpKnob != null) {
            canvas.drawBitmap(bmpKnob, null, rectAnalogKnob, paintBitmap)
        } else {
            canvas.drawCircle(currentKnobX, currentKnobY, knobRadius, paintPressed)
            canvas.drawCircle(currentKnobX, currentKnobY, knobRadius, paintBorder)
        }
    }

    private fun drawActionButtons(canvas: Canvas, mask: Int) {
        // Button B (Attack)
        val bPressed = (mask and NativeBridge.BTN_B) != 0
        val bmpAtt = bitmapAttack
        if (bPressed) {
            canvas.drawCircle(btnBX, btnBY, btnRadiusB * 1.05f, paintPressed)
        } else if (bmpAtt == null) {
            canvas.drawCircle(btnBX, btnBY, btnRadiusB, paintNormal)
        }

        if (bmpAtt != null) {
            if (bPressed) {
                canvas.save()
                canvas.scale(0.92f, 0.92f, btnBX, btnBY)
                canvas.drawBitmap(bmpAtt, null, rectBtnB, paintBitmap)
                canvas.restore()
            } else {
                canvas.drawBitmap(bmpAtt, null, rectBtnB, paintBitmap)
            }
        } else {
            canvas.drawCircle(btnBX, btnBY, btnRadiusB, paintBorder)
            canvas.drawText("B", btnBX, btnBY + 14f, paintText)
        }

        // Button A (Jump)
        val aPressed = (mask and NativeBridge.BTN_A) != 0
        val bmpJmp = bitmapJump
        if (aPressed) {
            canvas.drawCircle(btnAX, btnAY, btnRadiusA * 1.05f, paintPressed)
        } else if (bmpJmp == null) {
            canvas.drawCircle(btnAX, btnAY, btnRadiusA, paintNormal)
        }

        if (bmpJmp != null) {
            if (aPressed) {
                canvas.save()
                canvas.scale(0.92f, 0.92f, btnAX, btnAY)
                canvas.drawBitmap(bmpJmp, null, rectBtnA, paintBitmap)
                canvas.restore()
            } else {
                canvas.drawBitmap(bmpJmp, null, rectBtnA, paintBitmap)
            }
        } else {
            canvas.drawCircle(btnAX, btnAY, btnRadiusA, paintBorder)
            canvas.drawText("A", btnAX, btnAY + 14f, paintText)
        }

        // Button Item (Sub-weapon)
        val itemPressed = (mask and NativeBridge.BTN_ITEM) != 0
        val bmpItem = getCurrentSubweaponBitmap()
        if (itemPressed) {
            canvas.drawCircle(btnItemX, btnItemY, btnRadiusItem * 1.05f, paintPressed)
        } else if (bmpItem == null) {
            canvas.drawCircle(btnItemX, btnItemY, btnRadiusItem, paintNormal)
        }

        if (bmpItem != null) {
            if (itemPressed) {
                canvas.save()
                canvas.scale(0.92f, 0.92f, btnItemX, btnItemY)
                canvas.drawBitmap(bmpItem, null, rectBtnItem, paintBitmap)
                canvas.restore()
            } else {
                canvas.drawBitmap(bmpItem, null, rectBtnItem, paintBitmap)
            }
        } else {
            canvas.drawCircle(btnItemX, btnItemY, btnRadiusItem, paintBorder)
            canvas.drawText("ITEM", btnItemX, btnItemY + 14f, paintText)
        }
    }

    private fun drawSelectStart(canvas: Canvas, mask: Int) {
        val dp1 = resources.displayMetrics.density
        val corner = 10f * dp1 * selStartScale
        val selPressed = (mask and NativeBridge.BTN_MENU) != 0
        canvas.drawRoundRect(rectSelect, corner, corner, if (selPressed) paintPressed else paintNormal)
        canvas.drawRoundRect(rectSelect, corner, corner, paintBorder)
        paintText.textSize = 14f * dp1 * selStartScale
        canvas.drawText("MENU", rectSelect.centerX(), rectSelect.centerY() + 5f * dp1 * selStartScale, paintText)

        val startPressed = (mask and NativeBridge.BTN_START) != 0
        canvas.drawRoundRect(rectStart, corner, corner, if (startPressed) paintPressed else paintNormal)
        canvas.drawRoundRect(rectStart, corner, corner, paintBorder)
        canvas.drawText("START", rectStart.centerX(), rectStart.centerY() + 5f * dp1 * selStartScale, paintText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEditMode) {
            return handleEditTouchEvent(event)
        }
        return handleGameTouchEvent(event)
    }

    // =========================================================================
    // EDIT MODE TOUCH EVENT (DRAG & PINCH RESIZE)
    // =========================================================================
    private fun handleEditTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount
        val action = event.actionMasked

        // 1. Two-Finger Pinch to Resize Selected Element
        if (pointerCount >= 2) {
            val p0x = event.getX(0)
            val p0y = event.getY(0)
            val p1x = event.getX(1)
            val p1y = event.getY(1)
            val dist = Math.hypot((p0x - p1x).toDouble(), (p0y - p1y).toDouble()).toFloat()

            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                initialPinchDistance = dist
                initialPinchScale = when (selectedControl) {
                    EditableControl.ANALOG -> analogScale
                    EditableControl.BTN_B -> btnBScale
                    EditableControl.BTN_A -> btnAScale
                    EditableControl.BTN_ITEM -> btnItemScale
                    EditableControl.MENU_START -> selStartScale
                    EditableControl.NONE -> 1.0f
                }
            } else if (action == MotionEvent.ACTION_MOVE && initialPinchDistance > 10f) {
                val factor = dist / initialPinchDistance
                val newScale = (initialPinchScale * factor).coerceIn(0.6f, 1.8f)
                when (selectedControl) {
                    EditableControl.ANALOG -> analogScale = newScale
                    EditableControl.BTN_B -> btnBScale = newScale
                    EditableControl.BTN_A -> btnAScale = newScale
                    EditableControl.BTN_ITEM -> btnItemScale = newScale
                    EditableControl.MENU_START -> selStartScale = newScale
                    EditableControl.NONE -> {}
                }
                updateAllBounds()
                invalidate()
                notifyControlChanged()
            }
            return true
        }

        // 2. Single Finger Selection & Move with Grid Snap
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y

                val hitControl = findControlAt(x, y)
                if (hitControl != EditableControl.NONE) {
                    selectedControl = hitControl
                    activeEditPointerId = event.getPointerId(0)

                    val center = getControlCenter(hitControl)
                    touchDragOffsetX = center.x - x
                    touchDragOffsetY = center.y - y

                    vibrate(12)
                    notifyControlChanged()
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeEditPointerId != -1 && selectedControl != EditableControl.NONE) {
                    val idx = event.findPointerIndex(activeEditPointerId)
                    if (idx != -1) {
                        val rawX = event.getX(idx) + touchDragOffsetX
                        val rawY = event.getY(idx) + touchDragOffsetY

                        val finalX = if (isGridSnapEnabled) Math.round(rawX / gridStepPx) * gridStepPx else rawX
                        val finalY = if (isGridSnapEnabled) Math.round(rawY / gridStepPx) * gridStepPx else rawY

                        setControlPosition(selectedControl, finalX, finalY)
                        updateAllBounds()
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeEditPointerId = -1
                initialPinchDistance = 0f
            }
        }
        return true
    }

    private fun findControlAt(x: Float, y: Float): EditableControl {
        val dp1 = resources.displayMetrics.density
        // Check Analog
        if (Math.hypot((x - analogCenterX).toDouble(), (y - analogCenterY).toDouble()) <= analogRadius + 20f * dp1) {
            return EditableControl.ANALOG
        }
        // Check Button B
        if (Math.hypot((x - btnBX).toDouble(), (y - btnBY).toDouble()) <= btnRadiusB + 16f * dp1) {
            return EditableControl.BTN_B
        }
        // Check Button A
        if (Math.hypot((x - btnAX).toDouble(), (y - btnAY).toDouble()) <= btnRadiusA + 16f * dp1) {
            return EditableControl.BTN_A
        }
        // Check Button Item
        if (Math.hypot((x - btnItemX).toDouble(), (y - btnItemY).toDouble()) <= btnRadiusItem + 16f * dp1) {
            return EditableControl.BTN_ITEM
        }
        // Check Select & Start
        val selRectPadded = RectF(
            rectSelStartBounds.left - 20f * dp1,
            rectSelStartBounds.top - 20f * dp1,
            rectSelStartBounds.right + 20f * dp1,
            rectSelStartBounds.bottom + 20f * dp1
        )
        if (selRectPadded.contains(x, y)) {
            return EditableControl.MENU_START
        }
        return EditableControl.NONE
    }

    private fun getControlCenter(control: EditableControl): PointF {
        return when (control) {
            EditableControl.ANALOG -> PointF(analogCenterX, analogCenterY)
            EditableControl.BTN_B -> PointF(btnBX, btnBY)
            EditableControl.BTN_A -> PointF(btnAX, btnAY)
            EditableControl.BTN_ITEM -> PointF(btnItemX, btnItemY)
            EditableControl.MENU_START -> PointF(selStartCenterX, selStartCenterY)
            EditableControl.NONE -> PointF(0f, 0f)
        }
    }

    private fun setControlPosition(control: EditableControl, x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dp1 = resources.displayMetrics.density
        val margin = 10f * dp1

        when (control) {
            EditableControl.ANALOG -> {
                analogCenterX = x.coerceIn(analogRadius + margin, w - analogRadius - margin)
                analogCenterY = y.coerceIn(analogRadius + margin, h - analogRadius - margin)
                knobX = analogCenterX
                knobY = analogCenterY
            }
            EditableControl.BTN_B -> {
                btnBX = x.coerceIn(btnRadiusB + margin, w - btnRadiusB - margin)
                btnBY = y.coerceIn(btnRadiusB + margin, h - btnRadiusB - margin)
            }
            EditableControl.BTN_A -> {
                btnAX = x.coerceIn(btnRadiusA + margin, w - btnRadiusA - margin)
                btnAY = y.coerceIn(btnRadiusA + margin, h - btnRadiusA - margin)
            }
            EditableControl.BTN_ITEM -> {
                btnItemX = x.coerceIn(btnRadiusItem + margin, w - btnRadiusItem - margin)
                btnItemY = y.coerceIn(btnRadiusItem + margin, h - btnRadiusItem - margin)
            }
            EditableControl.MENU_START -> {
                val halfW = (rectStart.right - rectSelect.left) / 2f
                val halfH = (rectSelect.height()) / 2f
                selStartCenterX = x.coerceIn(halfW + margin, w - halfW - margin)
                selStartCenterY = y.coerceIn(halfH + margin, h - halfH - margin)
            }
            EditableControl.NONE -> {}
        }
    }

    // =========================================================================
    // GAMEPLAY TOUCH EVENT (ANALOG JOYSTICK & ACTION BUTTONS)
    // =========================================================================
    private fun handleGameTouchEvent(event: MotionEvent): Boolean {
        var newMask = 0

        val pointerCount = event.pointerCount
        val action = event.actionMasked
        val actionIndex = event.actionIndex

        // Check if our active analog pointer was lifted
        if (action == MotionEvent.ACTION_UP ||
            (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(actionIndex) == analogPointerId)) {
            analogPointerId = -1
            knobX = analogCenterX
            knobY = analogCenterY
        }

        var activeAnalogFound = false

        for (i in 0 until pointerCount) {
            if (action == MotionEvent.ACTION_POINTER_UP && i == actionIndex) {
                continue
            }
            if (action == MotionEvent.ACTION_UP) {
                continue
            }

            val pid = event.getPointerId(i)
            val px = event.getX(i)
            val py = event.getY(i)

            // Check Analog Stick
            val dx = px - analogCenterX
            val dy = py - analogCenterY
            val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

            if (pid == analogPointerId || (analogPointerId == -1 && dist <= analogRadius * 1.5f)) {
                analogPointerId = pid
                activeAnalogFound = true

                if (dist > maxKnobTravel) {
                    val ratio = maxKnobTravel / dist
                    knobX = analogCenterX + dx * ratio
                    knobY = analogCenterY + dy * ratio
                } else {
                    knobX = px
                    knobY = py
                }

                val deadzone = analogRadius * 0.18f
                if (dist >= deadzone) {
                    val angle = (Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())) + 360) % 360

                    if (angle in 337.5..360.0 || angle in 0.0..22.5) {
                        newMask = newMask or NativeBridge.BTN_RIGHT
                    } else if (angle in 22.5..67.5) {
                        newMask = newMask or NativeBridge.BTN_RIGHT or NativeBridge.BTN_DOWN
                    } else if (angle in 67.5..112.5) {
                        newMask = newMask or NativeBridge.BTN_DOWN
                    } else if (angle in 112.5..157.5) {
                        newMask = newMask or NativeBridge.BTN_LEFT or NativeBridge.BTN_DOWN
                    } else if (angle in 157.5..202.5) {
                        newMask = newMask or NativeBridge.BTN_LEFT
                    } else if (angle in 202.5..247.5) {
                        newMask = newMask or NativeBridge.BTN_LEFT or NativeBridge.BTN_UP
                    } else if (angle in 247.5..292.5) {
                        newMask = newMask or NativeBridge.BTN_UP
                    } else if (angle in 292.5..337.5) {
                        newMask = newMask or NativeBridge.BTN_RIGHT or NativeBridge.BTN_UP
                    }
                }
            }

            // Check Button B (Attack)
            val distB = Math.hypot((px - btnBX).toDouble(), (py - btnBY).toDouble())
            if (distB <= btnRadiusB * 1.3f) {
                newMask = newMask or NativeBridge.BTN_B
            }

            // Check Button A (Jump)
            val distA = Math.hypot((px - btnAX).toDouble(), (py - btnAY).toDouble())
            if (distA <= btnRadiusA * 1.3f) {
                newMask = newMask or NativeBridge.BTN_A
            }

            // Check Button Item (Sub-weapon)
            val distItem = Math.hypot((px - btnItemX).toDouble(), (py - btnItemY).toDouble())
            if (distItem <= btnRadiusItem * 1.3f) {
                newMask = newMask or NativeBridge.BTN_ITEM
            }

            // Check Select
            if (rectSelect.contains(px, py)) {
                newMask = newMask or NativeBridge.BTN_MENU
            }

            // Check Start
            if (rectStart.contains(px, py)) {
                newMask = newMask or NativeBridge.BTN_START
            }
        }

        if (!activeAnalogFound) {
            analogPointerId = -1
            knobX = analogCenterX
            knobY = analogCenterY
        }

        if (newMask != currentButtonsMask) {
            android.util.Log.d("VCView", "Touch action=$action mask=0x${Integer.toHexString(newMask)} (old=0x${Integer.toHexString(currentButtonsMask)})")
            val pressedNow = (newMask and currentButtonsMask.inv()) != 0
            if (pressedNow) {
                vibrate(12)
            }

            // Trigger Settings Menu when MENU button is pressed
            if ((newMask and NativeBridge.BTN_MENU) != 0 && (currentButtonsMask and NativeBridge.BTN_MENU) == 0) {
                onMenuClickListener?.invoke()
            }

            currentButtonsMask = newMask
            NativeBridge.nativeSetInput(currentButtonsMask)
            invalidate()
        }

        return true
    }

    private fun vibrate(ms: Long) {
        if (!isVibrationEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (ignored: Exception) {}
    }
}
