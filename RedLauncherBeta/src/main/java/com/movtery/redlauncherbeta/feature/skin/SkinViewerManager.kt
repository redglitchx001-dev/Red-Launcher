package com.movtery.redlauncherbeta.feature.skin
import android.webkit.WebView
import android.webkit.WebSettings
object SkinViewerManager {
    fun setup3DViewer(webView: WebView, skinUrl: String, capeUrl: String?) {
        val settings = webView.settings
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        val html = """<!DOCTYPE html><html lang="en"><head><script src="https://cdnjs.cloudflare.com/ajax/libs/skinview3d/3.0.0/skinview3d.bundle.js"></script></head><body style="margin:0; overflow:hidden; background-color:transparent;"><canvas id="skin_container"></canvas><script>let skinViewer = new skinview3d.SkinViewer({canvas: document.getElementById("skin_container"), width: window.innerWidth, height: window.innerHeight, skin: "${skinUrl}"}); ${if (capeUrl != null) "skinViewer.loadCape('${capeUrl}');" else ""} skinViewer.autoRotate = true;</script></body></html>"""
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}
