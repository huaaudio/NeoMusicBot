# NeoMusicBot 现代化与测试版验收记录

审查日期：2026-09-21。本文件持续记录当前改动；[首次审查记录](review-2026-09-21.md)
保留当时的版本与验证范围，不能代表最新提交已经通过验收。

## 验收状态

最近完成实物回读的应用 CI：`d5ea9b0` 的 [35686250282](https://github.com/huaaudio/NeoMusicBot/actions/runs/35686250282)。
2026-09-22 两平台构建与 Java 27 检查通过，三个实际 artifact 已下载，外层哈希与大小通过；
两平台实际 ZIP 的发布关联、Canvas、Maven 原始材料、QuickJS 和新增 Deno 材料核验通过。
证据为 `tools/ci-success-d5ea9b0/verified-artifacts.json` 与 `verified-materials.json`。
Deno 的 Gitiles 时间戳修复已通过实际云端包回读，不再待验证。
本次媒体检查为 YouTube 匿名 authentication-required、Bilibili 匿名 access-denied，provider 模式通过；
因此整次工作流失败，`release_eligible=false`，不能使用这些包发布。
较早 `5c2c3da` 的 [35683045221](https://github.com/huaaudio/NeoMusicBot/actions/runs/35683045221) 曾全部通过，
但不能替代最终提交验证。
Linux 原生完整材料云端回读基线为 `a10d3f4` 的 [35678485785](https://github.com/huaaudio/NeoMusicBot/actions/runs/35678485785)；
37 个原生文件与报告和仓库定义一致，源码、声明及 Cargo 集合的实际材料 ZIP 通过干净回读。
`7e9a242` 增补匹配的 Rust 标准库/编译器源码，本地 5,169 文件材料 ZIP 已回读通过；
相应 [原生 CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35682198358) 的实际云端完整材料包也已干净回读通过；
`5c2c3da` 的后续 [原生 CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35683045284) 已成功，其完整材料 ZIP 也已干净回读通过。
其余材料审查和发行资产选择尚待完成，见 [原生升级记录](native-linux-upgrade.md)。
以下早期提交记录用于保留故障和修复过程，不代表最新流水线仍有相同故障。

| 项目 | 当前证据 / 待完成事项 |
| --- | --- |
| 仓库独立化 | GitHub `huaaudio/NeoMusicBot` 已为 `fork: false`；保留上游历史和许可证 |
| 基线跨平台构建 | 提交 `a16fe2e` 的 [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35560633442) 中 Windows/Linux 编译、122 个测试、JDAVE 加载和发行包组装成功；在线 YouTube 探测失败，因此整个工作流未通过 |
| 新依赖 | 提交 `48e8d50` 的 [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35561728333) 中 Windows/Linux 构建、测试、JDAVE 加载、发行包组装均通过；锁定媒体探测仍失败，不能发布 |
| Java 27 | `48e8d50` 的 Windows/Linux 兼容矩阵均通过；发行字节码与构建基线仍为 Java 25 LTS |
| 全模块审查 | 进行中，见问题表；未完成项不能按已通过处理 |
| 干净发行包 | `f616f13` 的 [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35564696089) 已通过 Windows/Linux 最终 ZIP 干净解压、启动器、配置、原生解码及 provider 离线启动；锁定在线媒体探测仍失败，因此整体尚未通过 |
| Discord 实际语音 | 已完成本轮授权频道内可听播放、连续两首、分 P、控制、至少十分钟播放及无人退出；另有真实 DAVE 和应用语音重连证据，见[验收记录](discord-voice-acceptance.md)。最终发行包仍须验证 |
| Pre-release | 尚未发布；必须使用最终成功 CI 的同一提交和经过验证的完整发行包 |

## 依赖选择与迁移

2026-09-21 查询官方 release 与 Maven 元数据。版本选择不等于功能验证。

| 组件 | 原版本 → 选定版本 | 来源 / 迁移与保留理由 |
| --- | --- | --- |
| JDA | 6.5.0 → 6.7.0 | [Release](https://github.com/discord-jda/JDA/releases/tag/v6.7.0)；复核频道混淆与交互频道权限语义，本轮真实语音验收见单独记录 |
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

直接依赖已完成一轮升级；[间接依赖记录](dependency-followups.md) 记录 13 项已升级版本、引入关系、
验证范围及保留 HttpComponents 4.x 的具体原因。最新代码的跨平台验收仍需完成。

| ID / 严重度 | 位置与触发条件 | 影响 | 处理与验证状态 |
| --- | --- | --- | --- |
| AUD-001 / P1 | `scripts/ci/media_canary.sh`：任一音源失败即退出且丢弃所有错误信息 | 无法区分工具参数、限流、认证或解析错误；其他音源未检查 | 诊断已修复；`08aa25b` 的完整 CI 在隔离的一次性 runner 上三项媒体全部通过。托管网络仍可能出现 YouTube authentication-required、Bilibili access-denied；不会降低发布检查或用本地报告替代 |
| AUD-002 / P1 | `pom.xml` 与解析器：Jackson 3 移除旧 API、JUnit 6 不再自动执行 JUnit 4 | 编译失败或测试覆盖丢失 | 迁移 JSON API 与所有旧测试 imports/assumptions；Windows 122 测试数量保持一致，121 通过、1 POSIX 测试按平台跳过 |
| AUD-003 / P1 | `SlashCommandListener` 的 Slash 与选择控件入口：配置频道被删除或不可见时缓存返回 null | 频道限制被跳过 | 按持久化 ID 判断，管理员界面显示不可用而非 any；`SettingsChannelRestrictionTest` 验证缓存缺失与显式清除两种情况 |
| AUD-004 / P1 | `BotConfig.writeDefaultConfig()`：对已有配置执行生成 | 覆盖 Token 和用户配置 | `CREATE_NEW` 拒绝覆盖，CLI 返回失败；回归测试先复现再通过 |
| AUD-005 / 待核实 | 发行包：依赖许可证下载告警、缓存路径与旧原生文件 | 材料不完整或依赖开发/构建环境 | Maven 许可覆盖与两平台干净目录验收已通过；新增 provider 安装树/缓存依赖清单与解压复核，AUD-033 后两平台实物各包含 183 个 npm 包；独立工具、native/WASM 及第三方源码/许可覆盖尚未完成，见 [材料记录](distribution-licenses.md) |
| AUD-006 / P2 | 内部包名、环境变量、旧 bot-listing 自动消息 | 独立项目仍有继承行为或旧名称 | 已迁移 `io.github.huaaudio.neomusicbot`、`NeoMusicBot` 主类及 `NEOMUSICBOT_*`；旧 main 与变量前缀保留兼容，重复变量拒绝启动；移除硬编码第三方服务器消息/退出逻辑，保留合法来源署名 |
| AUD-007 / P1 | `BotConfig.writeToFile()`：交互补填 owner 或 token | 重写默认模板导致已有配置与注释丢失 | 使用 HOCON 文档更新指定键，保留其他值；临时文件刷盘后替换，POSIX 新文件权限 0600；回归先复现再通过 |
| AUD-008 / P1 | `SettingsManager.load()`：主文件损坏且没有有效备份；或频道 ID 拼写错误 | 空设置覆盖原数据、错误 ID 静默变成无限制 | 无法恢复时拒绝启动并保留文件；拒绝错误 ID；两个故障回归先失败、修复后通过 |
| AUD-009 / P1 | `SettingsManager.drainWrites()`：I/O 写入失败 | dirty 标记丢失，flush 返回成功且退出时不再保存 | 保留待保存状态，flush/后续更新/关闭时重试，不无休止循环；跨平台目录阻塞写入用例先失败、修复后通过 |
| AUD-010 / P2 | Windows 二次执行 `mvn clean`，`target/bgutil-provider-src/.git` 含只读 pack 文件 | 清理失败，影响本地重复打包 | 发行脚本在校验 provider commit 后移除生成 checkout 的 `.git`；Windows 校验绝对目标位于 target 下；不会删除项目自己的 Git 数据 |
| AUD-011 / P1 | Linux 发行包采用通用 `yt-dlp` zipimport 资产 | 在没有 Python 的机器上无法运行，与完整发行包目标不符 | 改用固定哈希的 `yt-dlp_linux`；ZIP 验证器检查 ELF/PE 文件头及工具版本 |
| AUD-012 / P1 | Windows `Compress-Archive` 与仅在构建目录检查 provider | 隐藏缓存可能漏包，无法证明换机器后离线启动 | 使用 Python ZIP 完整收录文件并生成逐文件 SHA256SUMS；解压到新目录后以全新 HOME 和 `--deny-net --cached-only` 运行 provider；`d4a8296`、`f616f13` 两平台云端实测通过 |
| AUD-013 / P1 | Windows 启动器位于带括号的目录，错误提示展开未加引号的 JAR 路径 | CMD 在解析 if 块时提前报错，即使 JAR 存在也不能启动 | 临时干净目录首次复现退出 255；为输出路径加引号，由实际启动器运行回归验证 |
| AUD-014 / P1 | Maven 缺失许可信息只警告，Shade 遇到同名 NOTICE/LICENSE 只保留首份 | 发行材料遗漏部分依赖许可和声明 | 按上游源码补齐版本限定的许可映射，下载失败阻止打包；验证许可文件覆盖 SBOM 的全部 44 个组件；同名许可/声明合并保留，详见 `src/license/README.md` |
| AUD-015 / P2 | Windows CI 使用 8.3 临时目录名，ZIP 路径比较及 Deno 权限参数未规范化 | 合法包被误判为目录外文件；provider 以长路径读取资源而被短路径权限拒绝 | 路径比较与创建临时根目录后统一 resolve；回归覆盖相对根目录；`3970117` CI 暴露 Deno 参数问题；`d4a8296`、`f616f13` 两平台干净解压验证已通过 |
| AUD-016 / P2 | 更新检查只使用 `/releases/latest`，版本标签包含空 prerelease 段 | 测试版收不到后续 beta 提醒；错误标签触发 NumberFormatException | 测试版查询已发布 releases，正式版保留 stable 通道；拒绝错误标签，HTTP 客户端复用、10 秒总超时、2 MiB 响应上限、所有分支关闭响应；错误标签回归先复现异常 |
| AUD-017 / P1 | `Bot` 初始化或退出时，播放器初始化、单个语音连接、JDA 关闭发生异常 | 后续组件不清理，设置存储/线程池残留；持有 Bot 锁调用外部库可导致回调等待 | 初始化失败清理已有组件；退出逐项隔离错误、不持有 Bot 监视器；迟到的 JDA/GUI 立即关闭；5 个故障回归修改前失败，修改后通过 |
| AUD-018 / P1 | `SerialExecutor` 后端拒绝第一次 drain 提交 | scheduled 标志永久停在 true，后续任务被静默接受但不执行，同步调用无限等待 | 入队与调度原子化，拒绝时回滚，关闭后立即拒绝新任务；正常退出排空已接受任务并取消延迟/周期任务；2 个故障回归修改前失败，修改后通过 |
| AUD-019 / P2 | JVM 退出或窗口关闭与后台清理并发 | 缺少应用层有序退出处理，窗口可能提前强制终止进程 | Bot 注册自己的退出 hook，等待正在进行的清理；取消 JDA 的独立 hook；GUI 后台退出并在清理完成后销毁窗口；独立子 JVM 验证退出时设置存储已关闭 |
| AUD-020 / P1 | owner 追加歌单先读取解析后的条目，再整体重写 | 丢失注释与 shuffle 指令，改变已有顺序；并发追加可覆盖另一请求 | 存储层串行读改写，保留原文、UTF-8 临时文件刷盘后原子替换；实际 Slash 追加回归先复现丢失，修复后通过；40 项并发追加完整保留 |
| AUD-021 / P2 | `PlaylistLoader` 首次创建、目录为文件、目录名以 `.txt` 结尾 | 创建失败、列表抛出 NPE、将目录误列为歌单 | 创建父目录、只枚举普通文件、不可用目录返回空列表并记录错误类型；3 个回归修改前失败、修改后通过 |
| AUD-022 / P2 | 歌单存储方法接收包含 `../` 的名称 | 存储层自身缺少目录边界；当前 owner 命令另有名称校验，但复用 API 可能访问目录外文件 | 存储层拒绝路径字符与控制符，不跟随歌单符号链接；保留合法旧中文名称；越界写入回归先复现覆盖，修改后拒绝 |
| AUD-023 / P2 | `/dj repeat` 直接写设置，未调用共同的音乐命令上下文 | 有 DJ 权限的用户能在配置限制外修改循环模式 | 复用文字频道与服务器上下文检查；实际 Slash 回归先复现越权修改，修复后拒绝且允许正确频道操作 |
| AUD-024 / P2 | 超时清理测试依赖子 JVM 在 350 ms 内写 PID | Windows Java 27 在 JVM 启动较慢时，进程已被正确终止却被测试误报为未启动 | 在进程创建时捕获真实 OS 句柄，维持 350 ms 超时及存活检查；测试子进程刻意延迟写 PID 2 秒；`b877a29` 两平台 Java 25/27 均通过 |
| AUD-025 / P2 | 旧 `--self-test` 只检查 DAVE 加载与 Lavaplayer 解码 | JNA/opus-java 绑定及 JDA 使用的 Tink 接口不在发行包自检覆盖内 | 新增实际 Opus 编解码、JDA 两种 RTP 加密往返及篡改拒绝；完整 ZIP 验证器要求新检查字段；Commons Logging 真实桥接入口亦受日志关闭测试覆盖 |
| AUD-026 / P1 | `/skip` 等待播放线程后重读请求者频道，并分别读取投票人数与票数；用户此时切换频道或离开 | 在其他频道按更低门槛跳过歌曲、离开后仍可直接跳过、空指针或计票不一致 | 在播放线程内获取 Bot 频道的一份合格听众快照，重新核实投票资格，原子记录投票与执行跳过；普通命令和搜索控件也改为一次捕获频道并判空；6 个行为用例中 5 个修复前失败、修复后全部通过 |
| AUD-027 / P1 | `Make Release` 只核对工作流名称及 conclusion，版本输入与产物不关联，创建 `prerelease: false` 的草稿 | 可混入其他提交或错误版本的包，不满足测试版发布目标 | 新增来源、默认分支、触发类型、完整 SHA、POM/JAR/SBOM、平台报告与媒体报告关联检查；创建测试版草稿、下载核对全部资产后公开且不设 Latest；12 个 Python 测试及 actionlint 通过，真实发布链路待执行 |
| AUD-028 / P1 | `Playlist.loadTracks()`：消费者抛出异常，或 Lavaplayer 有序执行器首次提交被拒绝 | 完成回调丢失、后续条目不再加载；重复结果回调还会重复入队 | 逐项提交并串行处理结果，每项只接受一次回调，异常后继续下一项；独立处理嵌套歌单每首歌曲，返回不可变结果快照；4 个故障回归修复前失败、修复后通过，并验证 10,000 项同步失败不递归溢出及指定分 P/顺序保持 |
| AUD-029 / P2 | 全局/服务器投票比例和服务器音量缺少数值校验；手动配置或 API 写入越界、非有限、错误类型的数值 | 投票门槛不可达到或被降为一票；非有限值使 JSON 保存异常；音量被默认、截断或溢出 | 加载和 setter 均校验比例与音量；音量按精确整数解析，保留合法数字字符串及服务器比例 -1 继承语义；不修改无效原文件，按既有策略恢复有效备份；22 个故障用例修复前失败、修复后通过 |
| AUD-030 / P2 | `AloneInVoiceHandler.isAlone()` 在判空与读取成员间再次获取连接频道 | 此时断开会抛出空指针，语音事件的无人计时更新中断 | 一次捕获频道并使用该快照；确定性断开用例修复前复现空指针，修复后与未连接/有人监听用例均通过 |
| AUD-031 / P2 | `TimeUtil`：跳转时间使用 int 单位累加或非有限/越界 double，失败后又按单位解析 | `4294968s` 被截成 704 毫秒；`NaN` 被当作 0，超范围时间可能静默饱和或变为另一时间 | 使用精确十进制与 long 溢出检查；拒绝非有限、缺失字段、十六进制及超范围输入，阻止无效数值回退为单位；保留小数、相对跳转及既有有效单位写法，故障回归和完整构建通过 |
| AUD-032 / P2 | `TextAreaOutputStream`：UTF-8 字符跨 write 边界；一次写入多行或分块写入换行 | 中文/表情出现替代字符；日志行数上限失效，长期运行的界面文档持续增长 | 增量 UTF-8 解码并保留未完成字符，关闭时完成解码；按实际文档行数裁剪，保留最新的未完成行；ConsolePanel 显式使用 UTF-8；4 项确定性故障回归先失败、修复后通过 |
| AUD-033 / P3 | provider 的默认 Deno 安装将开发依赖也装入发行目录；单加 `--prod` 仍安装全部 304 个包 | 随包分发不用于运行的 lint/编译工具及原生依赖，增加大小和第三方材料范围 | 提交经核对的运行锁文件并保存原配置/锁文件；各运行包的版本、完整性和依赖记录与上游一致；两平台及媒体 canary 共用相同预处理，实际安装降为 183 包；`1133449` 两平台完整 ZIP 和干净解压验证通过 |
| AUD-034 / P1 | Slash 交互/注册/控件失败以及部分语音与启动监听异常直接将 Throwable 交给日志器 | 异常消息、cause 或 suppressed 链中的 Webhook 路径、Cookie 等内容可被原样打印 | 共用有限长度的脱敏异常摘要，保留操作上下文与异常类型，不附加原始异常链；实际响应编辑失败回调的 3 个泄露断言修复前均失败，修复后通过 |
| AUD-035 / P2 | `NowplayingHandler.onTrackUpdate()`：开启 songinstatus 后遇到超过 128 个 Unicode 字符、空白或缺失的媒体标题 | JDA 拒绝活动名称并抛出异常，播放回调中断且状态保留旧歌名 | 仅截短展示用标题并保留 Unicode 字符完整性；缺失/空白标题恢复配置的活动；6 个真实 JDA 名称校验故障回归修复前失败，9 个专项用例修复后通过 |
| AUD-036 / P1 | Canvas npm 安装脚本额外下载平台原生资产，npm integrity 不覆盖这些文件 | 锁文件相同仍无法保证发行包 native 内容相同，缺失/变更不被单独识别 | 两平台官方预编译压缩包及全部文件独立固定哈希；禁用 lifecycle 脚本，显式安装并在 ZIP 解压后按仓库定义核验；发布门禁要求 `provider.native=passed`。材料审查仍归 AUD-005 |
| AUD-037 / P2 | jsoup 的 POM 许可 URL 指向可变官网页面；`751bea0` 的普通 push CI 在该页面读取超时 | Linux 的 230 个 Java 测试通过后，许可证收集失败导致构建退出 | 仅对 jsoup 1.23.2 的已核对元数据，改用 release tag 对应固定 commit 中的原始 LICENSE，保留作者署名和许可条款；继续严格拒绝下载失败，不忽略材料检查 |
| AUD-038 / P3 | `MediaTrackKey.serializedId()` 使用系统默认语言转换音源名称；土耳其语等环境将 ASCII I 转为无点的 ı | 同一 Bilibili 视频的 `AudioTrackInfo.identifier` 因系统语言不同而变化 | 改为 `Locale.ROOT`；现有真实 track 创建/序列化用例切换到 tr-TR 后先复现错误，修复后通过。二进制 track 序列化本来单独保存 canonicalId/分 P，本问题不据此宣称播放失败或持久化数据丢失 |
| AUD-039 / P2 | 普通 push 的媒体任务总在 GitHub 托管网络运行；`d63bff5` 的托管检查被 YouTube/Bilibili 拒绝，同提交隔离网络三项通过 | 正常 push 持续失败，需重复触发完整工作流才能完成真实验收 | 增加默认分支按运行编号/尝试次数选择隔离 runner、本机自动领取控制器及单任务注册/注销；不跳过媒体任务、不容忍失败。`c651760` 的普通 push [35621240219](https://github.com/huaaudio/NeoMusicBot/actions/runs/35621240219) 四个构建及三项媒体全部通过；实际发行包已下载核验，runner 已注销。机器和控制器在线且代码一致时才能完成 |
| AUD-040 / P2 | 隔离执行器只在注册前核对排队任务，实际 runner ID 在执行后确认；其他工作流可指定同一调度标签 | 标签不能保证领取的任务身份，执行后检查为时已晚；未发现实际误领事件 | 增加只读 job-start hook，在工作流步骤前核对八项 GitHub 身份字段；不符时暂停当前 Worker 并由外部启动器终止隔离环境，阻止后续 always 步骤。44 项 Python 检查、真实 bubblewrap 正反用例及实际 runner 无凭据预检通过；`45e5c3a` 的普通 push 35630463963 出现 `runner.job-policy=passed`，三项媒体与四个构建成功，runner 30 已注销，实际产物复核通过 |
| AUD-041 / P1 | 播放失败回调对轨道 identifier 只剥离查询参数 | 媒体 URL 的用户名、密码、签名路径及 fragment 仍可能写入日志 | 对轨道标识复用统一脱敏策略，再限制字段长度；真实日志回调中 4 项虚构秘密泄露断言修复前失败，修复后通过，保留错误说明和严重级别且不附加异常链 |
| AUD-042 / P3 | FairQueue/LinearQueue 的 add 未遵守基类同步修改约定 | 公平插入与 clear 并发时可越界；当前 GuildPlaybackSession 已串行化应用操作，未观察到实际 Slash 故障 | 两个 add 使用相同实例锁；先复现 FairQueue 越界，再验证队列、会话原子性与恢复共 18 项通过 |

## 阶段验证证据

- `b500d6e` 的普通 push 应用 CI 四个构建、三项媒体全部成功，runner 31 自动领取并完成注销；三个 artifact 按 GitHub 哈希/大小下载，实际 ZIP 的平台关联、原生文件与现有源码/许可材料复核通过。证据 `tools/ci-success-b500d6e/verified-artifacts.json`、`verified-materials.json`。
- Linux 实验已将 Cairo、librsvg、Pango、GLib、HarfBuzz、Fontconfig、FreeType 七个共享组件升级，并重新编译 Canvas。仓库配方在全新目录构建成功，37 个文件通过实际版本、隔离格式/字体与生产安装器归档回读检查，证据 `tools/native-recipe-package-fixed.log`。librsvg 完整锁图的 357 个 crate、641 份原始声明及 8 份补充正文已完成实际文件验证，5 项新回归通过。20 个 Ubuntu 对应源码包已收集；其余材料、最终二进制绑定与云端 CI 仍在进行。新库尚未进入发行包，详见 [实验记录](native-linux-upgrade.md)。
- `45e5c3a` 普通 push 的三个 artifact 均已按 GitHub 外层哈希/大小下载；两个实际 ZIP 的平台门禁、native 文件、Maven 原始许可和已有 Windows 原生源码材料再次验证。证据 `tools/ci-success-45e5c3a/verified-artifacts.json`、`verified-materials.json`；控制器记录本次领取成功且注册清理为 `removed`。
- `c651760` 普通 push 的实际 artifact 已下载，外层 SHA-256/大小、两平台发行关联与 native 内容、三份固定 Maven 原始许可，以及 Windows 的 34 个父源码包和 363 个 Cargo 包均复核通过；证据 `tools/ci-success-c651760/verified-artifacts.json`、`verified-materials.json`、`push-controller-acceptance.json`。两平台各执行 230 个 Java 测试与 41 个 Python 检查，Windows 的一个 POSIX 用例按平台跳过。
- 本轮完整 Windows Maven `verify` 通过：230 个 Java 测试，0 失败、0 错误、1 个 POSIX 平台跳过；44 个 Maven SBOM 组件许可材料完整。protobuf、SLF4J、jsoup 三份实际保存的文本与固定提交的上游原文逐字节一致，证据 `tools/isolated-controller-verify-fixed.log`、`tools/fixed-maven-license-verification.json`。
- AUD-039 的真实 WSL 退出测试已连接一个不领取任何任务的 JIT runner，关闭控制器 stdin 后，Linux 启动器按预期返回 130、临时目录已删除、GitHub 注册已移除；未触发或取消任何 CI 作业。证据 `tools/controlled-runner-stop.json`。普通 push 的端到端验证仍以新提交运行结果为准。
- AUD-037 后续核对遇到 protobuf-java 4.36.2 和 slf4j-api 2.0.19 的 OSI 通用许可页返回 403；分别改为相应 release tag 的固定提交中原始 LICENSE / LICENSE.txt，保留 Google/QOS.ch 署名及原文。修改前 Java 的 230 个测试已经通过，构建在许可收集阶段失败，日志 `tools/isolated-controller-verify.log`。
- AUD-038 的修改前后日志为 `tools/media-locale-before.log`、`tools/media-locale-after.log`。AUD-039 的 6 个控制器回归覆盖外部仓库/PR、分支与提交错配、重试变化、重复任务、错误标签、错误注册清理、runner 退出与任务结果区分、凭据不写入记录及本机互斥锁；共 41 个 Python CI 检查通过，actionlint 与 Bash 语法检查通过。
- `d63bff5` 的[完整 CI 35617005911](https://github.com/huaaudio/NeoMusicBot/actions/runs/35617005911) 已通过，两个实际发行包和媒体报告已下载核对。Windows 的 363 个 Cargo 源码包、642 份原始声明和 10 份补充文本，以及两平台 jsoup 固定许可文本再次核验通过，证据 `tools/ci-success-d63bff5/verified-materials.json`。同提交的普通 push 运行因托管音源拒绝而失败，不能混淆两次执行的结果。
- AUD-037 本地完整 Windows 构建通过：230 个 Java 测试，0 失败、0 错误，1 个 POSIX 测试按平台跳过；44 个 Maven SBOM 组件均有保存的许可文件。jsoup 1.23.2 实际下载文本与固定上游 LICENSE 字节完全一致，SHA-256 为 `f5d724c5818010c61bff2e177f5b6452434bc054807522bf241940bca8b1d6b1`；证据 `tools/jsoup-license-verify.log`、`tools/jsoup-license-verification.json`，对应提交的云端验证另行记录。
- `49034a1` 的[完整 CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35578968982) 已成功：Windows/Linux 构建、27 个 Python 检查、Java 27 两平台测试、固定 Canvas 安装、解压后离线实际绘图/PNG 编码，以及三项锁定媒体检查均通过。三个 artifact 下载后外层哈希/大小匹配 GitHub，两个发行包再次通过发布关联和原生定义核验，媒体报告三项通过且源提交一致；证据 `tools/ci-success-49034a1/verified-artifacts.json`、`tools/canvas-materials-real.log`。临时 runner 已退出并移除，GitHub 注册数为 0。
- AUD-005 的 Windows Canvas 包级材料新增 34 份固定源码包及 55 份原始许可文件，覆盖全部 44 个实际 DLL，并将源码包配方与二进制 `.BUILDINFO` 哈希关联。提交 `be7c094` 的完整 CI [35611351107](https://github.com/huaaudio/NeoMusicBot/actions/runs/35611351107) 已通过，两平台与媒体 artifact 下载核验完成，Windows 实际 ZIP 中这些源码和许可材料逐项复核通过；证据 `tools/ci-success-be7c094/verified-materials.json`。
- AUD-005 的 Windows librsvg Rust 材料新增两份锁文件并集的 363 个原始 crate 源码包、642 份原始声明及 10 份补充文本；构建配方修改由相同 Cargo 版本重建，明确区别于上游留存锁文件和实际链接组件清单。35 个 Python 检查通过，新增用例覆盖锁图缺项、父包错配、源码/文本篡改、不安全 tar 项和 ZIP 迁移；实际 363 个源码包及文件校验通过，日志 `tools/librsvg-material-tests.log`、`tools/librsvg-materials-real.log`。整份发行材料审查继续，详见[材料记录](distribution-licenses.md)。
- AUD-036 本地验证：27 个 Python CI 测试通过，包括下载损坏、缺失/多余/重复 native 文件、路径越界、链接/特殊文件、内容篡改、平台错配、配置重新启用安装脚本和 ZIP 迁移后的核验。日志 `tools/canvas-native-tests.log`。
- 两个官方 Canvas 原生包的全部 79 个文件与 `08aa25b` 成功 CI 实物逐字节一致；新安装器及重新打包/解压验证均通过。全新 Windows Deno 安装确认跳过 lifecycle 脚本，显式安装后 provider 离线启动、Canvas 像素绘制及 PNG 编码通过；日志 `tools/canvas-native-real.log`。首次只移除命令行授权仍会执行上游 `deno.json` 中已授权的脚本，现已按原始配置哈希校验后清空该列表，并保留 `deno.json.upstream`；新流程的云端跨平台验证尚待此次提交。
- `48e8d50` Windows 本地 `verify dependency:tree` 成功；122 测试，0 失败，1 个 POSIX 测试按平台跳过。日志 `target/modernize-verify.log` 与树 `target/modern-dependencies.txt` 为本地忽略文件。
- Maven 图仍由 JDA 引入 Jackson 2.22.2，与应用的 Jackson 3 包名不同；不能排除 JDA 需要的 Jackson 2。
- 许可证下载仍有 nanojson、youtube-source、base64 与 GNU 站点缺失/超时告警；此阶段构建成功不表示发行材料齐全。
- 配置与设置专项测试共 14 个全部通过；配置覆盖的 2 个失败与设置恢复的 3 个失败已在修改前复现。
- 此阶段完整 Windows 测试共 130 个，129 通过、0 失败、1 POSIX 测试按平台跳过；`target/review-settings-all.log`。
- 命名迁移后干净 Windows 构建共 132 个测试，131 通过、0 失败、1 POSIX 测试按平台跳过；日志 `tools/namespace-test.log`。新增变量别名/凭据冲突测试，并覆盖新旧前缀均不传给媒体子进程。
- 云端媒体失败只记录固定分类，不输出签名 URL、Cookie 或原始提取器日志；未通过的音源不标记为验证成功。
- 新增独立 `--self-test`：加载 JDAVE，并实际解码本项目生成的 0.4 秒 AAC、Opus、MP3 测试音。Windows 干净 `verify` 共 133 个测试，132 通过、1 POSIX 测试跳过；日志 `tools/bundle-codec-verify.log`。
- 移除旧仓库内 5 个非支持平台的陈旧 `libconnector.so`；支持的 Windows/Linux x86-64 原生解码器由固定版本的 Lavaplayer native 依赖提供。
- yt-dlp 独立资产的依赖包含 GPL 组件，不能只携带核心的 Unlicense；发行包补入上游第三方许可证汇总与校验后的源码压缩包。provider 保留完整上游源码和许可证。其余第三方材料覆盖仍在审查，AUD-005 尚未关闭。
- `48a4e71` Linux CI 的 133 个测试全部通过，最终 ZIP 收录 17,786 个文件并通过全新目录验证；Java 27 两平台通过。Windows 首轮在 Python 路径校验回归失败，已定位为 AUD-015，未宣称该提交跨平台全绿。
- 严格许可证收集在修正前失败于 base64 缺失 URL，修正后成功；本地 `verify_licenses.py` 确认 SBOM 的 44 个组件均有已保存的完整许可材料。6 个 CI 验证器测试通过。
- Shade 产物与测试 classpath 中的运行时 JAR 逐项比对，30 份上游 LICENSE/NOTICE 资源全部保留；`3970117` 的两平台 CI 均通过许可覆盖与声明保留检查。
- 更新检查专项 7 个测试通过；完整 Windows `verify` 共 138 个测试，137 通过、1 个 POSIX 权限测试按平台跳过，许可证收集及打包成功；日志 `tools/update-full-verify.log`。
- `d4a8296` 云端两平台构建/完整发行包、Java 27 测试全部成功；在线探测仍为 YouTube 两模式 authentication-required、Bilibili access-denied，没有绕过失败门禁。
- `f616f13` 云端 Windows/Linux 完整发行包及 Java 27 两平台测试全部通过；同次锁定在线探测仍为 YouTube 两模式 authentication-required、Bilibili access-denied。
- 本地 Windows 的 `f616f13` 运行时代码（JAR SHA-256 `4ef6ce72995e9e0950203a9540527969fe57052cabcbf66fa3db94c472aff9b8`），配合 yt-dlp 2026.08.19 / Deno 2.9.7，在无 Cookie、无 provider 的匿名模式下，Bilibili、指定第二 P、YouTube 三项均由实际音源适配器解码出 10 帧；日志 `tools/online-audit/java-probes.txt`。这是本地网络短时解码证据，不替代云端门禁或 Discord 语音测试；复现方式见 [在线媒体验收](media-validation.md)。
- 生命周期回归修改前 8 个测试中 7 个失败；修复后连同既有播放器事件/停止测试共 11 个通过。新增独立子 JVM 退出验证后，完整 Windows 测试共 146 个，145 通过、1 个 POSIX 测试跳过；日志 `tools/lifecycle-before.log`、`tools/lifecycle-after.log`、`tools/lifecycle-full.log`。

- `7bbdba3` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35565880533) 的 Windows/Linux 构建与完整 ZIP 均成功，Linux Java 27 成功；Windows Java 27 在 AUD-024 所述测试时序失败。在线探测仍为 YouTube 两模式 authentication-required、Bilibili access-denied。
- 13 项间接依赖升级后，完整 Windows `verify dependency:tree` 共 155 个测试，154 通过、1 个 POSIX 测试跳过；日志 `tools/transitive-full-final.log`。SBOM 仍为 44 项，全部有已保存许可；31 份上游许可/声明完整保留；6 个 Python CI 验证器测试通过。
- 升级后本地打包 JAR 的 `--self-test` 全部通过，包括新增 JNA/Opus 和 JDA RTP 加密检查；实际在线音源 Bilibili、第二 P、YouTube、SoundCloud 均解码出 10 帧，无 Cookie/provider。JAR SHA-256：`bf13d9c46de08bfbfbf836cae379b2dc29e0ad8ab9d974c9112bfc505ec01e3c`；脱敏记录 `tools/online-audit/java-probes-transitive.txt`。仍未测试真实 Discord 语音。
- `b877a29` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35566990914) 的 Windows/Linux 构建、完整发行包及 Java 27 检查全部通过，包含新增 Opus/RTP 自检。锁定在线探测仍为 YouTube 两模式 authentication-required、Bilibili access-denied，整次流水线未通过。
- 跳歌修复的 22 个专项测试全部通过；完整 Windows `verify` 共 161 个测试，160 通过、1 个 POSIX 测试跳过，许可证收集及 SBOM 生成成功。修改前后日志分别为 `tools/skip-vote-before.log`、`tools/skip-vote-after.log`，完整日志 `tools/skip-vote-full.log`；云端待回归。
- `cc185b0` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35567577355) 的 Windows/Linux 构建、完整 ZIP 与 Java 27 检查全部通过；锁定媒体检查仍为 YouTube 两模式 authentication-required、Bilibili access-denied。
- 发布校验新增 6 个测试方法，覆盖来源提交/仓库/分支/工作流、测试版版本、包内外 SBOM、工具和报告篡改、在线门禁、资产下载完整性及 Release 状态；连同既有验证器共 12 个 Python 测试通过。actionlint 1.7.12 验证两份工作流，Bash 语法检查通过。平台产物关联检查已加入正常构建，待云端实物回归；尚未创建任何测试版标签或 Release。流程见 [发布说明](prerelease-process.md)。
- `ba2fd8f` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35568207876) 四个 Java 构建均被旧的 `ReleaseLauncherPolicyTest` 字符串断言拦住：它仍只在 YAML 中寻找已迁入 Python 的内联媒体校验。已改为检查工作流实际调用验证器、创建标签及公开发布的顺序，并保留严格媒体失败行为测试；该 Java 类的 4 项本地测试通过（`tools/release-policy-after.log`），云端待回归。

- `c8c97c1` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35568452617) 的 Windows/Linux 构建、完整发行包和 Java 27 检查全部通过；两个平台均用实际 ZIP 通过新增的发布产物关联校验。锁定在线探测仍为 YouTube 两模式 authentication-required、Bilibili access-denied；没有创建测试版。
- 歌单异步加载回归连同串行执行器、播放会话与队列专项共 27 项通过；完整 Windows `verify` 共 167 项，166 通过、1 个 POSIX 测试按平台跳过。有效故障基线为 `tools/playlist-callback-before-controlled.log`，修复后为 `tools/playlist-callback-after.log`、`tools/playlist-callback-full.log`。拒绝提交用例在真实 Lavaplayer 管理器关闭后显式设置 `AbortPolicy`，准确触发拒绝分支；不声称正常 `shutdown()` 一定产生拒绝，其默认队列策略可能仍接受无工作线程的任务。

- `4dfda65` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35569343813) 的 Windows/Linux 发行包和 Java 27 两平台检查全部通过；锁定在线媒体检查仍未通过。
- 数值配置专项 34 项测试全部通过；完整 Windows `verify` 共 193 项，192 通过、1 项 POSIX 测试按平台跳过，SBOM 与许可证收集成功。故障基线 `tools/numeric-settings-before.log`（29 项中 22 项失败），修复后 `tools/numeric-settings-after.log`、`tools/numeric-settings-full.log`；全量测试包含实际 Slash 投票用例。

- provider 清单在固定提交/Deno 的实际 Windows 安装树上生成成功，包含 304 个包；新增 scope/嵌套/缓存、锁版本不匹配、元数据冲突，以及真实 ZIP 解压迁移后材料被修改/删除的回归。15 个 Python CI 测试、两份工作流 actionlint、Linux 组装脚本语法检查通过；清单明确不声明许可审查完成，实际两平台发行包回归待执行。

- `98ede80` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35570293384) Windows/Linux 完整 ZIP、原生自检、provider 清单与干净解压复核，以及 Java 27 两平台检查均通过；锁定在线媒体检查失败，未发布测试版。
- AUD-005 继续补齐 13 份原始第三方许可材料，并建立版本和字节哈希映射；实际 Windows 的 304 包安装树补充材料校验通过。新增补充材料版本变化、路径越界、篡改、缺失及真实 ZIP 迁移后的校验。独立工具全量材料审查仍未完成，详见 [材料记录](distribution-licenses.md)。

