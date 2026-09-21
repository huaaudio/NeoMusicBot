# 间接依赖升级清单

核对日期：2026-09-21。当前版本及引入关系来自本项目生成的 `target/bom.json`，
候选版本来自下面链接的 Maven Central 发布元数据。候选版本尚未替换，也尚未完成兼容性验收；
本表不能作为依赖现代化已经完成的证据。

| 依赖 | 当前 → 候选 | 引入方 | 下一步验证 |
| --- | --- | --- | --- |
| [jsoup](https://repo.maven.apache.org/maven2/org/jsoup/jsoup/maven-metadata.xml) | 1.16.1 → 1.23.2 | Lavaplayer | HTML 解析 API 与实际音源加载 |
| [Commons Codec](https://repo.maven.apache.org/maven2/commons-codec/commons-codec/maven-metadata.xml) | 1.11 → 1.22.1 | HttpClient 4 | 编码及 HTTP 集成 |
| [Commons IO](https://repo.maven.apache.org/maven2/commons-io/commons-io/maven-metadata.xml) | 2.13.0 → 2.22.0 | Lavaplayer / lava-common | 音频流读取、关闭和解码 |
| [Commons Logging](https://repo.maven.apache.org/maven2/commons-logging/commons-logging/maven-metadata.xml) | 1.2 → 1.4.0 | HttpClient 4 | 日志桥接及敏感日志关闭规则 |
| [JNA](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/maven-metadata.xml) | 4.4.0 → 5.19.1 | opus-java-api 1.1.1 | 原生加载及 Opus 编解码；跨大版本需验证 ABI/调用方式 |
| [Tink](https://repo.maven.apache.org/maven2/com/google/crypto/tink/tink/maven-metadata.xml) | 1.18.0 → 1.23.0 | JDA 6.7.0 | JDA 使用的加密 API、相关依赖及语音集成 |
| [Gson](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/maven-metadata.xml) | 2.10.1 → 2.14.0 | Tink | 结合 Tink 升级后重新解析依赖图及测试 |
| [Protobuf Java](https://repo.maven.apache.org/maven2/com/google/protobuf/protobuf-java/maven-metadata.xml) | 4.28.2 → 4.36.2 | Tink | 结合 Tink 使用的生成代码验证运行时兼容性 |
| [nanojson](https://repo.maven.apache.org/maven2/com/grack/nanojson/maven-metadata.xml) | 1.7 → 1.10 | youtube-source v2 / common | YouTube JSON 解析、错误分类及实际加载；更新版本限定许可映射 |

[HttpClient 4 元数据](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpclient/maven-metadata.xml)
和 [HttpCore 4 元数据](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpcore/maven-metadata.xml)
仍分别列出 4.5.14、4.4.16。它们不代表 HttpComponents 5 的版本状态。
目前 Lavaplayer 和本项目 HTTP 安全适配器使用 `org.apache.http` API；迁移到 5 需要检查上游支持、
改写适配器并复测重定向、地址限制、凭据头处理和媒体解码，不能仅替换 Maven 坐标。

执行升级时应先读各项目发布说明，再以明确版本约束验证依赖图、单元测试、原生编解码与在线音源，
同步更新许可证和 SBOM。若确认某项暂时不能升级，在本表写明实际失败证据及后续方案，
不要将“尚未尝试”写成“无法升级”。
