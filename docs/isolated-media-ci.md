# 使用独立网络执行媒体 CI

GitHub 托管机器可能被媒体站点要求登录或拒绝访问。这样的失败仍会阻止发布。
`Build and Test` 支持在默认分支手动运行时，通过 `media_runner` 指定一次性 Linux x86-64
runner 的唯一标签。四个平台/Java 构建继续使用 GitHub 托管机器，只有锁定媒体检查使用该标签。
普通 push、pull request 和非默认分支的手动运行都忽略此输入。

替换的是执行环境，检查内容、固定版本与哈希、成功条件、提交关联和发布门禁保持一致。
本地报告不能上传冒充 CI 报告；媒体步骤必须由 GitHub 派发，并在同一次工作流中产生报告。

## Ubuntu / WSL 一次性 runner

仓库提供 `scripts/ci/isolated_media_runner.sh`，供已有 Ubuntu x86-64 环境使用。
需要非 root 用户、可用的非特权用户命名空间、bubblewrap，以及脚本开头列出的常用命令。
它从官方版本下载固定哈希的 GitHub runner，在新建的 `/tmp/neomusicbot-runner.*` 目录运行。
它不会安装系统服务，退出后删除自己创建的目录。

先执行无凭据预检：

```sh
bash scripts/ci/isolated_media_runner.sh --check
```

预检加载实际 runner 程序，并确认 Windows 挂载盘、系统服务目录和用户 SSH 目录不可见，
系统程序目录只读。运行时仅绑定独立 runner 目录，创建空白 HOME 和临时目录，清空继承环境，
隔离进程与挂载命名空间；使用当前机器的网络，以便访问媒体服务。
这是对指定可信工作流的进程和文件系统隔离，不应拿它执行任意外部仓库代码。

仓库管理员在隔离环境外完成以下操作：

1. 为此次运行生成唯一标签，例如 `neomusicbot-media-` 加 32 位随机十六进制字符串。
2. 从当前默认分支触发 `Build and Test`，将此标签填入 `media_runner`。
3. 通过 GitHub 的仓库级 `actions/runners/generate-jitconfig` API 创建同名 JIT runner，
   标签数组仅含此次唯一标签；核对返回的 runner 名称、标签和 ID。
4. 将返回的 `encoded_jit_config` 直接通过标准输入交给本脚本。不要把管理员 API Token
   放入 runner 环境、工作区或命令行，也不要输出 JIT 配置。
5. 等待整个工作流完成，核对提交 SHA、四个构建和三项媒体结果。
   JIT runner 只接受一个任务，完成后由 GitHub 注销；若启动失败或没有领取任务，
   管理员须按本次记录的 runner ID 检查并删除残留注册，不能只关闭本地进程。

脚本从就绪后开始最多运行 45 分钟；媒体 job 本身仍有 20 分钟上限。需要再次验证时创建新 runner，
不要复用先前 job 的工作目录。Java 构建在托管机器上使用工作流安装的 JDK；此脚本只运行媒体任务，
使用只读系统程序与新建的 HOME，不继承开发环境的缓存和私人配置。

官方参考：[JIT runner 与隔离](https://docs.github.com/en/actions/reference/security/secure-use#using-just-in-time-runners)、
[仓库 runner API](https://docs.github.com/en/rest/actions/self-hosted-runners#create-configuration-for-a-just-in-time-runner-for-a-repository)。
