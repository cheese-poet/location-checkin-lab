package dev.lab.location.core;

/** 风险等级。由总分与 {@link Thresholds} 的切分点决定。 */
public enum RiskLevel {

    LOW("低", Action.PASS),
    MEDIUM("中", Action.STEP_UP),
    HIGH("高", Action.REVIEW);

    public final String label;
    public final Action action;

    RiskLevel(String label, Action action) {
        this.label = label;
        this.action = action;
    }
}
