# Windows 原生文件选择器

2026-09-12 本地修改，基于 `codex/eye-rig-reliability`。不是上游正式发行版。

## 行为

Windows 的打开 PSD、打开工程、另存工程和导出目录统一使用 Native File Dialog Extended 的 `IFileOpenDialog` / `IFileSaveDialog`。地址栏、搜索、导航侧栏、视图和窗口尺寸由 Windows 文件对话框管理；外观跟随 Windows，不由软件绘制仿资源管理器界面。当前绑定的对话框标题由 Windows 按系统语言提供，文件类型说明使用软件所选语言。

- PSD 与工程通过真正的文件类型筛选项过滤；打开窗口的文件名框不再写入 `*.psd` 或 `*.psd2live`。
- 取消后立即结束。原代码将取消当成原生窗口失败，继续打开备用窗口；现在取消与错误分开处理。
- 目录选择不再启动 PowerShell。对话框带所属窗口句柄；所有原生调用在同一 AWT 事件线程初始化并释放 COM，选择结果的原生内存及时释放。
- 在本次应用运行中分别保留最近的 PSD、工程、保存和导出目录；调用方提供当前工程或 PSD 路径时优先沿用。未建立的输出子目录会向上寻找有效父目录。
- 保存自动补 `.psd2live`，保留中文和空格。补扩展名导致实际目标改变且已存在时，单独确认真实目标，防止越过系统覆盖确认。选择器本身不写文件。
- Compose 与旧 Swing 前端共用一个入口。非 Windows 平台保留 AWT/Swing 对话框，不改变全局 Swing Look & Feel。

## 依赖与验证

复用项目已有 LWJGL 3.4.2 BOM，增加同版本 `lwjgl-nfd` 及本机 native 模块；打包增加约 108 KB 的两个 JAR。许可证和上游来源见 `THIRD_PARTY_NOTICES.md`。

- `check`：172 项测试全部通过，包含 7 项文件选择回归。
- Windows NFD 原生库加载、COM 初始化和释放通过无窗口测试。
- `createDistributable` 通过，原生 Windows DLL 已包含在发布目录的 NFD native JAR 中。
- 未启动或操作用户桌面窗口；搜索、缩略图、DPI、多显示器、焦点与原生覆盖提示仍待手动界面验收。macOS/Linux 未在本机验收。

手动验收：退出旧版后启动同目录的新 `PSD2Live.exe`；依次打开工程、导入 PSD、选择导出目录、另存工程。检查地址栏粘贴路径、搜索、中文文件名、文件类型筛选、取消一次即关闭和已有文件覆盖提示。启动时保留旁边的 `app` 和 `runtime` 目录。

官方实现核对（2026-09-12）：

- https://github.com/btzy/nativefiledialog-extended
- https://github.com/LWJGL/lwjgl3/tree/3.4.2/modules/lwjgl/nfd
