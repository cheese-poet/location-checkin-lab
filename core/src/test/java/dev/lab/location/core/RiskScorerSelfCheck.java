package dev.lab.location.core;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 零依赖自检：不引入 JUnit，直接跑一个 main。
 *
 * <p>为什么不用测试框架：这个模块的全部价值在于「规则行为是否可复现」，
 * 而它不需要任何外部依赖就能编译运行，可以在没有 Android SDK、没有网络的环境里验证。
 * 换成 JUnit 只是把断言换成注解，逻辑完全一致。
 *
 * <p>运行方式：
 * <pre>
 *   javac -encoding UTF-8 -d out $(find core/src -name '*.java')   # 或见 tools/run-selfcheck.ps1
 *   java -cp out dev.lab.location.core.RiskScorerSelfCheck
 * </pre>
 *
 * <p>用例分成三类，这是刻意设计的结构：
 * <ul>
 *   <li><b>正常场景</b>（S01/S02/S07）：必须落在 LOW，这是误伤回归线；</li>
 *   <li><b>攻击场景</b>（S03~S06/S09/S10）：必须被抓住，验证拦截能力；</li>
 *   <li><b>配置反例</b>（S08/S11）：演示「参数传错会把全班误伤」，以及弱信号单独出现时的分级。</li>
 * </ul>
 */
