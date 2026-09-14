# PR0.7 工作流公共接口与交接

日期：2026-09-14。版本：`0.3.0-pr0.7`，仅公共服务测试里程碑，不是完整 V0.3。

本补丁从 `origin/codex/v0.3-foundation` 的 `8c72d16239d9bc3bc8d4b8e1c27706c89d84bfed` 建立 `codex/v0.3-workflow-platform`，包含 B 的交接清单、安装测试 Bash 路径修复和脱敏问题记录。基础 PR #1 已合入 main，SHA 为 `816e8f7646bd5b0f01ae815cff17e2e4741f907a`。本补丁通过独立 PR 交接，实际合并 SHA 见 PR 记录及测试包 SOURCE_COMMIT；不将本地检查当作 GitHub CI 通过。

用户最新要求：本轮只合并 A/B 公共基础并安装测试；A1/B1 等用户反馈后再开始，不自动派发工作流开发任务。

## 1. 实现与边界

`PlatformStore.workflow()` 返回 `WorkflowContracts.WorkflowService`；`PlatformStore.notifications()` 返回 `NotificationService`。真实实现是公共包内的 `WorkflowEngine`，与正式记录共用一个嵌入式 H2 连接、PlatformStore 锁及事务。没有向 B 暴露数据库连接、SQL 或任意事务执行权限，也没有新增数据库端口或网络依赖。

本次负责公共持久化和业务状态转换，不编写 B 的 `src/workflow/` 或业务 CSS。现有网页尚未调用这些接口：操作员仍只读；现有管理员填写入口暂时保留旧 `publishDirect` 行为，没有差异确认页。B1 与公共路由接入后才开放新的草稿／提交／复核 UI，并将旧直接保存入口切换至预览确认链路。不能把旧 `publishDirect` 用作操作员审批入口。

权限申请、安全提示确认、通知中心、公共审计页面属于后续 A1；导入细化属于 A2；本次通知接口不能代替申请审批与开户的事务实现。没有新增反馈截止字段、无风险按钮或未经确认的表格。

## 2. 身份和数据边界

- 每次调用必须传经过当前会话验证的 `UserAccount.actor()`，identityRevision 必须为正。服务重新检查持久化身份、版本、停用和首次改密状态。四参数测试 ActorContext 不被新接口接受。
- 私人草稿及预览只属于本人。即使超管、分行管理员可读全行正式数据，也不可读取别人草稿／预览。转支行后不能恢复旧支行草稿。
- 提交后的不可变快照属于机构业务记录：超管及分行管理员可看全部，支行角色只能看本机构。`mineOnly=true` 仅用于筛选本人提交，不是授权替代品。
- 只有操作员可保存草稿及提交复核；只有本支行复核员可批准或退回，不能自审。分行管理员、支行管理员、复核员可以直接修改自己的授权数据；超管不能填写、上传或审批。
- 一单限定一个数据集、一个机构，允许包含该机构多个期次，最多 200 条输入记录。只允许 DatasetSchema 黄色列；来源列和非法下拉值拒绝。未变化字段被移除，正式确认不能是空变更。
- 正式统计和导出仍只读 `RecordRepository`。私人草稿、预览、待审及退回均不改变正式值／行版本／完成率。批准或直接确认后，任意黄色正式值非空即完成；清空最后一个正式填报值后恢复未完成。

## 3. 真实方法及调用顺序

下表省略所有方法的第一个参数 `ActorContext actor`。类型均来自 `xinguan.platform` 和 `WorkflowContracts`；日期为 LocalDate，时间为 Instant。

