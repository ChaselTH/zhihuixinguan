# FEEDBACK-007 维护修复与验收

2026-09-18，用户授权当前维护人接手 [PR #11](https://github.com/ChaselTH/zhihuixinguan/pull/11)，基于外部交付 `0c82e0b1ab55f52181e91891d941c9d7e89006e6` 修复后集成。另一台 AI 无新的 READY 任务。应用 VERSION 保持 `0.3.0-rc.8`，schema 7，不打包、不改用户运行实例、不启用云端 CI。

## 审查问题与处理

| 问题 | 修复 | 回归证据 |
| --- | --- | --- |
| R1 跨页填写被拆成多份草稿 | `/details` 显式携带草稿 ID；只有一份活动草稿时恢复该份，多份时要求选择；跨页保存合并完整差异，提交不局限当前页 | 真实页面表单保存第一页、第二页，确认预览包含两页；翻页、过滤和保存返回保留上下文 |
| R2 支行追溯链接无效 | 使用稳定机构编码；只进行一次 HTML 转义；默认不限制当前表种／来源月份 | 从实际页面提取 href 并请求，武进入口成功且默认涵盖三表 |
| R3 大表未保存保护未加载 | 大表及草稿差异编辑共用 `workflow-edit-form`；比较输入基线，过滤表单提交不清除编辑状态；无脚本时显示先保存提示 | 43 条现代／attachEvent DOM 桩断言，覆盖输入、粘贴、下拉、恢复原值、过滤、保存及浏览器返回 |
| R4 历史按两个时间口径重复过滤 | 统一复用公共审计接口，按操作时间过滤、数据库分页；不从提交快照重建已清理审计 | 旧来源月份今天修改仍能查到；单记录超过 100 次修改可翻页；清理后不复现明细 |
| R5 第 101 份草稿无法恢复 | `editableDraft` 按 ID／本人／当前机构精确校验，不以列表前 100 条判断活动状态 | 101 份活动草稿的末页恢复、他人 ID、错误表种及冻结版本测试 |
| R6 空差异草稿残留 | SQL 在 LIMIT/OFFSET 前排除规范空快照；不删除历史数据 | 撤销全部变化、31 份空草稿夹杂活动草稿、末页数量及工作台一致性 |

草稿页面改为“企业／来源时间、修改字段、原正式值、我的草稿”的紧凑差异视图。只显示本人改过的行和字段；需要新增其他字段时返回完整清单。稀疏编辑字段集合由服务端原草稿决定，漏传所需字段会拒绝，不会按空白覆盖。

## 公共接口与事务约定

```java
// 仅本人可以调用；不存在／越权抛 SecurityException；冻结版本抛 ALREADY_DECIDED。
Draft editable = store.workflow().editableDraft(actor, draftId);
// 包含未消耗、非空差异版本；在分页前过滤。
List<Draft> active = store.workflow().drafts(actor, "multi", 0, 30);

// 操作员：保存完整差异集合 → 服务端预览 → 确认进入本支行复核。
Draft saved = store.workflow().saveDraft(actor, editable.id(), editable.version(),
    "multi", completeChanges, editable.priorSubmissionId(), requestId);
Preview preview = store.workflow().previewDraft(actor, saved.id(), saved.version());
Submission pending = store.workflow().confirm(actor, preview.id(), confirmRequestId);

// 分行管理员：同种清单、多真实支行，在一次事务中确认。
Preview batch = store.workflow().previewDirect(divisionActor, "multi", branchChanges);
// 单支行为 DIRECT；多支行为 BATCH_DIRECT，不是普通 CZ 业务单据。
List<BranchSnapshot> groups = WorkflowContracts.branchSnapshots(batch.rows());
Submission applied = store.workflow().confirm(divisionActor, batch.id(), batchRequestId);
```

- `BATCH_DIRECT` 是明确标记的批次信封；`CZ` 仅表示分行管理批次，真实行机构仍保存在冻结的 `SnapshotRow.before.organizationId` 和正式记录中。`branchSnapshots` 按真实机构构造不可变子快照，预览和提交详情分机构展示。未新增子单业务表或重复审计体系。
- 操作员 REVIEW、支行管理员／复核员 DIRECT 仍只允许本支行。只有分行管理员能创建和确认 BATCH_DIRECT；没有删除全局机构检查。
- 全部正式写入、提交快照、逐行真实机构审计、通知及幂等记录在一个平台事务完成；任何冲突或注入故障全批回滚。确认只接收服务端凭据，不能替换值。
- 支行读取混合批次时只得到本支行行快照及本支行显示机构；审计、历史、分页和通知分别限制域。分行批次汇总通知只发给提交人，支行不收到其他机构信息。
- `draft` 保留本人原始读取兼容；编辑界面必须使用 `editableDraft`。空草稿可按精确 ID 展示空态，但不占活动列表。退回时恢复该版本最新退回单关联，重新提交不改旧单。
- 数据库结构未改变，V001～V007 校验不变。新 BATCH_DIRECT 是现有 VARCHAR 字段中的新模式值；生成这类数据后不要降级运行不识别此模式的旧二进制，应使用升级前备份做回退。
- 统一修改记录复用 `AccessPlatform.audit` 的操作时间、角色／机构和清理规则。按表种筛选也包含对应提交／复核事件；默认不按当前业务来源月份截断。

## 本地验证

必须执行并保留结果：

```text
node tools/build.mjs --test --offline
node tests/install-smoke.mjs
git diff --check
```

2026-09-18 最终执行结果：三项均通过。`BUILD_OK`；重点套件 `WORKFLOW_PLATFORM_OK assertions=241`、`FEEDBACK007_REGRESSION_OK assertions=156`、`WORKFLOW_INTERACTIONS_OK assertions=43`、`WORKFLOW_ROUTES_OK assertions=92`、`BUSINESS_VIEW_OK assertions=221`。`HTTP_SMOKE_OK assertions=273` 及其五角色子套件、其余既有平台／导入／反馈／维护套件全部通过。`INSTALL_SMOKE_OK`；无 GitHub CI 运行或通过声明。

新增 `Feedback007RegressionTest` 以及 `workflow-interactions.test.mjs`，已纳入离线构建测试；扩充 `WorkflowPlatformTest` 的混合机构原子写入、第二行／通知／事务末尾故障回滚、幂等并发、过期版本及支行裁剪测试。既有 Main 五角色 HTTP、导入、真实 XLSX 导出、人员管理、操作记录、反馈日期和迁移测试保留。

安装验证为合成运行时模拟，覆盖新装、升级保留初始化配置与备份、迁移失败、校验失败，不能等同麒麟实机验收。

## 浏览器检查与限制

使用 Codex 内置 Chromium 浏览器、隔离合成数据库和随机回环端口，未访问用户 2874 运行库。

- 草稿差异视图在 1366×900、1920×1000 检查，长文本、下拉及顶部按钮无重叠。DOM 测得 1366 视口下文档宽 1351；1920 下文档宽 1920，右侧按钮最远 1864.6，没有页面级横向溢出。
- 多重预警统一表测得 1366 视口、1295 宽滚动容器、5503 宽表格，顶部横向滚动条存在；10 条分页和草稿 ID 随过滤保留。
- 实际点击草稿保存、返回大表、切换每页数量、第二页新增填写、提交预览、确认提交和全部草稿：预览共 3 条改动，包含第一页原有 2 条；确认后草稿列表显示空态。
- 未保存编辑尝试离开后仍停留原页面并保留输入；内置浏览器未向工具暴露原生 beforeunload 对话框，不能据此声称已验证每种浏览器的原生弹窗外观。
- 截图在本次维护任务的浏览器工具结果中留存。真实 Win7 IE／文档模式及麒麟电脑仍需用户现场测试；不把 Chromium 和 attachEvent 桩测试写成旧 IE 实测。

本次合并不更新 U 盘包，也不重启用户本地服务；安装包与运行版本应在另行发布时从最终 main 构建。
