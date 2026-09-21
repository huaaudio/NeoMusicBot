# 安装、配置与升级 NeoMusicBot

NeoMusicBot 运行在自己的 Windows x86-64 或 Linux x86-64 电脑/服务器上，
通过 Discord 语音频道播放音乐。它不是 Bilibili 直播间聊天机器人。
当前开发与验证状态见 [审查记录](modernization-audit.md)，测试版以 Release 页的实际发布状态为准。

## 首次安装

1. 安装 64 位 Java 25 LTS。Java 27 已加入两平台 CI 兼容测试。运行 `java -version`
   确认终端实际找到的版本；设置 `JAVA_HOME` 时应指向 JDK 根目录。
2. 从 [Releases](https://github.com/huaaudio/NeoMusicBot/releases) 选择标记为 Pre-release
   的版本，下载匹配操作系统的完整 ZIP 和 `SHA256SUMS`。GitHub 自动生成的 Source code ZIP
   是源码，不包含运行所需的媒体工具。尚无测试版时可按 README 从源码构建，但单个 JAR 不包含外部工具。
3. 校验 ZIP 的 SHA-256。Linux 使用 `sha256sum NeoMusicBot-linux-x86-64.zip`；
   PowerShell 使用 `Get-FileHash .\NeoMusicBot-windows-x86-64.zip -Algorithm SHA256`，
   与 `SHA256SUMS` 中对应一行比较。
4. 解压完整 ZIP 到可写目录，保留 `tools`、`licenses` 等子目录。以下命令都在解压目录内运行。

Windows PowerShell：

```powershell
.\run_neomusicbot.cmd generate-config
```

Linux：

```sh
sh ./run_neomusicbot.sh generate-config
```

这会创建 `config.txt`，已存在时拒绝覆盖。修改配置中的 `owner` 为自己的 Discord 用户 ID。
自定义路径可通过 `NEOMUSICBOT_CONFIG` 设置；相对路径基于启动时的工作目录。

完整发行包自带 yt-dlp 独立可执行文件、Deno 和 provider 源码/缓存，运行时不需要另装
Python、Node.js 或 npm；Java 仍需按步骤 1 安装。不要删除以点开头的缓存目录。
第三方组件分别适用各自许可证，见 `licenses`、`sources` 和 `THIRD_PARTY_VERSIONS.txt`。

可先执行 `run_neomusicbot.cmd --self-test`（Linux：`sh ./run_neomusicbot.sh --self-test`）。
它不读取机器人凭据、不连接 Discord，检查 JDAVE 和内置测试音的 AAC、Opus、MP3 解码。
`--version` 显示当前版本，`--help` 显示参数。自检通过不代表在线音源可访问或 Discord 已有声音。
错误参数返回 2，配置生成失败或启动配置无效返回 1，便于服务管理器判断失败。

## Discord 应用

在 [Developer Portal](https://discord.com/developers/applications) 创建应用并取得 Bot Token。
使用服务器安装（Guild Install），安装链接包含 `bot` 与 `applications.commands` scope。
这些步骤的页面说明见 [Discord 官方入门](https://docs.discord.com/developers/quick-start/getting-started)。

本项目使用 Gateway 接收 Slash 交互，不需要设置 Interactions Endpoint URL，
也不需要 Message Content、Presence 或 Server Members 特权 Intent。
机器人需要在使用的频道拥有查看频道、发送消息、嵌入链接、附加文件、连接语音和说话权限；
无需给予 Administrator。只支持普通语音频道，暂不支持 Stage。

将 Token 单独保存在自己可读的文本文件中，仅一行，不要放进 Git 或反馈日志。
配置文件中的 `token` 保持 `BOT_TOKEN_HERE`。例如准备好文件后：

```powershell
$env:NEOMUSICBOT_DISCORD_TOKEN_FILE = 'C:\private\discord-token.txt'
.\run_neomusicbot.cmd
```

```sh
export NEOMUSICBOT_DISCORD_TOKEN_FILE="$HOME/.config/neomusicbot/discord-token.txt"
sh ./run_neomusicbot.sh
```

也可设置 `NEOMUSICBOT_DISCORD_TOKEN`，或在 `config.txt` 中配置 `token`，但三种来源只能选一种。
程序不会自动读取 `.env` 文件。服务管理器必须显式配置环境变量与工作目录。

## 播放与权限

先加入语音频道，再执行 `/play query:BV13x41117TL`。Bilibili 视频链接、BV/av 编号、
`b23.tv` 短链接均可；指定分 P 使用 `https://www.bilibili.com/video/视频编号?p=2`。
每次请求加入一 P；不指定时选择第一 P。关键词和 `/search` 当前搜索 YouTube。
Bilibili 关键词、收藏夹和整份合集导入尚未实现。

- `/queue`、`/now-playing`、`/seek`、`/skip` 用于查看及控制播放。
- `/dj` 包含暂停、继续、停止、循环和音量等控制。机器人 owner、Manage Server 管理员及配置的 DJ 角色可用。
- `/config` 需要 Manage Server，可配置文字/语音频道限制、DJ 角色、公平/线性队列和投票跳过比例。
- `/owner debug` 仅 owner 可用，输出脱敏诊断；工具 ready 表示本地工具可用，不代表在线音源或 Discord 可听性已验证。

全局 Slash 命令在启动后注册。开发时可设置 `NEOMUSICBOT_COMMAND_GUILD_ID` 仅注册到一个服务器；
正式使用应移除它。旧前缀命令已不再注册。

## 从旧版本迁移

1. 停止旧进程，备份 `config.txt`、`serversettings.json`、`.bak` 和自己的歌单目录。
2. 解压新版本到新目录，复制上述用户数据；不要把旧版 `tools` 覆盖到新目录。
3. 使用新的 `run_neomusicbot.cmd` / `run_neomusicbot.sh` 与相邻的 `NeoMusicBot.jar`。
4. 原有配置键、服务器设置文件和歌单格式继续支持。交互补填 owner/token 会保留已有配置和注释。
5. 环境变量新前缀为 `NEOMUSICBOT_`，旧 `JMUSICBOT_` 前缀仍可使用。后缀不变。
   同一变量只能设置一个前缀，两个都设置会报错；启动器不会用内置工具覆盖任一已设置路径。
6. Java 内部包迁移到 `io.github.huaaudio.neomusicbot`，主类为 `NeoMusicBot`。
   旧 `com.jagrosh.jmusicbot.JMusicBot` 仅保留 main 转发入口；自定义 Java 扩展需要更新 imports。
7. 本项目保留上游作者与 Apache 2.0 许可证，已取消针对特定第三方服务器的自动提示消息和自动退出行为。

升级后先在测试语音频道验证两首连续播放、分 P、停止、跳过及断线恢复。
如果回退，使用旧发行目录和升级前备份，避免混用两个版本的工具目录。

## 常见问题

| 现象 | 检查与处理 |
| --- | --- |
| 找不到 Java / UnsupportedClassVersionError | 检查 `java -version` 与 `JAVA_HOME`，使用 64 位 Java 25 或经过验证的更新版本 |
| 找不到 NeoMusicBot.jar 或媒体工具 | 下载完整平台 ZIP 并完整解压；从解压目录启动，勿只复制脚本/JAR |
| Token 配置错误 | 只保留一种来源；变量前缀不能重复；Token 文件必须可读且只有一行；Token 不是 Client Secret |
| 没有 Slash 命令 | 确认安装到服务器、包含 applications.commands scope，查看启动注册结果；检查开发服务器 ID |
| 无法进入或说话 | 检查机器人在目标频道的 VIEW_CHANNEL、CONNECT、SPEAK 以及频道覆盖权限；使用普通语音频道 |
| 配置频道不可用 | `/config show` 查看；管理员用 `/config text-channel` 或 `/config voice-channel` 设置新频道或 `clear`，不会自动解除限制 |
| 服务器设置损坏 | 程序尝试 `.bak` 恢复；两者都不可用时停止启动并保留文件。先备份现场，再修复 JSON/ID，勿直接删除数据 |
| 设置保存失败 | 检查磁盘空间、目录权限与占用；失败不会算作保存成功，恢复后可再次修改/正常关闭以重试 |
| YouTube 要求认证 / Bilibili 拒绝访问 | 可能受请求来源、限流、地区或账号权限影响。用自己的网络核实视频可用性；必要时配置自己有权使用的 Cookie 文件，不能保证匿名访问始终成功 |
| 加入频道却无声音 | `/owner debug` 检查版本及工具；核对防火墙/UDP 连接、语音状态和音源。原生加载或本地解码成功不能代替实际语音验证 |

Cookie 可选且默认关闭；仅通过相应的 `NEOMUSICBOT_*_COOKIES_FILE` 指向本地文件。
Linux 下 Cookie 文件须为当前用户所有、权限不向其他用户开放；文件要求详见配置错误提示。
提交问题时提供系统、Java、发行版本/提交、脱敏诊断和复现步骤，不要贴 Token、Cookie 或带签名的媒体 URL。