public final class RiskScorerSelfCheck {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // 强制 UTF-8 输出，避免 Windows 控制台默认代码页把中文变成乱码。
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));

        RiskScorer scorer = new RiskScorer();

        System.out.println("======================================================================");
        System.out.println(" location-checkin-lab  ·  风险评分引擎自检");
        System.out.println(" 说明：本结果只用于分级处置，不构成作弊判定");
        System.out.println("======================================================================");

        // ---------------------------------------------------------- 正常场景（误伤回归线）

        // S01 室外步行：provider 会在 gps/fused 之间切换，精度自然波动，有卫星。
        Sim s01 = new Sim();
        s01.accuracy(6f).step(1.4, 1)
                .provider("fused").accuracy(7f).step(1.4, 1)
                .provider("gps").accuracy(8f).step(1.4, 1)
                .accuracy(6f).step(1.4, 1)
                .accuracy(7f).step(1.4, 1);
        RiskResult r01 = scorer.score(s01.samples, CheckinContext.normal(), ClientEnv.none());
        expect("S01", "正常·室外步行GPS", r01, RiskLevel.LOW);

        // S02 室内仅网络定位：provider=fused 且卫星数为 0。
        //    这是最关键的一条误伤回归线——GNSS 规则必须对它完全不生效。
        Sim s02 = indoorNetworkOnly();
        RiskResult r02 = scorer.score(s02.samples, CheckinContext.normal(), ClientEnv.none());
        expect("S02", "正常·室内仅网络定位(0星)", r02, RiskLevel.LOW);

        // S07 全班正常签到：花名册已排除，网格聚类字段为 0。
        RiskResult r07 = scorer.score(indoorNetworkOnly().samples,
                new CheckinContext(12.0, 200L, 300L, 1, 0, 5.0, 30), ClientEnv.none());
        expect("S07", "正常·全班同教室(已排除花名册)", r07, RiskLevel.LOW);

        // ---------------------------------------------------------- 攻击场景

        // S03 粗糙 mock：典型低质量实现，字段几乎没修。
        Sim s03 = new Sim().mock().invalidElapsed().accuracy(3f).satellites(0, 0);
        s03.hold(3, 1);
        RiskResult r03 = scorer.score(s03.samples, CheckinContext.normal(), ClientEnv.none());
        expect("S03", "攻击·粗糙mock(室内)", r03, RiskLevel.HIGH,
                "MOCK_FLAG", "ELAPSED_INVALID", "GNSS_NO_SATELLITE", "STATIC_ACCURACY");

        // S04 室外标准 mock：真机 GNSS 正常工作，只有 isMock 标记命中。
        //    结果只到 MEDIUM —— 说明单靠客户端标记不足以拦截，这是量化给决策者看的。
        Sim s04 = new Sim().mock().accuracy(6f);
        s04.step(1.4, 1).accuracy(7f).step(1.4, 1).accuracy(8f).step(1.4, 1);
        RiskResult r04 = scorer.score(s04.samples, CheckinContext.normal(), ClientEnv.none());
        expect("S04", "攻击·室外mock(仅标记命中)", r04, RiskLevel.MEDIUM, "MOCK_FLAG");

        // S05 精致 mock：已经绕过 isMock 标记、字段也都修对了，
        //    但仍被 GNSS 交叉验证 + 服务端设备聚类抓住 —— 纵深防御生效的证据。
        Sim s05 = new Sim().satellites(0, 0);
        s05.accuracy(6f).step(1.4, 1)
                .accuracy(7f).step(1.4, 1)
                .accuracy(8f).step(1.4, 1)
                .accuracy(9f).step(1.4, 1)
                .accuracy(7f).step(1.4, 1);
        RiskResult r05 = scorer.score(s05.samples,
                new CheckinContext(12.0, 200L, 300L, 4, 0, 5.0, 30), ClientEnv.none());
        expect("S05", "攻击·精致mock(绕过标记)", r05, RiskLevel.HIGH,
                "GNSS_NO_SATELLITE", "DEVICE_SHARED_HIGH");

        // S06 代签：位置信号完全正常，客户端一分未得。
        //    全靠服务端统计兜住 —— 这正是「位置签到的安全性上限取决于服务端」的体现。
        RiskResult r06 = scorer.score(indoorNetworkOnly().samples,
                new CheckinContext(12.0, 200L, 8L, 4, 6, 5.0, 30), ClientEnv.none());
        expect("S06", "攻击·代签(位置信号全正常)", r06, RiskLevel.HIGH,
                "DEVICE_SHARED_HIGH", "GRID_CLUSTER", "LATE_WINDOW");

        // S09 系统时间被回拨：墙上时钟与单调时钟不一致。
        Sim s09 = new Sim().accuracy(6f);
        s09.step(1.4, 1).accuracy(7f).step(1.4, 1).wallClockJump(-61_000L).accuracy(8f).step(1.4, 1);
        RiskResult r09 = scorer.score(s09.samples, CheckinContext.normal(), ClientEnv.none());
        expect("S09", "攻击·系统时间回拨", r09, RiskLevel.MEDIUM, "CLOCK_TAMPERED");

        // S10 瞬移：1 秒跨 2 公里。
        Sim s10 = new Sim().accuracy(8f);
        s10.step(0, 1).teleport(2000, 1, 0f);
        RiskResult r10 = scorer.score(s10.samples, CheckinContext.normal(), ClientEnv.none());
        expect("S10", "攻击·瞬移(1秒2公里)", r10, RiskLevel.MEDIUM, "TELEPORT", "SPEED_MISMATCH");

        // ---------------------------------------------------------- 配置反例

        // S08 反例：忘记排除花名册，把全班 40 个账号当成「聚集」。
        //    结果是全体进入二次验证 —— 这就是该字段传错时的真实代价。
        RiskResult r08 = scorer.score(indoorNetworkOnly().samples,
                new CheckinContext(12.0, 200L, 300L, 1, 40, 5.0, 30), ClientEnv.none());
        expect("S08", "反例·未排除花名册(全班误伤)", r08, RiskLevel.MEDIUM, "GRID_CLUSTER_LARGE");

        // S11 弱信号单独出现：气压计与上报海拔差 170 米，但只有一个样本。
        //    按设计只到 LOW —— 目的是展示「弱信号单独出现不足以升级处置」。
        Sim s11 = new Sim().accuracy(8f).altitude(220.0, 50.0);
        s11.step(0, 1);
        RiskResult r11 = scorer.score(s11.samples, CheckinContext.normal(), ClientEnv.none());
        expect("S11", "边界·气压计不符(单样本)", r11, RiskLevel.LOW, "BAROMETER_MISMATCH");

        // ---------------------------------------------------------- 汇总

        System.out.println("----------------------------------------------------------------------");
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (!FAILURES.isEmpty()) {
            System.out.println("失败清单：");
            for (String f : FAILURES) {
                System.out.println("  - " + f);
            }
        }

        System.out.println();
        System.out.println("=========== 审计报告样例（S05 精致 mock）===========");
        System.out.print(r05.auditReport());

        System.out.println();
        System.out.println("=========== 误伤回归样例（S02 室内仅网络定位）===========");
        System.out.print(r02.auditReport());

        System.out.flush();
        System.exit(failed == 0 ? 0 : 1);
    }

    /** S02/S06/S07/S08 共用的室内网络定位轨迹：5 个样本，缓慢漂移约 8 米，精度自然波动。 */
    private static Sim indoorNetworkOnly() {
        Sim s = new Sim().provider("fused").satellites(0, 0);
        s.accuracy(40f).step(2, 1)
                .accuracy(35f).step(2, 1)
                .accuracy(45f).step(2, 1)
                .accuracy(38f).step(2, 1)
                .accuracy(42f).step(2, 1);
        return s;
    }

    private static void expect(String id, String desc, RiskResult r, RiskLevel level, String... requiredCodes) {
        List<String> problems = new ArrayList<>();
        if (r.level != level) {
            problems.add("期望等级 " + level.name() + "，实得 " + r.level.name() + "（" + r.score + " 分）");
        }
        for (String code : requiredCodes) {
            if (!r.hasCode(code)) {
                problems.add("缺少预期规则 " + code);
            }
        }
        boolean ok = problems.isEmpty();
        if (ok) {
            passed++;
        } else {
            failed++;
            FAILURES.add(id + " " + desc + " -> " + String.join("; ", problems));
        }

        System.out.printf("[%s] %-4s score=%3d %-6s %s%n",
                ok ? "PASS" : "FAIL", id, r.score, r.level.name(), desc);
        System.out.println("        codes=" + r.codes());
        if (!ok) {
            System.out.println("        !! " + String.join("; ", problems));
        }
    }

    /**
     * 轨迹模拟器：用「向北移动若干米 / 间隔若干秒」的方式生成采样序列，
     * 速度和方位角自动与位移保持一致（除非调用 {@link #teleport} 故意制造矛盾）。
     */
    private static final class Sim {

        final List<PoseSample> samples = new ArrayList<>();

        double lat = 39.9042;
        double lng = 116.4074;
        long timeMs = 1_700_000_000_000L;
        long elapsedNanos = 900_000_000_000L;

        String provider = "gps";
        float accuracy = 8f;
        int satellites = 12;
        int usedInFix = 8;
        boolean mock = false;
        double altitude = Double.NaN;
        double barometer = Double.NaN;
        boolean validElapsed = true;
        long pendingWallJumpMs = 0L;

        Sim provider(String v) {
            provider = v;
            return this;
        }

        Sim accuracy(float v) {
            accuracy = v;
            return this;
        }

        Sim satellites(int total, int fixed) {
            satellites = total;
            usedInFix = fixed;
            return this;
        }

        Sim mock() {
            mock = true;
            return this;
        }

        /** 同时设置气压计海拔与上报海拔（用于交叉验证）。 */
        Sim altitude(double barometerM, double reportedM) {
            barometer = barometerM;
            altitude = reportedM;
            return this;
        }

        /** 模拟「粗糙实现」：完全不填 elapsedRealtimeNanos。 */
        Sim invalidElapsed() {
            validElapsed = false;
            return this;
        }

        /** 模拟用户修改系统时间。 */
        Sim wallClockJump(long ms) {
            pendingWallJumpMs += ms;
            return this;
        }

        /** 追加一个采样点：向北移动 meters 米，间隔 seconds 秒，速度自动与位移一致。 */
        Sim step(double meters, double seconds) {
            timeMs += pendingWallJumpMs;
            pendingWallJumpMs = 0L;
            timeMs += (long) (seconds * 1000);
            elapsedNanos += (long) (seconds * 1_000_000_000L);
            lat += meters / 111_320.0;
            return add(seconds > 0 ? (float) (meters / seconds) : 0f);
        }

        Sim hold(int count, double seconds) {
            for (int i = 0; i < count; i++) {
                step(0, seconds);
            }
            return this;
        }

        /** 瞬移：位置大幅跳变，但上报速度为 0（制造矛盾）。 */
        Sim teleport(double meters, double seconds, float reportedSpeedMps) {
            timeMs += (long) (seconds * 1000);
            elapsedNanos += (long) (seconds * 1_000_000_000L);
            lat += meters / 111_320.0;
            return add(reportedSpeedMps);
        }

        private Sim add(float speedMps) {
            samples.add(PoseSample.builder(lat, lng)
                    .provider(provider)
                    .accuracy(accuracy)
                    .speed(speedMps)
                    .bearing(0f)
                    .mock(mock)
                    .satellites(satellites, usedInFix)
                    .altitude(altitude)
                    .barometerAltitude(barometer)
                    .timeMs(timeMs)
                    .elapsedRealtimeNanos(validElapsed ? elapsedNanos : 0L)
                    .build());
            return this;
        }
    }

    private RiskScorerSelfCheck() {
    }
}
