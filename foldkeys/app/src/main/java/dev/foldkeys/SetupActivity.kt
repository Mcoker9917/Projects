package dev.foldkeys

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** The app icon opens this: two buttons to turn the keyboard on, and a box to try it. */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Fold Keys"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "1. Turn on Fold Keys in the keyboard list.\n2. Pick it as your keyboard.\n3. Try it in the box below.\n\n" +
                "Tips: hold the space bar to move the cursor. Double-tap shift for caps lock. " +
                "On the inner screen the keys split for your thumbs. Long-press 123 to join or split them yourself. " +
                "Long-press the emoji button to switch keyboards."
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        })
        root.addView(Button(this).apply {
            text = "1. Turn on Fold Keys"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Choose keyboard"
            setOnClickListener { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
        })
        root.addView(Button(this).apply {
            text = "Allow microphone (for the mic button)"
            setOnClickListener { askMic() }
        })
        root.addView(EditText(this).apply { hint = "3. Type here to try it" })
        setContentView(root)
        // The keyboard sends people here when they tap the mic before allowing it.
        if (intent.getBooleanExtra("ask_mic", false)) askMic()
    }

    private fun askMic() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }
}
