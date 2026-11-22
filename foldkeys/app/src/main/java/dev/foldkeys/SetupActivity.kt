package dev.foldkeys

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
                "Half-fold the phone to split the keys for your thumbs, or long-press 123 to split or join them yourself."
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
        root.addView(EditText(this).apply { hint = "3. Type here to try it" })
        setContentView(root)
    }
}
