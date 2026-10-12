package dev.lab.location.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 评分结果。
 *
 * <p><b>本结果只用于分级处置，不构成作弊判定。</b>
 * 请连同 {@link #findings} 里的原始证据一起落库，作为复核与申诉的依据。
 */
public final class RiskResult {

    /** 对外展示的总分，0~100（各规则权重求和后截断）。 */
    public final int score;

    /** 截断前的原始分。用于观察真实严重程度，避免多个中档信号叠加后被 100 掩盖。 */
    public final int rawScore;

    public final RiskLevel level;
    public final Action action;

    /** 命中的规则，按权重降序。 */
    public final List<Finding> findings;

    private RiskResult(int score, int rawScore, RiskLevel level, Action action, List<Finding> findings) {
        this.score = score;
        this.rawScore = rawScore;
        this.level = level;
        this.action = action;
        this.findings = Collections.unmodifiableList(findings);
    }

    static RiskResult of(List<Finding> raw, Thresholds t) {
        List<Finding> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingInt((Finding f) -> f.weight).reversed());

        int sum = 0;
        for (Finding f : sorted) {
            sum += f.weight;
        }
        int score = Math.min(100, sum);

        RiskLevel level;
        if (score < t.lowMax) {
            level = RiskLevel.LOW;
        } else if (score < t.mediumMax) {
            level = RiskLevel.MEDIUM;
        } else {
            level = RiskLevel.HIGH;
        }
        return new RiskResult(score, sum, level, level.action, sorted);
    }

    public boolean isPass() {
        return level == RiskLevel.LOW;
    }

    /** 一句话结论。 */
    public String summary() {
        return "风险分 " + score + " / 100（原始 " + rawScore + "） → " + level.label + " → " + action.label;
    }

    /** 命中规则的代号列表，便于统计各规则的命中率。 */
    public List<String> codes() {
        List<String> out = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            out.add(f.code);
        }
        return out;
    }

    public boolean hasCode(String code) {
        for (Finding f : findings) {
            if (f.code.equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** 多行审计报告，可直接展示或落库。 */
    public String auditReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(summary()).append('\n');
        sb.append("说明：本结果只用于分级处置，不构成作弊判定。\n");
        if (findings.isEmpty()) {
            sb.append("  未命中任何异常规则。\n");
            return sb.toString();
        }
        for (Finding f : findings) {
            sb.append("  ").append(f).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return summary() + " findings=" + findings.size();
    }
}
