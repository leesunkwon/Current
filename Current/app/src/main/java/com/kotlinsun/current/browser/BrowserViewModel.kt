package com.kotlinsun.current.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kotlinsun.current.data.BrowserStore
import com.kotlinsun.current.engine.webview.WebViewEngine

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    val controller = BrowserController(application, BrowserStore.get(application), WebViewEngine())

    override fun onCleared() {
        controller.destroy()
        super.onCleared()
    }
}
