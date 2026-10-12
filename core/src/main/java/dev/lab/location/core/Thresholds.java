package dev.lab.location.core;

/**
 * 全部可调参数与权重的集中定义。
 *
 * <p>调参建议：<b>先保证误伤率为零，再谈拦截率。</b>
 * 上线前请用一批真实用户的正常签到数据回归，确认正常样本不会落入 MEDIUM 以上。
 * 权重不是拍脑袋定的，而是按「伪造成本」排序：伪造成本越高、权重越大。
 */
public final class Thresholds {

    // ---------------------------------------------------------------- 分级切分点

    /** 低于此分视为 LOW（放行）。 */
    public final int lowMax = 35;

    /** 低于此分视为 MEDIUM（二次验证），否则为 HIGH（人工复核）。 */
    public final int mediumMax = 70;

    // ---------------------------------------------------------------- L1 客户端标记

    /** 系统标记为模拟位置。伪造成本低，但语义明确。 */
    public int wMockFlag = 50;

    // ---------------------------------------------------------------- 时钟一致性

    /** {@code elapsedRealtimeNanos} 非法（为 0 / 不递增）。 */
    public int wElapsedInvalid = 40;

    /** 墙上时钟与单调时钟不一致（用户改了系统时间）。 */
    public int wClockSkew = 25;

    /** 允许的时钟偏差容差（毫秒）。 */
    public long elapsedTimeSkewToleranceMs = 5_000L;

    // ---------------------------------------------------------------- GNSS 交叉验证

    /** provider 是 gps，但可见卫星数为 0。mock 无法伪造 GNSS 状态，这是最强信号。 */
    public int wGnssNoSatellite = 45;

    /** 有可见卫星但一颗都没参与解算。 */
    public int wGnssNoUsedInFix = 30;

    // ---------------------------------------------------------------- 位置字段分布

    /** 多次采样精度完全相同（真机会波动）。 */
    public int wStaticAccuracy = 20;

    /** 精度小于 1 米或恰好为 0（真实 GPS 几乎不可能）。 */
    public int wImplausibleAccuracy = 15;

    /** 判定「精度恒定」所需的最少样本数。 */
    public int staticAccuracyMinSamples = 3;

    /** 精度合理下限（米）。低于它反而可疑。 */
    public float implausibleAccuracyM = 1.0f;

    /** 位置发生了明显移动，但 provider 全程不变（弱信号）。 */
    public int wStaticProvider = 10;

    /** 触发 provider 不变规则所需的最小累计位移（米）。 */
    public double staticProviderMinDisplacementM = 30.0;

    // ---------------------------------------------------------------- 运动学

    /** 相邻采样推算速度超过物理可能（瞬移）。 */
    public int wTeleport = 25;

    /** 推算速度与上报速度严重不符。 */
    public int wSpeedMismatch = 20;

    /** 判定瞬移的速度阈值（米/秒）。 */
    public double teleportSpeedMps = 80.0;

    /** 上报速度与推算速度允许的偏差（米/秒）。 */
    public double speedToleranceMps = 15.0;

    // ---------------------------------------------------------------- 传感器交叉

    /** 气压计海拔与 GPS 海拔严重不符。 */
    public int wBarometerMismatch = 15;

    /** 气压计与 GPS 海拔允许的偏差（米）。 */
    public double barometerToleranceM = 50.0;

    // ---------------------------------------------------------------- 客户端环境

    /** AppOps 中确实存在被授权使用模拟位置的应用。 */
    public int wMockAppOp = 40;

    /** 开发者选项已开启。 */
    public int wDevOptions = 10;

    /** 疑似 root / 解锁。误伤率偏高，保持低权重。 */
    public int wRootSuspected = 15;

    // ---------------------------------------------------------------- 服务端统计

    /** IP 归属地与申报坐标距离过大。 */
    public int wIpFar = 35;

    /** 同上，中等距离。 */
    public int wIpMid = 20;

    /** 中距离阈值（公里）。 */
    public double ipMidKm = 150.0;

    /** 远距离阈值（公里）。 */
    public double ipFarKm = 500.0;

    /** 同一设备为多个账号签到。 */
    public int wDeviceSharedHigh = 40;

    /** 同上，仅两个账号。 */
    public int wDeviceSharedLow = 25;

    /** 触发「多账号共用设备」高档位所需的账号数。 */
    public int deviceSharedHighCount = 3;

    /** 花名册之外的账号聚集在同一网格。 */
    public int wGridCluster = 30;

    /** 同上，规模较大时的高档位。 */
    public int wGridClusterLarge = 50;

    /** 触发网格聚集的账号数下限。 */
    public int gridClusterCount = 5;

    /** 触发网格聚集高档位的账号数下限。 */
    public int gridClusterLargeCount = 15;

    /** 集中在窗口末尾提交。天然误伤较高（人就是喜欢拖到最后），权重低。 */
    public int wLateWindow = 10;

    /** 判定「扎堆在末尾」的剩余秒数。 */
    public long lateWindowSeconds = 20L;

    /** 与历史活动区域突变。 */
    public int wHistoryJump = 15;

    /** 触发历史突变规则所需的最少历史样本数（冷启动保护）。 */
    public int historyMinSamples = 5;

    /** 判定历史突变的距离阈值（公里）。 */
    public double historyJumpKm = 200.0;

    // ---------------------------------------------------------------- 其他

    /** 一个样本都没采到。 */
    public int wNoLocation = 30;

    /** 默认参数。 */
    public static Thresholds defaults() {
        return new Thresholds();
    }

    private Thresholds() {
    }
}
