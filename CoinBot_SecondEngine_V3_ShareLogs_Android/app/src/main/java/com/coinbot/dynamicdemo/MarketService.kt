package com.coinbot.dynamicdemo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

class MarketService : Service() {
    companion object {
        const val ACTION_START = "coinbot.START"
        const val ACTION_STOP = "coinbot.STOP"
        const val ACTION_CLEAR_LOG = "coinbot.CLEAR_LOG"
        private const val CHANNEL = "coinbot_live"
        private const val NOTI_ID = 101
    }

    data class Coin(
        val market: String,
        var price: Double = 0.0,
        var prevSecondPrice: Double = 0.0,
        var secondVolume: Double = 0.0,
        val secPrices: ArrayDeque<Double> = ArrayDeque(),
        val secVolumes: ArrayDeque<Double> = ArrayDeque(),
        val minuteCloses: ArrayDeque<Double> = ArrayDeque(),
        var score: Double = 0.0,
        var prevScore: Double = 0.0,
        var up: Double = 0.0,
        var prevUp: Double = 0.0,
        var down: Double = 0.0,
        var prevDown: Double = 0.0,
        var regime: String = "학습중",
        var targetWeight: Double = 0.0,
        var qty: Double = 0.0,
        var avg: Double = 0.0,
        var lastTradeMs: Long = 0,
        var pendingTarget: Double = 0.0,
        var pendingDirection: Int = 0,
        var pendingCount: Int = 0
    )

