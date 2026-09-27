package com.kotlinsun.current

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.pdf.viewer.fragment.PdfViewerFragment

class CurrentPdfFragment : PdfViewerFragment() {
    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        (activity as? PdfActivity)?.showPdfError()
    }
}

class PdfActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PRIVATE = "private_pdf"
        const val EXTRA_DARK = "dark_pdf"
    }
    private lateinit var pdfContainer: FrameLayout
    private lateinit var errorMessage: TextView

    fun showPdfError() {
        runOnUiThread {
            if (!::errorMessage.isInitialized || isFinishing || isDestroyed) return@runOnUiThread
            pdfContainer.visibility = View.GONE
            errorMessage.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = intent.getBooleanExtra(EXTRA_DARK, systemDark)
        delegate.localNightMode = if (dark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (intent.getBooleanExtra(EXTRA_PRIVATE, false))
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val uri = intent.data ?: run { finish(); return }
        val background = if (dark) Color.BLACK else Color.rgb(250, 250, 250)
        val surface = if (dark) Color.rgb(26, 26, 26) else Color.WHITE
        val foreground = if (dark) Color.WHITE else Color.BLACK
        val outline = if (dark) Color.rgb(66, 66, 66) else Color.rgb(212, 212, 212)
        val primary = foreground
        val onPrimary = background
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun buttonBackground(fill: Int, border: Int? = null): RippleDrawable {
            val shape = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(16).toFloat()
                border?.let { setStroke(dp(1), it) }
            }
            return RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000),
                shape, null)
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        window.statusBarColor = surface
        window.navigationBarColor = background
        val font = ResourcesCompat.getFont(this, R.font.pretendard_medium)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(surface)
        }
        val back = Button(this).apply {
            text = getString(R.string.back)
            isAllCaps = false
            typeface = font
            textSize = 14f
            setTextColor(foreground)
            this.background = buttonBackground(surface, outline)
            minHeight = dp(48)
            setOnClickListener { finish() }
        }
        val external = Button(this).apply {
            text = getString(R.string.pdf_external_open)
            isAllCaps = false
            typeface = font
            textSize = 14f
            setTextColor(onPrimary)
            this.background = buttonBackground(primary)
            minHeight = dp(48)
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@PdfActivity, R.string.pdf_app_missing, Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    Toast.makeText(this@PdfActivity, R.string.pdf_access_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(View(this), LinearLayout.LayoutParams(dp(8), dp(1)))
        toolbar.addView(external, LinearLayout.LayoutParams(0, dp(48), 2f))
        root.addView(toolbar)
        root.addView(View(this).apply { setBackgroundColor(outline) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        errorMessage = TextView(this).apply {
            text = getString(R.string.pdf_open_error)
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = ResourcesCompat.getFont(this@PdfActivity, R.font.pretendard_regular)
            setTextColor(foreground)
            setBackgroundColor(background)
            val inset = dp(24)
            setPadding(inset, inset, inset, inset)
            visibility = View.GONE
        }
        root.addView(errorMessage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pdfContainer = FrameLayout(this).apply {
            id = R.id.pdf_container
            setBackgroundColor(background)
        }
        root.addView(pdfContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        val fragment = (supportFragmentManager.findFragmentByTag("pdf") as? PdfViewerFragment)
            ?: CurrentPdfFragment().also {
                supportFragmentManager.beginTransaction().replace(R.id.pdf_container, it, "pdf").commitNow()
            }
        runCatching { fragment.documentUri = uri }.onFailure {
            showPdfError()
        }
    }
}
