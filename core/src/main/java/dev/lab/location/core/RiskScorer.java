package dev.lab.location.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 风险评分引擎。
 *
 * <h2>设计原则</h2>
 * <ol>
 *   <li><b>分层加权，不做一票否决。</b>任何单条规则都可能被 hook 或误伤，
 *       所以全部规则只贡献分数，最终由 {@link RiskResult} 分级处置。</li>
 *   <li><b>权重按伪造成本排序。</b>服务端统计 &gt; GNSS 交叉 &gt; 时钟一致性 &gt; 字段分布 &gt; 客户端标记。</li>
 *   <li><b>可解释。</b>每条命中都带原始证据，用于人工复核与申诉。</li>
 *   <li><b>先控误伤。</b>对可能误伤正常用户的规则（开发者选项、provider 不变、末尾扎堆）
 *       一律给低权重，并且对室内场景做了显式的规则隔离。</li>
 * </ol>
 *
 * <p>本类是无状态且线程安全的（{@link Thresholds} 在构造时固定）。
 */
public final class RiskScorer {

    private final Thresholds t;

    public RiskScorer() {
        this(Thresholds.defaults());
    }

    public RiskScorer(Thresholds thresholds) {
        this.t = thresholds;
    }

    /**
     * 对一个签到请求评分。
     *
     * @param samples 客户端上报的位置采样序列（至少 3 个才有统计意义，建议 5 个）
     * @param ctx     服务端上下文
     * @param env     客户端环境取证
     * @return 分级结果，<b>不构成作弊判定</b>
     */
    public RiskResult score(List<PoseSample> samples, CheckinContext ctx, ClientEnv env) {
        List<Finding> findings = new ArrayList<>();

        if (samples == null || samples.isEmpty()) {
            findings.add(new Finding("NO_LOCATION", t.wNoLocation,
                    "一个位置样本都没有采集到，无法判定是否在场",
                    "samples=0"));
        } else {
            ruleMockFlag(samples, findings);
            ruleElapsedRealtime(samples, findings);
            ruleGnss(samples, findings);
            ruleAccuracy(samples, findings);
            ruleProviderConsistency(samples, findings);
            ruleKinematics(samples, findings);
            ruleBarometer(samples, findings);
        }

        ruleClientEnv(env, findings);
        ruleServerSide(ctx, findings);

        return RiskResult.of(findings, t);
    }

    // ------------------------------------------------------------------ L1 客户端标记

