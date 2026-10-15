package org.asr.runtime.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.asr.runtime.BuildConfig
import org.asr.runtime.R

/** App information and data-use disclosure in the dashboard's visual style. */
class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val dark = intent.getBooleanExtra(EXTRA_DARK_THEME, false)
        applyTheme(dark)
        val backend = BuildConfig.ASR_DDS_BACKEND.uppercase()
        findViewById<TextView>(R.id.txtAboutVersion).text = "VERSION  ${BuildConfig.VERSION_NAME}"
        findViewById<TextView>(R.id.txtAboutBackend).text = "DDS BACKEND  $backend"
        findViewById<TextView>(R.id.txtAboutSubtitle).text = "ROBOT SENSOR NODE · $backend DDS"

        findViewById<View>(R.id.btnAboutBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAboutSource).setOnClickListener { openSourceRepository() }
    }

    private fun applyTheme(dark: Boolean) {
        val background = if (dark) Color.rgb(11, 11, 11) else Color.rgb(245, 247, 250)
        val surface = if (dark) Color.rgb(23, 23, 23) else Color.WHITE
        val primary = if (dark) Color.WHITE else Color.BLACK
        val body = if (dark) Color.rgb(205, 205, 205) else Color.rgb(51, 51, 51)
        val secondary = if (dark) Color.rgb(160, 160, 160) else Color.rgb(115, 115, 115)
        val edge = if (dark) Color.rgb(56, 56, 56) else Color.rgb(227, 229, 232)

        findViewById<View>(R.id.aboutRoot).setBackgroundColor(background)
        listOf(R.id.cardAboutBuild, R.id.cardAboutData, R.id.cardAboutOpenSource).forEach { id ->
            findViewById<View>(id).background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * resources.displayMetrics.density
                setColor(surface)
                setStroke((resources.displayMetrics.density + 0.5f).toInt(), edge)
            }
        }
        setTextColorsRecursively(findViewById(R.id.aboutRoot), body, secondary)
        findViewById<TextView>(R.id.txtAboutDescription).setTextColor(body)
        findViewById<TextView>(R.id.txtAboutVersion).setTextColor(primary)
        findViewById<TextView>(R.id.txtAboutBackend).setTextColor(body)
        findViewById<TextView>(R.id.txtAboutSubtitle).setTextColor(secondary)
        findViewById<TextView>(R.id.btnAboutBack).setTextColor(secondary)
        findViewById<TextView>(R.id.btnAboutSource).setTextColor(primary)

        @Suppress("DEPRECATION")
        window.statusBarColor = background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    private fun setTextColorsRecursively(view: View, body: Int, secondary: Int) {
        if (view is TextView && view.id == View.NO_ID) {
            view.setTextColor(if (view.text.toString() in SECTION_TITLES) secondary else body)
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) setTextColorsRecursively(view.getChildAt(index), body, secondary)
        }
    }

    private fun openSourceRepository() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser is available", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_DARK_THEME = "org.asr.runtime.extra.DARK_THEME"
        private const val SOURCE_URL = "https://github.com/ShreyasDamase/asr-android-runtime"
        private val SECTION_TITLES = setOf("BUILD", "SENSOR DATA", "OPEN SOURCE")
    }
}
