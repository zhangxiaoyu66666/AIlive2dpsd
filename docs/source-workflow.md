# See-Through 到 Live2D 的制作流程

2026-09-12

## 界面操作

标签栏下的“制作流程”连接当前机器上的 See-Through（默认 `http://127.0.0.1:7866`）。先启动 See-Through，再点击连接。连接只检查接口，不启动程序、不安装模型、不修改 See-Through。

1. 选择 PNG、JPEG 或 WebP，设置分辨率、种子、左右拆分和显存卸载，点击上传并拆图。显示服务实际返回的进度；收到结果后显示合成预览、尺寸、图层名和 SHA-256。
2. 检查并确认拆图结果。已有 PSD 也可以直接选择为候选结果，不要求连接服务。
3. 默认以确认结果作为导入版。需要继续拆分时，先保存可编辑 PSD，在 Photoshop 等软件中编辑并保存，再点“重新读取导入 PSD”，或选择另一份实际导入 PSD。预览和哈希更新后再导入；确认版保持原样。
4. 空标签直接载入模型；已有模型的标签会把新导入版放入新标签，保留旧模型、参数和历史。不会尝试把旧网格/关键帧盲目套到改变后的图层结构上。
5. 在导入的标签中调整绑定并生成。每次生成写入所选输出目录下的独立 `generation-时间-随机ID` 目录，附带 `source-lineage.json`。保存 `.psd2live` 后可在其他位置重开，确认版和导入版均由工程自身携带。

“检查源文件”比较当前磁盘 PSD 与已导入快照。发现修改只提示重新选择版本，不会替换正在编辑的模型。重新读取后需要明确点击导入。流程生成按钮始终生成当前标签已经导入的模型；尚未导入的候选预览不参与生成。

## 版本与失败语义

- 确认和导入分别记录原路径、固定副本、字节数、修改时间、捕获时间、SHA-256。判断版本采用内容哈希，不能用同名文件或时间戳替代。
- 捕获时检测源文件是否仍在保存，变化则失败；确认/导入/工程打开时重新验证固定副本。
- 生成记录包含项目 ID、历史 HEAD、确认/导入哈希，以及每个实际产物的路径、大小、哈希。生成时的输入和历史固定；生成期间发生的后续编辑仍然保留。
- Gradio 生成器可能在 `generating` 事件返回 PSD，而在 `complete` 事件返回空输出。连接器保留最后一个真实文件描述，并在下载前原子保存，恢复下载不要求服务重放已消费事件。
- 停止等待只取消本地接收，不保证终止服务端推理。恢复使用原 event ID，不自动重新提交。服务重启或事件已经丢失且本地未收到文件描述时，可选择服务生成的 PSD 作为候选结果；不要盲目重复提交。
- 未导入的候选是当前标签的会话状态；关闭前应保存可编辑 PSD。任务 ID 与下载描述留在工作区的 `source-workflows` 目录，但当前界面不自动恢复关闭前的候选标签。已导入的完整来源链保存在工程里。
- 只连接本地 HTTP 地址。下载必须来自同一服务的 Gradio 文件路由。上传图片限 32 MiB，PSD 导入限 1 GiB。

## MCP

沿用多标签 `tab_id` 与写入 `lease_id`，与 UI 共用同一个控制器：

1. `tab_create` 创建空标签；`tab_claim` 获取凭证。
2. `source_workflow` 的 `connect` 动作传 `endpoint`；`decompose` 传 `image` 和可选 `resolution`、`seed`、`split`、`offload`。
3. 轮询 `source_workflow_get`，等待 `busy=false` 并检查 `error`。它区分 `workflowBusy`、`projectBusy`，返回候选哈希、尺寸、图层名和持久版本记录。传 `include_preview=true` 可取得与 `previewSha256` 对应的 PNG 预览；普通轮询不传图像。不要根据一次异步提交成功推断拆图/导入/生成已完成。
4. `confirm` 必须传候选 `sha256`；`stage_import` 可换成手工拆分 PSD 的 `path`。`save_psd` 可以把当前候选保存到指定 `path`，同样要求准确哈希，并受标签路径占用保护。
5. `import` 必须传实际导入候选 `sha256`。返回 `importedTabId`；如果是新标签，先重新认领它，等 `project_get_state.loaded=true` 且 `busy=false`。MCP 导入不会切换用户正在看的标签。
6. `generate` 传 `output`、当前 `expected_history_head_node_id`。轮询导入标签，完成后读取 `currentProjectVersions.generation`，检查记录和文件。

`stage_result` 用于既有 PSD，`check_source` 检测外部修改，`resume` 续取原事件，`cancel_wait` 停止本地等待。没有后台自动生成或自动覆盖模型。

## 验证入口

普通 `check` 包含合成 PSD 的确认/导入、同路径不同内容、工程归档往返、快照校验、生成文件哈希、真实 HTTP multipart/SSE 协议与文件选择器回归。

`sourceWorkflowLiveCheck -PworkflowImage=... -PworkflowOutput=...` 是显式选择的本地集成检查，不属于普通测试。首次上传并运行真实推理；输出目录保存 event ID。已消费事件可用 `-PworkflowResultUrl=...` 明确指定该事件的真实文件 URL，续验下载与生成，不重复跑 GPU。此恢复模式不能单独证明新提交的完整流式接收已成功。

原生界面操作与多模型 GPU 预览仍需在新版软件中人工验收，CPU 导出和无窗口测试不能替代该部分。

本机验收记录（2026-09-12）：190 项自动测试通过，包含真实 ViewModel 的导入、生成、保存及删除外部源文件后重开。打包运行时的 MemoryUtil、NFD、COM 初始化通过。使用 See-Through 官方示例实际上传、拆分得到 28 层 PSD，再通过服务 HTTP 下载并导出 11 个文件；打包 JVM 也通过同一真实文件的下载和导出。首次流式收尾暴露了 Gradio 空输出行为，已修复并用对应事件序列回归；修复后复用了该真实产物，没有重复启动整次 GPU 推理。证据位于构建目录下 `source-workflow-*.log` 和 `source-workflow-live-qa/export/source-lineage.json`。未进行新版窗口点击验收。

接口核对日期：2026-09-12。实现参照 [Gradio HTTP 调用](https://www.gradio.app/guides/querying-gradio-apps-with-curl)、[Gradio API 页面](https://www.gradio.app/guides/view-api-page)、[Ktor SSE 客户端](https://ktor.io/docs/client-server-sent-events.html) 与 [Ktor multipart 请求](https://ktor.io/docs/client-requests.html)，同时核对本机 `/gradio_api/info` 和真实生成器输出行为。使用现有 Ktor/PSD/ZIP 实现，不引入第二套协议或图像解码器。
