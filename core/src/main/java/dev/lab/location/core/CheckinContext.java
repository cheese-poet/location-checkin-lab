package dev.lab.location.core;

/**
 * 服务端独有的上下文。
 *
 * <p>这是整条防线里<b>唯一攻击者看不到、也改不了</b>的一层：它由服务端自己统计得出。
 * 客户端信号可以被 hook，模型参数和统计数据不能。
 *
 * <p><b>重要约定：{@link #sameGridAccountCountOutsideRoster} 必须排除本次签到的花名册。</b>
 * 一个 40 人的班级坐在同一间教室里，本来就该有 40 个账号落在同一个网格里——那是正常现象。
 * 只有「不在花名册里的账号」混进来才是异常信号。传错这个字段会导致全班被误伤，
 * 见 {@code RiskScorerSelfCheck} 里的反例用例。
 */
public final class CheckinContext {

    /** IP 归属地到申报坐标的距离（公里）；-1 表示未知。移动网络出口会漂移，阈值要放宽。 */
    public final double ipToClaimedKm;

    /** 客户端与服务端的时间差绝对值（毫秒）。 */
    public final long serverClockSkewMs;

    /** 距离签到窗口关闭还有多少秒。值很小说明集中在最后时刻提交。 */
    public final long secondsBeforeWindowClose;

    /** 本次签到中，与该账号共用同一设备指纹的账号数（含自己）。1 表示独占设备。 */
    public final int sameDeviceAccountCount;

    /** 与该账号落在同一网格（约 50 米）内、且<b>不在花名册</b>中的账号数。 */
    public final int sameGridAccountCountOutsideRoster;

    /** 该账号历史活动区域中心到申报坐标的距离（公里）；-1 表示未知。 */
    public final double historyBaselineKm;

    /** 历史签到样本数。样本太少时不启用基线规则，避免新生被误伤（冷启动保护）。 */
    public final int historySampleCount;

    public CheckinContext(double ipToClaimedKm,
                          long serverClockSkewMs,
                          long secondsBeforeWindowClose,
                          int sameDeviceAccountCount,
                          int sameGridAccountCountOutsideRoster,
                          double historyBaselineKm,
                          int historySampleCount) {
        this.ipToClaimedKm = ipToClaimedKm;
        this.serverClockSkewMs = serverClockSkewMs;
        this.secondsBeforeWindowClose = secondsBeforeWindowClose;
        this.sameDeviceAccountCount = sameDeviceAccountCount;
        this.sameGridAccountCountOutsideRoster = sameGridAccountCountOutsideRoster;
        this.historyBaselineKm = historyBaselineKm;
        this.historySampleCount = historySampleCount;
    }

    /**
     * 一个「一切正常」的服务端上下文：单设备单账号、IP 与坐标同城、不在窗口末尾扎堆。
     * 注意它<b>不代表</b>低风险——客户端信号仍然独立参与评分。
     */
    public static CheckinContext normal() {
        return new CheckinContext(12.0, 200L, 300L, 1, 0, 5.0, 30);
    }
}
