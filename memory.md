# 开发问题记录

## 2026-09-16：A2 持久化导入与验证边界

- 旧导入预览在 Main 内存中，服务重启即失去任务。改为 V004 本人暂存和选择版本，确认使用同一 H2 事务提交正式记录、导入批次、审计及任务状态，不能嵌套调用会自行 commit 的公开 importRows。
- 预览保存的是正式数据基线，不是授权凭证；确认还必须重验当前账号／身份版本、任务拥有者、过期时间、选择版本及基线。相同重试返回原结果，变更决定重放拒绝。
- 首轮升级测试仍断言 schema 3 和迁移序列 1/2/3；V004 追加后应同步更新期望并保留 V1/V2/V3 升级测试，不改旧迁移文件来迁就测试。
- 缺缓存测试在已有空字符串单元格上调用 setCellFormula，会留下有效的空字符串缓存；它不同于真正无缓存。测试显式 unsetV 构造无缓存，解析器拒绝缺缓存／错误缓存，不把它默认为数值零；数字公式客户编码与数字常量执行相同精度保护。
- 基础状态页面曾硬编码 schema 1，现由 store.schemaVersion() 注入实际值，避免功能已升级但诊断页仍显示旧结构。
- 页面检查和测试仅使用虚构数据。现代浏览器的 1366／1920 视觉检查及安装器模拟测试不代表 Win7 IE／麒麟实机通过。B2 用户反馈已完成，但当前远端未查到，交接状态必须区分用户报告与已核验 PR。

## 2026-09-15：A1/B1 集成接入与回归

- Git 自动合并保留了两侧开发记录，无需以一侧覆盖另一侧；主要差异是 Main 未接入 B1、通知跳转缺失和 submissionId 审计参数不一致。
- 审计单据筛选必须先检查权限并按 workflow_audit_links 精确过滤，不能忽略未识别参数后显示全部日志。
- 旧批量保存入口如果继续调用 publishDirect 会跳过新流程确认；兼容入口现改为预览，确认后才发布。
- 补充全部草稿分页、复核筛选 if 作用域、退回关联随筛选保留；新增真实 Main 五角色和实际 Excel 导出测试。
- 集成测试曾假设不存在的单据返回 404；公共工作流有意将不存在与越权统一为 403，修正测试期望，未削弱防枚举检查。

## 2026-09-15：A1 回归与页面检查

- 审批创建账号必须复用同一个数据库事务，不能在外层审批事务内调用会自行 commit 的公开 createUser；现拆出同包 helper，并对账号、通知和最终决策阶段注入故障验证回滚。
- 匿名权限申请不可复用 Path=/login 的 CSRF Cookie；独立 ZXAPPLY Cookie 使用 Path=/access/apply，一次性验证，配合来源限流和待办认证号唯一约束。
- A1 新增审计测试曾持有直接修改前的 BusinessRecord 版本，后续保存草稿被正确拒绝；测试改为重新读取正式版本，未放宽生产冲突检查。
- PowerShell 启动 Java 时 `-Dfile.encoding=UTF-8` 必须整体加引号，避免参数被拆分为错误的主类名。
- 本地 Chromium 虚构页面检查发现公共导航的 span 样式覆盖通知气泡间距；增加限定选择器，保持小尺寸红色气泡。视觉检查不等同于 Win7 / IE 实机验收。

## 2026-09-14：PowerShell 文档分段读取失败

- 现象：使用 `[Math]::Min($range[1], $lines.Count)` 控制文档读取范围时，PowerShell 报错 `Argument types do not match`，循环未执行。
- 原因：数组元素与 `Count` 在该表达式中的运行时数值类型不一致，无法匹配同一个 `Math.Min` 重载。
- 解决：先把起止值显式转换为 `[int]`，再调用 `[Math]::Min([int]$end, [int]$lines.Count)`；修正后成功读取目标行。
- 避免复发：涉及 .NET 重载的 PowerShell 数值运算不要依赖隐式类型推断，尤其是值来自异构数组或管道结果时。