- `06217bb` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35571073576) 两平台发行包均通过 13 份许可补充材料、provider 清单、原生自检与干净解压复核；Java 27 两平台通过。锁定在线媒体检查仍失败。
- 无人频道快照回归修复前 3 项中 1 项报空指针，修复后全部通过；完整 Windows `verify` 共 196 项，195 通过、1 项 POSIX 测试按平台跳过。日志 `tools/alone-snapshot-before.log`、`tools/alone-snapshot-full.log`。Discord 实际连接/重连验收仍未进行。

- `3311f71` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35571567405) 的 Windows/Linux 完整发行包与 Java 27 两平台检查均通过；锁定在线媒体仍为 YouTube 两模式 authentication-required、Bilibili access-denied。
- 时间解析故障基线 22 项中 12 项失败（`tools/seek-time-before.log`）；最终完整 Windows `verify` 共 213 项，212 通过、1 项 POSIX 测试按平台跳过（`tools/seek-time-full-final.log`）。覆盖 Long.MAX_VALUE 毫秒边界、半毫秒舍入、单位溢出、非有限/科学计数法/十六进制输入、空输入及正常相对跳转；最新版云端回归待完成。

- `a8e1992` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35572454165) 的 Windows/Linux 完整发行包与 Java 27 两平台检查均通过；锁定在线媒体检查仍失败。
- 桌面控制台基线 6 项中 4 项失败（`tools/gui-console-before.log`），修复后全部通过；新增大于内部缓冲区的 UTF-8 写入用例后，完整 Windows `verify` 共 220 项，219 通过、1 项 POSIX 测试按平台跳过（`tools/gui-console-full.log`）。测试使用真实 Swing 文档和事件线程，覆盖清空、关闭、逐字节/数组偏移、所有字符分块边界、混合换行及多行裁剪；未将这些测试视为整套桌面界面人工验收。

