package dev.qiqi.dataagent.plan;

/** One-turn preference; the server still enforces plan and query safety gates. */
public enum AnalysisMode {
    AUTO, FAST, REVIEW;

    public static AnalysisMode orAuto(AnalysisMode mode) {
        return mode == null ? AUTO : mode;
    }
}