    private void ruleMockFlag(List<PoseSample> samples, List<Finding> findings) {
        for (int i = 0; i < samples.size(); i++) {
            if (samples.get(i).mock) {
                findings.add(new Finding("MOCK_FLAG", t.wMockFlag,
                        "系统标记该位置来自模拟位置应用（isMock / isFromMockProvider）",
                        "sample[" + i + "].mock=true"));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 时钟一致性

    private void ruleElapsedRealtime(List<PoseSample> samples, List<Finding> findings) {
        for (int i = 0; i < samples.size(); i++) {
            PoseSample s = samples.get(i);

            if (s.elapsedRealtimeNanos <= 0) {
                findings.add(new Finding("ELAPSED_INVALID", t.wElapsedInvalid,
                        "位置样本缺少单调时钟（elapsedRealtimeNanos 为 0），真实系统写入必然带该字段",
                        "sample[" + i + "].elapsedRealtimeNanos=" + s.elapsedRealtimeNanos));
                return;
            }

            if (i > 0) {
                PoseSample p = samples.get(i - 1);
                long eDeltaMs = (s.elapsedRealtimeNanos - p.elapsedRealtimeNanos) / 1_000_000L;
                long tDeltaMs = s.timeMs - p.timeMs;

                if (eDeltaMs <= 0) {
                    findings.add(new Finding("ELAPSED_NOT_MONOTONIC", t.wElapsedInvalid,
                            "单调时钟没有递增，位置序列的时间轴被改写",
                            "sample[" + (i - 1) + "→" + i + "] Δelapsed=" + eDeltaMs + " 毫秒"));
                    return;
                }
                if (Math.abs(tDeltaMs - eDeltaMs) > t.elapsedTimeSkewToleranceMs) {
                    findings.add(new Finding("CLOCK_TAMPERED", t.wElapsedInvalid,
                            "墙上时钟与单调时钟不一致，系统时间被修改过",
                            "样本[" + (i - 1) + "→" + i + "] Δtime=" + tDeltaMs
                                    + " 毫秒 而 Δelapsed=" + eDeltaMs + " 毫秒"));
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ GNSS 交叉验证

    private void ruleGnss(List<PoseSample> samples, List<Finding> findings) {
        // 只对 provider=gps 生效，这是关键的误伤隔离：
        // 室内只用网络定位时，provider 会是 network / fused，卫星数为 0 完全正常。
        // 但真实设备在室内通常也不会把 provider 报成 gps —— 那正是「声称来自 GPS 却看不到一颗卫星」
        // 这个矛盾成立的原因，也是 mock 最难绕过的一环（伪造 GNSS 状态需要 hook 到 HAL 层）。
        for (int i = 0; i < samples.size(); i++) {
            PoseSample s = samples.get(i);
            if (!s.isGpsProvider()) {
                continue;
            }
            if (s.satelliteCount == 0) {
                findings.add(new Finding("GNSS_NO_SATELLITE", t.wGnssNoSatellite,
                        "provider=gps 但系统报告 0 颗可见卫星，与「来自 GPS」自相矛盾",
                        "sample[" + i + "] provider=gps satellites=0 usedInFix=" + s.usedInFixCount));
                return;
            }
            if (s.satelliteCount > 0 && s.usedInFixCount == 0) {
                findings.add(new Finding("GNSS_NO_USED_IN_FIX", t.wGnssNoUsedInFix,
                        "有可见卫星但一颗都没参与定位解算，不符合真实 GPS 特征",
                        "sample[" + i + "] satellites=" + s.satelliteCount + " usedInFix=0"));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 字段分布

    private void ruleAccuracy(List<PoseSample> samples, List<Finding> findings) {
        if (samples.size() >= t.staticAccuracyMinSamples) {
            float first = samples.get(0).accuracyM;
            boolean allSame = true;
            for (PoseSample s : samples) {
                if (Float.compare(s.accuracyM, first) != 0) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                findings.add(new Finding("STATIC_ACCURACY", t.wStaticAccuracy,
                        "连续多次采样的水平精度完全一致，真实 GPS 会在数米范围内波动",
                        samples.size() + " 个样本 accuracy 均为 " + first + " 米"));
            }
        }

        for (int i = 0; i < samples.size(); i++) {
            float a = samples.get(i).accuracyM;
            if (a <= t.implausibleAccuracyM) {
                findings.add(new Finding("IMPLAUSIBLE_ACCURACY", t.wImplausibleAccuracy,
                        "报告的水平精度优于 1 米，真实 GPS 几乎不可能达到（注意：不是越小越可信）",
                        "sample[" + i + "].accuracy=" + a + " 米"));
                return;
            }
        }
    }

    private void ruleProviderConsistency(List<PoseSample> samples, List<Finding> findings) {
        if (samples.size() < 2) {
            return;
        }
        double total = 0;
        for (int i = 1; i < samples.size(); i++) {
            PoseSample p = samples.get(i - 1);
            PoseSample c = samples.get(i);
            total += Geo.haversineMeters(p.lat, p.lng, c.lat, c.lng);
        }
        if (total < t.staticProviderMinDisplacementM) {
            return;
        }
        String first = samples.get(0).provider;
        for (PoseSample s : samples) {
            if (!first.equals(s.provider)) {
                return;
            }
        }
        findings.add(new Finding("STATIC_PROVIDER", t.wStaticProvider,
                "累计位移明显但位置来源全程未变，真实设备通常会在 gps/network/fused 间切换（弱信号）",
                "累计位移 " + Math.round(total) + " 米，provider 全程为 " + first));
    }

    // ------------------------------------------------------------------ 运动学

    private void ruleKinematics(List<PoseSample> samples, List<Finding> findings) {
        if (samples.size() < 2) {
            return;
        }
        boolean teleport = false;
        boolean mismatch = false;
        String teleportEvidence = null;
        String mismatchEvidence = null;

        for (int i = 1; i < samples.size(); i++) {
            PoseSample p = samples.get(i - 1);
            PoseSample c = samples.get(i);
            double dtSec = (c.timeMs - p.timeMs) / 1000.0;
            if (dtSec <= 0) {
                continue; // 时间轴异常，交由时钟规则处理
            }
            double distM = Geo.haversineMeters(p.lat, p.lng, c.lat, c.lng);
            double impliedMps = distM / dtSec;

            if (!teleport && impliedMps > t.teleportSpeedMps) {
                teleport = true;
                teleportEvidence = "样本[" + (i - 1) + "→" + i + "] 位移 " + Math.round(distM)
                        + " 米 / " + dtSec + " 秒，推算 " + Math.round(impliedMps) + " 米/秒";
            }
            if (!mismatch && impliedMps > 5.0
                    && Math.abs(impliedMps - c.speedMps) > t.speedToleranceMps) {
                mismatch = true;
                mismatchEvidence = "样本[" + (i - 1) + "→" + i + "] 推算 " + Math.round(impliedMps)
                        + " 米/秒，上报 " + c.speedMps + " 米/秒";
            }
        }

        if (teleport) {
            findings.add(new Finding("TELEPORT", t.wTeleport,
                    "相邻采样之间出现物理上不可能的位移", teleportEvidence));
        }
        if (mismatch) {
            findings.add(new Finding("SPEED_MISMATCH", t.wSpeedMismatch,
                    "上报速度与位移推算速度严重不符", mismatchEvidence));
        }
    }

    // ------------------------------------------------------------------ 传感器交叉

    private void ruleBarometer(List<PoseSample> samples, List<Finding> findings) {
        for (int i = 0; i < samples.size(); i++) {
            PoseSample s = samples.get(i);
            if (!s.hasBarometer() || !s.hasAltitude()) {
                continue;
            }
            double diff = Math.abs(s.barometerAltitudeM - s.altitudeM);
            if (diff > t.barometerToleranceM) {
                findings.add(new Finding("BAROMETER_MISMATCH", t.wBarometerMismatch,
                        "气压计推算海拔与上报海拔严重不符",
                        "sample[" + i + "] 上报海拔=" + Math.round(s.altitudeM)
                                + " 米，气压计=" + Math.round(s.barometerAltitudeM) + " 米"));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 客户端环境

    private void ruleClientEnv(ClientEnv env, List<Finding> findings) {
        if (env == null) {
            return;
        }
        if (!env.mockLocationPackages.isEmpty()) {
            findings.add(new Finding("MOCK_APP_OP", t.wMockAppOp,
                    "设备上存在被授权使用模拟位置的应用（由 AppOps 枚举得出，不依赖目标应用自证）",
                    "packages=" + env.mockLocationPackages));
        }
        if (env.developmentSettingsEnabled) {
            findings.add(new Finding("DEV_OPTIONS_ON", t.wDevOptions,
                    "开发者选项处于开启状态（弱信号，开发机与极客用户会误伤）",
                    "DEVELOPMENT_SETTINGS_ENABLED=1"));
        }
        if (env.rootSuspected) {
            findings.add(new Finding("ROOT_SUSPECTED", t.wRootSuspected,
                    "设备疑似已 root 或已解锁（误伤率偏高，仅作加权）",
                    "root heuristic hit"));
        }
    }

    // ------------------------------------------------------------------ 服务端统计

    private void ruleServerSide(CheckinContext ctx, List<Finding> findings) {
        if (ctx == null) {
            return;
        }

        if (ctx.ipToClaimedKm >= 0) {
            if (ctx.ipToClaimedKm > t.ipFarKm) {
                findings.add(new Finding("IP_FAR_FROM_CLAIM", t.wIpFar,
                        "IP 归属地距申报坐标过远",
                        Math.round(ctx.ipToClaimedKm) + " 公里"));
            } else if (ctx.ipToClaimedKm > t.ipMidKm) {
                findings.add(new Finding("IP_MID_FROM_CLAIM", t.wIpMid,
                        "IP 归属地距申报坐标较远，可能是代理或跨网出口",
                        Math.round(ctx.ipToClaimedKm) + " 公里"));
            }
        }

        if (ctx.serverClockSkewMs > t.elapsedTimeSkewToleranceMs) {
            findings.add(new Finding("SERVER_CLOCK_SKEW", t.wClockSkew,
                    "客户端时间与服务端时间偏差过大",
                    "偏差 " + ctx.serverClockSkewMs + " 毫秒"));
        }

        if (ctx.sameDeviceAccountCount >= t.deviceSharedHighCount) {
            findings.add(new Finding("DEVICE_SHARED_HIGH", t.wDeviceSharedHigh,
                    "同一台设备在本次签到中为多个账号提交",
                    "同设备账号数=" + ctx.sameDeviceAccountCount));
        } else if (ctx.sameDeviceAccountCount == 2) {
            findings.add(new Finding("DEVICE_SHARED_LOW", t.wDeviceSharedLow,
                    "同一台设备为两个账号提交（可能只是借用手机，也可能是代签）",
                    "同设备账号数=2"));
        }

        if (ctx.sameGridAccountCountOutsideRoster >= t.gridClusterLargeCount) {
            findings.add(new Finding("GRID_CLUSTER_LARGE", t.wGridClusterLarge,
                    "花名册之外的账号大量聚集在同一网格",
                    "花名册外同网格账号数=" + ctx.sameGridAccountCountOutsideRoster));
        } else if (ctx.sameGridAccountCountOutsideRoster >= t.gridClusterCount) {
            findings.add(new Finding("GRID_CLUSTER", t.wGridCluster,
                    "花名册之外的账号聚集在同一网格",
                    "花名册外同网格账号数=" + ctx.sameGridAccountCountOutsideRoster));
        }

        if (ctx.secondsBeforeWindowClose >= 0
                && ctx.secondsBeforeWindowClose <= t.lateWindowSeconds) {
            findings.add(new Finding("LATE_WINDOW", t.wLateWindow,
                    "在签到窗口即将关闭时才提交（弱信号，拖延是常见行为）",
                    "距窗口关闭 " + ctx.secondsBeforeWindowClose + " 秒"));
        }

        if (ctx.historySampleCount >= t.historyMinSamples
                && ctx.historyBaselineKm >= 0
                && ctx.historyBaselineKm > t.historyJumpKm) {
            findings.add(new Finding("HISTORY_JUMP", t.wHistoryJump,
                    "签到位置与该账号历史活动区域差异过大",
                    "距历史活动中心 " + Math.round(ctx.historyBaselineKm) + " 公里"));
        }
    }
}
