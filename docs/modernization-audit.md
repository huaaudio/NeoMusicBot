# NeoMusicBot 现代化与测试版验收记录

审查日期：2026-09-21。本文件持续记录当前改动；[首次审查记录](review-2026-09-21.md)
保留当时的版本与验证范围，不能代表最新提交已经通过验收。

## 验收状态

| 项目 | 当前证据 / 待完成事项 |
| --- | --- |
| 仓库独立化 | GitHub `huaaudio/NeoMusicBot` 已为 `fork: false`；保留上游历史和许可证 |
| 基线跨平台构建 | 提交 `a16fe2e` 的 [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35560633442) 中 Windows/Linux 编译、122 个测试、JDAVE 加载和发行包组装成功；在线 YouTube 探测失败，因此整个工作流未通过 |
| 新依赖 | 提交 `48e8d50` 的 [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35561728333) 中 Windows/Linux 构建、测试、JDAVE 加载、发行包组装均通过；锁定媒体探测仍失败，不能发布 |
| Java 27 | `48e8d50` 的 Windows/Linux 兼容矩阵均通过；发行字节码与构建基线仍为 Java 25 LTS |
| 全模块审查 | 进行中，见问题表；未完成项不能按已通过处理 |
| 干净发行包 | 待验证解压后的启动、配置生成、原生加载与真实媒体解码 |
| Discord 实际语音 | 此轮尚无测试服务器/凭据，未验证真实 DAVE 握手、频道可听性或长期运行；用户曾实测旧版本，不等同本轮升级验收 |
| Pre-release | 尚未发布；必须使用最终成功 CI 的同一提交和经过验证的完整发行包 |

## 依赖选择与迁移

2026-09-21 查询官方 release 与 Maven 元数据。版本选择不等于功能验证。

