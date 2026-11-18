package com.satulin.geniuslyrics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Uses Android's built-in Chromium WebView engine to search and read lyrics directly
 * from genius.com when OkHttp requests are rejected by TLS/bot filters.
 * Blocks images, fonts, CSS, and third-party ad scripts for fast (<1s) loading.
 */
object GeniusWebViewFetcher {

    private const val TAG = "GeniusWebViewFetcher"
    private val mutex = Mutex()

    @SuppressLint("StaticFieldLeak")
    private var sharedWebView: WebView? = null

    data class GeniusHit(
        val title: String,
        val artist: String,
        val url: String
    )

    private val BLOCKED_EXTENSIONS = listOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
        ".woff", ".woff2", ".ttf", ".eot", ".otf",
        ".mp4", ".webm", ".mp3", ".ogg", ".css"
    )

    private val BLOCKED_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
        "google-analytics.com", "mixpanel.com", "facebook.net", "facebook.com",
        "amazon-adsystem.com", "scorecardresearch.com", "criteo.com",
        "pubmatic.com", "adnxs.com", "rubiconproject.com", "quantserve.com",
        "taboola.com", "outbrain.com", "twitter.com", "tiktok.com", "branch.io"
    )

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(context: Context): WebView {
        sharedWebView?.let { return it }
        val wv = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        sharedWebView = wv
        return wv
    }

    private fun shouldBlockUrl(url: String): Boolean {
        val lower = url.lowercase()
        val pathOnly = lower.substringBefore("?")
        if (BLOCKED_EXTENSIONS.any { pathOnly.endsWith(it) }) return true
        if (BLOCKED_HOSTS.any { lower.contains(it) }) return true
        return false
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun unquoteJsResult(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            val parsed = JSONTokener(raw).nextValue()
            parsed as? String ?: raw
        } catch (e: Exception) {
            raw.trim('"')
        }
    }

    private suspend fun loadAndEval(
        context: Context,
        url: String,
        jsExpr: String,
        timeoutMs: Long = 8000L,
        postLoadDelayMs: Long = 150L
    ): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    val wv = getOrCreateWebView(context)
                    val handler = Handler(Looper.getMainLooper())
                    var finished = false

                    val finishOnce: (String?) -> Unit = { result ->
                        if (!finished) {
                            finished = true
                            handler.removeCallbacksAndMessages(null)
                            if (cont.isActive) cont.resume(result)
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            if (shouldBlockUrl(reqUrl)) {
                                return emptyResponse()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            super.onPageFinished(view, loadedUrl)
                            if (finished) return
                            handler.postDelayed({
                                if (finished || !cont.isActive) return@postDelayed
                                try {
                                    view?.evaluateJavascript(jsExpr) { jsResult ->
                                        finishOnce(unquoteJsResult(jsResult))
                                    }
                                } catch (e: Exception) {
                                    finishOnce(null)
                                }
                            }, postLoadDelayMs)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            if (failingUrl == url) {
                                finishOnce(null)
                            }
                        }
                    }

                    cont.invokeOnCancellation {
                        handler.post {
                            try {
                                wv.stopLoading()
                            } catch (ignored: Exception) {}
                        }
                    }

                    wv.stopLoading()
                    wv.loadUrl(url)
                } catch (e: Exception) {
                    Log.w(TAG, "WebView load error: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    suspend fun searchSongs(context: Context, query: String): List<GeniusHit> = mutex.withLock {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://genius.com/api/search/multi?per_page=8&q=$encoded"
        val body = loadAndEval(
            context = context,
            url = searchUrl,
            jsExpr = "(function(){ return document.body ? (document.body.innerText || document.body.textContent || '') : ''; })()",
            timeoutMs = 7000L,
            postLoadDelayMs = 100L
        ) ?: return emptyList()

        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return emptyList()

        val hits = mutableListOf<GeniusHit>()
        try {
            val root = JSONObject(trimmed)
            val sections = root.optJSONObject("response")?.optJSONArray("sections") ?: return emptyList()
            for (i in 0 until sections.length()) {
                val section = sections.getJSONObject(i)
                val secType = section.optString("type", "")
                if (secType == "song" || secType == "top_hit") {
                    val arr = section.optJSONArray("hits") ?: continue
                    for (h in 0 until arr.length()) {
                        val hitObj = arr.getJSONObject(h)
                        val result = hitObj.optJSONObject("result") ?: continue
                        val resType = result.optString("_type", hitObj.optString("type", ""))
                        if (resType == "song") {
                            val title = result.optString("title", "").trim()
                            val artist = result.optJSONObject("primary_artist")?.optString("name", "")?.trim() ?: ""
                            val url = result.optString("url", "").trim()
                            if (title.isNotBlank() && url.startsWith("http")) {
                                hits.add(GeniusHit(title = title, artist = artist, url = url))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebView JSON parse error: ${e.message}")
        }
        return hits.distinctBy { it.url }
    }

    suspend fun scrapeLyricsFromUrl(context: Context, songUrl: String): String? = mutex.withLock {
        val jsExtract = """
            (function() {
                var containers = document.querySelectorAll('div[data-lyrics-container="true"]');
                if (!containers || containers.length === 0) {
                    var legacy = document.querySelector('.lyrics');
                    return legacy ? (legacy.innerText || '') : '';
                }
                var parts = [];
                for (var i = 0; i < containers.length; i++) {
                    var clone = containers[i].cloneNode(true);
                    var junk = clone.querySelectorAll('[data-exclude-from-selection="true"], script, style, button');
                    for (var j = 0; j < junk.length; j++) {
                        junk[j].remove();
                    }
                    var txt = clone.innerText || clone.textContent || '';
                    if (txt.trim().length > 0) parts.push(txt.trim());
                }
                return parts.join('\n\n');
            })()
        """.trimIndent()

        val text = loadAndEval(
            context = context,
            url = songUrl,
            jsExpr = jsExtract,
            timeoutMs = 9000L,
            postLoadDelayMs = 350L
        )
        return if (!text.isNullOrBlank() && text.length >= 25) text else null
    }
}
