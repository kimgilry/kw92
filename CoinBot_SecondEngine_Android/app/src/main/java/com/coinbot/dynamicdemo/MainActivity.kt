package com.coinbot.dynamicdemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var account: TextView
    private lateinit var btc: TextView
    private lateinit var eth: TextView
    private lateinit var allocation: TextView
    private lateinit var logView: TextView

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(10, 18, 32)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 40)
        }

        fun title(t: String, size: Float = 28f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE); setPadding(0, 10, 0, 10)
        }
        fun cardText() = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.rgb(225, 235, 250))
            setBackgroundColor(Color.rgb(18, 31, 52))
            setPadding(22, 20, 22, 20)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; textSize = 17f
            setOnClickListener { action() }
        }

        root.addView(title("CoinBot Second Engine", 30f))
        root.addView(title("1초 수신 · 5초 재평가 · 10초 동적 재배분 · 급변 즉시 재평가", 14f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
        val start = button("백그라운드 데모 시작") {
            val i = Intent(this, MarketService::class.java).setAction(MarketService.ACTION_START)
            ContextCompat.startForegroundService(this, i)
        }
        val stop = button("정지") {
            startService(Intent(this, MarketService::class.java).setAction(MarketService.ACTION_STOP))
        }
        buttons.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f))
        root.addView(buttons)

        status = cardText(); account = cardText(); btc = cardText(); eth = cardText(); allocation = cardText(); logView = cardText()
        listOf(status, account, btc, eth, allocation, logView).forEach {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 14, 0, 0)
            root.addView(it, lp)
        }

        root.addView(title(
            "실제 주문은 하지 않는 모의투자 전용입니다. 앱을 시작한 뒤 홈 화면으로 나가도 포그라운드 서비스가 시세 스트림을 유지하며, 알림에 연결 상태가 표시됩니다. 제조사 배터리 절전/강제종료 시에는 연결이 끊길 수 있습니다.",
            13f
        ))
        scroll.addView(root)
        return scroll
    }

    private fun render() {
        val prefs = getSharedPreferences("coinbot_state", MODE_PRIVATE)
        val raw = prefs.getString("state", null) ?: return
        try {
            val j = JSONObject(raw)
            val nf = NumberFormat.getNumberInstance(Locale.KOREA)
            val connected = j.optBoolean("connected")
            status.text = buildString {
                append(if (connected) "● LIVE 연결 정상" else "● 연결 대기/재연결 중")
                append("\n마지막 수신: ").append(j.optString("lastUpdate", "-"))
                append("\n모드: ").append(j.optString("mode", "-"))
            }
            account.text = "총 평가금 ${nf.format(j.optDouble("equity", 50000.0).toLong())}원" +
                    "\n현금 ${nf.format(j.optDouble("cash", 50000.0).toLong())}원" +
                    "\n누적손익 ${nf.format(j.optDouble("pnl", 0.0).toLong())}원" +
                    "\nMDD ${String.format(Locale.KOREA, "%.2f%%", j.optDouble("mdd", 0.0) * 100)}"

            fun coinText(name: String, o: JSONObject): String {
                return "$name  ${nf.format(o.optDouble("price", 0.0).toLong())} KRW" +
                        "\n엔진점수 ${String.format(Locale.KOREA, "%.0f", o.optDouble("score", 0.0) * 100)}" +
                        "  |  목표비중 ${String.format(Locale.KOREA, "%.1f%%", o.optDouble("targetWeight", 0.0) * 100)}" +
                        "\n예상범위 -${String.format(Locale.KOREA, "%.2f%%", o.optDouble("down", 0.0) * 100)}" +
                        " / +${String.format(Locale.KOREA, "%.2f%%", o.optDouble("up", 0.0) * 100)}" +
                        "\n상태 ${o.optString("regime", "-")}"
            }
            btc.text = coinText("BTC", j.getJSONObject("btc"))
            eth.text = coinText("ETH", j.getJSONObject("eth"))
            allocation.text = j.optString("allocation", "현금 100%")
            logView.text = "최근 판단 기록\n" + j.optJSONArray("logs")?.let { arr ->
                (0 until arr.length()).joinToString("\n") { arr.optString(it) }
            }.orEmpty()
        } catch (_: Exception) {}
    }
}
