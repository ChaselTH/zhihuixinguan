# FEEDBACK-009 合并前修复交接

## 接手与授权

- 2026-09-21 人工交接；用户已明确确认原 AI 暂停开发和推送，并授权本轮接手。未修改协作任务入口，未创建定时任务。
- 原 PR：<https://github.com/ChaselTH/zhihuixinguan/pull/12>，接手时开放、未合并，评论／审查为空。
- 核实后的原功能基准：`7c501e487c3cba485c3137393fc44e0efc8a0b36`；main：`78014d5680c7d8546b44c336ad4822f18c5e599e`。与交接材料一致。
- 独立修复分支：`codex/fix-feedback-009-review`，直接从上述原功能提交创建，保留其全部功能。未改 main 或原功能分支。
- GitHub 连接身份 `Miso-Soup98`，仓库权限接口返回 `write` / `push=true`。有效协作者权限已确认；接口没有邀请接受时间记录，不能独立报告邀请接受时间。
- Git 2.46.0.windows.1 可用，`ls-remote` 与 clone 成功；没有 `gh`，GitHub 连接工具具备 Git data 和创建 PR 能力。上传前再次检查远端变化。
- 用户明确确认 V009/V010 无人使用；V001～V008 不得修改。本轮修正尚未发布的 V009；如发现已有旧 V009 测试库，必须保全并从 schema 8 备份重试，不修改迁移校验记录强开。

## 数据与送审模型

- 导入的黄色字段是不可变候选内容，`Mode.IMPORT` 的上传人是来源责任人，不是支行业务填写人。必须由独立支行复核员明确核对候选，之后才允许分行终审；上传本身不发布、不伪造终审。
- 单分行管理员可以在支行独立确认后终审其上传候选。只针对此种模式核验逐行支行确认事实，普通 `REVIEW` / `DIRECT` 的禁止本人审核不放宽。
- 退回重提是明确操作，冻结正式值和版本，生成新的审批链；普通无变化保存继续产生空差异，不伪造字段修改或正式版本。
- 提交回执与完整业务快照分开。支行返回的明细按当前业务行阶段逐行过滤，混合单据仅下发当前可见行；其余仅有流程元数据。
- 部分退回以活动行状态判定恢复与前单关联，不以整单 `RETURNED` 为条件。接续只能使用已提交快照，不读取其他人的私人草稿。

## 环境准备

按项目文档检查后，本机原有 Java 运行时缺少 `jar`，14 项离线依赖无可用缓存。用户明确允许单独下载测试依赖，构建仍保持 `--offline`。

- 便携 Microsoft OpenJDK 21.0.12.1，ZIP SHA-256 `192441a9d27da813bada974bb88b4cf64d37a9589ed37f204374d411ca5ce07f`，与微软官方校验文件一致；仅放在被忽略的 `build/toolchain`。
- `vendor/dependencies` 的 14 项依赖下载自 Maven Central，每项与 `dependencies.lock.json` 的 SHA-256 一致。
- Node 24.19.0；Git Bash 5.2.26，以进程环境 `BASH_EXE` 明确指定，未修改系统 PATH。
- 测试仅创建隔离的合成 H2 数据和临时环回 HTTP 服务，不读取或操作用户业务数据库、初始化配置或现有运行服务。

## R1～R8 修复与回归

