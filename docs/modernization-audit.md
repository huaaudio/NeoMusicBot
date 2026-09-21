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
| 干净发行包 | `f616f13` 的 [CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35564696089) 已通过 Windows/Linux 最终 ZIP 干净解压、启动器、配置、原生解码及 provider 离线启动；锁定在线媒体探测仍失败，因此整体尚未通过 |
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

直接依赖已完成一轮升级；[间接依赖记录](dependency-followups.md) 记录 13 项已升级版本、引入关系、
验证范围及保留 HttpComponents 4.x 的具体原因。最新代码的跨平台验收仍需完成。

| ID / 严重度 | 位置与触发条件 | 影响 | 处理与验证状态 |
| --- | --- | --- | --- |
| AUD-001 / P1 | `scripts/ci/media_canary.sh`：任一音源失败即退出且丢弃所有错误信息 | 无法区分工具参数、限流、认证或解析错误；其他音源未检查 | 已修复；3 个诊断测试通过。`48e8d50` 云端报告 YouTube 两种模式均 authentication-required，Bilibili access-denied；媒体验收仍未通过 |
| AUD-002 / P1 | `pom.xml` 与解析器：Jackson 3 移除旧 API、JUnit 6 不再自动执行 JUnit 4 | 编译失败或测试覆盖丢失 | 迁移 JSON API 与所有旧测试 imports/assumptions；Windows 122 测试数量保持一致，121 通过、1 POSIX 测试按平台跳过 |
| AUD-003 / P1 | `SlashCommandListener` 的 Slash 与选择控件入口：配置频道被删除或不可见时缓存返回 null | 频道限制被跳过 | 按持久化 ID 判断，管理员界面显示不可用而非 any；`SettingsChannelRestrictionTest` 验证缓存缺失与显式清除两种情况 |
| AUD-004 / P1 | `BotConfig.writeDefaultConfig()`：对已有配置执行生成 | 覆盖 Token 和用户配置 | `CREATE_NEW` 拒绝覆盖，CLI 返回失败；回归测试先复现再通过 |
| AUD-005 / 待核实 | 发行包：依赖许可证下载告警、缓存路径与旧原生文件 | 材料不完整或依赖开发/构建环境 | Maven 许可覆盖与两平台干净目录验收已通过；新增 provider 安装树/缓存依赖清单与解压复核，Windows 实物包含 304 个 npm 包；独立工具、native/WASM 及第三方源码/许可覆盖尚未完成，见 [材料记录](distribution-licenses.md) |
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

## 阶段验证证据

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

- 歌单修复阶段（`7bbdba3`）完整 Windows `verify` 共 154 个测试，153 通过、1 个 POSIX 测试跳过；日志 `tools/playlist-full.log`。
  44 个 SBOM 组件许可覆盖、30 份上游许可/声明保留检查通过。
  该阶段本地 JAR SHA-256 为 `fdce6a91e19c0dfeb08e51b4e572294d0e8b0132fc3d0f24820634e7198c7d60`；
  更新后的在线探测程序再次通过 Bilibili、第二 P 和 YouTube 各 10 帧解码，无 Cookie/provider；
  日志 `tools/online-audit/java-probes-playlist.txt`。对应云端结果及后续依赖升级证据见上一节。

以下均需完成代码检查及相应的行为验证，不能仅由单元测试绿色代替审查：

- 启动、配置、更新检查、环境变量、启动脚本：配置及变量兼容、更新渠道/异常标签、入口失败状态已检查修复；Windows 含括号路径启动器实测通过；最新提交跨平台回归待完成。
- Discord Slash 注册、权限、交互生命周期、频道混淆：已检查多数命令入口、用户/服务器绑定和频道限制；修复 AUD-023/026；其余异步交互及频道混淆仍待复核。
- 语音连接、DAVE、重连、退出、播放器资源释放：已检查初始化/退出资源管理并修复 AUD-017/019；`b877a29` 新版 JNA/Opus 与 RTP 加密自检在本地及云端两平台通过；语音重连状态机复核及真实 Discord 验收仍待完成。
- 播放队列、公平排序、并发、暂停/重复/停止、状态恢复：已有基线修复，需升级后复核。
- Bilibili 分 P、短链接、媒体 URL、Cookie 与子进程：已有基线测试，需新版工具集成验证。
- YouTube、SoundCloud、Discord 附件、播放列表：新版工具/依赖下，本地匿名 YouTube 与 SoundCloud 短时解码通过；歌单存储及 owner 修改已检查并修复 AUD-020/021/022，异步加载审查修复 AUD-028；其余源仍待复核。
- 配置与服务器设置持久化、备份和故障处理：已检查并修复 AUD-004/007/008/009/029，专项测试通过；最终平台 CI 待复核。
- CI、SBOM、许可证、哈希、打包、发布门禁：进行中。
- 桌面控制台：已复核日志解码与行数限制并修复 AUD-032；GUI 关闭继续使用既有后台有序 shutdown，完整桌面交互人工验收尚未进行。
