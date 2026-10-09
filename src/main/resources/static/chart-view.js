/* Noman's query-snapshot exploration: field roles -> explicit spec -> chart.
 * Inspired by Graphic Walker's documented dimensions/measures and visual channels;
 * implemented locally, without importing its React UI or source code.
 */
window.NomanCharts = (() => {
  const palette = ["#6578d7", "#8dcac0", "#d4aa65", "#ab8192", "#89a0e8"];
  const number = value => typeof value === "number" && Number.isFinite(value) ? value
    : typeof value === "string" && /^-?(?:\d+\.?\d*|\.\d+)$/.test(value.trim()) && Number.isFinite(Number(value)) ? Number(value) : null;
  function fields(columns, rows) {
    return columns.map(name => ({ name, role: rows.some(row => row[name] != null) &&
      rows.every(row => row[name] == null || number(row[name]) !== null) ? "measure" : "dimension" }));
  }
  function infer(columns, rows) {
    const roles = fields(columns, rows);
    const dimensions = roles.filter(field => field.role === "dimension").map(field => field.name);
    const category = dimensions[0] || columns[0];
    const metric = roles.find(field => field.role === "measure" && field.name !== category)?.name;
    const series = dimensions.find(name => name !== category) || "";
    return { category, metric, series: new Set(rows.map(row => row[category])).size < rows.length ? series : "",
      type: "bar", aggregate: "none", sort: "original" };
  }
  function compile(spec, columns, rows) {
    if (!columns.includes(spec.category) || !columns.includes(spec.metric) || spec.category === spec.metric)
      throw Error("请选择不同的分类字段和数值指标。");
    if (spec.series && (!columns.includes(spec.series) || [spec.category, spec.metric].includes(spec.series)))
      throw Error("系列字段须与分类、指标不同。");
    if (!["bar", "horizontal_bar", "line", "pie"].includes(spec.type) || !["none", "sum", "mean", "count"].includes(spec.aggregate))
      throw Error("图型或聚合方式无效。");
    const categories = [], groups = new Map();
    for (const row of rows) {
      if (row[spec.category] == null || (spec.series && row[spec.series] == null)) throw Error("分类或系列存在空值，请先查看表格。");
      const category = String(row[spec.category]), group = spec.series ? String(row[spec.series]) : spec.metric;
      if (!categories.includes(category)) categories.push(category);
      if (!groups.has(group)) groups.set(group, new Map());
      const bucket = groups.get(group);
      if (spec.aggregate === "none" && bucket.has(category)) throw Error("分类与系列组合重复，请选择系列字段或明确聚合方式。");
      const value = number(row[spec.metric]);
      if (spec.aggregate !== "count" && value === null) throw Error("指标存在空值或非数值，请先查看表格，不会自动补零。");
      const previous = bucket.get(category) || { sum: 0, count: 0 };
      bucket.set(category, { sum: previous.sum + (value || 0), count: previous.count + 1 });
    }
    const valueOf = point => !point ? null : spec.aggregate === "count" ? point.count : spec.aggregate === "mean" ? point.sum / point.count : point.sum;
    if (spec.sort !== "original") categories.sort((a, b) => {
      const total = category => [...groups.values()].reduce((sum, group) => sum + (valueOf(group.get(category)) || 0), 0);
      return (total(a) - total(b)) * (spec.sort === "desc" ? -1 : 1);
    });
    if (spec.type === "pie") {
      if (groups.size !== 1) throw Error("占比图只支持一个系列，请取消系列字段。");
      const group = groups.values().next().value;
      const data = categories.map(name => ({ name, value: valueOf(group.get(name)) }));
      if (data.some(point => point.value < 0) || !data.some(point => point.value > 0)) throw Error("占比图需要非负数据和正数总额。");
      return { tooltip: { trigger: "item" }, series: [{ name: spec.metric, type: "pie", radius: ["38%", "68%"], data }] };
    }
    const horizontal = spec.type === "horizontal_bar";
    const categoryAxis = { type: "category", data: categories, axisTick: { show: false } };
    const valueAxis = { type: "value", name: spec.aggregate === "count" ? "行数" : spec.metric };
    return { tooltip: { trigger: "axis" }, xAxis: horizontal ? valueAxis : categoryAxis, yAxis: horizontal ? categoryAxis : valueAxis,
      legend: groups.size > 1 ? { type: "scroll", top: 12 } : { show: false },
      dataZoom: categories.length > 12 ? [{ type: "inside", ...(horizontal ? { yAxisIndex: 0 } : {}) },
        { type: "slider", ...(horizontal ? { yAxisIndex: 0, right: 8, width: 14 } : { bottom: 8, height: 16 }) }] : [],
      series: [...groups].map(([name, group]) => ({ name, type: horizontal ? "bar" : spec.type,
        data: categories.map(category => valueOf(group.get(category))), barMaxWidth: 32, symbolSize: 6, lineStyle: { width: 3 }, connectNulls: false })) };
  }
  function style(option) {
    const copy = JSON.parse(JSON.stringify(option));
    return { ...copy, animation: false, backgroundColor: "#ffffff", color: palette,
      title: { show: false }, textStyle: { fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, sans-serif", color: "#626879", fontSize: 12 },
      grid: { left: 20, right: 32, top: copy.legend?.show === false || !copy.legend ? 35 : 60, bottom: 44, containLabel: true },
      legend: copy.legend ? { ...copy.legend, top: 12 } : { show: false },
      tooltip: { ...copy.tooltip, renderMode: "richText", confine: true },
      ...(copy.yAxis ? { yAxis: { ...copy.yAxis, splitLine: { show: copy.yAxis.type === "value", lineStyle: { color: "#edf0f5" } } } } : {}),
      ...(copy.xAxis ? { xAxis: { ...copy.xAxis, splitLine: { show: copy.xAxis.type === "value", lineStyle: { color: "#edf0f5" } } } } : {}) };
  }
  function mount(node, option) {
    if (!window.echarts) throw Error("图表组件未加载，仍可查看表格。");
    const plot = window.echarts.init(node);
    plot.setOption(style(option));
    const observer = new ResizeObserver(() => {
      if (!node.isConnected) { observer.disconnect(); plot.dispose(); return; }
      if (node.clientWidth && node.clientHeight) plot.resize();
    });
    observer.observe(node);
    return { plot, dispose() { observer.disconnect(); plot.dispose(); } };
  }
  function image(option) {
    const node = document.createElement("div");
    node.style.cssText = "position:fixed;left:-10000px;top:0;width:1000px;height:600px;";
    node.setAttribute("aria-hidden", "true"); document.body.append(node);
    let mounted;
    try {
      mounted = mount(node, option);
      return mounted.plot.getDataURL({ type: "png", pixelRatio: 1, backgroundColor: "#fff" });
    } finally { if (mounted) mounted.dispose(); node.remove(); }
  }
  return { palette, fields, infer, compile, style, mount, image };
})();