| 问题 | 原因和修改 | 主要文件 | 回归证据 |
| --- | --- | --- | --- |
| R1 | 月份删除仅查旧占用表，漏掉支行通过后的分行任务。预览和事务内确认联合检查两级在途任务，任何一行在途即整月阻断；同时补齐导入预览计数与底层覆盖保护。 | `MaintenancePlatform.java`、`ImportPlatform.java`、`PlatformStore.java` | `Feedback009ReviewTest.deletion/transactions`：操作员及两种支行直接角色送审、删除预览后进入终审、其他支行同行月记录整批保留、导入覆盖拒绝；原有维护／导入并发回滚测试继续执行。 |
| R2 | V009 以任意历史 RETURNED 覆盖当前状态。改为待审优先，按真实决定时间、批准事实及正式版本初始化新行状态；无效旧退回不能覆盖后续发布。旧版多人同一行待审保留全部原记录，先显式退回重复任务再推进剩余任务。 | `V009__two_stage_workflow.sql`、`LegacyWorkflowMigration.java`、`SchemaMigrations.java` | `Schema8ReviewFixture` 使用真正旧应用生成库；`Feedback009UpgradeReviewTest` 验证三类“退回→修订→批准”、当前退回、待审、重复待审、重启及 12 张历史表逐值哈希。 |
| R3 | 提交详情只有机构裁剪，完整快照仍下发。服务层根据当前行状态／完成规则裁剪正文并保留流程回执；覆盖旧预览、草稿、列表、历史和幂等重试；混合单据逐行处理，版本冲突也不得附带隐藏当前值。 | `WorkflowEngine.java`、`WorkflowPages.java` | 平台混合行／跨机构／重试回归；`Feedback009HttpReviewTest` 三表、五角色、两支行的详情、全量筛选、旧 URL、通知跳转、XLSX；全域只读快照仍可查。按任务约定保留原有授权操作审计历史。 |
| R4 | 工作表名或说明关键词先于真实表头识别。改为表头优先，仅完整匹配已知单列说明文本且无额外数据时跳过，未知非空页报错。同步本版模板说明，保留旧版标准说明识别。 | `WorkbookImporter.java`、`ExcelExporter.java` | `Feedback009WorkbookReviewTest`：三类表四种误导名称、同类多 sheet、三子表改名、伪说明页／真实说明页夹带未知数据，共 16 项。 |
| R5 | 把导入来源责任人当成普通送审作者，额外要求第二名分行管理员。明确 IMPORT 候选模型，保留独立支行确认并逐行核验确认事件；只对该模式允许原上传人终审，不放宽普通本人审批限制。 | `WorkflowEngine.java`、`WorkflowPages.java` | `Feedback009ReviewTest.singleAdministrator`：仅一名分行管理员、三类预填导入；两级前正式值不变、跳级被拒、独立支行确认后由上传者终审；普通复核员本人修改仍不能本人审批。 |
| R6 | 普通差异去重把原值重提当成空提交。新增明确核对入口和服务预览，保留原值／版本但创建新审核轮次，校验当前退回前单；普通无变化保存仍为空。修复原值重提后返回修改的完整表单，并让多次同值重开产生独立通知。 | `WorkflowContracts.java`、`WorkflowEngine.java`、`WorkflowRoutes.java`、`WorkflowPages.java`、`BusinessRowPresentation.java` | 平台检查新单关联／版本不虚增／普通空保存／通知幂等；HTTP 勾选确认、CSRF、原值预览、两级重新批准、返回修改、再次恢复后真实修改。 |
| R7 | 完成重开只接受 PUBLISHED。页面与服务同时允许完成的 LEGACY_PUBLISHED，保留正式值／版本和旧审批历史。 | `WorkflowEngine.java`、`WorkflowRoutes.java`、`BusinessRowPresentation.java` | 平台旧完成状态回归，以及真实 schema 8 升级后的完成行重开。 |
| R8 | 草稿消耗／恢复／前单关联使用整单 RETURNED 条件和原作者限制。改用逐行活动退回状态，恢复时只构造可编辑当前基线，旧提交快照不变；提供复制共享送审内容为新私人草稿的接续接口；软删退回行不阻断其他草稿和分页。 | `WorkflowEngine.java`、`WorkflowContracts.java`、`AccessPlatform.java`、`BusinessWorkflowState.java`、`WorkflowRoutes.java`、`WorkflowPages.java` | 部分退回仅恢复 A、B 保持待审；原作者停用后同支行接续、异支行拒绝、私人草稿及其编号不进入共享查询；终审后原草稿按当前版本修订；删除退回行后草稿列表可用。 |

上述 Java 文件分别位于 `src/platform/`、`src/workflow/`、`src/risk/` 或 `src/`。当前接口／数据模型另见 [公共接口补充](../../FEEDBACK-009-workflow-contracts.md)；旧公共接口文档加了指向本版契约的提示，原协作入口和原交付报告未修改。

## 先失败、再修复的证据