- `d6d0fac` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35572821137) 的 Windows/Linux 完整发行包与 Java 27 两平台检查均通过，包含控制台回归；锁定在线媒体检查失败。
- 名称复核将源码 Manifest 与 Shade 入口统一为新主类，保留旧 main 转发入口；移除无调用的旧公共/认证机器人限制方法。重新 `package -DskipTests` 成功，JAR 的新入口和旧入口均输出 `NeoMusicBot 0.4.5`；此步骤没有重复执行全量测试。JAR SHA-256 为 `88f37ee9af3e3c6a071a54b541d5f248e79c77e29a1d3c4fce47f8879804f523`，证据 `tools/branding-package.log`、`tools/branding-entrypoints.json`。
- 同一 JAR 的离线原生/音频自检全部通过。初次在受限沙箱中使用系统临时目录时 JDAVE 报无法打开 DLL；改用工作区临时目录后通过，再以正常权限使用系统临时目录也通过，定位为执行环境限制。保留失败及两种成功日志 `tools/branding-native-failure.log`、`tools/branding-self-test-workspace-temp.log`、`tools/branding-self-test-system-temp.log`，未削弱自检。
- 已准备 [测试版说明草稿](prerelease-notes.md)，包括功能、迁移、测试范围和限制；未确定测试版标签，未执行发布。

