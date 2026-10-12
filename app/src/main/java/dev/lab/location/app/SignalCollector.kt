package dev.lab.location.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.lab.location.core.PoseSample

/**
 * 采集一组位置样本，并转成与平台无关的 [PoseSample] 交给检测引擎。
 *
 * <p><b>为什么一定要采多个点</b>：单个坐标的信息量极低。精度是否波动、provider 是否切换、
 * 两个时钟是否一致、位移与速度是否自洽——这些信号只有在**序列**上才看得出来。
 * 只查一个点的签到系统，能用的判据只剩「有没有 mock 标记」这一条。
 */
class SignalCollector(
    context: Context,
    private val gnss: GnssWatcher
) : SensorEventListener {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var barometerAltitudeM = Double.NaN

    private var barometerStarted = false

    /**
     * 启动气压计。它提供一路独立的海拔来源：GPS 海拔是算出来的，气压计是量出来的，
     * 两者差出几十米以上就说明其中一路是伪造的。
     */
    fun startBarometer() {
        if (barometerStarted) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        barometerStarted = true
    }

    fun stopBarometer() {
        if (!barometerStarted) return
        sensorManager.unregisterListener(this)
        barometerStarted = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE && event.values.isNotEmpty()) {
            barometerAltitudeM = SensorManager.getAltitude(
                SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                event.values[0]
            ).toDouble()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * 连续采集 [sampleCount] 个样本后回调。
     *
     * <p>采集期间界面必须在前台：Android 10+ 对后台定位有限制，系统也会按省电策略降频，
     * 退到后台很可能收不满样本。超时保护是必要的——某些设备上 provider 就是不回调。
     */
    @SuppressLint("MissingPermission")
    fun collect(
        provider: String,
        sampleCount: Int = 5,
        intervalMs: Long = 1000L,
        onDone: (List<PoseSample>) -> Unit
    ) {
        val samples = ArrayList<PoseSample>(sampleCount)
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            onDone(samples)
        }

        val listener = object : LocationListener {

            override fun onLocationChanged(location: Location) {
                samples += toSample(location)
                if (samples.size >= sampleCount) {
                    runCatching { lm.removeUpdates(this) }
                    handler.post { finish() }
                }
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        // 超时保护：避免 provider 不回调时界面永远卡在「采集中」
        handler.postDelayed({
            runCatching { lm.removeUpdates(listener) }
            finish()
        }, intervalMs * sampleCount + 6_000L)

        runCatching {
            lm.requestLocationUpdates(provider, intervalMs, 0f, listener, Looper.getMainLooper())
        }.onFailure {
            finish()
        }
    }

    /**
     * 把 Android 的 [Location] 映射成平台无关的 [PoseSample]，字段一一对应。
     *
     * <p>注意 provider 相关的三个「有值才取」判断：真机上 `hasAltitude()` 为 false 是常态
     * （室内定位拿不到海拔），这时应记为「未知」而不是 0，否则会污染海拔交叉验证。
     */
    private fun toSample(location: Location): PoseSample =
        PoseSample.builder(location.latitude, location.longitude)
            .provider(location.provider ?: "unknown")
            .accuracy(if (location.hasAccuracy()) location.accuracy else -1f)
            .altitude(if (location.hasAltitude()) location.altitude else Double.NaN)
            .speed(if (location.hasSpeed()) location.speed else 0f)
            .bearing(if (location.hasBearing()) location.bearing else 0f)
            .mock(location.isMockCompat())
            .satellites(gnss.satelliteCount, gnss.usedInFixCount)
            .barometerAltitude(barometerAltitudeM)
            .timeMs(location.time)
            .elapsedRealtimeNanos(location.elapsedRealtimeNanos)
            .build()

    /**
     * 跨版本读取 mock 标记。
     *
     * <p>Android 12（API 31）起改用 `Location.isMock()`，之前是 `isFromMockProvider()`。
     * 注意这两个在 Kotlin 里都按属性访问（Java 的 `isXxx()` 布尔方法会被映射成属性）。
     */
    @Suppress("DEPRECATION")
    private fun Location.isMockCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock
        } else {
            isFromMockProvider
        }
}
