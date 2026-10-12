package dev.lab.location.core;

/**
 * 命中风险后的处置动作。
 *
 * <p><b>这里没有「判作弊」这个选项，是刻意的设计。</b>
 * 纯位置签到的误伤不可避免（地下室没有 GPS、定制 ROM、开发机），
 * 一个自动拉黑用户的系统一定会出事。系统只负责记录证据和分级，裁决交给流程和人。
 */
public enum Action {

    /** 放行。 */
    PASS("放行"),

    /** 二次验证：要求重新签到、追加动态码或延长采样。 */
    STEP_UP("二次验证：重新签到 / 动态码 / 延长采样"),

    /** 人工复核：完整保留证据，由教师或管理员裁决。 */
    REVIEW("人工复核：保留证据，不自动判作弊");

    public final String label;

    Action(String label) {
        this.label = label;
    }
}
