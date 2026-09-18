# rc.7：操作记录权限与按月删除

版本 0.3.0-rc.7 / schema 7。本次由当前维护人开发，另一台 AI 无 READY 任务。不启用 GitHub CI。

## 操作记录权限

- 超级管理员查看全部记录，并可按筛选条件清理。
- 分行管理员查看所有支行及分行的非超管操作；按事件发生时持久化的 actor_role 排除 SUPER_ADMIN，不按人员现在的角色推测。
- 支行管理员查看本支行人员及作用于本支行的非超管操作，不再只显示自己操作；操作员／复核员仍只能查看本支行业务记录，不能查看账号安全类别。
- 普通记录列表、指定提交单审计、工作流审计接口、基础状态最近事件均执行超管操作排除规则。私人草稿内容不进入共享操作记录。

## 超管清理操作记录

在“操作记录”按类别、机构、清单、日期、操作人／编号查询后，点击“清理当前筛选的操作记录”，核对范围与数量，再确认。作用于筛选结果的全部分页，不只是当前页；只清理预览时冻结的事件 ID，不包含预览后新增事件。

明细及对应 workflow_audit_links 在同一事务物理删除，业务数据、账号、提交快照不变。清理明细无法通过网页恢复；系统另写 AUDIT_PURGE 摘要（数量、条件、目标摘要散列），该摘要排除后续清理。未选择清理范围、不确认、不具权限或 CSRF 失败均不会删除。

## 分行管理员按月份删除

“数据更新 → 按月份删除数据”，选择现存数据月份，预览三类清单全部支行的数量，再确认。不按上传时间或某一个上传文件计批次：同月的多次导入一并处理。

与现有月度展示一致，期次与选中月份有交集即纳入范围；跨月期次会显示警告数量，整条删除也影响其他关联月份。任一涉及的记录有待复核单则整批拒绝，先批准或退回，再重新预览。

删除采用正式数据逻辑撤除：V007 新增 record_deletions，不改 V001～V006。正式 list/find、统计、月份选项、反馈日期可见范围、明细、授权导出、来源重复匹配均排除已删除记录。历史正式行和审批快照为追溯保留，不做磁盘擦除；可重新导入，但会产生新 ID，不复活旧草稿。旧草稿可查看，不能提交；旧导入预览在基线变化后必须重新上传。每条删除按原所属机构记录 DATA_MONTH_DELETE，支行仅看自己范围。

## 公共接口及失败语义

入口 `PlatformStore.maintenance()`：

```java
var p = store.maintenance().previewAudit(root, filter, submissionId);
store.maintenance().confirm(root, p.token(), "audit");
var months = store.maintenance().months(division);
var p2 = store.maintenance().previewMonth(division, "2026-09");
store.maintenance().confirm(division, p2.token(), "month");
```

Preview 提供 token、kind、scope、count、counts、pending、crossMonth；confirm 返回处理条数。预览只读、不写业务库。确认凭据只在服务端保留 15 分钟，绑定本人 ID 和身份版本；重启失效；单次最多 20000 条、全局最多 50 个未过期凭据。重复或并发使用同一有效凭据仅返回原结果；仅在 DB 提交成功后标记完成。

SecurityException 对应 403；无目标、无效条件和缺失明确确认对应 400；预览后正式数据版本／成员变化、待复核、过期或目标已被清理对应 409；数据库错误整批回滚。SQL 参数绑定，不接收客户端提供的任意记录列表。

HTTP：`GET /imports/delete`、`POST /imports/delete/preview`（month）、`POST /imports/delete/confirm`（token、confirmed=yes）；`POST /audit/cleanup/preview`（与列表一致的筛选字段）、`POST /audit/cleanup/confirm`（token、confirmed=yes）。所有 POST 都经 Main 的统一身份、安全提示、首次改密及 CSRF 校验，不能依靠隐藏按钮代替权限校验。

## 验证与安装

新增 MaintenanceHttpTest、MaintenancePlatformTest，验证五角色、筛选、精确冻结目标、跨支行、CSRF、版本冲突、待复核保护、幂等并发、失败回滚、旧草稿／导入阻止复活、真实 XLSX 导出、重启、跨月警告、V6→V7 升级及中断拒绝。另保留全量原有测试和安装模拟。2026-09-18 全量离线构建通过：新 HTTP 55 项、平台 55 项、综合 HTTP 273 项；安装模拟输出 INSTALL_SMOKE_OK。

按发布检查执行 `node tools/build.mjs --test --offline` 与 `node tests/install-smoke.mjs`。浏览器使用无数据库的虚构只读页面核对布局，不点击真实清理按钮。实际旧 IE 和麒麟仍需现场验收。

升级前停止旧服务，使用完整 rc.7 安装目录运行 install.sh，保留完整旧版备份。schema 7 不得用 rc.6 或更早程序打开。本轮开发／安装包不自动清理用户电脑上的任何账号、操作记录或业务数据。