| 方法 | 返回与约束 |
| --- | --- |
| `saveDraft(id, expectedVersion, dataset, changes, priorSubmissionId, requestId)` | Draft。新草稿 id 空、版本 0；已有草稿必须传最新草稿版本。changes 是本次完整草稿的 RecordChange 列表，不是对原草稿的增量补丁；每条包含正式 recordId、expectedVersion 和字段差异。允许空列表保存清空后的草稿。 |
| `draft(id)` / `drafts(dataset, offset, limit)` | 恢复一个／分页列出本人当前机构草稿；dataset 可为空。保存后使用返回的 version。 |
| `previewDraft(draftId, expectedVersion)` | Preview，含冻结的原值、新值、期次和行版本；生成预览不会创建待办。 |
| `previewDirect(dataset, changes)` | 直接修改 Preview；不提前修改正式值。 |
| `preview(previewId)` | 恢复本人的服务端预览；返回 expiresAt 供页面显示，实际过期校验仍在 confirm。 |
| `confirm(previewId, requestId)` | Submission。只接收预览 ID，不接收客户端替换内容；重验身份、15 分钟有效期、草稿版本和正式行版本。REVIEW 生成待审单；DIRECT 直接原子生效。 |
| `submission(id)` / `submissions(Query)` | 机构授权的单据详情／分页列表，包含冻结快照和当前决定。 |
| `pendingReviews(Query)` | 仅复核员可查本支行；强制 SUBMITTED 状态。通知已读不影响这里的待办。 |
| `approve(submissionId, requestId)` | 返回 APPROVED 单据；正式记录、版本、决定、审计和通知同事务。 |
| `reject(submissionId, reason, requestId)` | 返回 RETURNED 单据；原因必填，最多 2000 字；不改正式数据。 |
| `recordHistory(recordId, offset, limit)` | 按授权正式记录查询关联提交单，不含私人草稿。 |
| `auditTrail(submissionId)` | 关联单据的公共审计事件；含提交人、实际审批／直接修改人、正式旧新值，不从私人草稿提取正文。 |

Draft、Preview、Submission 和 rows 列表均不可变。每个 SnapshotRow 提供 `before()`（正式记录基线）、`change()`（拟提交差异）以及按模板顺序生成的 `fields()`（key、title、before、after）。B 使用这些字段生成“本次修改”页，所有文本必须 HTML 转义。不得用浏览器传来的旧值代替服务端差异。

AuditEntry 的 before／after 延用公共审计的 Codec 编码整行值；非空时用 `Codec.decode` 及对应 DatasetSchema 解读，元数据事件通常为空。优先用 SnapshotRow.fields() 展示提交差异，不直接把编码串当业务文字。

Submission 状态只有 `SUBMITTED`、`APPROVED`、`RETURNED`，另有 Mode `REVIEW`／`DIRECT`。草稿是独立对象；版本冲突是失败结果，不持久化一个可能掩盖部分写入的 CONFLICT 状态。DIRECT 单据从创建起就是 APPROVED，不进入复核队列。

### 操作员到复核员调用示例

以下是服务层接入片段。`store` 是已有 PlatformStore；`operatorActor` 和 `reviewerActor` 分别来自各自真实登录会话，不能由 HTTP 参数创建；`recordId` 是已授权查询所得稳定 ID。

```java
var workflow = store.workflow();
var official = store.find(operatorActor, recordId);
var change = new RecordChange(official.id(), official.version(), Map.of("feedback", "虚构测试反馈"));
var draft = workflow.saveDraft(operatorActor, "", 0, "negative",
    List.of(change), "", UUID.randomUUID().toString());
var preview = workflow.previewDraft(operatorActor, draft.id(), draft.version());
// GET 展示 preview.rows().fields()；确认 POST 使用页面上同一个 requestId。
String requestId = UUID.randomUUID().toString();
var submitted = workflow.confirm(operatorActor, preview.id(), requestId);
// 独立复核员会话，先读取详情，再用带 CSRF 的确认 POST 调用：
var reviewed = workflow.approve(reviewerActor, submitted.id(), UUID.randomUUID().toString());
```

真实可执行的完整虚构账号、数据导入、草稿、通知、审批和导出案例见 `tests/WorkflowPlatformTest.java` 及 `tests/WorkflowReadModelTest.java`，随 `node tools/build.mjs --offline --test` 执行，不要求先创建生产账号或上传实际表格。

