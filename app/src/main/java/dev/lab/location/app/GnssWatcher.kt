package dev.lab.location.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * GNSS 状态观察器 —— 整个检测体系里最难伪造的一路信号。
 *
 * <p>mock 注入的是「结果」：一个坐标对象。而这里拿到的是「过程」：定位接收机实际看到了
 * 几颗卫星、其中几颗参与了定位解算。一个应用无法通过公开 SDK 影响这些数字，
 * 想伪造就得 hook 到 HAL 层，成本陡增。
 *
 * <p>检测逻辑的关键不是「卫星数为 0 就是假的」，而是**自相矛盾**：
 * 位置对象声称来源是 `gps`，接收机却一颗卫星都看不到。真实的室内设备通常会把
 * provider 报成 `fused` 或 `network`，而 mock 往往固定写成 `gps` —— 矛盾就是这么暴露的。
 *
 * <p>注意：注册该回调需要定位权限，且部分定制 ROM 会限制回调频率。
 */
class GnssWatcher(context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var callback: GnssStatus.Callback? = null

    /** 可见卫星数；-1 表示未知（未注册、系统版本过低或没有定位权限）。 */
    @Volatile
    var satelliteCount: Int = -1
        private set

    /** 参与定位解算的卫星数；-1 表示未知。 */
    @Volatile
    var usedInFixCount: Int = -1
        private set

    val isRunning: Boolean
        get() = callback != null

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (callback != null) return

        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                satelliteCount = status.satelliteCount
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                }
                usedInFixCount = used
            }
        }
        callback = cb
        runCatching { lm.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper())) }
    }

    fun stop() {
        callback?.let { runCatching { lm.unregisterGnssStatusCallback(it) } }
        callback = null
        satelliteCount = -1
        usedInFixCount = -1
    }
}