    private val btc = Coin("KRW-BTC")
    private val eth = Coin("KRW-ETH")
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var ws: WebSocket? = null
    private var connected = false
    private var reconnectDelay = 2L
    private var started = false
    private var cash = 50000.0
    private val initial = 50000.0
    private var peak = initial
    private var mdd = 0.0
    private val logs = ArrayDeque<String>()
    private var secondCounter = 0
    private var lastEvalMs = 0L
    @Volatile private var shockNow = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                return START_NOT_STICKY
            }
            ACTION_CLEAR_LOG -> {
                clearHistory()
                addLog("기록 초기화")
                saveState()
            }
            ACTION_START, null -> {
                if (!started) startEngine()
            }
        }
        return START_STICKY
    }

    private fun startEngine() {
        started = true
        startForeground(NOTI_ID, notification("연결 시작 중"))
        ensureHistoryHeader()
        addLog("엔진 시작")
        appendHistory("ENGINE_START", null, 0.0)
        seedHistory(btc)
        seedHistory(eth)
        connectWs()
        exec.scheduleAtFixedRate({ onSecond() }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stopEngine() {
        started = false
        connected = false
        ws?.close(1000, "user stop")
        ws = null
        exec.shutdownNow()
        addLog("사용자 정지")
        saveState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun seedHistory(c: Coin) {
        val req = Request.Builder()
            .url("https://api.upbit.com/v1/candles/minutes/1?market=${c.market}&count=200")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { addLog("${c.market} 과거분봉 로드 실패") }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string() ?: return
                    try {
                        val arr = JSONArray(body)
                        synchronized(c) {
                            c.minuteCloses.clear()
                            for (i in arr.length() - 1 downTo 0) {
                                c.minuteCloses.addLast(arr.getJSONObject(i).getDouble("trade_price"))
                            }
                            trim(c.minuteCloses, 240)
                        }
                    } catch (_: Exception) {}
                }
            }
        })
    }

    private fun connectWs() {
        if (!started) return
        val req = Request.Builder().url("wss://api.upbit.com/websocket/v1").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                reconnectDelay = 2L
                val sub = """[
                    {"ticket":"coinbot-second-demo"},
                    {"type":"trade","codes":["KRW-BTC","KRW-ETH"],"is_only_realtime":true},
                    {"format":"DEFAULT"}
                ]""".trimIndent()
                webSocket.send(sub)
                addLog("Upbit WebSocket 연결")
                updateNotification()
            }

            override fun onMessage(webSocket: WebSocket, text: String) { parseTrade(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { parseTrade(bytes.utf8()) }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                addLog("WebSocket 재연결 대기")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!started || exec.isShutdown) return
        val wait = reconnectDelay
        reconnectDelay = min(60L, reconnectDelay * 2)
        exec.schedule({ connectWs() }, wait, TimeUnit.SECONDS)
    }

    private fun parseTrade(raw: String) {
        try {
            val j = JSONObject(raw)
            val market = j.optString("code")
            val c = if (market == "KRW-BTC") btc else if (market == "KRW-ETH") eth else return
            val p = j.optDouble("trade_price", 0.0)
            val v = j.optDouble("trade_volume", 0.0)
            if (p <= 0) return
            synchronized(c) {
                c.price = p
                c.secondVolume += v
            }
        } catch (_: Exception) {}
    }

    private fun onSecond() {
        if (!started) return
        secondCounter++
        sample(btc); sample(eth)

        // 1초 데이터는 항상 상태를 갱신한다.
        // 5초마다 엔진 재평가, 10초마다 모의 자금 재배분.
        val shock = isShock(btc) || isShock(eth)
        shockNow = shock
        if (secondCounter % 5 == 0 || shock) evaluate()
        if (secondCounter % 10 == 0 || shock) rebalance()
        if (secondCounter % 60 == 0) {
            appendMinute(btc); appendMinute(eth)
        }
        saveState()
        if (secondCounter % 5 == 0 || shock) {
            appendHistory(if (shock) "SHOCK_EVAL" else "STATE", null, 0.0)
            updateNotification()
        }
    }

    private fun sample(c: Coin) {
        synchronized(c) {
            if (c.price <= 0) return
            c.prevSecondPrice = c.secPrices.lastOrNull() ?: c.price
            c.secPrices.addLast(c.price)
            c.secVolumes.addLast(c.secondVolume)
            c.secondVolume = 0.0
            trim(c.secPrices, 180)
            trim(c.secVolumes, 180)
        }
    }

    private fun appendMinute(c: Coin) {
        synchronized(c) {
            if (c.price <= 0) return
            c.minuteCloses.addLast(c.price)
            trim(c.minuteCloses, 240)
        }
    }

    private fun isShock(c: Coin): Boolean {
        synchronized(c) {
            if (c.secPrices.size < 20 || c.price <= 0) return false
            val prev = c.secPrices.elementAt(c.secPrices.size - 2)
            val oneSec = abs(c.price / prev - 1.0)
            val rets = returns(c.secPrices.takeLastList(20))
            val vol = max(0.00015, std(rets))
            val vols = c.secVolumes.takeLastList(20)
            val avgV = vols.dropLast(1).average().takeIf { it > 0 } ?: 0.0
            val surge = avgV > 0 && vols.last() > avgV * 3.0
            return oneSec > vol * 3.2 || surge
        }
    }

    private fun evaluate() {
        // 이전 예측을 보존해 "좋아지는 중 / 나빠지는 중"을 판단한다.
        btc.prevScore = btc.score
        eth.prevScore = eth.score
        btc.prevUp = btc.up
        eth.prevUp = eth.up
        btc.prevDown = btc.down
        eth.prevDown = eth.down

        val bi = scoreCoin(btc)
        val ei = scoreCoin(eth)

        btc.score = bi.first
        eth.score = ei.first
        btc.up = bi.second.first
        btc.down = bi.second.second
        eth.up = ei.second.first
        eth.down = ei.second.second
        btc.regime = regime(btc.score)
        eth.regime = regime(eth.score)

        val eq = equity().coerceAtLeast(1.0)
        val btcCurrentW = (btc.qty * btc.price / eq).coerceIn(0.0, 1.0)
        val ethCurrentW = (eth.qty * eth.price / eq).coerceIn(0.0, 1.0)

        // 신규 진입 후보는 0~1 연속값으로 계산한다.
        // 계단식 30/50/70 같은 고정 단계는 사용하지 않는다.
        val bSignal = entrySignal(btc)
        val eSignal = entrySignal(eth)

        val totalSignal = bSignal + eSignal
        var bw = 0.0
        var ew = 0.0

        if (totalSignal > 0.0) {
            val riskBudget = (0.18 + 0.74 * max(bSignal, eSignal))
                .coerceIn(0.10, 0.92)
            bw = riskBudget * bSignal / totalSignal
            ew = riskBudget * eSignal / totalSignal
        }

        // 핵심 변경:
        // 보유 중 손실 구간에서는 "점수 하락 = 즉시 청산"하지 않는다.
        // 일반 하락은 대기하고, 회복 신호가 좋아지면 추가매수한다.
        bw = holdingAwareTarget(btc, btcCurrentW, bw)
        ew = holdingAwareTarget(eth, ethCurrentW, ew)

        // 두 코인 합계가 92%를 넘으면 현재 보유분을 존중하면서 비례 조정.
        val sum = bw + ew
        if (sum > 0.92) {
            val scale = 0.92 / sum
            bw *= scale
            ew *= scale
        }

        btc.targetWeight = adaptiveCommitTarget(btc, bw.coerceIn(0.0, 0.92))
        eth.targetWeight = adaptiveCommitTarget(eth, ew.coerceIn(0.0, 0.92))
        lastEvalMs = System.currentTimeMillis()
    }


    private fun adaptiveCommitTarget(c: Coin, proposed: Double): Double {
        val current = c.targetWeight
        val delta = proposed - current
        val absDelta = abs(delta)

        // 작은 흔들림은 매매하지 않고 목표 자체를 유지한다.
        val noiseBand = (0.035 + 0.10 * c.down.coerceIn(0.0, 0.12)).coerceIn(0.035, 0.055)
        if (absDelta < noiseBand) {
            c.pendingCount = 0
            return current
        }

        val direction = if (delta > 0) 1 else -1

        if (direction == c.pendingDirection) {
            c.pendingCount += 1
        } else {
            c.pendingDirection = direction
            c.pendingCount = 1
        }
        c.pendingTarget = proposed

        // 변화가 클수록 더 빨리 반영하고, 미세 변화일수록 여러 번 확인한다.
        val required = when {
            shockNow && absDelta >= 0.18 -> 1
            absDelta >= 0.25 -> 1
            absDelta >= 0.12 -> 2
            else -> 3
        }

        // 손실 중 목표 축소는 일반적으로 HOLD. 비상방어는 holdingAwareTarget에서만 허용.
        val pnlPct = if (c.avg > 0.0 && c.price > 0.0) c.price / c.avg - 1.0 else 0.0
        if (c.qty > 0.0 && pnlPct < 0.0 && proposed < current) {
            c.pendingCount = 0
            return current
        }

        if (c.pendingCount < required) return current

        c.pendingCount = 0

        // 한 번에 목표 전체로 점프하지 않고 신호 강도에 따라 연속적으로 이동.
        val stepFactor = when {
            absDelta >= 0.30 -> 0.80
            absDelta >= 0.18 -> 0.65
            absDelta >= 0.10 -> 0.50
            else -> 0.35
        }

        val committed = current + delta * stepFactor
        return committed.coerceIn(0.0, 0.92)
    }

    private fun entrySignal(c: Coin): Double {
        if (c.price <= 0) return 0.0

        val score01 = ((c.score + 0.15) / 1.15).coerceIn(0.0, 1.0)
        val upsideEdge = if (c.up + c.down > 0.0)
            (c.up / (c.up + c.down)).coerceIn(0.0, 1.0) else 0.5

        val improving = ((c.score - c.prevScore) * 2.2).coerceIn(-0.35, 0.35)
        val targetDrift = if (c.prevUp > 0.0)
            ((c.up / c.prevUp) - 1.0).coerceIn(-0.25, 0.25) else 0.0
        val downsideDrift = if (c.prevDown > 0.0)
            ((c.down / c.prevDown) - 1.0).coerceIn(-0.25, 0.25) else 0.0

        return (
            0.55 * score01 +
            0.30 * upsideEdge +
            0.15 * (0.5 + improving + targetDrift - downsideDrift)
        ).coerceIn(0.0, 1.0)
    }

    private fun holdingAwareTarget(c: Coin, currentW: Double, freshTarget: Double): Double {
        if (c.qty <= 0.0 || c.avg <= 0.0 || c.price <= 0.0) {
            return freshTarget
        }

        val pnlPct = c.price / c.avg - 1.0
        val scoreDelta = c.score - c.prevScore
        val upDrift = if (c.prevUp > 0.0) c.up / c.prevUp - 1.0 else 0.0
        val downDrift = if (c.prevDown > 0.0) c.down / c.prevDown - 1.0 else 0.0

        // 변동성/예상하방에 따라 동적으로 안전 무효화 구간을 만든다.
        // 평범한 하락에는 사용하지 않고, 비정상적인 붕괴에만 적용한다.
        val catastrophicLimit = -max(0.035, min(0.09, c.down * 2.6))
        val catastrophic =
            pnlPct <= catastrophicLimit &&
            c.score < -0.70 &&
            downDrift > 0.12

        if (catastrophic) {
            addLog("${c.market} 안전 무효화 감지 → 방어 축소")
            return (currentW * 0.25).coerceAtLeast(0.0)
        }

        // 손실 중이면 하락 점수만으로 매도하지 않는다.
        if (pnlPct < 0.0) {
            val recoveryImproving =
                scoreDelta > 0.03 ||
                upDrift > 0.03 ||
                (c.score > -0.10 && downDrift <= 0.05)

            if (recoveryImproving) {
                // 회복 기대가 좋아질 때만 물타기.
                // 추가 비중도 연속적으로 계산한다.
                val recoveryStrength = (
                    0.45 * ((c.score + 1.0) / 2.0).coerceIn(0.0, 1.0) +
                    0.35 * (0.5 + scoreDelta * 3.0).coerceIn(0.0, 1.0) +
                    0.20 * (0.5 + upDrift - downDrift).coerceIn(0.0, 1.0)
                ).coerceIn(0.0, 1.0)

                val addRoom = (0.92 - currentW).coerceAtLeast(0.0)
                val desiredAdd = addRoom * recoveryStrength * 0.45
                return max(currentW, currentW + desiredAdd)
            }

            // 회복 신호가 아직 없으면 그냥 대기.
            return currentW
        }

        // 수익 구간에서는 예상상방이 약해지거나 점수가 꺾일 때만 익절.
        val weakening =
            scoreDelta < -0.04 ||
            upDrift < -0.05 ||
            (c.score < 0.0 && downDrift > 0.05)

        if (weakening) {
            val weakness = (
                max(0.0, -scoreDelta * 2.5) +
                max(0.0, -upDrift) +
                max(0.0, downDrift)
            ).coerceIn(0.0, 1.0)

            val keepRatio = (1.0 - 0.75 * weakness).coerceIn(0.20, 1.0)
            return min(freshTarget, currentW * keepRatio)
        }

        // 수익 중이고 상승 기대가 유지되면 freshTarget을 따라가되
        // 불필요한 축소는 하지 않는다.
        return max(currentW * 0.85, freshTarget)
    }

    private fun scoreCoin(c: Coin): Pair<Double, Pair<Double, Double>> {
        synchronized(c) {
            if (c.price <= 0 || c.secPrices.size < 30) return 0.0 to (0.0 to 0.0)
            val s = c.secPrices.toList()
            val p = c.price
            val p5 = s[max(0, s.size - 6)]
            val p15 = s[max(0, s.size - 16)]
            val p30 = s[max(0, s.size - 30)]
            val m5 = p / p5 - 1.0
            val m15 = p / p15 - 1.0
            val m30 = p / p30 - 1.0
            val secVol = max(0.0002, std(returns(s.takeLastList(30))))

            var minScore = 0.0
            if (c.minuteCloses.size >= 20) {
                val m = c.minuteCloses.toList()
                val sma5 = m.takeLast(5).average()
                val sma20 = m.takeLast(20).average()
                minScore = tanh(((p / sma5 - 1) * 0.8 + (sma5 / sma20 - 1) * 1.2) / 0.006)
            }

            var fiveScore = 0.0
            if (c.minuteCloses.size >= 30) {
                val m = c.minuteCloses.toList()
                val fives = m.chunked(5).map { it.last() }
                if (fives.size >= 6) {
                    val last3 = fives.takeLast(3).average()
                    val last6 = fives.takeLast(6).average()
                    fiveScore = tanh((last3 / last6 - 1) / 0.008)
                }
            }

            val micro = tanh((0.45*m5 + 0.35*m15 + 0.20*m30) / (secVol * 5.0))
            val score = (0.50*micro + 0.30*minScore + 0.20*fiveScore).coerceIn(-1.0, 1.0)

            val minuteVol = if (c.minuteCloses.size >= 20)
                std(returns(c.minuteCloses.toList().takeLastList(20))) else secVol * 4
            val base = max(secVol * sqrt(60.0), minuteVol)
            val up = max(0.001, base * (2.2 + 1.4*max(score, 0.0)))
            val down = max(0.001, base * (1.9 + 1.5*max(-score, 0.0)))
            return score to (up to down)
        }
    }

    private fun rebalance() {
        val eq = equity()
        tradeTo(btc, eq * btc.targetWeight, "BTC")
        tradeTo(eth, eq * eth.targetWeight, "ETH")
    }

    private fun tradeTo(c: Coin, targetValue: Double, name: String) {
        synchronized(c) {
            if (c.price <= 0) return
            val now = System.currentTimeMillis()
            val current = c.qty * c.price
            val diff = targetValue - current

            val targetGap = if (equity() > 0) abs(diff) / equity() else 0.0
            val adaptiveCooldown = when {
                shockNow && targetGap > 0.20 -> 4000L
                targetGap > 0.20 -> 10000L
                targetGap > 0.10 -> 18000L
                else -> 30000L
            }
            if (now - c.lastTradeMs < adaptiveCooldown) return

            val minTrade = max(1500.0, equity() * (0.035 + min(0.025, c.down)))
            if (abs(diff) < minTrade) return
            val fee = 0.0005
            val slip = 0.0002

            if (diff > 0) {
                val spend = min(diff, cash)
                if (spend < 1000) return
                val execPrice = c.price * (1 + slip)
                val net = spend * (1 - fee)
                val q = net / execPrice
                val oldCost = c.avg * c.qty
                c.qty += q
                c.avg = if (c.qty > 0) (oldCost + execPrice*q) / c.qty else 0.0
                cash -= spend
                c.lastTradeMs = now
                addLog("$name 모의매수 ${spend.roundToInt()}원 → ${f1(c.targetWeight*100)}%")
                appendHistory("BUY", name, spend)
            } else {
                val pnlPct = if (c.avg > 0.0) c.price / c.avg - 1.0 else 0.0
                val dynamicEmergency = -max(0.035, min(0.09, c.down * 2.6))
                val emergency = pnlPct <= dynamicEmergency && c.score < -0.70

                // 일반 손실 구간에서는 매도 금지: 대기 또는 회복 시 추가매수.
                if (pnlPct < 0.0 && !emergency) {
                    return
                }

                val sellValue = min(-diff, current)
                if (sellValue < 1000 || c.qty <= 0) return
                val q = min(c.qty, sellValue / c.price)
                val gross = q * c.price * (1 - slip)
                cash += gross * (1 - fee)
                c.qty -= q
                if (c.qty < 1e-12) { c.qty = 0.0; c.avg = 0.0 }
                c.lastTradeMs = now
                addLog("$name ${if (emergency) "비상방어" else "익절"} ${gross.roundToInt()}원 → ${f1(c.targetWeight*100)}%")
                appendHistory(if (emergency) "EMERGENCY_SELL" else "TAKE_PROFIT", name, gross)
            }
        }
    }

    private fun equity(): Double = cash + btc.qty*btc.price + eth.qty*eth.price

    private fun saveState() {
        val eq = equity()
        peak = max(peak, eq)
        mdd = min(mdd, eq/peak - 1.0)
        val now = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        val j = JSONObject()
        j.put("connected", connected)
        j.put("lastUpdate", now)
        j.put("mode", "1초수신/5초판단/10초재배분")
        j.put("equity", eq)
        j.put("cash", cash)
        j.put("pnl", eq - initial)
        j.put("mdd", mdd)
        j.put("btc", coinJson(btc))
        j.put("eth", coinJson(eth))
        val bw = if (eq > 0) btc.qty*btc.price/eq else 0.0
        val ew = if (eq > 0) eth.qty*eth.price/eq else 0.0
        val cw = if (eq > 0) cash/eq else 1.0
        j.put("allocation", "BTC ${f1(bw*100)}% · ETH ${f1(ew*100)}% · 현금 ${f1(cw*100)}%")
        val arr = JSONArray()
        synchronized(logs) { logs.forEach { arr.put(it) } }
        j.put("logs", arr)
        getSharedPreferences("coinbot_state", Context.MODE_PRIVATE)
            .edit().putString("state", j.toString()).apply()
    }

    private fun coinJson(c: Coin) = JSONObject().apply {
        put("price", c.price); put("score", c.score); put("targetWeight", c.targetWeight)
        put("up", c.up); put("down", c.down); put("regime", c.regime)
        put("avg", c.avg); put("qty", c.qty)
    }

    private fun addLog(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        synchronized(logs) {
            logs.addFirst("$t  $s")
            while (logs.size > 20) logs.removeLast()
        }
    }


    private fun ensureHistoryHeader() {
        val f = java.io.File(filesDir, "coinbot_history.csv")
        if (!f.exists() || f.length() == 0L) {
            f.writeText(
                "timestamp,event,coin,amount_krw,equity,cash,balance_pnl," +
                "btc_price,btc_score,btc_target,btc_qty,btc_avg,btc_up,btc_down,btc_regime," +
                "eth_price,eth_score,eth_target,eth_qty,eth_avg,eth_up,eth_down,eth_regime,shock\n"
            )
        }
    }

    private fun clearHistory() {
        val f = java.io.File(filesDir, "coinbot_history.csv")
        if (f.exists()) f.delete()
        ensureHistoryHeader()
    }

    private fun csv(v: Any?): String {
        val raw = v?.toString() ?: ""
        return "\"" + raw.replace("\"", "\"\"") + "\""
    }

    private fun appendHistory(event: String, coin: String?, amount: Double) {
        try {
            ensureHistoryHeader()
            val f = java.io.File(filesDir, "coinbot_history.csv")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA).format(Date())
            val eq = equity()
            val row = listOf(
                ts, event, coin ?: "", f1(amount), f1(eq), f1(cash), f1(eq - initial),
                f1(btc.price), f1(btc.score), f1(btc.targetWeight), f1(btc.qty), f1(btc.avg), f1(btc.up), f1(btc.down), btc.regime,
                f1(eth.price), f1(eth.score), f1(eth.targetWeight), f1(eth.qty), f1(eth.avg), f1(eth.up), f1(eth.down), eth.regime,
                shockNow.toString()
            ).joinToString(",") { csv(it) }
            f.appendText(row + "\n")
        } catch (_: Exception) {}
    }

    private fun regime(s: Double) = when {
        s > 0.38 -> "상승"
        s > 0.10 -> "약상승"
        s < -0.38 -> "하락"
        s < -0.10 -> "약하락"
        else -> "중립"
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CoinBot 실시간 모의엔진")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification() {
        val b = if (btc.price > 0) "${(btc.price/1_000_000).let{f1(it)}}M" else "-"
        val e = if (eth.price > 0) "${(eth.price/1_000_000).let{f1(it)}}M" else "-"
        val txt = "${if (connected) "LIVE" else "RECONNECT"} · BTC $b · ETH $e · ${allocationShort()}"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTI_ID, notification(txt))
    }

    private fun allocationShort(): String {
        val eq = equity()
        if (eq <= 0) return "현금"
        val bw = btc.qty*btc.price/eq*100
        val ew = eth.qty*eth.price/eq*100
        return "B${bw.roundToInt()} E${ew.roundToInt()} C${(100-bw-ew).roundToInt()}"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "실시간 모의투자 엔진", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        addLog("시스템 서비스 시간 제한")
        saveState()
        stopEngine()
    }

    override fun onDestroy() {
        connected = false
        ws?.cancel()
        if (!exec.isShutdown) exec.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun <T> trim(q: ArrayDeque<T>, n: Int) { while (q.size > n) q.removeFirst() }
    private fun List<Double>.takeLastList(n: Int) = if (size <= n) this else subList(size-n, size)
    private fun ArrayDeque<Double>.takeLastList(n: Int) = toList().takeLastList(n)
    private fun returns(x: List<Double>): List<Double> {
        if (x.size < 2) return emptyList()
        val out = ArrayList<Double>(x.size-1)
        for (i in 1 until x.size) if (x[i-1] != 0.0) out.add(x[i]/x[i-1]-1.0)
        return out
    }
    private fun std(x: List<Double>): Double {
        if (x.isEmpty()) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it-m)*(it-m) } / x.size)
    }
    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)
}
