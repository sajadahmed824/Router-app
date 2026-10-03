package com.ali.routerapp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class Resp(val code: Int, val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()

    fun cookies(): List<String> =
        headers.entries
            .filter { it.key.equals("Set-Cookie", true) }
            .flatMap { it.value }
            .map { it.substringBefore(";") }
}

class MainActivity : Activity() {

    private lateinit var ipBox: EditText
    private lateinit var userBox: EditText
    private lateinit var passBox: EditText
    private lateinit var logView: TextView

    private val loginKeys = listOf("login.cgi", "PassWord", "base64", "X_HW_Token", "GetRandCount")
    private val respKeys = listOf("sessionid", "sid", "cookie", "rror", "lock", "ail")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)

        val title = TextView(this)
        title.text = "Router App - Test v0.3"
        title.textSize = 20f
        root.addView(title)

        ipBox = EditText(this)
        ipBox.hint = "Router IP"
        ipBox.setText("192.168.100.1")
        ipBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        root.addView(ipBox)

        userBox = EditText(this)
        userBox.hint = "Username"
        userBox.inputType = InputType.TYPE_CLASS_TEXT
        root.addView(userBox)

        passBox = EditText(this)
        passBox.hint = "Password"
        passBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(passBox)

        val b1 = Button(this)
        b1.text = "1) Connection test"
        b1.setOnClickListener { connectionTest() }
        root.addView(b1)

        val b2 = Button(this)
        b2.text = "2) Login + page test"
        b2.setOnClickListener { loginTest() }
        root.addView(b2)

        val b3 = Button(this)
        b3.text = "Copy log"
        b3.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", logView.text))
        }
        root.addView(b3)

        logView = TextView(this)
        logView.textSize = 12f
        logView.typeface = Typeface.MONOSPACE
        logView.setTextIsSelectable(true)
        root.addView(logView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun log(s: String) {
        runOnUiThread { logView.append(s + "\n") }
    }

    private fun clearLog() {
        runOnUiThread { logView.text = "" }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun absUrl(base: String, src: String): String =
        if (src.startsWith("http")) src
        else if (src.startsWith("/")) base + src
        else "$base/$src"

    private fun grep(tag: String, text: String, keys: List<String>, max: Int) {
        var hits = 0
        for (k in keys) {
            var from = 0
            while (hits < max) {
                val i = text.indexOf(k, from)
                if (i < 0) break
                val a = maxOf(0, i - 60)
                val b = minOf(text.length, i + 100)
                log("  [$tag] " + text.substring(a, b).replace(Regex("\\s+"), " "))
                hits++
                from = i + k.length + 100
            }
        }
    }

    // Use the Wi-Fi network directly, even if mobile data is also on
    private fun wifiNetwork(): Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
        }
        return null
    }

    private fun request(method: String, url: String, body: String? = null, cookie: String? = null): Resp {
        val net = wifiNetwork() ?: throw Exception("Wi-Fi is not connected")
        val conn = net.openConnection(URL(url)) as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val hdrs = HashMap<String, List<String>>()
        for ((k, v) in conn.headerFields) {
            if (k != null) hdrs[k] = v
        }
        conn.disconnect()
        return Resp(code, hdrs, text)
    }

    private fun connectionTest() {
        val base = "http://" + ipBox.text.toString().trim()
        clearLog()
        thread {
            try {
                log("Testing $base ...")
                val r = request("GET", "$base/")
                log("HTTP ${r.code}")
                log("Location: ${r.header("Location") ?: "-"}")
                log("Size: ${r.body.length} bytes")
                log("Done.")
            } catch (e: Exception) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun afterLogin(base: String, cookieHeader: String) {
        log("C) Page test")
        val pages = listOf(
            "/html/ssmp/deviceinfo/deviceinfo.asp",
            "/html/bbsp/userdevinfo/userdevinfo.asp",
            "/html/amp/wlanbasic/wlanbasic.asp",
            "/html/amp/opticinfo/opticinfo.asp",
            "/html/bbsp/waninfo/waninfo.asp"
        )
        for (p in pages) {
            try {
                val r = request("GET", base + p, null, cookieHeader)
                val loginPage = r.body.contains("txt_Username") || r.body.contains("loginbutton")
                log("$p -> HTTP ${r.code}, ${r.body.length} bytes" +
                    (if (loginPage) " (session rejected)" else ""))
            } catch (e: Exception) {
                log("$p -> ERROR ${e.javaClass.simpleName}")
            }
        }
        log("D) Device list test")
        try {
            val r = request(
                "POST", "$base/html/bbsp/common/GetLanUserDevInfo.asp", "", cookieHeader
            )
            log("HTTP ${r.code}, ${r.body.length} bytes")
            log("Start: " + r.body.trim().take(200).replace("\n", " "))
        } catch (e: Exception) {
            log("ERROR ${e.javaClass.simpleName}: ${e.message}")
        }
        log("Done.")
    }

    private fun loginTest() {
        val base = "http://" + ipBox.text.toString().trim()
        val user = userBox.text.toString()
        val pass = passBox.text.toString()
        clearLog()
        thread {
            try {
                log("A) Login page scripts")
                val home = request("GET", "$base/")
                val srcs = Regex("src\\s*=\\s*[\"']([^\"']+\\.js[^\"']*)[\"']", RegexOption.IGNORE_CASE)
                    .findAll(home.body).map { it.groupValues[1] }.toList()
                log("Scripts on page: ${srcs.size}")
                grep("index", home.body, loginKeys, 4)
                for (s in srcs.take(6)) {
                    try {
                        val r = request("GET", absUrl(base, s), null)
                        log("Script $s: ${r.body.length} bytes")
                        grep(s.substringAfterLast('/'), r.body, loginKeys, 4)
                    } catch (e: Exception) {
                        log("Script $s: ERROR")
                    }
                }

                val variants = listOf(
                    "base64" to Base64.encodeToString(pass.toByteArray(), Base64.NO_WRAP),
                    "plain" to pass
                )
                var cookieHeader: String? = null
                for ((name, pw) in variants) {
                    val tk = request("POST", "$base/asp/GetRandCount.asp", "")
                    val token = tk.body.trim().trimStart('\uFEFF').trim()
                    val form = "UserName=${enc(user)}&PassWord=${enc(pw)}" +
                        "&Language=english&x.X_HW_Token=${enc(token)}"
                    val lr = request(
                        "POST", "$base/login.cgi", form,
                        "Cookie=body:Language:english:id=-1;path=/"
                    )
                    val cookies = lr.cookies()
                    log("B) $name: HTTP ${lr.code}, ${lr.body.length} bytes")
                    log("   cookies: " + cookies.joinToString(" | ") { it.take(30) })
                    grep("reply", lr.body, respKeys, 5)
                    val sess = cookies.filter { it.contains("sessionid") || it.contains("sid=") }
                    if (sess.isNotEmpty()) {
                        log("   LOGIN OK with $name")
                        cookieHeader = sess.joinToString("; ")
                        break
                    }
                }
                val ch = cookieHeader
                if (ch == null) {
                    log("Login failed in all variants")
                    return@thread
                }
                afterLogin(base, ch)
            } catch (e: Exception) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
