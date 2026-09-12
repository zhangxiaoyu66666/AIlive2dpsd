# 预览卡顿修复（2026-09-12）

录屏显示“软件回退预览”。当前安装包未配置 Cubism 原生运行库；原先 Compose 回退路径每帧创建整个视口的 BufferedImage，由 Java2D 对每个三角形反复裁剪并绘制图集，随后再转换为 Compose 位图。实际 22 图层 PSD 有 16,232 个三角形，即使用户没有编辑，预览动画也会持续触发这条路径。原 FPS 仅统计 SDK 出帧，没有显示回退绘制耗时。

编辑器纹理绘制现在使用已有 Skia 的 drawVertices 批量接口，直接接入 Compose 画布。模型图集和 UV 在视口生命周期内复用；关闭或切换模型释放 Image、Shader、Paint。没有引入新的解码器、依赖、原生库或降低图像分辨率。保留几何蒙版、图层顺序覆盖、隐藏和调暗选项。原有 Java2D 绘制仍用于 Swing 工具和参考图；Compose 中只负责网格、文字、选框等注释，普通预览无需创建全屏 Java2D 位图。原生 Cubism 已配置时仍使用原生预览。

FPS 统计改为画布更新，不再将 SDK 生产帧数当作画布帧率。原生运行库不可用时显示“Skia 预览”，失败原因进入日志，并由 project_get_state 的可选 sdkStatus 字段提供诊断。Skia 会使用宿主的绘制后端，此文字不保证机器正在使用 GPU。

## 验证

- `previewRenderCheck -PworkflowPsd=<PSD>`：同一真实 PSD、相同视口、三组姿态，分别绘制旧 Java2D 与新 Skia，导出对照图片并比较预乘色彩与透明度。22 图层录屏素材平均通道差异约 0.19/255；旧路径中位数 66.75 ms、P95 96.19 ms，新路径中位数 28.84 ms、P95 37.55 ms。这里包含软件光栅化与截图读回，不能换算为桌面 GPU 帧率。
- `projectTabsUiCheck`：真实 Compose 离屏 1280×720 界面，连续改变模型姿态。普通预览帧中位数 24.67 ms、P95 28.97 ms；带悬停选框时 29.78 ms、P95 39.31 ms。包含标签、层级、预览界面。并验证普通滚轮、反向滚轮、拖动滚动条及首尾标签自动可见。
- `CurrentTabOpenTest`：当前标签替换、取消、加载失败保留模型/未保存修改、旧 MCP 凭证失效与旧路径释放。
- Windows 原生窗口拖放、GPU 帧率和手感尚待用户在新包中验证；测试没有操作或重启用户现有窗口。

2026-09-13 发行包复核：204 项测试全部通过，失败/错误/跳过为 0。使用发行包自己的 jlink JVM、JAR 和 Skiko 动态库再次离屏绘制同一 PSD，三组画面对照通过；旧路径中位数 66.00 ms，新路径 30.43 ms。发行目录为 `build/compose/preview-performance-binaries/main/app/PSD2Live`；MemoryUtil、NFD、COM 和 UTF-8 参数门禁通过。复核日志为 `build/packaged-preview-check.log`、`build/preview-performance-release.log`。

证据：`build/preview-stutter-qa`、`build/tab-navigation-qa`、`build/preview-fixes-check.log`。原理核对使用 [Skia Canvas API](https://api.skia.org/classSkCanvas.html)（2026-09-12）。
