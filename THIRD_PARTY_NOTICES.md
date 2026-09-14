# 第三方组件说明

智慧信管离线安装包包含以下第三方运行组件，仅用于在断网的麒麟 Linux 环境中运行及读取用户上传的电子表格：

- Microsoft Build of OpenJDK 21（x86_64 与 aarch64），其许可证和第三方声明随 Java 运行时归档一并保留。
- Apache POI 5.5.1、Apache XMLBeans 5.3.0 及其运行依赖，用于兼容读取 `.xls`、`.et` 与 `.xlsx`；Apache 项目组件依 Apache License 2.0 使用。
- Apache Commons 系列、Log4j API/Core、SparseBitSet 与 curvesapi，作为表格解析组件的离线运行依赖；各 JAR 内保留其原始许可元数据。
- H2 Database Engine 2.5.250，用于本地嵌入式数据库；原始许可文件保留在 JAR 内。未启用数据库 TCP 服务或网页控制台。

应用依赖的精确版本和 SHA-256 校验值见安装目录的 `dependencies.lock.json`。开发构建时校验 JAR 内容；工作电脑安装、启动和业务使用不下载依赖。

本系统的网页不从互联网加载任何第三方资源。
