# PR0 公共基础与后续双 AI 交接约定

A2 向后兼容补充：`PlatformStore.importing()` 提供真实 H2 持久化导入任务、逐条决定和原子确认，新增 schema 4 / V004，不改写旧迁移。HTTP 导出统一进入 `AuthorizedExportService`，保持现有月份／区间／机构／搜索语义及正式数据隔离。实际签名、异常、路由、限制和示例见 [A2 交接说明](A2-导入与导出交接.md)。A2 独立 PR 待评审，不替代 B2 的业务状态展示。后续增加完成状态筛选时必须同时更新授权导出，不复制另一套未授权查询。

A1/B1 已于 PR #4/#5 合并；后文各 PR0／A1 等待接入文字仅为历史说明，当前基线与任务状态以 [协作入口](ai-collaboration/README.md) 为准。

A1 向后兼容补充：新增 `PlatformStore.access()`、schema 3 和权限 / 通知 / 审计页面；原工作流与通知接口签名不变。详见 [A1 交接说明](A1-权限与公共页面交接.md)，代码是否已进入 main 以 A1 PR 合并状态为准。

2026-09-15：PR #1/#2 均已合并，代码基线 c8198f70cd4dabece3ce22c21f4611de056c42d5。用户初步安装测试后授权 A1/B1 并行；当前有效任务以 [协作入口](ai-collaboration/README.md) 为准，取代下方历史暂缓文字。现有 WorkflowService 和 NotificationService 签名在 B1 期间保持兼容，A1 只作向后兼容新增。

2026-09-14 协作修订：统一使用 `main` 作为后续共同开发基线，不再以 `codex/integration-v0.3` 为前置条件。先将 PR0.6 公共基础合入 main，再由公共维护人实现、测试并合并工作流公共契约补丁，A/B 才从 main 的同一明确提交创建业务分支。合入 main 不等于可以正式投产；发布仍须单独联调、离线及实机验收。

PR0.7 进展：从 v0.3 基础 `8c72d16239d9bc3bc8d4b8e1c27706c89d84bfed` 新建公共补丁分支，实现下述事务能力并通过本地测试。基础 PR #1 已合入 main（816e8f7646bd5b0f01ae815cff17e2e4741f907a），公共补丁随本文件所在 PR 交接，以实际合并记录为准。按用户最新要求，先合并、打包和安装测试，A1/B1 暂缓，不把合并视为开工授权。真实 API 见 [工作流公共接口](workflow-platform-api.md)，放行条件见 [B1 工作流就绪清单](B1-工作流就绪清单.md)。

PR0.6：`BootstrapConfig` 仅在空账号库读取应用目录下的 `bootstrap.local.properties`，只支持 auth_number/password。`AuthService` 不再内置初始化身份；普通构建只复制空白 bootstrap.example.properties，已有账号完全忽略本地初始化配置。公开源码及测试不得出现个人初始化凭据。私有离线打包只有显式传入 `-BootstrapConfig` 才附带该文件；该定制包不得公开。安装升级优先保留旧本地配置，账户密码仍以已有数据库为准。

PR0.5：人员新增和修改共用 `IdentityPages.organizationFields`，本地 `web/assets/identity.js` 使用旧 IE 兼容语法实现角色／支行联动。分行管理员隐藏并禁用支行字段，HTTP 创建和修改通过 `Main.userOrganization` 将其归属固定为 `CZ`；非分行管理员必须提交支行，仍由 PlatformStore 检查层级及机构范围。不得把该归属规则用于构建登录操作者的 ActorContext，操作者身份只能来自会话。JavaScript 禁用时显示支行选择及提示，不依赖脚本绕过权限。

PR0.4：超级管理员不强制首次改密；启动时仅清除已有超管的该标记并递增身份版本，绝不重置密码，普通账号规则不变。当前密码为固定只读掩码，不从数据库还原或从登录表单缓存。HTTP 本人改密必须调用 `AuthService.changePassword(session, next)`，由服务端核验最近 15 分钟内的真实登录；普通页面访问不得刷新 `authenticatedAt`，该值不可来自表单。GET 和 POST 均检查时限，POST 仍须 CSRF。底层 `PlatformStore.changeOwnPassword(actor, next)` 重验账号状态／版本并与当前哈希比对防止新旧相同，只能在通过上述认证后使用。修改成功撤销该账号全部旧会话。

PR0.3：`AuthService` 从持久化 users 认证，`UserAccount` 不含密码，`ActorContext` 新增 identityRevision；四参构造只供隔离服务测试，HTTP 必须使用 UserAccount.actor() 创建正版本上下文。会话每次请求校验账号 revision，平台读写事务也重验身份和首次改密状态。密码 PBKDF2-HMAC-SHA256 固定 600000 次、24 字节随机盐，初始随机密码只返回当次管理操作，不进日志。后续改变密码算法必须引入有版本的凭据格式和迁移，不能直接改常量。

