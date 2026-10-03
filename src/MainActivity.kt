package com.ali.routerapp

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class Resp(val code: Int, val headers: Map<String, List<String>>, val body: String) {
    fun cookies(): List<String> =
        headers.entries.filter { it.key.equals("Set-Cookie", true) }
            .flatMap { it.value }.map { it.substringBefore(";") }
}

data class DevRow(
    val ip: String, val mac: String, val host: String,
    val alias: String, val status: String, val portType: String
)

class MainActivity : Activity() {

    private lateinit var ipBox: EditText
    private lateinit var userBox: EditText
    private lateinit var passBox: EditText
    private lateinit var loginSection: LinearLayout
    private lateinit var mainSection: LinearLayout
    private lateinit var deviceList: LinearLayout
    private lateinit var wanText: TextView
    private lateinit var opticText: TextView
    private lateinit var statusText: TextView
    private var cookieHeader: String? = null
    private var baseUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)

        val title = TextView(this)
        title.text = "Router Manager"
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        // ---- Login section ----
        loginSection = LinearLayout(this)
        loginSection.orientation = LinearLayout.VERTICAL

        ipBox = EditText(this)
        ipBox.hint = "Router IP"
        ipBox.setText("192.168.100.1")
        ipBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        loginSection.addView(ipBox)

        userBox = EditText(this)
        userBox.hint = "Username"
        userBox.setText("telecomadmin")
        loginSection.addView(userBox)

        passBox = EditText(this)
        passBox.hint = "Password"
        passBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        loginSection.addView(passBox)

        val loginBtn = Button(this)
        loginBtn.text = "Login"
        loginBtn.setOnClickListener { doLogin() }
        loginSection.addView(loginBtn)

        statusText = TextView(this)
        statusText.setPadding(0, pad / 2, 0, pad / 2)
        loginSection.addView(statusText)

        root.addView(loginSection)

        // ---- Main (after login) section ----
        mainSection = LinearLayout(this)
        mainSection.orientation = LinearLayout.VERTICAL
        mainSection.visibility = View.GONE

        val refreshBtn = Button(this)
        refreshBtn.text = "Refresh"
        refreshBtn.setOnClickListener { loadEverything() }
        mainSection.addView(refreshBtn)

        val wanTitle = sectionTitle("Internet (WAN)")
        mainSection.addView(wanTitle)
        wanText = TextView(this)
        mainSection.addView(wanText)

        val opticTitle = sectionTitle("Fiber Signal")
        mainSection.addView(opticTitle)
        opticText = TextView(this)
        mainSection.addView(opticText)

        val devTitle = sectionTitle("Connected Devices")
        mainSection.addView(devTitle)
        deviceList = LinearLayout(this)
        deviceList.orientation = LinearLayout.VERTICAL
        mainSection.addView(deviceList)

        root.addView(mainSection)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun sectionTitle(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 17f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, 40, 0, 10)
        return t
    }

    private fun setStatus(s: String) {
        runOnUiThread { statusText.text = s }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun wifiNetwork(): Network? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
        }
        return null
    }

    private fun request(
        method: String, url: String, body: String? = null,
        cookie: String? = null, referer: String? = null, net: Network? = wifiNetwork()
    ): Resp {
        val n = net ?: throw Exception("Wi-Fi is not connected")
        val conn = n.openConnection(URL(url)) as HttpURLConnection
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
        for ((k, v) in conn.headerFields) if (k != null) hdrs[k] = v
        conn.disconnect()
        return Resp(code, hdrs, text)
    }

    // decode router's \x2e style hex-escaped strings
    private fun decodeHex(s: String): String {
        val r = Regex("\\\\x([0-9A-Fa-f]{2})")
        return r.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }
    }

    // split a JS call's argument list respecting quotes
    private fun parseArgs(inner: String): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '"') { inQuotes = !inQuotes; i++; continue }
            if (c == ',' && !inQuotes) { out.add(decodeHex(cur.toString())); cur = StringBuilder(); i++; continue }
            cur.append(c); i++
        }
        out.add(decodeHex(cur.toString()))
        return out
    }

    private fun doLogin() {
        val ip = ipBox.text.toString().trim()
        baseUrl = "http://$ip"
        val user = userBox.text.toString()
        val pass = passBox.text.toString()
        setStatus("Logging in...")
        thread {
            try {
                val tk = request("POST", "$baseUrl/asp/GetRandCount.asp", "")
                val token = tk.body.trim().trimStart('\uFEFF').trim()
                val pw64 = Base64.encodeToString(pass.toByteArray(), Base64.NO_WRAP)
                val form = "UserName=${enc(user)}&PassWord=${enc(pw64)}&Language=english&x.X_HW_Token=${enc(token)}"
                val lr = request("POST", "$baseUrl/login.cgi", form, "Cookie=body:Language:english:id=-1;path=/")
                val sess = lr.cookies().filter { it.contains("sessionid") || it.contains("sid=") }
                if (sess.isEmpty()) {
                    setStatus("Login failed. Check username/password.")
                    return@thread
                }
                cookieHeader = sess.joinToString("; ")
                setStatus("")
                runOnUiThread {
                    loginSection.visibility = View.GONE
                    mainSection.visibility = View.VISIBLE
                }
                loadEverything()
            } catch (e: Exception) {
                setStatus("ERROR: ${e.message}")
            }
        }
    }

    private fun loadEverything() {
        loadDevices()
        loadOptical()
        loadPublicIp()
    }

    private fun loadDevices() {
        val ch = cookieHeader ?: return
        thread {
            try {
                val r = request("POST", "$baseUrl/html/bbsp/common/GetLanUserDevInfo.asp", "", ch, "$baseUrl/")
                val rows = ArrayList<DevRow>()
                val regex = Regex("new USERDevice\\(([^)]*)\\)")
                for (m in regex.findAll(r.body)) {
                    val a = parseArgs(m.groupValues[1])
                    if (a.size < 10) continue
                    rows.add(
                        DevRow(
                            ip = a[1], mac = a[2], portType = a[7],
                            status = a[6], host = a[9], alias = a[13].takeIf { a.size > 13 } ?: "--"
                        )
                    )
                }
                runOnUiThread { renderDevices(rows) }
            } catch (e: Exception) {
                runOnUiThread {
                    deviceList.removeAllViews()
                    deviceList.addView(plainText("Could not load devices: ${e.message}"))
                }
            }
        }
    }

    private fun renderDevices(rows: List<DevRow>) {
        deviceList.removeAllViews()
        if (rows.isEmpty()) {
            deviceList.addView(plainText("No devices found."))
            return
        }
        for (d in rows) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(20, 20, 20, 20)
            card.setBackgroundColor(Color.parseColor("#F2F2F2"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 12)
            card.layoutParams = lp

            val name = if (d.alias != "--" && d.alias.isNotBlank()) d.alias
                       else if (d.host != "--" && d.host.isNotBlank()) d.host
                       else d.mac
            val nameView = TextView(this)
            nameView.text = name
            nameView.textSize = 16f
            nameView.setTypeface(null, Typeface.BOLD)
            card.addView(nameView)

            val online = d.status.equals("Online", true)
            val info = TextView(this)
            info.text = "${d.ip}  •  ${if (online) "🟢 Online" else "⚪ Offline"}  •  ${if (d.portType == "WIFI") "WiFi" else "Ethernet"}"
            info.textSize = 13f
            card.addView(info)

            val blockBtn = Button(this)
            blockBtn.text = "Block / Unblock (coming soon)"
            blockBtn.isEnabled = false
            card.addView(blockBtn)

            deviceList.addView(card)
        }
    }

    private fun loadOptical() {
        val ch = cookieHeader ?: return
        thread {
            try {
                val r = request("GET", "$baseUrl/html/amp/opticinfo/opticinfo.asp", null, ch, "$baseUrl/")
                val m = Regex("new stOpticInfo\\(([^)]*)\\)").find(r.body)
                if (m == null) {
                    runOnUiThread { opticText.text = "Not available" }
                    return@thread
                }
                val a = parseArgs(m.groupValues[1])
                // domain,LinkStatus,tx,rx,voltage,temp,bias,rfRx,rfOut,VendorName,VendorSN,...
                val tx = a.getOrNull(2) ?: "--"
                val rx = a.getOrNull(3) ?: "--"
                val temp = a.getOrNull(5) ?: "--"
                val vendor = a.getOrNull(9)?.trim() ?: "--"
                runOnUiThread {
                    opticText.text = "Signal (RX): $rx dBm\nTransmit: $tx dBm\nTemperature: $temp°C\nModule: $vendor"
                }
            } catch (e: Exception) {
                runOnUiThread { opticText.text = "Could not load: ${e.message}" }
            }
        }
    }

    private fun loadPublicIp() {
        thread {
            try {
                val r = request("GET", "https://api.ipify.org")
                runOnUiThread { wanText.text = "Public IP: ${r.body.trim()}" }
            } catch (e: Exception) {
                runOnUiThread { wanText.text = "Could not check public IP (needs internet)" }
            }
        }
    }

    private fun plainText(s: String): TextView {
        val t = TextView(this)
        t.text = s
        return t
    }
}
