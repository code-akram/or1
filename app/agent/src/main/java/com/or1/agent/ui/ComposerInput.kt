package com.or1.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.or1.agent.R

/**
 * The composer's text, held by a native EditText. A Compose text field recomposed, re-laid out and re-diffed the
 * semantics tree (the agent's accessibility service is always on) on every keystroke: about 26 ms a frame on this
 * phone, so typing lagged. Here Compose only sees whether there is text and whether the field has focus.
 */
class ComposerInput {
    private var view: EditText? = null

    var hasText by mutableStateOf(false)
        private set
    var focused by mutableStateOf(false)
        private set

    /** Hardware keyboards: Enter sends, Shift+Enter adds a line. */
    var onEnter: () -> Unit = {}
    var onFocusChange: (Boolean) -> Unit = {}

    /** The text while the card (and its view) is gone. */
    private var kept = ""

    val text: String get() = view?.text?.toString() ?: kept

    fun clear() {
        kept = ""
        view?.text?.clear()
    }

    /** Focuses the field and opens the keyboard (once the view is attached, or the request is dropped). */
    fun focusAndShowKeyboard() {
        val v = view ?: return
        v.post {
            v.requestFocus()
            v.context.getSystemService(InputMethodManager::class.java).showSoftInput(v, 0)
        }
    }

    fun dismiss() {
        val v = view ?: return
        v.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun createView(context: Context): EditText {
        val density = context.resources.displayMetrics.density
        return EditText(context).apply {
            background = null
            setPadding(0, 0, 0, 0)
            hint = "Ask or1…"
            setTextColor(Palette.Text.toArgb())
            setHintTextColor(Palette.Text2.toArgb())
            highlightColor = Palette.EmberDim.toArgb()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
            lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 23f, resources.displayMetrics).toInt()
            textCursorDrawable = GradientDrawable().apply {
                setColor(Palette.Ember.toArgb())
                setSize((2 * density).toInt(), 0)
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            // Grows with the text up to about seven lines, then scrolls inside.
            maxHeight = (180 * density).toInt()
            isVerticalScrollBarEnabled = false
            setText(kept)
            setSelection(kept.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val has = !s.isNullOrBlank()
                    if (has != hasText) hasText = has
                }
            })
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD) &&
                    event.keyCharacterMap.keyboardType != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD && !event.isShiftPressed
                ) {
                    if (event.action == KeyEvent.ACTION_DOWN && hasText) onEnter()
                    true
                } else false
            }
            setOnFocusChangeListener { _, hasFocus ->
                focused = hasFocus
                onFocusChange(hasFocus)
            }
            view = this
        }
    }

    /** The view is going away with the composer card; the next one starts from its text. */
    fun release(v: EditText) {
        if (view === v) {
            kept = v.text.toString()
            view = null
        }
    }
}
