# PR0 公共基础与后续双 AI 交接约定

基线分支：`codex/v0.3-foundation`，版本 `0.3.0-pr0.6`。应用户最新要求，账号登录与人员管理提前纳入公共基础。按原方案，PR0 先验收并合并到 `codex/integration-v0.3` 集成分支，A/B 再从同一合并提交创建分支；未合并前不并行改写公共基础。本版为公共基础检查点，不代表已创建 PR、合并到集成分支或完成全部业务。

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
- `WorkflowContracts` 约定 ChangeSet、状态及 saveDraft/preview/submit/approve/reject。当前只有接口，没有放行操作员的假实现。
- H2 嵌入数据库，单应用进程持有数据库；数据库无 TCP/控制台。`resources/db/V001__foundation.sql` 建立基础、用户、申请、草稿、提交、通知及审计表。迁移脚本已用校验值锁定；后续只能新增 V002 等，并先补版本迁移器，不能改 V001 后强行忽略校验。

当前身份表已实际使用，通知事件／阅读回执仍是结构预留，不声称通知服务已完成。B1 开始前，A1 与公共维护人须先增补通知发布接口、可调用的事务式审批正式写入接口及审核事务测试，并单独合并；B 不得临时绕过 OfficialDataWriter 对操作员直写的限制。操作员目前只读及导出，草稿流程接入后再开放填写。

## 文件所有权与合并顺序

| 阶段／负责人 | 独占范围 | 交付与依赖 |
| --- | --- | --- |
| PR0／本地 AI | `src/platform/`、`resources/db/`、`Main` 组合入口、构建依赖、安装、共同布局 | 当前阶段；回归、旧数据迁移与离线测试包 |
| A1／本地 AI | `src/identity/`、身份专用样式、身份测试 | 统一认证号登录、人员管理、首次改密和会话已在 PR0.3 提前实现；继续做权限申请、安全提示、通知及剩余联调，禁止另写一套登录或扩大业务权限 |
| B1／GitHub AI（合并基础及所需接口后派发，与 A1 并行） | 新增 `src/workflow/`、workflow 专用 CSS、草稿复核测试 | 基于共同 ActorContext/RecordChange 开发差异、草稿、提交与审核业务；先做独立服务测试，不改 AuthService 或身份页 |
| A2／A1 后 | 导入服务／模板工具／导入专用页面与测试 | 完善真实身份下三入口权限、细粒度错误报告与批量预览；不得改业务审批 |
| B2／B1 后 | `src/risk/`、新增 `src/internal/`、各自 CSS、业务页面测试 | 接入草稿／复核，正式状态颜色与进度、行内数据视图和审计查询；截止日期规则待字段确定 |
| 集成 PR／公共维护人 | Main 路由注册、AuthService 替换、平台接口与 migration loader、安装和版本 | 按约定小提交接入两条线，做五角色端到端与越权测试后发正式包 |

沿用原方案分支 `codex/v0.3-access`、`codex/v0.3-workflow`。两条线均从已合并 PR0 的集成分支同一提交切出，PR 也指向集成分支；B1 不等待 A1 全部页面，但业务 UI 集成要等真实会话及通知接口。PR0 的 default-package 兼容页面只能由独占文件负责人编辑；新增独立业务服务优先使用 package。

公共文件变更先提契约补丁，公共维护人合并后两条线同步同一基线；已开始评审的分支默认 merge 同步，不强推改写历史。不要两边各自建立 users 表、机构字典、Excel 列号或第二套数据存储；不要各自修改 V001、VERSION、dependencies.lock.json、Main 和安装脚本。PR 描述列出接口、迁移及测试，其他 AI review 只读，不直接改对方分支。

## 构建与验证

开发机器：JDK 17+、Node 22+。`node tools/build.mjs --test` 仅首次开发构建需要从 Maven 下载锁定 JAR；`--offline` 使用本地 vendor/dependencies。运行工作电脑交付不需要 Node。CI 不带用户 `.et` 或业务数据。

本地额外核验用户表头可运行 `node tools/build.mjs --offline --test --user-template`，工具不复制用户文件进入产物。每次构建独立输出目录，避免覆盖正在预览的 JAR。`build/foundation-build.json` 只在构建及测试通过后更新。

`node tools/build.mjs --test` 会依次运行机构联动脚本测试、FoundationTest、HttpSmokeTest、IdentityTest、BootstrapTest；安装器模拟另行运行 `node tests/install-smoke.mjs`。全部身份和业务回归均使用虚构数据；真实 HTTP 测试在隔离应用目录中创建虚构本地初始化配置，不写入构建产物。必须区分自动测试、模拟 Linux 运行时和真实麒麟验收。

`*.sql` 在 `.gitattributes` 中固定 LF。V001 当前源文件及已交付资源均为 LF；Windows 和 Linux 检出时不得转换其换行，否则迁移校验值会变化，已有安装可能无法打开。后续迁移仍禁止修改已应用文件。

离线打包：`packaging/build_offline.ps1 -RuntimeDir <已经准备好的Linux-JDK归档目录> -UsbRoot U:\`。运行时路径是明确参数，不再隐式依赖 smart_excel。打包白名单不包含 data、测试数据库、原 `.et`、上传临时文件和业务记录；U 盘逐文件 SHA-256 核验，不覆盖已有发布目录。
