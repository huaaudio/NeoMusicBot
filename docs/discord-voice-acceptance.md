# Discord 语音验收记录

2026-09-22 在用户授权的服务器及频道进行初步真实验收。凭据仅从授权的本地配置读取，
原配置不修改，测试实例使用独立目录，未将 Token 写入该目录或报告。

## 本轮范围

运行代码为 `5c0a3d2f9f106710f3c866289e76eb624ce582e1` 的本地编译类，
运行依赖来自已通过实物回读的 `a10d3f4` Windows CI 包。
这不是最终预发布资产的验收；最终同一提交发行包仍须独立验证。
本地证据保存在 `tools/discord-live-5c0a3d2/acceptance.json`，包括编译类哈希索引、
实例 PID、分组问题、用户反馈与尚未验证项。公开文档不保存服务器成员信息或凭据。

## 已取得证据

- 正式应用成功登录 Discord，指定测试服务器的 Slash 命令注册成功。
- 初步日志只观察到 MLS 会话创建。随后使用同一已验证 JAR 的 JDA/JDAVE 做带观测的真实频道探针，记录服务器选择协议 1、一次就绪转换，以及 936 个从 3 字节 Opus 静音帧扩为 15 字节的成功加密帧；共 950 次成功处理、0 失败，结束时仍连接。探针继承实际 `JDaveSession` 并完整委托操作，仅记录版本、计数与长度；不输出密钥或包内容，证据 `dave-frame-probe.log`。这补充了本轮握手证据，不替代最终资产验收。
- 用户确认第一首 Bilibili 音频可听、第二首可入队，暂停及恢复正常。
- 用户确认两首自然连续播放、指定第 2 P、强制跳过、单曲循环，以及停止并离开频道正常。
- 定向关闭测试进程的一条原有 Discord TCP 连接后，观察到新连接，用户确认 `/ping` 响应及音乐正常。
  Windows `SetTcpEntry` 初次返回 317，提升权限后返回 0；未更改网卡、防火墙或 WSL。
  该 TCP 连接的网关/REST 角色未独立确认；本项未中断语音 UDP，也没有捕获网关 RESUMED 或语音重连事件，因此不能据此判定语音断线重连已通过。

- 以 02:39:50 UTC 为保守起点，02:50:02 UTC 确认进程仍运行；随后用户确认至少十分钟持续播放正常，所有真人离开后 Bot 自动退出。退出日志包含 `DISCONNECTED_KICKED_FROM_CHANNEL`，单独该状态不能证明断连发起者，自动退出结果依据本轮用户观察。

- 补充的真实重连探针使用项目 `Bot`、`AudioHandler`、`GuildAudioConnectionListener` 和固定 Opus 静音轨道。定向关闭该独立进程的两条 TCP 连接（均返回 0）后，观察到 `ERROR_LOST_CONNECTION`，应用从 `CONNECTED` 转为 `RECONNECTING`，重新认证后回到 `CONNECTED`。最终仍是同一轨道，位置 64,760 毫秒，累计 3,225 个扩长后的加密帧、0 次加密失败；随后通过 `Bot.shutdown()` 正常断开并退出。证据 `tools/discord-reconnect-test/verification.json`。该探针使用仅 IPv4 的诊断 JVM，未模拟仅 UDP 丢包或长时间断网，不把有限场景扩大为所有网络故障均已覆盖。

## 最终发行关联

2026-09-22 已发布 [0.5.0-beta.1](https://github.com/huaaudio/NeoMusicBot/releases/tag/v0.5.0-beta.1)，发行提交 `3ff239c2ce385448d24ae603cb6d1ad2fc844be2`，来源 [完整 CI 35693959793](https://github.com/huaaudio/NeoMusicBot/actions/runs/35693959793)。Windows/Linux、Java 27 矩阵、三项在线媒体检查全部通过；两个实际 ZIP 的材料复核以及草稿/公开资产读回均通过。标签和资产不可变，未混入重新构建产物。

实际 Windows CI JAR 中所选语音、媒体、加密及原生文件共 3,937 项，与本轮人工验收所用依赖逐字节一致（`tools/voice-candidate-binary-comparison.json`）。应用后续修改为队列同步和 GUI 事件线程初始化，已完成回归。此关联不表示重复进行了一轮人工验收，以上有限故障场景的边界仍保留。
