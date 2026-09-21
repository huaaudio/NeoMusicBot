# 在线媒体验收

`scripts/ci/OnlineMediaProbe.java` 是开发者手动运行的联网检查，需要 Java 25 或更新版本、
本次构建的完整 JAR，以及与发行包版本一致的 yt-dlp 和 Deno。它不登录 Discord，也不读取
BotConfig、私人配置或 Cookie；音源子进程沿用程序的最小环境规则。

在源码目录执行下面的命令，将路径替换为待验发行包中的实际路径。Windows 工具带 `.exe` 后缀。

```sh
java --enable-native-access=ALL-UNNAMED -cp "/path/to/bundle/NeoMusicBot.jar" scripts/ci/OnlineMediaProbe.java "/path/to/bundle/tools/yt-dlp" "/path/to/bundle/tools/deno"
```

可在末尾传入两个额外参数：插件目录和 provider home，以启用生产代码中的 provider 模式。
未传这两个参数时，只测试默认匿名解析。不要把 Cookie、Token 或带签名的媒体链接放到参数里。

检查使用实际的 Bilibili 与 YouTube 音源适配器，依次加载一个 Bilibili 视频、指定第二 P 的视频
和一个 YouTube 视频。每项必须通过元数据解析，再由 Lavaplayer 解码出至少 10 个非空音频帧。
分 P 项还检查解析后的媒体标识确实为第二 P。所有项目都会执行，任意一项失败则进程返回非零。
元数据加载每项最多等待 90 秒，解码每项最多等待 30 秒。

为了避免泄露签名 URL，探测关闭第三方日志，只输出固定的 `PROBE` 状态字段和异常类型。
记录验证时请同时记录 Git 提交、JAR 的 SHA-256、系统与工具版本；不要提交原始提取器日志。

这项测试只覆盖短时间解析和解码，不证明整首播放、拖动、连续队列、重连或 Discord 频道可听性。
实际语音仍需在测试服务器检查连续播放、分 P、跳过、停止和断线恢复。
本地网络成功也不能替代 GitHub Actions 的在线检查；请求来源、认证和访问限制可能造成不同结果。