退回后仍保留原草稿与旧提交单。操作员读取当前正式记录、修正草稿，并将旧单 ID 作为 priorSubmissionId 传给 saveDraft，然后重新预览及确认；生成新单关联旧单，不更新旧快照。只能关联本人、同机构、同数据集的 RETURNED 单。无有效复核员时确认返回 NO_REVIEWER，草稿保留，管理员配置后重新确认。

### 查询参数

`Query(dataset, organization, state, from, through, mineOnly, offset, limit)`；`Query.firstPage()` 为不附加业务筛选的前 50 条，仍强制机构权限。dataset／organization／state／日期允许 null；指定 organization 必须是九支行内部 ID，不能传显示名。所有分页 limit 为 1～100，offset 为 0～1,000,000。

from／through 按来源期次与闭区间相交过滤，不按提交操作日期过滤。年度、季度、月份先由页面转换为起止日期。同一单若有任何一行匹配，返回完整提交单，以保持整单审批；日期不裁剪其快照，也不改变正式数据导出条件。列表按创建时间倒序、ID 稳定次序分页，刷新后重新取第一页以显示新数据。

## 4. 通知契约

| 方法（省略 actor） | 含义 |
| --- | --- |
| `inbox(unreadOnly, offset, limit)` | 本人的通知及个人 readAt。Notice 包含 id、type、organizationId、title、summary、submissionId、createdAt、readAt。 |
| `unreadCount()` | 当前授权、本人未读数量，可供右上角红色数字使用。 |
| `markRead(noticeId)` | 只能标记本人可访问的通知，重复标记无副作用。 |
| `publishNotice(organization, title, summary, recipientIds, requestId)` | 管理员发管理通知，title 1～100 字、summary 1～500 字、接收人 1～100 位，服务端检查机构与有效账号。固定 NOTICE 类型，不允许伪造审批事件或导航 URL。 |

业务提交自动通知本支行全部有效复核员；批准／退回通知提交人；直接生效通知实际修改人。通知和接收人回执与业务事务一起落库，同一事件只生成一次；同一个人已读不影响其他人。没有有效接收人时不会把通知转发到任意机构。账号调动后旧机构回执被当前机构授权隔离。

通知只含姓名、表名、条数和单据 ID，不放密码或完整企业明细。详情仍调用授权的 submission，不靠“知道链接”获取权限。`publishNotice` 是独立管理通知事务；A1 的权限申请／开户必须另补与自身业务同事务的事件，不可以在开户提交后调用它冒充原子性。

## 5. 幂等、冲突及故障

每次写命令使用全局唯一 requestId，10～100 位字母／数字／`_`／`-`，推荐 UUID。确认页生成一次并作为隐藏字段保存；双击、超时重试沿用原编号，不要每次 POST 生成新编号。requestId 不代替会话、CSRF 或权限验证。编号已用于其他操作／其他内容／其他用户时拒绝，不覆盖旧结果。

同一请求重试不重复写入，返回关联单据的当前决定或已保存草稿的当前版本。同一预览换请求号再确认也只返回同一单；同一操作员在另一草稿提交本人已有待审的同一记录会被拒绝。多个操作员可以分别提出同一记录的变更，先批准者生效，另一单必须在版本冲突后核对、退回再提，不自动覆盖。

| 异常 | B 应如何处理 |
| --- | --- |
| `SecurityException` | 未登录／身份已变／跨机构／不是本人草稿／缺少能力等；重新认证或显示无权访问，不输出其他机构记录及 SQL。不存在与无权访问统一处理。 |
| `WorkflowException` / `INVALID_INPUT` | 展示可修正输入提示，无正式写入。 |
| `VERSION_CONFLICT` | 整单未生效；conflicts 提供授权范围内的 base／current／proposed。首次保存草稿冲突可能没有 base；草稿版本冲突列表可能为空。重新恢复／核对并预览，不自动覆盖。 |
| `CONFIRMATION_EXPIRED` | 超过 15 分钟或预览身份版本已变化；重新预览。 |
| `ALREADY_SUBMITTED` / `ALREADY_DECIDED` | 刷新本人提交／待办，显示现有单据与决定，不进行第二次修改。 |
| `NO_REVIEWER` / `OWNER_CHANGED` | 前者由管理员配置复核员；后者提交人已停用、转机构或不再是操作员，复核员只能退回重新安排。 |
| `REQUEST_REUSED` | 同一编号对应内容不一致；重新核对并生成新的确认操作，不静默重放。 |
| `TRANSACTION_FAILED` | 显示整单失败／状态需查询；网络或提交结果不确定时按原编号重试查询结果，不宣称成功、不另建新单。 |