统一 `/login`，`/people` 人员管理，`/account/password` 本人改密，`/imports` 为分行管理员授权后的数据更新功能，不再有独立 `/admin` 身份。`src/importing/ImportPages.java` 接替旧 AdminPages；页面实例按请求创建，禁止在共享单例保存当前用户。新增／修改／停用／重置均经 PlatformStore 的层级权限和事务审计，禁止另建一套账号库。

## 已落地公共契约

- `src/platform/` 中类均为公共包 `xinguan.platform`。`ActorContext` 仅由可信登录服务创建；绝不能从 URL、隐藏字段或客户端所传 role/org 构建。
- `Role` 五角色；`Organizations` 分行 `CZ`、九支行稳定 ID、旧数据隔离 `UNASSIGNED`。枚举由这里唯一维护，不按显示名称比较权限。
- `AccessPolicy` 显式能力及人员管理关系：超级管理员业务只读；分行管理员独占上传；操作员不可直写；支行角色不可跨支行。
- `DatasetSchema` 唯一维护字段键、顺序、可编辑性、枚举与完成口径。当前 multi=23 列、negative=13 列、cross=15 列。三个模板来源已确认，不再沿用旧列号。
- `BusinessRecord` 带稳定 id、revision、模块、起止日期、机构、来源值与历史额外字段；`RecordChange` 是稳定 ID＋预期版本＋字段键差异，不使用行号定位保存。
- `RecordRepository.list/find` 必传 ActorContext，数据层先做机构约束，再筛选日期、模块。统计、详情、导出、通知附件和审计都须走同样的作用域规则。
- `OfficialDataWriter.publishDirect` 仅分行管理员／支行管理员／复核员可用，整批数据和审计一个事务，版本过期整批回滚，请求 ID 保证幂等。
- `PlatformStore.importRows` 独占分行管理员；业务来源指纹=模块＋规范期次＋机构＋全部非填报列。默认保留已有非空填写，显式覆盖可清空。确认版本基线和导入在同一锁／事务内验证。
- 旧批次不整批替换，不删除本次没出现的历史行、月份或模块。来自同一批文件、来源相同而填写内容不同的记录拒绝导入，要求先核对。来源有变化会是新记录，不做模糊企业合并。
- `PlatformStore.workflow()` 返回真实 WorkflowService，含 Draft／Preview／Submission／SnapshotRow、saveDraft／previewDraft／previewDirect／confirm／approve／reject 及授权查询。确认只收服务端预览 ID，不接受客户端替换值；共用正式写入校验原语但不嵌套调用会自行提交事务的 publishDirect。
- `PlatformStore.notifications()` 返回个人通知、未读、已读及管理通知服务。工作流事件和回执与业务事务一致；通知中心 UI 后续 A1 提供。
- H2 嵌入数据库，单应用进程持有数据库；数据库无 TCP/控制台。V001 完全未修改；新增 V002 和顺序迁移器，校验历史版本、记录迁移开始与完成，半途失败后停止启动并要求备份恢复，不盲目重跑 DDL。后续不得修改已经应用的迁移。

当前身份、草稿、提交单、通知事件／个人回执已有服务端实现。工作流网页尚未接入，操作员仍只读及导出，现有直接填写路由仍为过渡入口，B1 与 A 集成后切换为差异确认流程。权限申请、安全提示、通知中心和公共审计页尚未完成。

## B1 前置公共补丁（本分支已实现，待评审合并）

公共维护人先在独立 PR 中评审并合并以下能力；真实签名已见 workflow-platform-api.md，合并后记录共同基线，B 不需要猜测 API：

1. 私人草稿保存、列表、恢复及版本校验；所有读取／写入核验本人、当前身份和机构，不能只做支行级隔离。
2. 服务端差异和确认凭据：绑定当前用户、操作类型、草稿版本／规范化内容以及正式行版本；确认后不得接受未经预览的替换内容。
3. 提交单持久化与不可变快照：保存原值、新值、行版本及提交人，提交状态、审计和通知同事务落库；草稿后续变化不影响已有快照。
4. 审批和退回事务：只允许本支行复核员处理待审单，重验单据状态、记录版本和字段权限；批准将正式记录、审批决定、审计和通知一起提交，任一步失败整单回滚。退回必须有原因且不改变正式值。
5. 直接提交确认：复用现有 `publishDirect` 的校验和正式写入逻辑，在公共层补确认快照、变更单、审计与通知的一致性；不另建第二套直接发布系统，也不把该方法当操作员审批入口。
6. 通知发布、个人列表／未读数／已读，以及授权待办与提交详情查询。事件与业务变更同事务；个人已读不改变待办状态，角色／机构变化后仍重验访问范围。
7. 重复请求、并发审批、冲突、失败回滚及升级测试；需要改表时先补版本迁移器，只新增迁移，不改已应用的 V001。