本轮先在原 PR SHA 编译执行原有全套测试，得到 `BUILD_OK`；随后只增加维护人复现测试并在原业务代码上执行，实际得到以下失败：

- R1：division tasks missing from delete preview。
- R2：真实旧应用输出 `SCHEMA8_REAL_FIXTURE_OK` 后，升级断言发现已批准 multi 行变为 RETURNED。
- R3：mixed receipt leaks hidden division row。
- R4：valid business sheet silently skipped。
- R5：单分行管理员导入被 NO_REVIEWER 拒绝。
- R6：原接口无显式原值重提方法；其普通同值草稿为空，无法走预览确认。
- R7：LEGACY_PUBLISHED 完成行被拒绝重开。
- R8：PARTIAL 单据对应草稿被判已消费，无法恢复退回行。

本地忽略目录保留 `build/baseline-build.log`、`review-red.log`、`workbook-red.log`、`upgrade-red.log`；不将运行日志、生成数据库或二进制提交仓库。测试后来补充了事务故障、同请求并发、重复通知、原值草稿返回修改、私人恢复审计、旧版多人待审和删除退回行等边界。

原有三处测试的授权读取断言随 R3 收紧：已完成跨支行单据检查本机构回执；发布／删除后的旧草稿检查无业务正文。`ImportPlatformTest`、`MaintenancePlatformTest` 同时新增 SQL 读取隔离测试库的原始草稿 payload 等值断言，证明脱敏没有清除存储内容，未把错误结果改成通过。

## 迁移和旧数据保留

真实旧库由 `tests/feedback009-upgrade.mjs` 使用 Git 中 rc.10 提交 `7507f9a384753baa3ebca8719583fb0cea555de8` 的 src/resources 编译后，通过旧公共接口创建；旧应用自身断言 schema=8。没有用新版删表来冒充本轮 R2 验证。

升级前后及再次启动后，以下 12 张历史表的全部原字段按规范编码／排序计算哈希并逐一相同：`official_records`、`submissions`、`drafts`、`audit_events`、`users`、`notification_events`、`notification_receipts`、`record_deletions`、`feedback_deadlines`、`completion_rules`、`workflow_audit_links`、`pending_submission_records`。覆盖正式值／版本、旧审批链、私人草稿、账号、通知与已读、软删除、期限和必填规则。新增投影明确区分历史发布、当前退回和当前待支行复核。旧版不带月份的暂存导入仍拒绝确认，要求重新上传选择月份。

| 数据来源 | 兼容方式与验证 |
| --- | --- |
| 新装 | 顺序执行 V001～V010；新增平台及 HTTP 测试反复创建全新合成库。 |
| 正式 rc.10 / schema 8 | 正常升至 schema 10，重启不重做迁移，原历史表哈希保持；旧待审仍须支行→分行两级，旧已完成可重开。 |
| 已应用原 PR V009/V010 的测试库 | 用户已确认无人使用，因此本轮不新增兼容版本迁移。独立测试先由旧 rc.10 造库，再用原 PR 代码升到 schema 10，最后验证修复版明确 checksum 拒绝且上述历史表不变。若出现这种库，应保全现场，在隔离目录从经过验证的 schema 8 升级前备份重做；没有备份时另行设计兼容迁移，不能改 checksum、清库或降级强开。 |

V001～V008、V010、应用 VERSION 均未改动。迁移故障仍沿用 STARTED/DONE 标记和保全／备份恢复机制，不假设 H2 DDL 可全部回滚。

## 本地验证结果

2026-09-21，最终代码完整执行以下命令，全部退出码 0：

| 命令 | 结果 |
| --- | --- |
| `node tools/build.mjs --test --offline` | `BUILD_OK`。本地记录 `build/full-review-verified.log`，构建临时目录 `build/foundation-app-AYXc4f`。未改为联网构建，所有依赖按锁文件校验。 |
| `node tests/install-smoke.mjs` | `INSTALL_SMOKE_OK`：新装、私人初始化、升级保留配置与备份、迁移失败保全、校验失败保护。日志 `build/install-review.log`，只用合成 runtime。 |
| `git diff --check` | 无输出，通过；提交前及最后报告补充后复核。 |