- `39f46bc` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35573383714) 的 Windows/Linux 完整发行包及 Java 27 两平台检查均通过；锁定媒体检查仍失败。
- provider 运行配置的 5 个新增测试覆盖源提交/数据指纹、运行根依赖、包版本/完整性/依赖记录变化、原始文件字节保留及 ZIP 解压迁移后篡改；共 21 个 Python CI 测试通过。Bash、PowerShell 语法及 3 份工作流 actionlint 通过。固定 provider 提交的实际安装和 `--frozen` 缓存成功，183 个包，离线启动版本为 2.0.0；不会把此结果视为在线 token 生成已验证。
- 同一运行配置经正式预处理程序从原始源码生成、安装并缓存后，独立 provider ZIP 含 12,069 个文件；全新目录解压后哈希、原始/运行配置和清单验证通过，在全新 home 与 `--cached-only --deny-net` 下启动成功。ZIP SHA-256 为 `ad7a6f0e4b52e91a257a1707b847cba85d3975f5a9629b732bf3a3b03cfc7519`；证据 `tools/distribution-license-audit/provider-runtime-final/report.json`，完整应用两平台 ZIP 仍待云端回归。

- `1133449` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35574463867) 的 Windows/Linux 完整发行包与 Java 27 两平台检查均通过，包含新的运行配置、固定依赖、原始材料保留和干净目录 provider 启动验证；锁定在线媒体仍失败。
- 日志脱敏回归使用实际 Slash 响应失败回调和 Logback 异常链格式化，注入的均为虚构测试标记；修改前 1 个用例的 3 个泄露断言均失败（`tools/slash-logging-before.log`）。修复后完整 Windows `verify` 共 221 项，220 通过、1 项 POSIX 测试按平台跳过（`tools/slash-logging-full.log`）；未声称已在真实 Discord 上触发网络失败验收。