## 2026-09-14：Windows 安装器模拟找不到 Bash

- 现象：`node tests/install-smoke.mjs` 报错 `spawnSync C:\Program Files\Git\bin\bash.exe ENOENT`，安装器模拟未开始执行。
- 原因：测试把 Git for Windows 的 Bash 路径写死为 `C:\Program Files\Git\bin\bash.exe`；本机 Git 安装在 `D:\Git`，且 `bash.exe` 未加入 PATH。
- 解决：为测试增加 Bash 解析顺序：`BASH_EXE` 环境变量、PATH 中的 `bash.exe`、根据 `where git.exe` 推导 Git 安装根目录、常见 Program Files 路径。修正后找到 `D:\Git\bin\bash.exe`，安装器模拟完整通过。
- 避免复发：跨机器测试不得假定 Git 或其他工具安装在系统盘固定目录；必须允许显式覆盖并提供可诊断的自动发现与缺失提示。

## 2026-09-14：PowerShell 中的正则扫描表达式损坏

- 现象：在双引号 PowerShell 命令中执行敏感信息正则扫描时，`rg` 报错 `unclosed character class`。
- 原因：正则字符类旁的反引号被 PowerShell 当作转义符处理，传给 `rg` 的表达式已经不是原始文本。
- 解决：对只需确认固定敏感标记的检查改用 `Select-String -SimpleMatch` 和独立字符串列表；修正后新文档扫描通过。
- 避免复发：从 PowerShell 传递包含反引号、美元符号或复杂字符类的正则前必须检查实际参数；固定标记扫描优先使用字面量匹配。

## 2026-09-15：单分支克隆导致远端 main 状态陈旧

- 现象：`git fetch origin --prune` 成功后，`origin/main` 仍停留在初始版本，但 GitHub API 已显示 PR #1、#2、#3 合并。
- 原因：仓库最初用单分支方式克隆，`remote.origin.fetch` 仅包含 `codex/v0.3-foundation`，普通 fetch 不会更新其他远端分支。
- 解决：将 refspec 调整为 `+refs/heads/*:refs/remotes/origin/*` 后重新 fetch，`origin/main` 更新到任务发布提交 `1050fdaa`。
- 避免复发：协作巡检不能只看 fetch 命令是否成功；先核对 `remote.origin.fetch`，再比较 GitHub 远端默认分支与本地 remote-tracking ref。

## 2026-09-15：工作流测试的 Period 类型名称冲突

- 现象：`javac` 报告 `Period` 构造器参数不匹配，同时匹配 `java.time.Period` 与 `xinguan.platform.Period`。
- 原因：测试同时使用 `java.time.*` 和 `xinguan.platform.*` 通配导入，两个包都声明了 `Period`。
- 解决：构造业务期次时显式写为 `new xinguan.platform.Period(...)`，编译恢复。
- 避免复发：Java 文件同时使用多个含同名类型的通配导入时，对领域类型使用完整限定名或改为显式导入。

## 2026-09-15：独立 Java 测试未复制数据库迁移资源

- 现象：工作流路由测试编译成功，但启动 `PlatformStore` 时报 `缺少数据库迁移资源：V001__foundation.sql`。
- 原因：专用测试运行器只编译了 Java 类，没有像正式构建脚本一样把 `resources/` 复制到测试 classpath 根目录。
- 解决：在运行 Java 前执行 `fs.cp(resources, output, {recursive:true})`，V001/V002 可由类加载器读取，真实 H2 测试通过。
- 避免复发：独立 Java 测试必须复刻运行时资源布局；只把依赖 JAR 和 `.class` 放入 classpath 不足以启动依赖类路径资源的模块。

## 2026-09-15：PowerShell 直接启动 Edge 无法可靠取得退出码

