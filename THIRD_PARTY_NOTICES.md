# 第三方组件说明

智慧信管离线安装包包含以下第三方运行组件，仅用于在断网的麒麟 Linux 环境中运行及读取用户上传的电子表格：

- Microsoft Build of OpenJDK 21（x86_64 与 aarch64），其许可证和第三方声明随 Java 运行时归档一并保留。
- Apache POI 5.5.1、Apache XMLBeans 5.3.0 及其运行依赖，用于兼容读取 `.xls`、`.et` 与 `.xlsx`；Apache 项目组件依 Apache License 2.0 使用。
- Apache Commons 系列、Log4j API/Core、SparseBitSet 与 curvesapi，作为表格解析组件的离线运行依赖；各 JAR 内保留其原始许可元数据。

本系统的网页不从互联网加载任何第三方资源。