完整构建中的各组结果如下（计数按各测试自己的输出，不合并为不准确的总数）：

| 测试组 | 通过计数 |
| --- | --- |
| 身份／业务／工作流 JavaScript DOM 模拟 | 12 / 27 / 43 |
| Foundation / Identity / Bootstrap | 185 / 62 / 18 |
| Access / Workflow integration / Import / Business HTTP | 46 / 69 / 99 / 70 |
| Feedback / Maintenance / People delete / Completion rule HTTP | 24 / 55 / 31 / 93 |
| FEEDBACK-009 新 HTTP / HTTP smoke 主测试 | 123 / 293 |
| Workflow platform / Read model / Access platform / Workflow routes | 275 / 180 / 78 / 104 |
| Import platform / Import workbook / Business view | 102 / 281 / 233 |
| FEEDBACK-007 regression / Deadlines / Feedback view | 156 / 46 / 15 |
| Maintenance platform / Completion rules / Completion rule view | 56 / 103 / 37 |
| FEEDBACK-009 新平台 / 新工作簿 | 51 / 16 |
| 真实旧应用升级 | `SCHEMA8_REAL_FIXTURE_OK`、`FEEDBACK009_UPGRADE_REVIEW_OK`、`REAL_SCHEMA8_UPGRADE_OK` |
| 原 PR 已迁移库边界 | `ORIGINAL_PR_SCHEMA10_FIXTURE_OK`、`ORIGINAL_PR_MIGRATION_REJECTED_WITH_HISTORY_PRESERVED` |

编译存在依赖注解处理／旧 API 的提示，不影响退出码和测试结果；没有关闭依赖校验、删掉失败测试或将未执行项写成通过。全量验证之后仅补交接报告，不再改代码。

## 远端与交付

推送前再次 `git fetch`、读取远端 heads 和开放 PR：main 仍为 `78014d5680c7d8546b44c336ad4822f18c5e599e`，PR #12 仍开放未合并，head 仍为 `7c501e487c3cba485c3137393fc44e0efc8a0b36`，开放 PR 列表只有 #12，无重复修复分支。原功能提交是本修复分支祖先，保留其全部必要功能；新增内容为上列 R1～R8、安全边界回归和独立文档。

已使用本机 Git 成功提交和推送独立分支，并以 `Miso-Soup98` 的授权 GitHub 连接创建面向 main 的 [PR #13](https://github.com/ChaselTH/zhihuixinguan/pull/13)。实际写入和 PR 创建均成功；main、原功能分支 SHA 未变。

- 代码／测试／接口及完整验证报告提交：`217fb8202b2639d637cc966b1292c8e752f94d33`。
- 后续仅补本段上传凭据的文档提交；最终分支 head SHA 在 PR 和本轮交接消息中记录，避免文件自引用提交 SHA。
- 本 PR 包含原 PR #12 的两次功能／报告提交和本轮修复。原报告保持原样；可用 `git diff 7c501e487c3cba485c3137393fc44e0efc8a0b36..codex/fix-feedback-009-review` 独立审查本轮增量。
- 由维护人决定如何替代／整合原 PR #12；未执行合并、关闭原 PR、发布或部署。状态：**等待维护人审查**。

## 未验证事项与交付边界

- 本轮在 Windows、JDK 21、目标 Java 17 字节码上验证；没有麒麟／Win7／真实 IE 设备验收，也没有重新执行浏览器像素布局截图验收。核心新入口为服务端普通 HTML 表单并测了无客户端脚本的 HTTP 提交，不把它称为实机兼容认证。
- 安装器测试使用独立合成 shell runtime，仅验证安装控制流、备份和失败保全，不是实际离线安装包验收；没有发布或部署。
- 真旧库回归需本地 Git 保留上述两个历史提交及离线依赖缓存；不含 Git 历史的源码导出目录不能独立运行该项回归。普通构建不需要这项历史测试源；本轮未交付新的安装包。
- 仅隔离合成数据，未读取客户业务库、初始化密码或私人离线包；原日志／构建产物保持忽略，不上传。
- 未启用 CI、修改发布版本、合并 main、关闭原 PR 或改协作任务入口。本轮交付状态：等待维护人审查。
