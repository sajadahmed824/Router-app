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
    private var cookieHeader: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)

        val title = TextView(this)
        title.text = "Router App - Dump v0.5"
        title.textSize = 20f
        root.addView(title)

        ipBox = EditText(this)
        ipBox.hint = "Router IP"
        ipBox.setText("192.168.100.1")
        ipBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        root.addView(ipBox)

        userBox = EditText(this)
        userBox.hint = "Username"
        userBox.setText("telecomadmin")
        userBox.inputType = InputType.TYPE_CLASS_TEXT
        root.addView(userBox)

        passBox = EditText(this)
        passBox.hint = "Password"
        passBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(passBox)

        val b1 = Button(this)
        b1.text = "1) Login"
        b1.setOnClickListener { doLogin() }
        root.addView(b1)

        val b2 = Button(this)
        b2.text = "2) Copy device list"
        b2.setOnClickListener { dumpAndCopy(ROUTER_DEVLIST, "POST") }
        root.addView(b2)

        val b3 = Button(this)
        b3.text = "3) Copy WAN info"
        b3.setOnClickListener { dumpAndCopy("/html/bbsp/waninfo/waninfo.asp", "GET") }
        root.addView(b3)

        val b4 = Button(this)
        b4.text = "4) Copy optical info"
        b4.setOnClickListener { dumpAndCopy("/html/amp/opticinfo/opticinfo.asp", "GET") }
        root.addView(b4)

        val b5 = Button(this)
        b5.text = "5) Copy device-mgmt page"
        b5.setOnClickListener { dumpAndCopy("/html/ssmp/deviceinfo/deviceinfo.asp", "GET") }
        root.addView(b5)

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

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dump", text))
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun wifiNetwork(): Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
        }
        return null
    }

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        cookie: String? = null,
        referer: String? = null
    ): Resp {
        val net = wifiNetwork() ?: throw Exception("Wi-Fi is not connected")
        val conn = net.openConnection(URL(url)) as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        if (referer != null) conn.setRequestProperty("Referer", referer)
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

    private fun doLogin() {
        val base = "http://" + ipBox.text.toString().trim()
        val user = userBox.text.toString()
        val pass = passBox.text.toString()
        clearLog()
        thread {
            try {
                val tk = request("POST", "$base/asp/GetRandCount.asp", "")
                val token = tk.body.trim().trimStart('\uFEFF').trim()
                val pw64 = Base64.encodeToString(pass.toByteArray(), Base64.NO_WRAP)
                val form = "UserName=${enc(user)}&PassWord=${enc(pw64)}" +
                    "&Language=english&x.X_HW_Token=${enc(token)}"
                val lr = request(
                    "POST", "$base/login.cgi", form,
                    "Cookie=body:Language:english:id=-1;path=/"
                )
                val sess = lr.cookies().filter { it.contains("sessionid") || it.contains("sid=") }
                if (sess.isEmpty()) {
                    log("Login failed")
                    return@thread
                }
                cookieHeader = sess.joinToString("; ")
                log("Login OK. Now press buttons 2/3/4/5 one by one.")
                log("Each press copies full data to clipboard - paste it in chat.")
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            }
        }
    }

    private fun dumpAndCopy(path: String, method: String) {
        val base = "http://" + ipBox.text.toString().trim()
        val ch = cookieHeader
        if (ch == null) {
            log("Please press 1) Login first")
            return
        }
        clearLog()
        thread {
            try {
                val r = request(
                    method, base + path,
                    if (method == "POST") "" else null,
                    ch, "$base/"
                )
                copyToClipboard(r.body)
                log("$path")
                log("HTTP ${r.code}, ${r.body.length} bytes")
                log("Copied full content to clipboard.")
                log("Now paste it (long-press -> Paste) into the chat.")
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            }
        }
    }

    companion object {
        const val ROUTER_DEVLIST = "/html/bbsp/common/GetLanUserDevInfo.asp"
    }
}
