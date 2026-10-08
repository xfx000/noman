---
name: hyperframes
description: Explicitly invoked creative video planning and HyperFrames HTML composition. Available only after local opt-in and a user message beginning with /hyperframes.
---

# HyperFrames 创意视频草案

仅当本轮用户消息以 `/hyperframes` 开头时使用。以用户语言沟通。这里的自由创作能力是策划与生成源码；已完成数据报告的 `report-video` 钩子有独立的固定模板 MP4 渲染流程，不由本 skill 自动调用。

## 工作流

1. 从消息提取用途、受众、画幅、时长、文案、视觉风格、可用素材和声音要求。缺少非关键项时明确作出合理假设。
2. 给出简短分镜表：场景、起止秒、画面、动画、声音。时间覆盖完整，媒体来源明确；不要虚构已取得的素材或授权。
3. 用户要求可运行草案时，输出一个完整 `index.html` 代码块。根节点必须同时有 `data-composition-id`、`data-width`、`data-height` 和 **总时长 `data-duration`**。例如 5 秒纯 CSS 视频使用 `<div id="video" data-composition-id="intro" data-width="1920" data-height="1080" data-duration="5" data-no-timeline>`。每个时间轴场景使用 `class="clip"`、稳定且唯一的 `id`、`data-start`、`data-duration`、`data-track-index`。纯 CSS 动画且没有 JavaScript timeline 时，根节点必须有 `data-no-timeline`；如果使用 JavaScript timeline，则按官方接口注册到 `window.__timelines[compositionId]`。所有动画必须能定位到任意时间点；避免依赖页面加载时间、随机数或用户交互。
4. 只引用用户实际提供或明确允许使用的媒体路径。素材缺失时使用 HTML/CSS/SVG 自绘占位，并列出替换位置。
5. 输出前逐项检查：根节点有明确总时长，最后一个场景结束时间不超过总时长；每个场景有唯一 `id`；画幅、文字溢出、媒体路径与声音约束成立；`data-no-timeline` / timeline 注册二选一。不能声称已经运行 lint、preview 或 render。若用户提供了真实 lint 错误，先据此修正草案。
6. 给出本地复现步骤：安装 Node.js 22+、FFmpeg，运行 `npx hyperframes init my-video`，进入新目录并放入 `index.html`，再运行 `npx hyperframes lint`、`npx hyperframes preview`、`npx hyperframes render`。CLI 参数以用户安装版本的官方文档为准。

## 边界

用户没有明确写 `/hyperframes` 时不使用本 skill。不要自动下载安装依赖、执行命令、访问云渲染、上传媒体或宣称输出了视频文件。若需要从业务数据制作视频，先说明当前创意模式未接入数据分析工具，由用户提供经过核实的数值或另行完成数据分析。

参考：[HyperFrames 官方仓库](https://github.com/heygen-com/hyperframes) 与其文档。此 skill 是 Qiqi 的接入草案，不复制上游 skill 内容。
