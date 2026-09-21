# 间接依赖升级与保留记录

核对日期：2026-09-21。引入关系来自生成的 `target/bom.json`，版本来自 Maven Central 元数据及上游发布说明。
下表的目标版本已固定到 `pom.xml`；本地验证与对应提交的云端结果在[审查记录](modernization-audit.md)中分别记录，
不能仅由版本号或编译通过认定实际语音已经验收。

| 依赖 | 升级前 → 当前 | 引入方 | 验证范围 |
| --- | --- | --- | --- |
| [jsoup](https://repo.maven.apache.org/maven2/org/jsoup/jsoup/maven-metadata.xml) | 1.16.1 → 1.23.2 | Lavaplayer | HTML 解析 API 与实际音源加载 |
| [Commons Codec](https://repo.maven.apache.org/maven2/commons-codec/commons-codec/maven-metadata.xml) | 1.11 → 1.22.1 | HttpClient 4 | 编码及 HTTP 集成 |
| [Commons IO](https://repo.maven.apache.org/maven2/commons-io/commons-io/maven-metadata.xml) | 2.13.0 → 2.22.0 | Lavaplayer / lava-common | 音频流读取、关闭和解码 |
| [Commons Logging](https://repo.maven.apache.org/maven2/commons-logging/commons-logging/maven-metadata.xml) | 1.2 → 1.4.0 | HttpClient 4 | 日志桥接及敏感日志关闭规则 |
| [JNA](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/maven-metadata.xml) | 4.4.0 → 5.19.1 | opus-java-api 1.1.1 | 原生加载及 Opus 编解码；实际编码/解码 20 ms 立体声测试音 |
| [Tink](https://repo.maven.apache.org/maven2/com/google/crypto/tink/tink/maven-metadata.xml) | 1.18.0 → 1.23.0 | JDA 6.7.0 | 调用 JDA 的 AES-GCM/XChaCha20 RTP 适配器，往返加密并拒绝篡改头部/负载 |
| [Gson](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/maven-metadata.xml) | 2.10.1 → 2.14.0 | Tink | 结合 Tink 升级后重新解析依赖图及测试 |
| [Protobuf Java](https://repo.maven.apache.org/maven2/com/google/protobuf/protobuf-java/maven-metadata.xml) | 4.28.2 → 4.36.2 | Tink | 依赖图与集成构建；高于 Tink 1.23.0 声明的 4.33.6 |
| [nanojson](https://repo.maven.apache.org/maven2/com/grack/nanojson/maven-metadata.xml) | 1.7 → 1.10 | youtube-source v2 / common | YouTube JSON 解析、错误分类及实际加载；重新检查 1.10 源码并更新许可映射 |
| [Kotlin stdlib](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml) | 2.1.21 → 2.4.20 | OkHttp / Okio | 本项目不编译 Kotlin；验证 Java 调用方、HTTP 请求及运行时链接 |
| [Okio](https://repo.maven.apache.org/maven2/com/squareup/okio/okio-jvm/maven-metadata.xml) | 3.18.1 → 3.18.2 | OkHttp | 更新检查的响应读取、关闭及异常行为 |
| [Error Prone annotations](https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/maven-metadata.xml) | 2.22.0 → 2.50.0 | Okio / Tink / Gson | 编译和运行时依赖收敛；不引入 Error Prone 编译器 |
| [JetBrains annotations](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/maven-metadata.xml) | 24.0.0 → 26.1.0 | JDA / Kotlin | Java 编译与注解引用 |

[HttpClient 4 元数据](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpclient/maven-metadata.xml)
和 [HttpCore 4 元数据](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpcore/maven-metadata.xml)
仍分别列出 4.5.14、4.4.16。另行核实的 HttpClient 5 / HttpCore 5 最新稳定版分别为 5.6.4 / 5.4.3，
不是元数据中列出的 5.7-alpha1 / 5.5-beta2。
目前 Lavaplayer 和本项目 HTTP 安全适配器使用 `org.apache.http` API；迁移到 5 需要检查上游支持、
改写适配器并复测重定向、地址限制、凭据头处理和媒体解码，不能仅替换 Maven 坐标。
保留原因是当前 Lavaplayer 对外的 HTTP 类型与内部音源实现仍依赖 4.x；本项目的
`PublicMediaHttpContextFilter` 等也直接使用这些类型。后续在上游支持 5.x 或完成相应库迁移后一起替换。
当前保留 4.x 分支最新稳定版本。

Gson / Protobuf 随 Tink 一起完成本项目的构建和加密路径验证。本项目不提供 Tink 密钥集 JSON
导入导出功能，本轮未独立验收该库的全部序列化 API。

其余核实结果：SLF4J 保留最新稳定版 2.0.19，2.1.0-alpha1 不属于本次稳定升级目标；
OkHttp 5.5.0、nv-websocket-client 2.14、Commons Collections 4.6.0、Trove 3.1.0、
jsr305 3.0.2、base64 2.3.9、opus-java-api/natives 1.1.1 仍与各自发布元数据中的稳定版本一致。
其中 opus-java 的制品版本与其内嵌 libopus 的版本不是同一概念；JNA/Opus 自检只证明当前绑定能工作。

迁移依据包括 [jsoup 1.23.2](https://jsoup.org/news/release-1.23.2)、
[Commons Codec](https://commons.apache.org/proper/commons-codec/changes.html)、
[Commons IO](https://commons.apache.org/proper/commons-io/changes.html)、
[Commons Logging](https://commons.apache.org/proper/commons-logging/changes.html)、
[JNA 5.19.1](https://github.com/java-native-access/jna/blob/5.19.1/CHANGES.md)、
[Tink 1.23.0](https://github.com/tink-crypto/tink-java/releases/tag/v1.23.0)、
[Gson 2.14.0](https://github.com/google/gson/releases/tag/gson-parent-2.14.0)、
[Protobuf 36.2](https://github.com/protocolbuffers/protobuf/releases/tag/v36.2) 和
[Kotlin 2.4 兼容说明](https://kotlinlang.org/docs/compatibility-guide-24.html)。

JNA 原先通过运行时范围引入；新增原生自检直接调用其类型，因此将 JNA 明确列为编译依赖。
Tink 的可选 Google HTTP Client / Conscrypt 不随本项目打包。Commons Logging 的实际桥接入口
另有测试，确认将应用日志调到 DEBUG 后，HTTP 头、正文及错误链日志仍保持关闭。
jsoup 只约束 Lavaplayer 已有的间接依赖版本；直接依赖与应用源码仍禁止恢复通用 HTML 抓取。
