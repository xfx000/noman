---
name: report-video
description: Offer an evidence-backed 15-second analysis video after a completed Qiqi report; render only after the user accepts the offer.
---

# 分析结论短视频

## 触发钩子

- 仅在创意功能开关已开启、分析运行结束且最终报告存在时检查。
- 报告必须有属于当前用户和会话、可读取且包含数值列的 `queryId` 证据。
- 在该报告卡片中询问“把这份分析做成短视频？”，每次展示报告时只提示一次；用户点击“用 HyperFrames 生成”才执行渲染。关闭提示不改变分析结果。
- 已生成视频时，提示“查看短视频”，不重复渲染。

## 执行约束

1. 从服务端运行记录选取该报告关联的查询证据，优先选已有图表的 `queryId`，再选其他查询结果。始终按当前身份和会话重新鉴权读取原始结果。
2. 画面中的数值和类目只取自该查询结果。截断查询只能称为“预览”；不能将最大值解释成原因或全量排名。
3. 使用固定 HTML 模板制作 15 秒、16:9、无配音视频；视频末尾展示 `queryId`。不执行模型生成的任意 HTML、JavaScript 或外部媒体 URL。
4. 在独立工作目录运行 HyperFrames CLI；渲染后保存 MP4 和来源 HTML。失败只影响视频，不改变报告。
5. 视频只通过当前用户鉴权的接口提供预览和下载。用户关闭创意功能后不再出现新提示。

当前版本由报告完成钩子和服务端模板执行，不要求模型自动调用此 skill，也不会在普通聊天消息中主动生成视频。