提交、批准、退回、直接生效均使用公共层一个数据库事务。特别是审批不是先调用会提交事务的 publishDirect 再写审批表，而是共用事务内的正式写入校验原语。多行冲突、通知写入失败、审计失败均不能留下部分结果。多个 HTTP 请求在单应用进程内串行事务、重新检验版本；嵌入式数据库不支持另一个服务同时写同一数据目录。

## 6. V002 升级与验证

V001 完全未修改。新增 V002 补草稿关联、提交类型及快照索引、服务端确认凭据、待审记录占用、事件去重、个人机构回执和单据审计关联。顺序迁移器验证全部历史版本和校验值，拒绝旧程序打开未来数据库；`/health` 返回实际已初始化的 `SCHEMA=2`。

H2 DDL 不假设可以全部回滚。每次迁移先记录 STARTED，成功后才一起登记版本及 DONE；检测到中断时拒绝继续启动，提示保全现场并从经过验证的完整升级备份恢复。不自动删除表、不删用户记录、不修改迁移校验来强行启动。`install.sh` 仍应在旧服务停止后，对复制到暂存目录的数据运行新程序检查，成功后再切换；失败保留旧安装。不要直接拿工作数据库测试迁移或用旧 JAR 覆盖 V002 数据目录。

自动测试用临时虚构数据库覆盖：私人隔离、身份撤销、确认有效期、快照冻结、无复核员、审计关联、三表三类直接角色、个人已读、重复请求、并发决定、并发直接提交、多行版本冲突、四个审批故障点、通知失败、重启和 V001 账号／正式反馈／版本／旧审计／幂等与旧迁移标记保留。另有实际 XLSX 导出与 DashboardData 完成数测试，覆盖草稿、预览、待审、退回、批准和清空后状态。测试时钟及故障点只在包内构造器注入，不开放给 HTTP 或环境变量。

本轮本地测试的数量及通过记录见 [B1 就绪清单](B1-工作流就绪清单.md)。HTTP 冒烟仍只覆盖已有网页和 schema 健康检查，不能替代尚未接入的工作流 HTTP、CSRF、转义及无 JavaScript 核心链路测试。麒麟／Win7 实际 IE、正式传输保护和离线包实测仍待集成发布。

## 7. 后续协作顺序

1. 基础 PR #1 已合并，再审查合并本公共补丁。打包记录 main 完整 SHA，先交付用户安装测试；不重建已有公共补丁分支或重复开发接口。
2. 用户测试反馈并同意继续后，再确认共同 main 完整 SHA，创建 A1 `codex/v0.3-access`、B1 `codex/v0.3-workflow`；不让 B1 从未合并的 A1 分支起步。
3. B1 只写 workflow 模块及专用测试；使用本接口组织普通 HTML 表单、差异确认、待办和错误交互。A 根据 B 提供的路由／导航／测试清单作小接入提交，B1 合并前完成五角色 HTTP 主链路。
4. A1 通知中心／公共审计页与 B1 并行；A2 导入完善与 B2 业务展示继续分文件归属。最后统一升级、权限、麒麟离线和旧 IE 验收再打包，不提前声称可投产。

暂不制定自动清理草稿、预览、通知和审计的保留期限；在业务留存规则确认前不自动删除。正式发布前应按实际数据量压测分页、批量限制、存储增长及备份容量。