- 现象：用调用运算符运行 Edge headless 截图后 `$LASTEXITCODE` 为空，脚本误判为失败且没有生成截图。
- 原因：Windows GUI 可执行文件的直接调用没有在该宿主中提供可依赖的同步退出码语义。
- 解决：改用 `Start-Process -WindowStyle Hidden -Wait -PassThru`，检查进程对象的 `ExitCode`；1366 和 1920 截图均成功生成。
- 避免复发：PowerShell 自动化 GUI 程序时显式等待进程并从 `PassThru` 对象取退出码，不依赖 `$LASTEXITCODE`。

## 2026-09-15：固定测试时钟及随机 ID 导致列表断言不稳定

- 现象：工作流测试首次通过，复跑时“退回原因”或分页内 HTML 转义断言偶发失败。
- 原因：测试时钟固定使多个提交拥有相同创建时间，正式记录及提交再按随机 UUID 排序；测试用列表第一项推断刚创建的提交，并假定特殊客户总在第一页，实际顺序不稳定。
- 解决：从确认结果页面读取服务端返回的稳定提交单 ID，再按 ID 查询精确单据；先定位特殊客户的实际列表下标，再请求对应分页验证转义。
- 避免复发：测试不得依赖同时间或随机 ID 记录的隐式顺序；写操作后使用返回的稳定标识，分页断言先根据真实排序定位目标。

## 2026-09-15：右浮动导致主次按钮视觉顺序反转

- 现象：工作流编辑页 HTML 中先写“保存草稿”再写“提交”，实际截图却显示“提交”在左、“保存草稿”在右，与主操作应位于最右侧的阅读习惯相反。
- 原因：两个按钮都使用 `float:right`，右浮动元素的视觉排列顺序与 DOM 顺序相反。
- 解决：在 DOM 中先输出主按钮“提交”，再输出次按钮“保存草稿”，1366 与 1920 截图确认最终显示为左侧保存、右侧提交。
- 避免复发：旧 IE 兼容布局使用 float 时不能仅按 DOM 顺序判断视觉顺序；必须在目标分辨率截图核对主次动作位置。

## 2026-09-15：确认页面刷新会生成新的请求编号

- 现象：同一服务端预览或同一待审单每次重新渲染时都生成随机 `requestId`，刷新页面后不再沿用原确认动作编号。
- 原因：页面层直接在每次渲染时调用 `UUID.randomUUID()`，没有把请求编号稳定绑定到预览或单据。
- 解决：确认、批准、退回分别使用 `confirm-预览ID`、`approve-单据ID`、`reject-单据ID`；恢复同一预览的测试确认 requestId 保持一致。
- 避免复发：幂等请求编号应绑定服务端业务对象和动作，不应绑定某一次 HTML 渲染；刷新、双击和不确定重试必须复用同一编号。

## 2026-09-15：Git 智能 HTTP 推送持续被连接重置

- 现象：分支内容约 100 KB，普通 `git push` 连续两次报 `Recv failure: Connection was reset`；强制 HTTP/1.1 后连接长期无响应。同期 `gh api` 访问 GitHub 正常。Git Data API 创建的提交与本地提交 tree 完全相同，但提交 SHA 不同。
- 原因：本机到 GitHub 的 Git smart-HTTP 传输链路不稳定，不是提交大小、权限、认证或仓库规则错误；GitHub REST 创建的提交消息末尾没有换行，而本地 `git commit-tree -m` 会补换行，因此 commit 对象 SHA 不同。
- 解决：先逐个通过 GitHub Git Data API 创建并校验 blob，再基于精确基线创建相同 tree、commit 和分支 ref；以 tree SHA 证明文件内容一致，创建远端 ref 后 fetch 该对象并把本地分支指向远端提交。
- 避免复发：推送连接重置时先保留本地提交并有限次重试；若 GitHub API 可用，使用可校验的 Git Data API 传输对象，并同时核对 blob、tree、parent；不要仅凭 commit SHA 不同判断内容偏差，也不要丢弃提交或重复创建 PR。
