package dev.lab.location.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import dev.lab.location.core.CheckinContext
import dev.lab.location.core.ClientEnv
import dev.lab.location.core.PoseSample
import dev.lab.location.core.RiskResult
import dev.lab.location.core.RiskScorer
import dev.lab.location.core.Thresholds

/**
 * 演示界面：左边造攻击数据，右边验证检测规则。
 *
 * <p>流程是刻意的「注入 → 采集 → 评分」闭环，因为检测器的正确性只能靠这两件事同时验证：
 * 对攻击数据要能抓住，对正常数据必须毫发无伤。只有前者会变成误伤机器，
 * 只有后者等于没有检测。
 *
 * <p>本界面刻意用原生 Activity + Handler，不引入 androidx 与协程 —— 依赖越少，
 * 越容易在任意环境里一次构建通过。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_LOCATION = 1001

        /** 演示用的基准坐标（不指向任何具体地点）。 */
        private const val LAB_LAT = 39.9042
        private const val LAB_LNG = 116.4074
    }

    /** 服务端场景预设：模拟服务端的统计口径，用于演示同一批位置样本在不同上下文下的分级差异。 */
    private data class Scenario(val label: String, val ctx: CheckinContext)

    private val scenarios = listOf(
        Scenario("正常：单设备单账号，花名册外无聚集", CheckinContext(12.0, 200L, 300L, 1, 0, 5.0, 30)),
        Scenario("代签：同设备 4 账号 + 网格聚集 6 + 末尾 8 秒", CheckinContext(12.0, 200L, 8L, 4, 6, 5.0, 30)),
        Scenario("反例：忘记排除花名册（网格 40）", CheckinContext(12.0, 200L, 300L, 1, 40, 5.0, 30)),
        Scenario("远端代理：IP 距申报坐标 800 公里", CheckinContext(800.0, 200L, 300L, 1, 0, 5.0, 30))
    )

    private lateinit var tvStatus: TextView
    private lateinit var tvOutput: TextView
    private lateinit var spinnerScenario: Spinner

    private lateinit var mockController: MockLocationController
    private lateinit var gnssWatcher: GnssWatcher
    private lateinit var collector: SignalCollector

    private val scorer = RiskScorer(Thresholds.defaults())
    private val handler = Handler(Looper.getMainLooper())

    /** 周期性刷新状态行：授权状态、注入状态、GNSS 卫星数。 */
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvOutput = findViewById(R.id.tvOutput)
        spinnerScenario = findViewById(R.id.spinnerScenario)

        mockController = MockLocationController(this)
        gnssWatcher = GnssWatcher(this)
        collector = SignalCollector(this, gnssWatcher)

        spinnerScenario.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            scenarios.map { it.label }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        bindButtons()
        ensureLocationPermission()
        handler.post(statusTicker)
    }

    // ------------------------------------------------------------------ 按钮

    private fun bindButtons() {
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!MockLocationController.isAllowed(this)) {
                tvOutput.text = buildString {
                    appendLine("尚未获得模拟位置授权。两条途径任选其一：")
                    appendLine()
                    appendLine("1) 开发者选项 → 选择模拟位置信息应用 → 选中本应用")
                    appendLine("2) adb shell appops set $packageName android:mock_location allow")
                    appendLine()
                    appendLine("授权后无需重启，状态行会立刻变为「是」。")
                    appendLine("注意：授权开关本身就是检测器要读的信号之一（DEV_OPTIONS_ON / MOCK_APP_OP）。")
                }
                return@setOnClickListener
            }
            runCatching { mockController.start(MockLocationController.GPS_ONLY) }
                .onSuccess {
                    refreshStatus()
                    tvOutput.text = buildString {
                        appendLine("已注册测试 provider（gps），可以推送坐标或回放路线。")
                        appendLine("注入的位置会带 mock 标记——这是平台保证，SDK 层面去不掉。")
                        appendLine()
                        appendLine("用完务必点「停止 mock 并清理」：残留的测试 provider 会让整台设备的")
                        appendLine("定位一直返回假坐标，直到重启或被系统清掉。")
                    }
                }
                .onFailure {
                    tvOutput.text = "启动失败：${it.javaClass.simpleName} ${it.message}"
                }
        }

        findViewById<Button>(R.id.btnPush).setOnClickListener {
            runCatching {
                mockController.push(
                    lat = LAB_LAT,
                    lng = LAB_LNG,
                    accuracyM = 8f,
                    speedMps = 0f,
                    bearingDeg = 0f
                )
            }.onSuccess {
                tvOutput.text = "已注入一个静止点：$LAB_LAT, $LAB_LNG\n" +
                    "打开系统地图即可看到定位跳到该点。"
            }.onFailure {
                tvOutput.text = "注入失败：${it.message}\n（是否忘了先点「启动 mock 注入」？）"
            }
        }

        findViewById<Button>(R.id.btnRoute).setOnClickListener {
            runCatching {
                mockController.playRoute(
                    waypoints = listOf(
                        MockLocationController.Waypoint(LAB_LAT, LAB_LNG),
                        // 约 70 米，按 1.4 米/秒铺开 ≈ 50 个采样点
                        MockLocationController.Waypoint(LAB_LAT + 0.0006, LAB_LNG + 0.0003)
                    ),
                    stepMs = 1_000L,
                    metersPerStep = 1.4
                ) {
                    tvOutput.text = "路线回放结束。\n" +
                        "注意速度与方位角是由位移算出来的，所以数据内部自洽，" +
                        "不会触发 SPEED_MISMATCH —— 这正是为「只测想测的那条规则」而设计的。"
                }
            }.onSuccess {
                tvOutput.text = "开始回放步行路线（约 50 秒，1.4 米/秒）。"
            }.onFailure {
                tvOutput.text = "回放失败：${it.message}\n（是否忘了先点「启动 mock 注入」？）"
            }
        }

        findViewById<Button>(R.id.btnCollect).setOnClickListener {
            tvOutput.text = "采集 5 个位置样本中（约 5 秒，请保持本界面在前台）…"
            collector.collect(
                provider = LocationManager.GPS_PROVIDER,
                sampleCount = 5,
                intervalMs = 1_000L
            ) { samples ->
                val index = spinnerScenario.selectedItemPosition.coerceIn(0, scenarios.size - 1)
                val scenario = scenarios[index]
                val env = EnvProbe.collect(this)
                val result = scorer.score(samples, scenario.ctx, env)
                tvOutput.text = buildReport(samples, env, scenario, result)
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            mockController.stop()
            gnssWatcher.stop()
            collector.stopBarometer()
            tvOutput.text = buildString {
                appendLine("已移除测试 provider，并注销 GNSS 回调与气压计。")
                appendLine("设备定位已经回到真实来源；再点「采集样本并评分」应该只拿到真实位置。")
                appendLine()
                appendLine("这一条也是给防守方的提醒：mock 应用被系统回收时，定位会突然跳回真实坐标，")
                appendLine("在服务端看就是一次明显的瞬移——比一直伪装更容易暴露。")
            }
            refreshStatus()
        }
    }

    // ------------------------------------------------------------------ 权限与传感器

    private fun ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startSensors()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return

        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSensors()
        } else {
            tvOutput.text = buildString {
                appendLine("未授予定位权限。后果不只是「采不到样本」：")
                appendLine("GNSS 卫星状态也拿不到，检测器会失去最难伪造的一路信号，")
                appendLine("只能退回到客户端本地标记（而那是可被 hook 的）。")
            }
        }
    }

    private fun startSensors() {
        gnssWatcher.start()
        collector.startBarometer()
    }

    // ------------------------------------------------------------------ 展示

    private fun refreshStatus() {
        val allowed = MockLocationController.isAllowed(this)
        val satellites = gnssWatcher.satelliteCount
        val usedInFix = gnssWatcher.usedInFixCount

        tvStatus.text = buildString {
            append("模拟位置授权：").append(if (allowed) "是" else "否（开发者选项里选中本应用）")
            append("\n注入状态：").append(if (mockController.isRunning) "运行中" else "未启动")
            append("\nGNSS：可见 ")
            append(if (satellites < 0) "未知" else "$satellites 颗")
            append("，参与解算 ")
            append(if (usedInFix < 0) "未知" else "$usedInFix 颗")
        }
    }

    private fun buildReport(
        samples: List<PoseSample>,
        env: ClientEnv,
        scenario: Scenario,
        result: RiskResult
    ): String = buildString {
        appendLine("样本数：${samples.size}")
        samples.forEachIndexed { i, s ->
            appendLine(
                "  [$i] ${"%.5f".format(s.lat)}, ${"%.5f".format(s.lng)}" +
                    "  ${s.provider}  精度 ${"%.1f".format(s.accuracyM)}m" +
                    "  卫星 ${s.satelliteCount}/${s.usedInFixCount}" +
                    "  mock=${s.mock}"
            )
        }
        appendLine()
        appendLine("客户端环境取证：")
        appendLine("  被授权使用模拟位置的应用 = ${env.mockLocationPackages}")
        appendLine("  开发者选项开启 = ${env.developmentSettingsEnabled}")
        appendLine("  疑似 root = ${env.rootSuspected}")
        appendLine()
        appendLine("服务端场景：${scenario.label}")
        appendLine()
        append(result.auditReport())
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onDestroy() {
        super.onDestroy()
        // 顺序很重要：先清掉注入，再注销传感器。任何一步漏掉都会污染设备状态。
        mockController.stop()
        gnssWatcher.stop()
        collector.stopBarometer()
        handler.removeCallbacksAndMessages(null)
    }
}
