# 开发问题记录

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
