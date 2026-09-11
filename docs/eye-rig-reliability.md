# 眼部生成与 MCP 修复说明

本地修改基于 `tsunehimatoi/psd2live` 的 `e6f55d5`，分支为 `codex/eye-rig-reliability`。这是本地构建，不是上游正式发行版。

## 修改内容

- 新生成的网格以用户设置的 Alpha 阈值保留软边、小块睫毛和眉毛笔画。贝塞尔拟合必须覆盖原始轮廓，否则使用保留轮廓的恢复路径；保留孔洞和独立岛。
- 局部边缘间距可设到 1 像素，内部间距可设到 2 像素。界面、生成器和 MCP 共用设置解析器。内部采样仍受顶点预算限制。
- 检测同一睫毛图层中的上下眼皮。闭眼时合并成细线，单条睫毛保留原有厚度处理。眼白与睫毛共用闭合曲线；眼白和虹膜在最后 10% 闭合阶段淡出，避免残余三角形碎片。已有开关/换装透明度轴继续生效。
- 自动把落在所属脸层后面的眉毛放到该脸层前。其他图层的相对顺序、正常的刘海遮挡和显式绘制顺序覆盖保持有效；仅网格模式保留源顺序。
- `object_get` 返回通道每个关键帧的参数坐标和实际值，可直接发现“中间顺序正确、两个端点仍被脸挡住”的情况。
- 局部重新生成网格遇到已手工编辑的顶点关键帧、涉及几何的复制操作或胶水时拒绝修改，界面和 MCP 使用同一检查。不会通过删除动画来完成重建。
- 重开工程和切换历史时从权威快照恢复网格覆盖设置，避免预览与界面设置不同步。

## 工程兼容

新增持久化字段 `rigGenerationVersion`。新 PSD 工程使用版本 2；没有该字段的已有 `.psd2live` 使用版本 1，以保留依赖旧顶点顺序的手工关键帧。旧历史恢复也保持版本 1。未知版本明确报错。

这不是自动升级已有绑定的工具。旧工程可以继续编辑；本轮没有实现带顶点重映射的完整重拓扑迁移。新生成逻辑需要用于新工程，已有历史恢复不会被强行升级。

眉毛规则只处理明确分类为眉毛且中心位于脸层范围内的情况。闭眼预设不能替代复杂素材的人工精修；合在一个图层的双眼共享透明度，单眼眨眼时不能隐藏另一只眼。

## MCP

先读取 `project_get_state` 获取当前历史头，再调用修改工具。

`mesh_inspect`：

```json
{"layer_ids":["实际的源图层 ID"]}
```

返回有效网格设置、顶点/三角形数、基础透明度和绘制顺序、遮罩 ID、Alpha 阈值、被网格裁掉的像素数及其透明度权重比例。覆盖率通过贴图 UV 检查，范围是中性姿势的源像素；它不是前景遮挡或所有动作姿势的可见性诊断。

`mesh_settings_set`：

```json
{
  "expected_history_head_node_id":"刚读取的历史头",
  "layer_ids":["实际的源图层 ID"],
  "settings":{"max_edge_distance":2,"interior_density":4}
}
```

支持 1–32 个图层，单次历史事务。`settings: null` 清除局部覆盖；对象形式按字段修改。支持 `outer_margin`、`inner_margin_enabled`、`inner_margin`、`max_edge_distance`、`interior_density`。旧工程继续使用已记录的生成版本。

`keyform_set` 是指定坐标的关键帧编辑，并非整个通道的常量设置。修改后用 `object_get.channels[].cells` 检查全部相关坐标。

## 构建与验证

JDK 21，使用仓库自带 Gradle wrapper：

```powershell
.\gradlew.bat check createDistributable
.\gradlew.bat eyeRigVisualCheck '-PeyeRigSource=C:/art/character.psd'
.\gradlew.bat eyeRigVisualCheck '-PeyeRigSource=C:/art/character.psd2live'
```

可用 `-PeyeRigOutput=完整输出目录` 指定对照图目录。普通测试只使用合成图层，不包含用户素材。真实素材验证工具只读源文件，使用软件原生 PSD/工程解析器。

2026-09-12 本地验证结果：

- `check` 通过，161 项测试，包括软边、独立小岛、孔洞、眼皮合层、闭眼透明度、绘制顺序关键帧、局部网格修改及历史持久化。
- `createDistributable` 通过，输出为 `build/compose/binaries/main/app/PSD2Live/PSD2Live.exe`，需保留旁边的 `app` 和 `runtime` 文件夹。
- 原始赵雅思 PSD 的 8 个眼部/眉毛图层，在 Alpha 阈值 8 下，被网格裁掉的像素由 1,054 减少到 0。此指标不包括低于阈值的像素。
- 已输出并检查睁眼、半闭眼、接近闭眼、闭眼和单眼眨眼的模型图；同时生成视线和眉毛端点图。
- 现有修复候选工程通过原生解析与重建，保留版本 1、23 个历史节点和 27 条手工关键帧编辑。
- 真实图测试使用软件的无界面渲染路径；尚未由用户启动新版 Windows 窗口验收，也未在新版 GUI 的 Cubism SDK 预览中验收。

原始 PSD、已有工程和已安装的软件均未覆盖。
