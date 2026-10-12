package dev.lab.location.app

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.lab.location.core.ClientEnv
import java.io.File

/**
 * 客户端环境取证。
 *
 * <p><b>可信度声明</b>：这里每一项都在客户端本地读取，理论上都能被 hook 篡改，
 * 因此它们只作为加权项，权重都不高。真正难伪造的是服务端统计（见 core 模块的 CheckinContext）
 * 和硬件证明（Play Integrity）。
 */
object EnvProbe {

    /** root 痕迹路径。命中即为「疑似」，不构成证据。 */
    private val ROOT_PATHS = listOf(
        "/system/app/Superuser.apk",
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/data/local/bin/su",
        "/data/local/xbin/su"
    )

    fun collect(context: Context): ClientEnv = ClientEnv(
        mockLocationPackages(context),
        developmentSettingsEnabled(context),
        rootSuspected()
    )

    /**
     * 枚举被系统授权使用模拟位置的应用。
     *
     * 这条取证的价值在于**不依赖目标应用自证**：由系统 AppOps 直接给出「谁在当模拟位置应用」。
     *
     * <p>排除本应用自身：本演示工程就是一个 mock 应用，不排除的话每次评分都会白拿 40 分。
     * 真实场景里这里通常不会有本应用。
     *
     * <p>注意部分 ROM 会限制该查询，返回空列表；空列表不等于「没有问题」。
     */
    private fun mockLocationPackages(context: Context): List<String> {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return try {
            @Suppress("DEPRECATION")
            appOps.getPackagesForOps(arrayOf(AppOpsManager.OPSTR_MOCK_LOCATION))
                ?.filter { it.mode == AppOpsManager.MODE_ALLOWED }
                ?.map { it.packageName }
                ?.filter { it != context.packageName }
                ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 开发者选项是否开启。
     *
     * 这条规则误伤率高得离谱：开发机、刷机用户、部分定制 ROM 默认就是开着的。
     * 所以权重只有 10 分，单独命中不会升级处置。
     */
    private fun developmentSettingsEnabled(context: Context): Boolean = try {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
    } catch (t: Throwable) {
        false
    }

    /** 粗粒度 root 探测，同样只作低权重加权。 */
    private fun rootSuspected(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        return ROOT_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }
}
