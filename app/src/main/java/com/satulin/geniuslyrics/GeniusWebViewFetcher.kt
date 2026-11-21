package com.satulin.geniuslyrics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
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
 * Blocks heavy images, fonts, video, and third-party ad scripts for fast loading.
 */
object GeniusWebViewFetcher {

    private const val TAG = "GeniusWebViewFetcher"
    private const val CF_CHALLENGE_MARKER = "__CF_CHALLENGE__"
    private val mutex = Mutex()

    @SuppressLint("StaticFieldLeak")
    private var sharedWebView: WebView? = null

    data class GeniusHit(
        val title: String,
        val artist: String,
        val url: String,
        val artistNames: String = artist
    )

    const val EXTRACT_LYRICS_JS: String = """(function() {
    var containers = document.querySelectorAll('div[data-lyrics-container="true"]');
    if (!containers || containers.length === 0) {
        var legacy = document.querySelector('.lyrics');
        return legacy ? (legacy.innerText || legacy.textContent || '').trim() : '';
    }
    var parts = [];
    for (var i = 0; i < containers.length; i++) {
        var clone = containers[i].cloneNode(true);
        var junk = clone.querySelectorAll('[data-exclude-from-selection="true"], [class*="LyricsHeader"], script, style, button');
        for (var j = 0; j < junk.length; j++) {
            junk[j].remove();
        }
        var brs = clone.querySelectorAll('br');
        for (var b = 0; b < brs.length; b++) {
            brs[b].replaceWith(document.createTextNode('\n'));
        }
        var blocks = clone.querySelectorAll('div, p');
        for (var k = 0; k < blocks.length; k++) {
            blocks[k].insertAdjacentText('beforebegin', '\n');
        }
        var txt = (clone.textContent || '').replace(/\r/g, '').replace(/\n{3,}/g, '\n\n').trim();
        if (txt.length > 0) parts.push(txt);
    }
    return parts.join('\n\n');
})()"""

    private val BLOCKED_EXTENSIONS = listOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
        ".woff", ".woff2", ".ttf", ".eot", ".otf",
        ".mp4", ".webm", ".mp3", ".ogg"
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
            val defaultUa = settings.userAgentString
            if (!defaultUa.isNullOrBlank()) {
                settings.userAgentString = defaultUa.replace("; wv", "")
            }
        }
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(wv, true)
        } catch (e: Exception) {
            Log.w(TAG, "CookieManager init warning: ${e.message}")
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
        postLoadDelayMs: Long = 150L,
        maxPollAttempts: Int = 4
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

                    val wrappedJs = """
                        (function() {
                            var t = (document.title || '').toLowerCase();
                            var b = (document.body ? (document.body.innerText || '').slice(0, 500).toLowerCase() : '');
                            if (t.indexOf('just a moment') !== -1 ||
                                b.indexOf('verify you are human') !== -1 ||
                                b.indexOf('scrrrr') !== -1 ||
                                b.indexOf('make sure you\'re a human') !== -1) {
                                return '$CF_CHALLENGE_MARKER';
                            }
                            return ($jsExpr);
                        })()
                    """.trimIndent()

                    fun scheduleEval(view: WebView?, delayMs: Long, attempt: Int) {
                        handler.postDelayed({
                            if (finished || !cont.isActive) return@postDelayed
                            try {
                                view?.evaluateJavascript(wrappedJs) { jsResult ->
                                    if (finished) return@evaluateJavascript
                                    val unquoted = unquoteJsResult(jsResult)
                                    when {
                                        unquoted == CF_CHALLENGE_MARKER -> {
                                            Log.w(TAG, "Cloudflare human verification challenge hit on $url")
                                            try { view.stopLoading() } catch (_: Exception) {}
                                            finishOnce(null)
                                        }
                                        unquoted.isBlank() && attempt < maxPollAttempts -> {
                                            scheduleEval(view, 350L, attempt + 1)
                                        }
                                        else -> {
                                            finishOnce(unquoted)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                finishOnce(null)
                            }
                        }, delayMs)
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
                            handler.removeCallbacksAndMessages(null)
                            scheduleEval(view, postLoadDelayMs, 0)
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
                                handler.removeCallbacksAndMessages(null)
                                wv.stopLoading()
                            } catch (_: Exception) {}
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
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val searchUrl = "https://genius.com/api/search/song?per_page=8&q=$encoded"
        val body = loadAndEval(
            context = context,
            url = searchUrl,
            jsExpr = "(function(){ return document.body ? (document.body.innerText || document.body.textContent || '') : ''; })()",
            timeoutMs = 7000L,
            postLoadDelayMs = 100L,
            maxPollAttempts = 1
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
                            val artistNames = result.optString("artist_names", "").trim().ifBlank { artist }
                            val url = result.optString("url", "").trim()
                            if (title.isBlank() || !url.startsWith("http")) continue
                            if (artist.contains("Genius", ignoreCase = true)) continue
                            if (!url.endsWith("-lyrics", ignoreCase = true)) continue
                            hits.add(
                                GeniusHit(
                                    title = title,
                                    artist = artist,
                                    url = url,
                                    artistNames = artistNames
                                )
                            )
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
        val text = loadAndEval(
            context = context,
            url = songUrl,
            jsExpr = EXTRACT_LYRICS_JS,
            timeoutMs = 9500L,
            postLoadDelayMs = 300L,
            maxPollAttempts = 4
        )
        return if (!text.isNullOrBlank() && text.length >= 25) text else null
    }
}