| 组件 | 原版本 → 选定版本 | 来源 / 迁移与保留理由 |
| --- | --- | --- |
| JDA | 6.5.0 → 6.7.0 | [Release](https://github.com/discord-jda/JDA/releases/tag/v6.7.0)；复核频道混淆与交互频道权限语义，真实语音待验收 |
| JDAVE | 0.1.8 → 0.1.8 | [官方仓库](https://github.com/MinnDevelopment/jdave)；当前稳定版，要求 Java 25 FFM；两个平台均运行原生加载测试 |
| Lavaplayer | 2.2.7 → 2.2.7 | [Releases](https://github.com/lavalink-devs/lavaplayer/releases)；当前稳定版，需复核打包后的解码原生库 |
| youtube-source | 1.18.1 → 1.18.2 | [Release](https://github.com/lavalink-devs/youtube-source/releases/tag/1.18.2)；升级后仍需真实媒体验证 |
| yt-dlp | 2026.07.04 → 2026.08.19 | [Release](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19)；Windows/Linux 二进制均按官方资产 SHA-256 校验 |
| Deno | 2.9.3 → 2.9.7 | [Release](https://github.com/denoland/deno/releases/tag/v2.9.7)；两个平台 ZIP 校验、离线依赖缓存仍需干净解压验证 |
| bgutil provider | 1.3.1 → 2.0.0 | [Release](https://github.com/Brainicism/bgutil-ytdlp-pot-provider/releases/tag/2.0.0)；含服务端安全修复，本项目使用脚本模式；固定 tag commit 和插件 SHA-256 |
| Jackson | 2.22.0 → 3.2.2 | [迁移说明](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0)；包名改为 `tools.jackson`，`JsonNode.fields()` 改为 `properties().iterator()` |
| Rhino | 1.7.15 → 1.9.1 | [Releases](https://github.com/mozilla/rhino/releases)；实际依赖树确认 engine 与核心均为 1.9.1 |
| Logback / Config / org.json | → 1.6.3 / 1.4.9 / 20260814 | [Maven Central](https://repo.maven.apache.org/maven2/) 稳定元数据；由编译和功能测试验证 |
| JUnit | 5.13.4 + Vintage → Jupiter 6.1.3 | [JUnit](https://junit.org/)；所有 JUnit 4 测试迁移，移除 Vintage；必须核对用例数和跳过原因 |
| Maven / Wrapper | 3.9.16 / 3.3.4 保留 | [Maven 下载](https://maven.apache.org/download.cgi)；Maven 4 仍为候选版，不作为测试版发行构建基础 |
| Maven 插件 | Enforcer 3.6.3 / Compiler 3.16.0 / Surefire 3.6.0 / Shade 3.6.2 / CycloneDX 2.9.3 | 稳定版本；License 2.7.1 保留，许可证缺失问题另行处理 |
| JDK | Java 25 LTS 构建，新增 Java 27 测试 | [Java 27 发布](https://www.oracle.com/news/announcement/oracle-releases-java-27-and-strengthens-post-quantum-cryptography-support-2026-09-15/)；Temurin 27 尚无 GA，兼容测试用 GPL Oracle OpenJDK 27；保留 25 字节码以支持 LTS 用户 |
| GitHub Actions | checkout 7.0.1 / setup-java 6.0.1 / upload-artifact 7.0.1 / download-artifact 8.0.1 / gh-release 3.0.3 | 使用官方仓库 tag 对应 commit 固定；切换 Node 24 后待 CI 实测 |

## 可追踪问题

| ID / 严重度 | 位置与触发条件 | 影响 | 处理与验证状态 |
| --- | --- | --- | --- |
| AUD-001 / P1 | `scripts/ci/media_canary.sh`：任一音源失败即退出且丢弃所有错误信息 | 无法区分工具参数、限流、认证或解析错误；其他音源未检查 | 已修复；3 个诊断测试通过。`48e8d50` 云端报告 YouTube 两种模式均 authentication-required，Bilibili access-denied；媒体验收仍未通过 |
| AUD-002 / P1 | `pom.xml` 与解析器：Jackson 3 移除旧 API、JUnit 6 不再自动执行 JUnit 4 | 编译失败或测试覆盖丢失 | 迁移 JSON API 与所有旧测试 imports/assumptions；Windows 122 测试数量保持一致，121 通过、1 POSIX 测试按平台跳过 |
| AUD-003 / P1 | `SlashCommandListener` 的 Slash 与选择控件入口：配置频道被删除或不可见时缓存返回 null | 频道限制被跳过 | 按持久化 ID 判断，管理员界面显示不可用而非 any；`SettingsChannelRestrictionTest` 验证缓存缺失与显式清除两种情况 |
| AUD-004 / P1 | `BotConfig.writeDefaultConfig()`：对已有配置执行生成 | 覆盖 Token 和用户配置 | `CREATE_NEW` 拒绝覆盖，CLI 返回失败；回归测试先复现再通过 |
| AUD-005 / 待核实 | 发行包：依赖许可证下载告警、缓存路径与旧原生文件 | 材料不完整或依赖开发/构建环境 | 待补齐来源和干净目录验收 |
| AUD-006 / P2 | 内部包名、环境变量、旧 bot-listing 自动消息 | 独立项目仍有继承行为或旧名称 | 已迁移 `io.github.huaaudio.neomusicbot`、`NeoMusicBot` 主类及 `NEOMUSICBOT_*`；旧 main 与变量前缀保留兼容，重复变量拒绝启动；移除硬编码第三方服务器消息/退出逻辑，保留合法来源署名 |
| AUD-007 / P1 | `BotConfig.writeToFile()`：交互补填 owner 或 token | 重写默认模板导致已有配置与注释丢失 | 使用 HOCON 文档更新指定键，保留其他值；临时文件刷盘后替换，POSIX 新文件权限 0600；回归先复现再通过 |
| AUD-008 / P1 | `SettingsManager.load()`：主文件损坏且没有有效备份；或频道 ID 拼写错误 | 空设置覆盖原数据、错误 ID 静默变成无限制 | 无法恢复时拒绝启动并保留文件；拒绝错误 ID；两个故障回归先失败、修复后通过 |
| AUD-009 / P1 | `SettingsManager.drainWrites()`：I/O 写入失败 | dirty 标记丢失，flush 返回成功且退出时不再保存 | 保留待保存状态，flush/后续更新/关闭时重试，不无休止循环；跨平台目录阻塞写入用例先失败、修复后通过 |
| AUD-010 / P2 | Windows 二次执行 `mvn clean`，`target/bgutil-provider-src/.git` 含只读 pack 文件 | 清理失败，影响本地重复打包 | 此次验证已仅清理 target 内生成的 provider checkout 后成功干净构建；发行脚本仍需避免残留只读 checkout |

## 阶段验证证据

- `48e8d50` Windows 本地 `verify dependency:tree` 成功；122 测试，0 失败，1 个 POSIX 测试按平台跳过。日志 `target/modernize-verify.log` 与树 `target/modern-dependencies.txt` 为本地忽略文件。
- Maven 图仍由 JDA 引入 Jackson 2.22.2，与应用的 Jackson 3 包名不同；不能排除 JDA 需要的 Jackson 2。
- 许可证下载仍有 nanojson、youtube-source、base64 与 GNU 站点缺失/超时告警；此阶段构建成功不表示发行材料齐全。
- 配置与设置专项测试共 14 个全部通过；配置覆盖的 2 个失败与设置恢复的 3 个失败已在修改前复现。
- 此阶段完整 Windows 测试共 130 个，129 通过、0 失败、1 POSIX 测试按平台跳过；`target/review-settings-all.log`。
- 命名迁移后干净 Windows 构建共 132 个测试，131 通过、0 失败、1 POSIX 测试按平台跳过；日志 `tools/namespace-test.log`。新增变量别名/凭据冲突测试，并覆盖新旧前缀均不传给媒体子进程。
- 云端媒体失败只记录固定分类，不输出签名 URL、Cookie 或原始提取器日志；未通过的音源不标记为验证成功。

## 全模块检查覆盖

以下均需完成代码检查及相应的行为验证，不能仅由单元测试绿色代替审查：

- 启动、配置、更新检查、环境变量、启动脚本：配置及变量兼容已审查修复；更新检查、入口失败状态、启动器实际运行仍需验证。
- Discord Slash 注册、权限、交互生命周期、频道混淆：待检查。
- 语音连接、DAVE、重连、退出、播放器资源释放：待检查。
- 播放队列、公平排序、并发、暂停/重复/停止、状态恢复：已有基线修复，需升级后复核。
- Bilibili 分 P、短链接、媒体 URL、Cookie 与子进程：已有基线测试，需新版工具集成验证。
- YouTube、SoundCloud、Discord 附件、播放列表：待升级后复核。
- 配置与服务器设置持久化、备份和故障处理：已检查并修复 AUD-004/007/008/009，专项测试通过；最终平台 CI 待复核。
- CI、SBOM、许可证、哈希、打包、发布门禁：进行中。
