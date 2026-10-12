package dev.lab.location.app

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import dev.lab.location.core.Geo
import kotlin.math.ceil
import kotlin.math.max

/**
 * 模拟位置注入控制器。
 *
 * <p>用的全是 Android 公开 SDK（[LocationManager.addTestProvider] 系列），官方用途是自动化测试。
 * 它注入的是「结果」——一个坐标对象，而不是篡改 GPS 芯片，所以几乎所有走系统定位 API 的应用
 * 都会读到这个假坐标。
 *
 * <p><b>平台硬边界</b>：通过这套 API 注入的位置必然带 mock 标记（Android 12+ 的 `isMock`，
 * 更早版本的 `isFromMockProvider`），SDK 层面没有任何参数能去掉它。因此它能用来验证检测器，
 * 但不能用来模拟「无标记的注入」。
 *
 * <p><b>务必成对调用</b>：[start] 之后一定要 [stop]。测试代码里放进 `@After`，
 * 否则 mock provider 会残留在设备上，定位一直卡在假坐标，直到重启或被系统清掉。
 */
class MockLocationController(private val context: Context) {

    companion object {

        /** 只注入 GPS。最贴近真实攻击场景：假定位通常伪装成 GPS 来源。 */
        val GPS_ONLY = listOf(LocationManager.GPS_PROVIDER)

        /** 同时注入 GPS 与网络定位。用于测试「provider 全程不变」这条弱规则。 */
        val ALL_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )

        /**
         * 本应用是否已被系统授权注入模拟位置。
         *
         * 两条授权途径：
         * 1. 开发者选项 → 选择模拟位置信息应用；
         * 2. `adb shell appops set <包名> android:mock_location allow`（适合 CI，免手点）。
         */
        fun isAllowed(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val activeProviders = linkedSetOf<String>()
    private var routeToken = 0

    val isRunning: Boolean
        get() = activeProviders.isNotEmpty()

    /**
     * 注册并启用测试 provider。
     *
     * 用十参数重载而不是 API 31+ 的 ProviderProperties：前者从 API 1 起就可用，
     * 兼容性最好（后者只是写法更现代，能力等价）。
     */
    @SuppressLint("MissingPermission")
    fun start(providers: List<String> = GPS_ONLY) {
        providers.forEach { provider ->
            if (activeProviders.contains(provider)) return@forEach

            // provider 已存在时 addTestProvider 会抛 IllegalArgumentException，先清干净
            runCatching { lm.removeTestProvider(provider) }

            @Suppress("DEPRECATION")
            lm.addTestProvider(
                provider,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
            lm.setTestProviderEnabled(provider, true)
            activeProviders += provider
        }
    }

    /**
     * 注入一个坐标点。
     *
     * [Location.time] 与 [Location.elapsedRealtimeNanos] 必须一起设置：
     * 前者是墙上时钟（用户可改），后者是开机以来的单调时钟（用户改不了）。
     * 漏掉后者是「粗糙 mock」最典型的特征，也是检测器命中率最高的一条规则。
     */
    @SuppressLint("MissingPermission")
    fun push(
        lat: Double,
        lng: Double,
        accuracyM: Float = 8f,
        speedMps: Float = 0f,
        bearingDeg: Float = 0f,
        altitudeM: Double = 50.0,
        provider: String = LocationManager.GPS_PROVIDER
    ) {
        check(activeProviders.contains(provider)) {
            "provider $provider 尚未启动 mock，请先调用 start()"
        }
        val location = Location(provider).apply {
            latitude = lat
            longitude = lng
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = accuracyM
            this.altitude = altitudeM
            this.speed = speedMps
            this.bearing = bearingDeg
        }
        lm.setTestProviderLocation(provider, location)
    }

    /** 路线航点。 */
    data class Waypoint(val lat: Double, val lng: Double)

    /**
     * 沿折线回放位置。
     *
     * 把航点按 [metersPerStep] 打散成密集采样点，速度与方位角由相邻两点算出——
     * 这样生成的数据内部自洽，检测器的运动学规则抓不到它，
     * 于是你测的就是真正想测的那条规则（比如 GNSS 交叉验证），而不是被无关噪声干扰。
     */
    fun playRoute(
        waypoints: List<Waypoint>,
        stepMs: Long = 1000L,
        metersPerStep: Double = 1.4,
        accuracyJitterM: Float = 4f,
        provider: String = LocationManager.GPS_PROVIDER,
        onFinished: (() -> Unit)? = null
    ) {
        require(waypoints.size >= 2) { "至少需要两个航点" }

        val token = ++routeToken
        val points = densify(waypoints, metersPerStep)
        var index = 0

        val tick = object : Runnable {
            override fun run() {
                if (token != routeToken || index >= points.size) {
                    onFinished?.invoke()
                    return
                }
                val current = points[index]
                val previous = if (index == 0) points[0] else points[index - 1]
                val distanceM = Geo.haversineMeters(
                    previous.first, previous.second, current.first, current.second
                )
                val seconds = stepMs / 1000.0

                push(
                    lat = current.first,
                    lng = current.second,
                    // 精度加抖动：恒定精度是 mock 的典型指纹，真机一定会波动
                    accuracyM = 6f + (Math.random().toFloat() * accuracyJitterM),
                    speedMps = (distanceM / seconds).toFloat(),
                    bearingDeg = Geo.initialBearingDeg(
                        previous.first, previous.second, current.first, current.second
                    ).toFloat(),
                    provider = provider
                )
                index++
                handler.postDelayed(this, stepMs)
            }
        }
        handler.post(tick)
    }

    /** 停止路线回放，但保留 mock provider。 */
    fun stopRoute() {
        routeToken++
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 停止回放并彻底移除测试 provider。
     *
     * 这一步不能省：残留的 mock provider 会让整台设备的定位一直返回假坐标。
     */
    fun stop() {
        stopRoute()
        activeProviders.forEach { provider ->
            runCatching { lm.setTestProviderEnabled(provider, false) }
            runCatching { lm.removeTestProvider(provider) }
        }
        activeProviders.clear()
    }

    /** 把折线打散成间距约 [metersPerStep] 的点列。 */
    private fun densify(waypoints: List<Waypoint>, metersPerStep: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        out += waypoints[0].lat to waypoints[0].lng

        for (i in 1 until waypoints.size) {
            val a = waypoints[i - 1]
            val b = waypoints[i]
            val totalM = Geo.haversineMeters(a.lat, a.lng, b.lat, b.lng)
            val steps = max(1, ceil(totalM / metersPerStep).toInt())
            for (s in 1..steps) {
                val fraction = s.toDouble() / steps
                val p = Geo.lerp(a.lat, a.lng, b.lat, b.lng, fraction)
                out += p[0] to p[1]
            }
        }
        return out
    }
}
