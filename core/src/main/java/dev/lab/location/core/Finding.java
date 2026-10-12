package dev.lab.location.core;

/**
 * 一条命中的规则。
 *
 * <p>刻意保留 {@link #evidence} 原文：风险评分必须可解释、可申诉。
 * 「系统判定你作弊」是不可接受的，「系统记录到这几条异常，交人工复核」才是。
 */
public final class Finding {

    /** 规则代号，稳定不变，便于统计与服务端聚合。 */
    public final String code;

    /** 该规则的权重（对总分的贡献）。 */
    public final int weight;

    /** 给教师/管理员看的一句话说明。 */
    public final String message;

    /** 原始证据。落库留存，用于复核与申诉。 */
    public final String evidence;

    public Finding(String code, int weight, String message, String evidence) {
        this.code = code;
        this.weight = weight;
        this.message = message;
        this.evidence = evidence;
    }

    @Override
    public String toString() {
        return "[" + weight + "] " + code + "  " + message + "  (证据: " + evidence + ")";
    }
}