## 全模块检查覆盖

- `08aa25b` [完整 CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35576688077) 已成功：Windows/Linux 的完整发行包、两平台 Java 27，以及锁定媒体 job 全部 success。GitHub 实际派发的媒体 job `106260754831` 报告 youtube.anonymous、youtube.mweb-provider、bilibili.anonymous 三项 passed；使用固定工具、空白 HOME 和新的安装目录，无 Cookie，未放宽检查或延长媒体超时。runner 执行一个 job 后退出，注册已清理，独立临时目录已删除；本地记录 `tools/isolated-media-run/state.json`。这是开发提交的 CI 验证，不是测试版已发布，最终版本仍须重新运行同样检查。

- 为 AUD-001 的托管网络拒绝提供可复现执行路径：默认分支的手动 CI 可选择唯一标签的一次性媒体 runner，push/PR/其他分支仍使用托管 runner。Ubuntu WSL 的真实 bubblewrap 预检加载官方 SHA-256 固定的 runner 2.337.0 成功，确认私人目录不可见、系统程序只读，预检阶段未注册或安装服务（`tools/media-runner-preflight.log`）；Bash 语法及 actionlint 通过。后续实际派发和整次 CI 成功证据见上一条，详见[执行说明](isolated-media-ci.md)。
- 上述成功 CI 的两个平台 artifact 与媒体 artifact 已下载，外层 SHA-256/大小均与 GitHub 元数据一致；两个实际发行 ZIP 再次通过 `verify_platform()` 的发布产物关联校验，媒体报告三项通过且源提交一致。证据 `tools/ci-success-08aa25b/verified-artifacts.json`。实际 Canvas 安装带入 Windows 44 个 DLL/Linux 25 个共享库，已明确纳入 AUD-005 材料范围；未因本地旧安装缺少这些文件而忽略，详见[材料审查](distribution-licenses.md)。