公共层唯一维护持久化状态转换及原子性；B 的工作流服务组织编辑、预览、确认和复核交互，不直连数据库、不自行写审批状态或审计表。平台通知接口不依赖 A1 通知中心页面，B1 不需要等待 A1 所有页面完成。

## 文件所有权与合并顺序

| 阶段／负责人 | 独占范围 | 交付与依赖 |
| --- | --- | --- |
| PR0／本地 AI | 已有公共基础 | 审查后合入 main；公共基础检查通过不等于 B1 前置接口就绪 |
| 公共契约补丁／本地 AI | `src/platform/`、`resources/db/`、平台测试及必要的构建测试注册 | 先完成上述持久化、确认、通知及事务能力，附真实 API 和虚构调用用例，测试通过后独立合入 main |
| A1／本地 AI | `src/identity/`、`src/notifications/`、`src/audit/`、对应样式和测试 | 复用已有登录与人员管理；继续权限申请、安全提示、通知中心和公共审计页，禁止另写账号库或审批存储 |
| B1／GitHub AI（公共补丁就绪后，与 A1 并行） | 新增 `src/workflow/`、workflow 专用 CSS、草稿复核业务测试 | 通过公共接口组织草稿恢复、差异预览、确认提交、复核和冲突交互；不改 AuthService、身份页、数据库或公共事务 |
| A2／A1 后 | 导入服务／模板工具／导入专用页面与测试 | 完善真实身份下三入口权限、细粒度错误报告与批量预览；不得改业务审批 |
| B2／B1 后 | `src/risk/`、新增 `src/internal/`、各自 CSS、业务页面测试 | 接入草稿／复核，正式状态颜色与进度、行内数据视图和审计查询；截止日期规则待字段确定 |
| 集成 PR／公共维护人 | Main 路由注册、公共布局、平台接口及迁移器、安装和版本 | 根据 B 的接入说明作小提交；五角色端到端、越权、升级及实机验收通过后发布，账号系统不重新替换 |

分支仍使用 `codex/v0.3-access`、`codex/v0.3-workflow`，但二者都从已合入 PR0 与公共补丁的 main 同一完整 SHA 切出，PR 目标也是 main。B1 合并后 B2 从最新 main 创建 `codex/v0.3-views`；A1 合并后 A2 从最新 main 创建 `codex/v0.3-import`。不要将 B 的基线设为 A 尚未合并的业务分支。PR0 的 default-package 兼容页面只能由独占文件负责人编辑；新增独立业务服务优先使用 package。

公共文件变更先提契约补丁，公共维护人合并后两条线同步同一基线；已开始评审的分支默认 merge 同步，不强推改写历史。不要两边各自建立 users 表、机构字典、Excel 列号或第二套数据存储；不要各自修改 V001、VERSION、dependencies.lock.json、Main 和安装脚本。PR 描述列出接口、迁移及测试，其他 AI review 只读，不直接改对方分支。

## 构建与验证

开发机器：JDK 17+、Node 22+。`node tools/build.mjs --test` 仅首次开发构建需要从 Maven 下载锁定 JAR；`--offline` 使用本地 vendor/dependencies。运行工作电脑交付不需要 Node。CI 不带用户 `.et` 或业务数据。

本地额外核验用户表头可运行 `node tools/build.mjs --offline --test --user-template`，工具不复制用户文件进入产物。每次构建独立输出目录，避免覆盖正在预览的 JAR。`build/foundation-build.json` 只在构建及测试通过后更新。

`node tools/build.mjs --test` 会依次运行机构联动脚本测试、FoundationTest、HttpSmokeTest、IdentityTest、BootstrapTest、xinguan.platform.WorkflowPlatformTest、WorkflowReadModelTest；安装器模拟另行运行 `node tests/install-smoke.mjs`。当前本地共 815 项断言通过。全部身份和业务回归均使用虚构数据；真实 HTTP 测试在隔离应用目录中创建虚构本地初始化配置，不写入构建产物。必须区分公共服务自动测试、待接入的工作流 HTTP、模拟 Linux 运行时和真实麒麟验收。

`*.sql` 在 `.gitattributes` 中固定 LF。V001 当前源文件及已交付资源均为 LF；Windows 和 Linux 检出时不得转换其换行，否则迁移校验值会变化，已有安装可能无法打开。后续迁移仍禁止修改已应用文件。

离线打包：`packaging/build_offline.ps1 -RuntimeDir <已经准备好的Linux-JDK归档目录> -UsbRoot U:\`。运行时路径是明确参数，不再隐式依赖 smart_excel。打包白名单不包含 data、测试数据库、原 `.et`、上传临时文件和业务记录；U 盘逐文件 SHA-256 核验，不覆盖已有发布目录。
