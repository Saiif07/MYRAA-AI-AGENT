package com.myraa.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Thin native shell: the whole screen is the web frontend (assets/ui.html: 3D avatar, modes, outfits, sheets).
 * Everything the page needs from Android goes through the small [Bridge] below.
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var web: WebView? = null
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    inner class Bridge {
        @JavascriptInterface fun send(text: String) { runOnUiThread { sendText(text) } }
        @JavascriptInterface fun mic() { runOnUiThread { micPressed() } }
        @JavascriptInterface fun toggleWake() { runOnUiThread { toggleWakeMode() } }
        @JavascriptInterface fun stop() { runOnUiThread { stopEverything() } }
        @JavascriptInterface fun setup() { runOnUiThread { setupStep() } }
        @JavascriptInterface fun attach() { runOnUiThread { attachFile() } }
        @JavascriptInterface fun wakeOn(): Boolean = Bus.wakeOn
        @JavascriptInterface fun openChannel(slug: String) { runOnUiThread { openTelegram(slug) } }
        @JavascriptInterface fun setMode(m: String) { if (Modes.keys.contains(m)) Cfg.put(applicationContext, "mode", m) }

        @JavascriptInterface fun getSettings(): String {
            val c = applicationContext
            val o = JSONObject()
            o.put("mode", Modes.current(c))
            o.put("model", Cfg.get(c, "model", "gemini:gemini-flash-latest"))
            o.put("base", Cfg.get(c, "base"))
            o.put("url", Cfg.get(c, "url"))
            o.put("n8nUrl", Cfg.get(c, "n8nUrl"))
            o.put("hasN8nToken", Cfg.secret(c, "n8nToken").isNotEmpty())
            o.put("hasKey", Cfg.secret(c, "apikey").isNotEmpty())
            o.put("hasToken", Cfg.secret(c, "token").isNotEmpty())
            o.put("hasTavily", Cfg.secret(c, "tavily").isNotEmpty())
            o.put("voicestop", Cfg.get(c, "voicestop") == "1")
            o.put("autosend", Cfg.get(c, "autosend") == "1")
            o.put("speak", Cfg.get(c, "speak", "1") == "1")
            return o.toString()
        }

        /** Partial updates are fine: only the keys that are present are changed. Secrets are only replaced when non-empty. */
        @JavascriptInterface fun saveSettings(json: String) {
            val c = applicationContext
            try {
                val o = JSONObject(json)
                if (o.has("model")) Cfg.put(c, "model", o.optString("model").trim().ifEmpty { "gemini:gemini-flash-latest" })
                if (o.has("base")) Cfg.put(c, "base", o.optString("base").trim())
                if (o.has("url")) Cfg.put(c, "url", o.optString("url").trim())
            if (o.has("n8nUrl")) Cfg.put(c, "n8nUrl", o.optString("n8nUrl").trim())
            if (o.optString("n8nToken").isNotBlank()) Cfg.putSecret(c, "n8nToken", o.optString("n8nToken").trim())
                if (o.optString("key").isNotEmpty()) Cfg.putSecret(c, "apikey", o.optString("key").trim())
                if (o.optString("tok").isNotEmpty()) Cfg.putSecret(c, "token", o.optString("tok").trim())
                if (o.optString("tav").isNotEmpty()) Cfg.putSecret(c, "tavily", o.optString("tav").trim())
                if (o.has("voicestop")) Cfg.put(c, "voicestop", if (o.optBoolean("voicestop")) "1" else "0")
                if (o.has("autosend")) Cfg.put(c, "autosend", if (o.optBoolean("autosend")) "1" else "0")
                if (o.has("speak")) Cfg.put(c, "speak", if (o.optBoolean("speak")) "1" else "0")
            } catch (e: Exception) { }
        }

        @JavascriptInterface fun triggerN8n(payload: String) {
        val c = applicationContext
        Thread {
            val result = JSONObject()
            var connection: java.net.HttpURLConnection? = null
            try {
                val endpoint = Cfg.get(c, "n8nUrl").trim()
                if (endpoint.isBlank()) throw IllegalStateException("Pehle Settings mein n8n Webhook URL save karo")
                val uri = Uri.parse(endpoint)
                val scheme = uri.scheme?.lowercase()
                val host = uri.host?.lowercase()
                val localHttp = scheme == "http" && (host == "localhost" || host == "127.0.0.1")
                if (scheme != "https" && !localHttp) {
                    throw IllegalArgumentException("Webhook URL HTTPS hona chahiye")
                }
                if (host.isNullOrBlank()) throw IllegalArgumentException("Webhook URL invalid hai")
                connection = (java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    val token = Cfg.secret(c, "n8nToken")
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..399) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(2500) } ?: ""
                result.put("ok", code in 200..299)
                result.put("status", code)
                result.put("body", body)
                if (code !in 200..299) result.put("error", "Webhook ne HTTP $code return kiya")
            } catch (e: Exception) {
                result.put("ok", false)
                result.put("error", e.message ?: "Webhook request fail hua")
            } finally {
                connection?.disconnect()
            }
            val message = JSONObject.quote(result.toString())
            runOnUiThread {
                web?.evaluateJavascript(
                    "if(window.MyraaN8nResult){window.MyraaN8nResult($message)}",
                    null
                )
            }
        }.start()
    }

    @JavascriptInterface fun getMemory(): String {
            val arr = JSONArray()
            for (m in Cfg.get(applicationContext, "mem").split("\n")) if (m.isNotBlank()) arr.put(m)
            return arr.toString()
        }

        @JavascriptInterface fun clearMemory() { Cfg.put(applicationContext, "mem", "") }

        @JavascriptInterface fun getHistory(): String {
            val arr = JSONArray()
            synchronized(Bus.lines) {
                for (l in Bus.lines.takeLast(60)) {
                    val mine = l.startsWith("Aap: ")
                    arr.put(JSONObject().put("r", if (mine) "Aap" else "MYRAA").put("t", l.removePrefix("Aap: ").removePrefix("MYRAA: ")))
                }
            }
            return arr.toString()
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try { tts = TextToSpeech(this, this) } catch (e: Exception) { tts = null }
        try {
            val f = java.io.File(filesDir, "crash.txt")
            if (f.exists()) { Bus.post("MYRAA: Pichhli baar crash hua tha:\n" + f.readText().take(300)); f.delete() }
        } catch (e: Exception) { }
        try { if (Build.VERSION.SDK_INT >= 21) window.statusBarColor = Color.TRANSPARENT } catch (e: Exception) { }
        try {
            val w = WebView(this)
            w.setBackgroundColor(Color.parseColor("#05030F"))
            val ws = w.settings
            ws.javaScriptEnabled = true
            ws.domStorageEnabled = true
            ws.allowFileAccess = false          // file:///android_asset still loads; nothing else on disk is reachable
            ws.allowContentAccess = false
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) { pushState() }
                override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean = true   // never navigate away from our own page
            }
            w.addJavascriptInterface(Bridge(), "MyraaBridge")
            w.loadUrl("file:///android_asset/ui.html")
            web = w
            setContentView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (e: Throwable) {
            web = null
            val t = android.widget.TextView(this)
            t.text = "WebView available nahi hai. Android System WebView update karo."
            t.setTextColor(Color.WHITE); t.setBackgroundColor(Color.BLACK); t.setPadding(40, 120, 40, 40)
            setContentView(t)
        }
        if (intent?.getBooleanExtra("autowake", false) == true && !Bus.wakeOn) toggleWakeMode()
    }

    // ---------- native -> page
    private fun js(script: String) {
        runOnUiThread { try { web?.evaluateJavascript(script, null) } catch (e: Exception) { } }
    }

    private fun avatarState(): String {
        val s = Bus.status
        return if (s.contains("LISTEN")) "listening" else if (s.contains("THINK")) "thinking" else if (s.contains("SPEAK")) "speaking"
        else if (s.contains("ERR")) "error" else "idle"
    }

    private fun pushState() {
        js("if(window.UI){UI.onState(" + JSONObject.quote(avatarState()) + ");UI.onWake(" + Bus.wakeOn + ");}")
    }

    override fun onResume() {
        super.onResume()
        Bus.uiVisible = true
        try { web?.onResume() } catch (e: Exception) { }
        Bus.onLine = { l ->
            val mine = l.startsWith("Aap: ")
            js("if(window.UI)UI.onLine(" + JSONObject.quote(if (mine) "Aap" else "MYRAA") + "," + JSONObject.quote(l.removePrefix("Aap: ").removePrefix("MYRAA: ")) + ")")
        }
        Bus.onState = { pushState() }
        Bus.onUi = { s -> js("if(window.UI){" + s + "}") }
        pushState()
    }

    override fun onPause() {
        Bus.uiVisible = false
        Bus.onLine = null; Bus.onState = null; Bus.onUi = null
        try { web?.onPause() } catch (e: Exception) { }
        super.onPause()
    }

    override fun onInit(s: Int) {
        try {
            if (s == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { Bus.state("SPEAKING") }
                    override fun onDone(id: String?) { Bus.state(if (Bus.wakeOn) "WAKE: bolo 'Hey Myraa'" else "IDLE") }
                    override fun onError(id: String?) { Bus.state("IDLE") }
                })
            }
        } catch (e: Exception) { }
    }

    // ---------- Telegram channels (only these two, nothing the page sends is ever opened as-is)
    private val channels = mapOf("Hackflix07" to "https://t.me/Hackflix07", "fuusaw" to "https://t.me/fuusaw")

    /** Opens the channel in the Telegram app; if no app handles tg://, falls back to the https link. */
    private fun openTelegram(slug: String) {
        val https = channels[slug] ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=" + slug)))
        } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(https))) }
            catch (x: Exception) { Bus.post("MYRAA: Telegram link nahi khul paya: " + https) }
        }
    }

    // ---------- page -> native
    private fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        Bus.post("Aap: " + t)
        Bus.state("THINKING")
        Brain.ask(this, t) { reply ->
            Bus.post("MYRAA: " + reply)
            val engine = tts
            if (engine == null || Cfg.get(this, "speak", "1") == "0") Bus.state(if (Bus.wakeOn) "WAKE: bolo 'Hey Myraa'" else "IDLE")
            else runOnUiThread {
                try {
                    Modes.apply(this, engine)
                    engine.speak(reply.take(300), TextToSpeech.QUEUE_FLUSH, null, "ui")   // never read long output aloud
                } catch (e: Exception) { Bus.state("IDLE") }
            }
        }
    }

    /** Stop: speech, queued answer, pending confirmation, running accessibility task. A later answer is discarded, never spoken. */
    private fun stopEverything() {
        Brain.cancelAll()
        try { tts?.stop() } catch (e: Exception) { }
        try { recognizer?.cancel() } catch (e: Exception) { }
        try { MyraaAccess.instance?.cancelTask() } catch (e: Exception) { }
        Bus.onStop?.invoke()
        Bus.state(if (Bus.wakeOn) "WAKE: bolo 'Hey Myraa'" else "IDLE")
    }

    private fun toggleWakeMode() {
        if (Bus.wakeOn) {
            Cfg.put(this, "wake_on", "0")
            stopService(Intent(this, WakeService::class.java))
            return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 2); return }
        startWake()
        if (Cfg.get(this, "setup_done") != "1") { Cfg.put(this, "setup_done", "1"); setupStep() }
    }

    private fun startWake() {
        try {
            val i = Intent(this, WakeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) { Bus.post("MYRAA: Wake start nahi ho paya: " + e.javaClass.simpleName) }
    }

    /** One step at a time: overlay, battery, contacts, accessibility. */
    private fun setupStep() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Bus.post("MYRAA: Step 1/4: 'Display over other apps' ON karo, taaki background mein bhi apps seedha khulein.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
            Bus.post("MYRAA: Step 2/4: Battery ko 'Allow' / 'Unrestricted' karo, taaki background mein band na ho.")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName)))
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Bus.post("MYRAA: Step 3/4: Contacts allow karo, taaki 'mom ko call karo' chale.")
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 3)
            return
        }
        if (!MyraaAccess.enabled()) {
            Bus.post("MYRAA: Step 4/4: Accessibility mein 'MYRAA' ko ON karo, taaki main kisi bhi app ke andar search kar sakun aur message bhej sakun.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        Bus.post("MYRAA: Sab set hai. Bolo: 'Hey Myraa, open YouTube'.")
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (c == 2) {
            if (micOk) {
                startWake()
                if (Cfg.get(this, "setup_done") != "1") { Cfg.put(this, "setup_done", "1"); setupStep() }
            } else Bus.post("MYRAA: Mic permission ke bina wake nahi chalega.")
        }
        if (c == 1 && micOk) listenOnce()
        if (c == 3) Bus.post("MYRAA: " + (if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) "Contacts permission mil gayi." else "Contacts permission nahi mili."))
    }

    private fun micPressed() {
        if (Bus.wakeOn) { Bus.post("MYRAA: Hands-free ON hai. 'Hey Myraa' bolo, ya power button se band karo."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        listenOnce()
    }

    private fun listenOnce() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { Bus.post("MYRAA: Speech recognition available nahi hai."); return }
        try {
            tts?.stop()
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { Bus.state("LISTENING") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) { Bus.state("IDLE"); Bus.post("MYRAA: Mic error code " + e) }
                override fun onResults(res: Bundle?) {
                    val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t != null) sendText(t) else Bus.state("IDLE")
                }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN"))
        } catch (e: Exception) { Bus.state("IDLE"); Bus.post("MYRAA: Mic start nahi hua: " + e.javaClass.simpleName) }
    }

    // ---------- file attach (backend mode)
    private fun attachFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, 5)
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc != 5 || res != RESULT_OK) return
        val uri = d?.data ?: return
        Bus.post("MYRAA: File upload ho rahi hai...")
        Thread {
            val msg = try {
                var name = "file"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ix >= 0 && c.moveToFirst()) name = c.getString(ix) ?: name
                }
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                contentResolver.openInputStream(uri)?.use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 12000000) throw IllegalStateException("File 12MB se badi hai")
                        bos.write(buf, 0, n)
                    }
                }
                Brain.upload(this, name, bos.toByteArray())
            } catch (e: Exception) { "Upload fail: " + (e.message ?: e.javaClass.simpleName).take(80) }
            Bus.post("MYRAA: " + msg)
        }.start()
    }

    override fun onBackPressed() {
        val w = web
        if (w == null) { moveTaskToBack(true); return }
        try {
            w.evaluateJavascript("(window.UI&&UI.back)?UI.back():false") { r -> if (r != "true") moveTaskToBack(true) }
        } catch (e: Exception) { moveTaskToBack(true) }
    }

    override fun onDestroy() {
        try { tts?.shutdown(); recognizer?.destroy(); web?.destroy() } catch (e: Exception) { }
        super.onDestroy()
    }
}