- AUD-005 新增两项明确声明 MIT 的运行包所对应的 SPDX 标准正文，原元数据和署名保留，未伪造上游版权声明；补充材料现为 14 份。21 个 Python CI 测试及实际 183 包安装树的版本/字节哈希映射复核通过（`tools/provider-license-standard-tests.log`、`tools/distribution-license-audit/provider-runtime-mit-inventory.json`）；原生/WASM 与独立工具材料审查继续进行。
- `aee1928` 固定工具的 Linux 本地探测在现有 Ubuntu WSL 执行：挂载盘目录下匿名 YouTube/Bilibili 通过，provider 模式为 network-timeout；复制到 WSL 原生临时目录后，同一套检查三项全部通过（`tools/wsl-media-audit/native-candidate.txt`）。未调整超时或放宽成功条件；由于网络时间点也不同，不能据此断言首轮失败仅由文件系统引起。本地结果不替代 GitHub 工作流或实际 Discord 语音验收。

- `aee1928` [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35575235177) 的 Windows/Linux 完整发行包与 Java 27 两平台检查均通过，包含日志异常链脱敏回归；锁定媒体仍为 YouTube 两模式 authentication-required、Bilibili access-denied。
- 歌曲活动名称的有效故障基线共 9 项，其中 6 项在 JDA 的真实名称校验处失败（`tools/nowplaying-before-controlled.log`）；修复后完整 Windows `verify` 共 230 项，229 通过、1 项 POSIX 测试按平台跳过（`tools/nowplaying-full.log`）。覆盖 ASCII/非 BMP 长标题、128 个 emoji 的合法边界、空白/null 标题、关闭显示、多服务器与停止播放；原始轨道标题保持不变。[JDA 活动名称规则](https://docs.jda.wiki/net/dv8tion/jda/api/entities/Activity.html#listening(java.lang.String))与本地 6.7.0 的代码点校验一致；未把代理 Presence 的结果当作真实 Discord 展示验收。

- 歌单修复阶段（`7bbdba3`）完整 Windows `verify` 共 154 个测试，153 通过、1 个 POSIX 测试跳过；日志 `tools/playlist-full.log`。
  44 个 SBOM 组件许可覆盖、30 份上游许可/声明保留检查通过。
  该阶段本地 JAR SHA-256 为 `fdce6a91e19c0dfeb08e51b4e572294d0e8b0132fc3d0f24820634e7198c7d60`；
  更新后的在线探测程序再次通过 Bilibili、第二 P 和 YouTube 各 10 帧解码，无 Cookie/provider；
  日志 `tools/online-audit/java-probes-playlist.txt`。对应云端结果及后续依赖升级证据见上一节。

以下均需完成代码检查及相应的行为验证，不能仅由单元测试绿色代替审查：

- 启动、配置、更新检查、环境变量、启动脚本：配置及变量兼容、更新渠道/异常标签、入口失败状态已检查修复；Windows 含括号路径启动器实测通过；最新提交跨平台回归待完成。
- Discord Slash 注册、权限、交互生命周期、频道混淆：已检查多数命令入口、用户/服务器绑定和频道限制；修复 AUD-023/026；其余异步交互及频道混淆仍待复核。
- 语音连接、DAVE、重连、退出、播放器资源释放：已检查初始化/退出资源管理并修复 AUD-017/019；`b877a29` 新版 JNA/Opus 与 RTP 加密自检在本地及云端两平台通过；本轮真实 Discord 与 TCP 断线恢复验收已完成，范围见专门记录；不代表所有网络故障情形均已覆盖。
- 播放队列、公平排序、并发、暂停/重复/停止、状态恢复：已复核队列插入/快照和会话串行修改，修复 AUD-042；18 项队列、原子性及恢复回归通过，真实播放控制验收通过；最终提交跨平台回归待完成。
- Bilibili 分 P、短链接、媒体 URL、Cookie 与子进程：已有基线测试，需新版工具集成验证。
- YouTube、SoundCloud、Discord 附件、播放列表：新版工具/依赖下，本地匿名 YouTube 与 SoundCloud 短时解码通过；歌单存储及 owner 修改已检查并修复 AUD-020/021/022，异步加载审查修复 AUD-028；其余源仍待复核。
- 配置与服务器设置持久化、备份和故障处理：已检查并修复 AUD-004/007/008/009/029，专项测试通过；最终平台 CI 待复核。
- CI、SBOM、许可证、哈希、打包、发布门禁：进行中。
- 桌面控制台：已复核日志解码与行数限制并修复 AUD-032；GUI 关闭继续使用既有后台有序 shutdown，完整桌面交互人工验收尚未进行。

- AUD-041 的故障基线为 `tools/audio-logging-before.log`：1 个用例包含 4 个失败断言；修复后播放生命周期、连接状态、会话原子性、恢复及脱敏共 20 项测试通过，日志 `tools/audio-logging-after.log`。本轮为本地 Java 行为验证，尚不代表该修复的两平台最终发行或真实 Discord 验收通过。


- 本轮已在授权 Discord 频道完成真实 Slash 注册、Bilibili 可听播放、两首自动衔接、指定分 P、跳过、单曲循环、暂停恢复、停止离开、至少十分钟持续播放及无人退出的用户验收。补充探针观察到 DAVE 协议 1 的就绪转换与实际扩长加密帧；真实语音 TCP 故障后，项目连接监听器经历 `CONNECTED → RECONNECTING → CONNECTED`，保留同一轨道并继续推进。详见 [语音验收记录](discord-voice-acceptance.md)。证据限定于所记载的代码/依赖组合与故障场景，最终预发布资产仍需对应验证。

- AUD-042：`tools/queue-concurrency-before.log` 中公平插入与清空竞争复现 `IndexOutOfBoundsException`；修复后 `QueueConcurrencyTest,FairQueueTest,GuildPlaybackSessionAtomicityTest,PlaybackResumeTest` 共 18 项全部通过（`tools/queue-concurrency-after.log`）。改动仅补齐同步约定，未改变公平排序；此证据不表示生产 Slash 已发生相同故障。

### 2026-09-22 补充代码复核：媒体边界与异步控件

基于 `c21b0ad` 复核以下实际执行路径，而非仅依据测试数量：

- `DiscordAttachmentAudioSourceManager`：首次加载和反序列化均验证 HTTPS、Discord CDN 主机、附件路径及公网地址；采用只接受直接音频的容器表，拒绝返回间接播放列表引用。
- `FilteredHttpAudioSourceManager`、`PublicMediaHttpContextFilter` 和 `YtDlpAudioSourceManager`：播放请求使用 GET/HEAD、公网 URL 与连接时 DNS 检查，禁用 HTTP 重定向和 Cookie 管理；HLS 使用同一过滤接口，保留解析器所需请求头。
- `SafeM3uPlaylistContainerProbe`：只接受含 HLS 指令的 M3U，不把普通播放列表转换成可再次分发的任意音源引用。
- `YtDlpMediaResolver`：进程并发槽、关闭状态重查、有限输出捕获、超时/中断清理及 `finally` 释放已复核；后代进程采样及有界终止仍属于当前实现范围，不承诺覆盖恶意进程主动脱离父进程树的情形。
- `SlashCommandListener` 搜索与分页控件：复核 UUID 会话、动作白名单、服务器/用户绑定、TTL、当前音乐上下文、加载令牌、选择时原子移除，以及发布/确认失败和超时释放。此次为代码复核；未新增真实 Discord 搜索菜单和分页人工验收，不能把普通播放 Slash 验收扩大为这些控件的人工通过。

对应本地 Java 25 行为回归共 26 项全部通过，日志 `tools/module-review-media-interactions.log`；覆盖附件 URL/播放列表、HTTP 请求和重定向、HLS、Bilibili 身份与分 P、进程生命周期及版本探测最小环境。`SlashCommandSecurityTest` 在当前代码中只覆盖版本探测环境，不能将其名称视作全部交互权限已由测试覆盖。最终两平台完整 CI 仍须通过。


### GUI 与发布流程复核（2026-09-22）

发现并修复 AUD-043 / P3：`NeoMusicBot` 曾在主线程创建并显示 Swing 窗口。现在通过 `GUI.open()` 在事件线程创建和显示；调用方等待初始化完成，中断时保留中断状态并进入现有启动失败清理。后台 shutdown 与日志事件线程刷新保留。相关控制台、退出、设置故障恢复与歌单行为回归 31 项通过（`tools/gui-persistence-review-tests.log`）；这些测试不构成完整桌面人工操作验收，窗口视觉与交互手工验收仍未进行。

复核 `make-release.yml` 与 `prepare_prerelease.py`：只接受同一默认分支提交的成功 Build and Test；检查包内外 SBOM、JAR 版本、原生/工具/材料报告和三项媒体结果；标签原子创建，草稿上传后逐文件哈希回读，再以非 Latest 的 Pre-release 公开并再次回读。源码附件已固定并公开，但应用发布尚未执行。候选版本选为 `0.5.0-beta.1`，远端查询未发现占用该版本的标签；最终创建仍由原子标签接口保证不覆盖。

### 跨平台源码访问记录修复（AUD-044）

`34ce106` 的完整 CI 已通过，但实际产物在本地跨平台回读时发现 Windows 的两份自有源码访问 JSON 被 Git 转换为 CRLF；Linux 为 LF，因此严格原文字节检查拒绝 Windows 包。逐字节诊断确认差异仅为换行，JSON 内容一致。`.gitattributes` 现明确将 `shared-sources.json` 与 `canvas/windows-source-access.json` 固定为 LF；三种 `core.autocrlf` 配置下实际 Git 检出共六项字节比较通过（`tools/source-record-checkout-verified.json`）。保留原有字节检查，未用语义比较绕过。修复后的提交必须重新完成完整 CI 和两平台产物回读，旧候选不发布。
