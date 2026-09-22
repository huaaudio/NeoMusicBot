# NeoMusicBot 0.5.0-beta.1 测试版说明

NeoMusicBot 是 Huaaudio 独立维护的 Discord 音乐机器人，重点支持 Bilibili 视频与分 P。
项目源自 [jagrosh/MusicBot](https://github.com/jagrosh/MusicBot)，保留原作者署名、Git 历史
和 Apache 2.0 许可证；此测试版由 NeoMusicBot 维护者发布。

## 功能与主要变化

- Bilibili 视频链接、BV/av 编号、`b23.tv` 短链接和 `?p=2` 分 P 选择；每次请求加入一 P。
- YouTube 播放与搜索、必要时的 yt-dlp 回退、SoundCloud 和 Discord 音频附件。
- 原生 Discord Slash 命令，包含播放、搜索选择、队列分页、跳转、投票跳过、DJ 控制和服务器配置。
- JDA 6 与 JDAVE 的现代语音接口；发行包含对应平台的原生库。
- 每个服务器串行处理播放状态，异步加载可取消，停止或更换播放后不再接受过期结果。
- 配置写入保留已有设置和注释；服务器设置支持备份恢复，损坏且无法恢复时停止启动并保留文件。
- 本地歌单按原子替换保存；加载失败不会阻塞后续条目，重复回调不会重复入队。
- 修复无效投票比例和音量、跳转时间溢出、断开时的频道读取异常，以及桌面中文日志与行数限制问题。
- 两个平台使用固定版本和校验和组装完整发行包，提供依赖清单、许可证材料和干净解压验证报告。
- 运行 ZIP 保留程序和原始声明；Deno、yt-dlp、QuickJS 与 Canvas 的对应源码通过固定独立附件提供，正常运行无需下载。

相较旧项目，命令入口、播放状态管理、外部媒体解析、构建与发布流程均已有较大变化。
旧前缀命令不再注册；lyrics、OAuth 和 eval 配置不再支持。
机器人不会自动下载或替换自己的程序文件。

## 安装

安装 64 位 Java 25 LTS；Java 27 也纳入兼容验证。下载适合系统的完整发行包：

| 平台 | 文件 | 启动器 |
| --- | --- | --- |
| Windows x86-64 | `NeoMusicBot-windows-x86-64.zip` | `run_neomusicbot.cmd` |
| Linux x86-64 | `NeoMusicBot-linux-x86-64.zip` | `sh ./run_neomusicbot.sh` |

使用发布资产 `SHA256SUMS` 校验下载文件，完整解压后先运行启动器的 `--self-test`，
再用 `generate-config` 创建配置，设置 owner，并通过文件或环境变量配置一个 Bot Token 来源。
已有配置文件不会被 `generate-config` 覆盖。

完整 ZIP 包含 yt-dlp、Deno、provider 源码/依赖及启动器；无需另装 Python、Node.js 或 npm。
Java 仍需自行安装。GitHub 的 Source code 压缩包与单个 JAR 均不能替代完整发行包。
详见[安装、配置与故障排查](https://github.com/huaaudio/NeoMusicBot/blob/bili/docs/install-and-upgrade.md)。

## 从旧版本迁移

1. 停止旧进程，备份配置、`serversettings.json`、对应 `.bak` 和歌单目录。
2. 将新发行包解压到新目录，再迁入自己的配置和数据；不要混用旧版媒体工具和原生库。
3. 启动器和 JAR 改为 `run_neomusicbot.*` 与 `NeoMusicBot.jar`。
4. 新环境变量采用 `NEOMUSICBOT_*`；旧 `JMUSICBOT_*` 继续作为别名，但同一项不能同时设置两种前缀。
5. 旧 Java main 入口保留转发；自定义 Java 扩展需改用 `io.github.huaaudio.neomusicbot` 包名。
6. 在测试语音频道检查连续播放、分 P、跳过、停止和断线恢复，再迁移日常使用。

## 验证范围

本版提交：`3ff239c2ce385448d24ae603cb6d1ad2fc844be2`。来源构建：[Build and Test 35693959793](https://github.com/huaaudio/NeoMusicBot/actions/runs/35693959793)。两平台实际 ZIP 已完成下载与材料回读；发布流水线已核验上传后的草稿和公开下载。
以下几类证据分别记录，不能互相替代：

- Windows/Linux 的编译与自动化测试、Java 27 兼容测试。
- 实际发行 ZIP 的干净解压、启动、配置生成、provider 离线启动、许可证和依赖清单复核。
- JDAVE 原生加载、JNA/Opus 编解码、RTP 加密往返与篡改拒绝、AAC/Opus/MP3 解码。
- 使用最终工具集的在线媒体解析；实际音源适配器的短时解码与指定第二 P 检查另行标明。
- 真实 Discord 服务器的 DAVE 握手、可听性、连续播放与重连；尚未实际执行的项目明确标为未验证。

## 已知限制

- 这是测试版，不代表所有网络环境、长时间播放和断线情形均已验证。
- 支持普通 Discord 语音频道，暂不支持 Stage。
- Bilibili 关键词、收藏夹和整份合集导入尚未实现；关键词查询与 `/search` 搜索 YouTube。
- 在线音源可能受请求来源、限流、地区或账号权限影响；匿名访问不保证始终可用。
- 排队中的 Discord 附件链接可能在开始播放前过期；不支持任意本地文件路径作为媒体请求。
- 本次发行平台限 Windows/Linux x86-64；没有验证 ARM 或 macOS 发行包。

遇到问题请附系统、Java 版本、发行版本/提交、脱敏诊断和复现步骤，勿附 Token、Cookie 或签名媒体 URL。

### Windows 原生组件保留范围

Windows 保留已验证的官方 Canvas 3.2.3 配套原生库，其中 Cairo 为 1.18.4，尚未包含
1.18.6 的 Windows/DirectWrite、裁剪和 CFF 边界修复。图像功能检查通过不能证明全部缺陷
路径不可达；具体版本、验证范围和保留依据见 [原生升级记录](native-linux-upgrade.md)。

人工验收范围：已完成授权 Discord 频道的连续播放、分 P、播放控制、至少十分钟持续播放、无人退出及带观测的 DAVE/语音重连；GUI 完整桌面操作、搜索菜单和分页的真实 Discord 人工操作未单独验收。Linux 运行基线为 Ubuntu 24.04 x86-64、glibc >= 2.38；不支持 Alpine/musl，其他发行版未独立验证。

发行提交和构建号见 `NeoMusicBot-build.json`；平台验收报告、媒体报告和 `SHA256SUMS` 随本次发行提供。

真实语音验收所用依赖与本版 Windows JAR 的 3937 个所选语音/媒体/加密及原生文件逐项一致；应用后续变化为队列同步修复与 Swing 初始化线程修复，均已回归验证。此关联不等于重新执行一轮人工验收。

公开发布后全部资产已再次下载，文件集合、SHA-256、标签提交及构建来源均与上述成功 CI 一致。
