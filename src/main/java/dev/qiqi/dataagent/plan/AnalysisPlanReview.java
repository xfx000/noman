package dev.qiqi.dataagent.plan;

import java.util.Locale;

/** 决定一份计划能不能直接记录，还是必须等用户确认。不访问存储。 */
public final class AnalysisPlanReview {
    public static final String NEED_SUBMIT = "本次计划需要用户确认，请改用 submit_analysis_plan";
    public static final String NEED_METRICS = "计划至少要写一个指标";
    public static final String NEED_TABLES = "计划要写明本次探查到的表";
    public static final String NEED_OUTLINE = "计划要写成编号章节，每章列出要点，并写明最终输出";

    private AnalysisPlanReview() {}

    public static String invalid(AnalysisPlan plan) {
        if (plan == null || plan.metrics().isEmpty()) return NEED_METRICS;
        if (plan.tables().isEmpty()) return NEED_TABLES;
        return null;
    }

    /** 给人看的提纲。单指标直接记录时可以没有。 */
    public static String invalidOutline(AnalysisPlan plan) {
        if (plan.title().isBlank() || plan.sections().isEmpty() || plan.deliverables().isEmpty()) return NEED_OUTLINE;
        boolean emptySection = plan.sections().stream().anyMatch(section -> section.title().isBlank() || section.items().isEmpty());
        boolean emptyDeliverable = plan.deliverables().stream().anyMatch(item -> item.title().isBlank());
        return emptySection || emptyDeliverable ? NEED_OUTLINE : null;
    }

    public static String needsUserConfirmation(AnalysisPlan incoming, AnalysisPlan confirmed) {
        if (confirmed == null) {
            if (incoming.metrics().size() > 1 || !incoming.assumptions().isEmpty() || hasChart(incoming) || hasReport(incoming))
                return NEED_SUBMIT;
            return null;
        }
        if (hasNewMetric(incoming, confirmed) || addsChartOrReport(incoming, confirmed)) return NEED_SUBMIT;
        return null;
    }

    private static boolean hasNewMetric(AnalysisPlan incoming, AnalysisPlan confirmed) {
        for (String metric : incoming.metrics()) {
            boolean known = false;
            for (String previous : confirmed.metrics()) {
                if (metric.equalsIgnoreCase(previous)) { known = true; break; }
            }
            if (!known) return true;
        }
        return false;
    }

    private static boolean addsChartOrReport(AnalysisPlan incoming, AnalysisPlan confirmed) {
        return (hasChart(incoming) && !hasChart(confirmed)) || (hasReport(incoming) && !hasReport(confirmed));
    }

    static boolean hasChart(AnalysisPlan plan) {
        return plan.outputs().stream().anyMatch(AnalysisPlanReview::isChart);
    }

    static boolean hasReport(AnalysisPlan plan) {
        return plan.outputs().stream().anyMatch(AnalysisPlanReview::isReport);
    }

    private static boolean isChart(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals("chart") || v.contains("图表") || v.contains("出图");
    }

    private static boolean isReport(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals("report") || v.contains("报告");
    }
}
