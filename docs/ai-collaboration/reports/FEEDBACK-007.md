# FEEDBACK-007 交付报告

## 领取与基线

- 领取时间：2026-09-18（由定时巡检发现 `origin/main` 已发布 READY）。
- 基线：`9679e3ff9bff170e8926edaf2a792fce31128f3e`（`0.3.0-rc.8` / schema 7）。
- 功能分支：`codex/v0.3-feedback-007`。
- 负责人：Alarm10969/Codex；ChaselTH 负责审查、麒麟／Win7 IE 实机和安装包交付。

## 实现摘要

- 三类风险大表保留原模板字段，在页面顶部提供统一保存／预览／草稿／提交操作；超级管理员仍只读，分行管理员可在全支行筛选中一次提交多机构直接修改，支行管理员、复核员和操作员继续受机构与角色边界约束。
- 分行管理员跨支行直接预览使用一个服务端确认单，按真实机构逐行校验和写入；版本冲突、字段越权、删除／身份变化或注入故障时整批回滚，不把部分成功伪装成整批成功。
- 草稿查询在分页前排除已提交／已通过的具体草稿版本；旧冻结版本不能通过编辑链接或保存接口改写，退回单仍可恢复。恢复草稿只展示真实改动行，正式值、统计和导出不被草稿覆盖；拥有者在业务表看到本人草稿值，其他账号只看到正式值和状态标签。
- 提交列表、提交详情、记录追溯和公共审计对跨支行单据执行行级机构裁剪；新增 `/records/history` 统一支行修改记录入口，支持支行、清单、日期和操作人／企业筛选，私人草稿与 `DRAFT_SAVE` 不进入共享追溯。
- 移除业务大表逐行编辑／追溯按钮，保留必要复核状态入口；新增本地 `workflow.js` 的 ES3/attachEvent 兼容离开未保存提示，服务端普通表单路径仍可独立工作。

## 公共接口与事务约束

- 未改变既有 `WorkflowService` 方法签名；分行跨支行能力通过 `previewDirect` 的服务端快照与现有确认单实现，`organization_id` 使用分行域，`submission_items` 保留每条真实记录及期次。
- `WorkflowEngine.snapshot` 仅对分行管理员直接修改放行多机构；每一行仍重复 `AccessPolicy`、数据集、黄色字段白名单和版本检查。
- 支行读取跨支行单据时以 `submission_items` 关联正式记录作为范围，返回对象和审计事件按机构裁剪，不能借分行级单据查看其他支行内容。
- 未新增数据库迁移；V001～V007 和 schema 7 保持不变。

## 验证证据

- `node tools/build.mjs --test --offline`：通过，`BUILD_OK`；全量合成回归通过，含 `WORKFLOW_PLATFORM_OK assertions=204`、`WORKFLOW_ROUTES_OK assertions=92`、`BUSINESS_VIEW_OK assertions=221` 及现有 HTTP／导入／审计／安装前检查。
- `node tests/install-smoke.mjs`：通过，`INSTALL_SMOKE_OK`；仅为隔离合成运行环境，不等同于麒麟或 Win7 IE 实测。
- `git diff --check`：通过（功能提交前执行）。
- 测试均使用隔离合成数据库、虚构账号和本地依赖；未读取或修改 2874 运行实例、真实业务数据、凭据或私人离线包。未启用 GitHub CI。

## UI 证据与限制

- 现有 `WorkflowRoutesTest`、`BusinessHttpTest` 和 `BusinessViewTest` 覆盖服务端 HTML 表单、角色范围、草稿／跨页、记录追溯和正式导出；工作流资源仍为本地 CSS/JS，未引入外网字体或库。
- 1366／1920 像素实际浏览器截图、麒麟系统和 Win7 IE 尚未在本机执行；由 ChaselTH 负责现场验收。

## ChaselTH R1～R6 审查修复

- 统一表的筛选、分页、表单和保存重定向现在携带当前草稿 ID；跨页保存会合并既有差异，提交预览包含活动草稿全部已保存行，不再从当前页猜测草稿。
- 机构历史入口将支行显示名转换为正式编码，查询参数只 HTML 编码一次；统一历史直接读取不可变提交快照，按提交事件时间过滤，并保留已撤除正式行的历史，来源期次使用独立 `period` 条件。
- `RiskPages` 直接加载本地 `workflow.js`；脚本同时覆盖操作员草稿表单和管理员／复核员 `/update-batch` 表单，保持 ES3/attachEvent 降级。
- 草稿恢复按本人、草稿 ID 和具体版本判断活动状态，不依赖前 100 条列表；草稿活动查询在分页前排除已消耗版本和零有效差异版本。
- 新增真实 Main 统一大表跨页保存／提交预览、生成历史链接后一次 HTML 解码访问、直接编辑脚本资源，以及超过 100 条草稿恢复和零差异草稿回归。

本轮审查修复后的本地回归：`node tools/build.mjs --test --offline` 通过（`WORKFLOW_ROUTES_OK assertions=95`、`BUSINESS_HTTP_OK assertions=58`、`BUILD_OK`）。

## 交付状态

- 功能提交完整 SHA：`5949ec0827dc2012977d028bad31bf693d060a16`。
- PR：https://github.com/ChaselTH/zhihuixinguan/pull/11（面向 `main`，等待 ChaselTH 审查）；本分支不自行合并、改版本或制作安装包。
