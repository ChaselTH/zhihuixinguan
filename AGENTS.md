# 项目开发约定

- 用户于 2026-09-16 明确要求本项目以后不使用 GitHub CI。不要新增、恢复或启用 GitHub Actions／其他云端 CI；只有用户明确改变此要求后才可调整。
- 保留本地验证：提交与合并前执行 `node tools/build.mjs --test --offline` 和 `node tests/install-smoke.mjs`。缺少缓存时先说明；不得把未运行的云端检查写成通过。
- 使用 Java 服务端 HTML、本地静态资源和离线依赖，维护麒麟安装及旧 IE 核心操作兼容；现代浏览器测试不等于旧 IE／麒麟实测。
- 真实业务表、数据库、初始化配置及私人离线包不提交 Git。源码交付用 Git 当前提交导出，不复制整个工作目录或 Git 历史。
- A/B 协作只以 `docs/ai-collaboration/README.md` 的最新有效任务为准，没有明确 READY 授权时不自动扩展开发。
