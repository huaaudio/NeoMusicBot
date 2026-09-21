# 使用独立网络执行媒体 CI

GitHub 托管机器可能被媒体站点要求登录或拒绝访问。这样的失败仍会阻止发布。
仓库变量 `MEDIA_RUNNER_MODE=isolated` 启用默认分支 push 和手动运行的隔离媒体验收。
四个平台/Java 构建继续使用 GitHub 托管机器，媒体任务使用
`neomusicbot-media-运行编号-重试次数` 标签，由本机控制器领取并执行。
每次 push 无需再先跑失败的托管音源检查、然后另外手动重跑整条工作流。
相同分支和触发类型的新运行会取消尚未完成的旧运行，避免过时任务一直排队。

默认分支手动运行仍可通过 `media_runner` 显式指定其他一次性 runner 标签。
pull request 和其他分支不会自动使用本机执行器。未启用变量的仓库继续使用托管机器。

替换的是执行环境，检查内容、固定版本与哈希、成功条件、提交关联和发布门禁保持一致。
本地报告不能上传冒充 CI 报告；媒体步骤必须由 GitHub 派发，并在同一次工作流中产生报告。

## 启动自动领取控制器

此项目选择已有的本机 Ubuntu WSL 执行媒体任务。需要 Python 3.11+、Git 中已配置的
GitHub 仓库管理员凭据，以及下节所列的 WSL 工具。控制器不保存 API Token 或 JIT 配置。
在默认分支的干净工作区执行：

```powershell
python scripts/ci/serve_isolated_media.py --watch
```

默认使用现有的 `Ubuntu` WSL；发行版名称不同可加 `--distro 名称`。
Ubuntu 主机可以直接运行相同 Python 命令，控制器会调用本地隔离脚本。
控制器进程和电脑需要保持运行；Ctrl+C 可停止控制器，没有安装系统服务或开机自启项。
长期运行时应使用独立后台进程并保存进程号和日志路径；临时终端会话结束可能同时结束其子进程。
在 Windows 上使用 `Start-Process -WindowStyle Hidden` 启动时，指定 Python 的完整路径、
本仓库工作目录，并将标准输出和错误分别重定向到日志。检查进程号和命令行确认控制器存活，
不要只依赖以前写入的状态文件。本机当前采用这一方式，重启电脑后仍需重新启动控制器。
机器关机或控制器停止时，媒体任务会排队，**不会被当作通过**，也不能发布。
单次领取已经排队的指定运行可使用 `--run-id 运行编号`，而不加 `--watch`。

控制器核对本仓库、默认分支、干净 checkout、远程当前提交、工作流路径、任务名称、运行编号、
重试次数和唯一标签。只运行与本地已审核代码相同的提交；本地修改未提交、其他电脑的提交
尚未同步或切换到其他分支时不会领取。runner 下载和隔离预检后还会再次核对，防止过时任务被启动。
每个任务注册一个 JIT runner，将预期任务身份和一次性配置仅通过 stdin 传入；任务结束后注销，
下一任务重新建立隔离目录。唯一标签只负责调度，不能证明实际领取的是预期任务。
runner 开始执行工作流步骤前，还会运行只读挂载的 hook，逐项核对 GitHub 提供的仓库、
工作流路径与 ref、分支、提交 SHA、触发事件、运行编号、重试次数及 job ID。
任何字段缺失或不符都会暂停当前 Worker，并通知隔离环境外的启动器终止整个 runner。
hook 不直接返回失败后继续调度步骤，避免后续 `if: always()` 步骤仍被执行。
控制器按 GitHub 的实际 job 结果判断成功，不能把 runner 程序退出码 0 当作媒体验收成功。
操作记录和进程信息在忽略提交的 `tools/isolated-media-controller/`；锁文件存在本身不代表进程还活着。

Actions 中媒体任务长时间 `Queued` 时，检查控制器是否仍在运行、仓库变量是否启用、
本地分支和 HEAD 是否与 GitHub 一致，以及工作区是否干净。检查失败时仍应查看媒体报告中的具体分类；
更换执行网络并不保证所有音源始终可用。API 认证或注册清理失败会使控制器退出，处理后再启动。

## Ubuntu / WSL 一次性 runner

仓库提供 `scripts/ci/isolated_media_runner.sh`，供已有 Ubuntu x86-64 环境使用。
需要非 root 用户、可用的非特权用户命名空间、bubblewrap，以及脚本开头列出的常用命令。
它从官方版本下载固定哈希的 GitHub runner，在新建的 `/tmp/neomusicbot-runner.*` 目录运行。
它不会安装系统服务，退出后删除自己创建的目录。

也可以单独执行无凭据预检：

```sh
bash scripts/ci/isolated_media_runner.sh --check
```

预检加载实际 runner 程序，并确认 Windows 挂载盘、系统服务目录和用户 SSH 目录不可见，
系统程序目录和任务策略目录只读。运行时绑定独立 runner 目录、只读策略目录和拒绝信号目录，
创建空白 HOME 和临时目录，清空继承环境，
隔离进程与挂载命名空间；使用当前机器的网络，以便访问媒体服务。
这是对指定可信工作流的进程和文件系统隔离，不应拿它执行任意外部仓库代码。

手动使用自定义标签时，仓库管理员在隔离环境外完成以下操作（自动控制器已处理注册与清理）：

1. 为此次运行生成唯一标签，例如 `neomusicbot-media-` 加 32 位随机十六进制字符串。
2. 从当前默认分支触发 `Build and Test`，将此标签填入 `media_runner`。
3. 通过 GitHub 的仓库级 `actions/runners/generate-jitconfig` API 创建同名 JIT runner，
   标签数组仅含此次唯一标签；核对返回的 runner 名称、标签和 ID。
4. 根据已核对的 GitHub run 元数据，用 `verify_isolated_job.expected_job(run)` 生成预期策略。
   脚本输出就绪标记后，通过标准输入依次传入两行：策略 JSON、返回的 `encoded_jit_config`。
   每行以换行结束；不要仅传 JIT 配置。不要把管理员 API Token 放入 runner 环境、工作区或命令行，
   也不要输出 JIT 配置。优先使用上述控制器完成这一过程。
5. 等待整个工作流完成，核对提交 SHA、四个构建和三项媒体结果。
   JIT runner 只接受一个任务，完成后由 GitHub 注销；若启动失败或没有领取任务，
   管理员须按本次记录的 runner ID 检查并删除残留注册，不能只关闭本地进程。

脚本从就绪后开始最多运行 45 分钟；媒体 job 本身仍有 20 分钟上限。需要再次验证时创建新 runner，
不要复用先前 job 的工作目录。Java 构建在托管机器上使用工作流安装的 JDK；此脚本只运行媒体任务，
使用只读系统程序与新建的 HOME，不继承开发环境的缓存和私人配置。

官方参考：[JIT runner 与隔离](https://docs.github.com/en/actions/reference/security/secure-use#using-just-in-time-runners)、
[仓库 runner API](https://docs.github.com/en/rest/actions/self-hosted-runners#create-configuration-for-a-just-in-time-runner-for-a-repository)。
任务前 hook 见 [GitHub 文档](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/run-scripts)；
实际步骤顺序按固定的 [runner 2.337.0 源码](https://github.com/actions/runner/blob/v2.337.0/src/Runner.Worker/JobExtension.cs)核对。
