package com.kotlinsun.current

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.pdf.viewer.fragment.PdfViewerFragment

class CurrentPdfFragment : PdfViewerFragment() {
    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        Toast.makeText(requireContext(), "PDF를 열지 못했습니다. 다른 PDF 앱으로 열어 주세요.",
            Toast.LENGTH_LONG).show()
    }
}

class PdfActivity : AppCompatActivity() {
    companion object { const val EXTRA_PRIVATE = "private_pdf" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_PRIVATE, false))
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val uri = intent.data ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val back = Button(this).apply {
            text = "뒤로"
            setOnClickListener { finish() }
        }
        val external = Button(this).apply {
            text = "다른 PDF 앱으로 열기"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@PdfActivity, "다른 PDF 앱이 없습니다.", Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    Toast.makeText(this@PdfActivity, "PDF 파일에 접근할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(back)
        root.addView(external)
        root.addView(FrameLayout(this).apply { id = R.id.pdf_container },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        val fragment = (supportFragmentManager.findFragmentByTag("pdf") as? PdfViewerFragment)
            ?: CurrentPdfFragment().also {
                supportFragmentManager.beginTransaction().replace(R.id.pdf_container, it, "pdf").commitNow()
            }
        runCatching { fragment.documentUri = uri }.onFailure {
            Toast.makeText(this, "PDF를 열지 못했습니다. 다른 앱으로 열어 주세요.", Toast.LENGTH_LONG).show()
        }
    }
}
