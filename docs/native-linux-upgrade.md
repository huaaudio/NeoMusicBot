# Linux 原生库源码构建

2026-09-22 已选择自建 Linux 原生资产 r2，并接入应用组装及材料门禁。
2026-09-22 已发布 [0.5.0-beta.1](https://github.com/huaaudio/NeoMusicBot/releases/tag/v0.5.0-beta.1)，发行提交 `3ff239c2ce385448d24ae603cb6d1ad2fc844be2`，来源 [完整 CI 35693959793](https://github.com/huaaudio/NeoMusicBot/actions/runs/35693959793)。Windows/Linux、Java 27 矩阵、三项在线媒体检查全部通过；两个实际 ZIP 的材料复核以及草稿/公开资产读回均通过。标签和资产不可变，未混入重新构建产物。下文早期记录保留当时状态。

## 已验证的升级

| 组件 | 当前 Linux 发行资产 | 本次源码构建 |
| --- | --- | --- |
| Canvas | 3.2.3 | 3.2.3，重新编译 C++ addon |
| Cairo | 1.16.0 | 1.18.6 |
| librsvg | 2.52.8 | 2.63.2，启用 pixbuf 与 AVIF |
| Pango | 1.48.0 | 1.58.2 |
| GLib | 固定旧资产中的传递依赖 | 2.90.0 |
| HarfBuzz | 固定旧资产中的传递依赖 | 14.5.0 |
| Fontconfig | 固定旧资产中的传递依赖 | 2.18.3 |
| FreeType | 2.10.4 | 2.14.3 |

[`inputs.json`](../src/provider-runtime/native-linux/inputs.json) 保存八份原始源码的
官方下载地址和 SHA-256，以及构建工具、Ubuntu SDK 包的固定版本、大小和校验和。
Canvas npm 源码包还与运行锁文件中的 SHA-512 integrity 核对过。
按[上游版本规则](https://gnome.pages.gitlab.gnome.org/librsvg/devel-docs/supported_versions.html)，
librsvg 2.63.x 属于稳定系列；微版本 90 及以上才是下一系列的开发版。

Pango 1.58.2 需要 GLib ≥ 2.88、HarfBuzz ≥ 11、Fontconfig ≥ 2.17 和 Cairo ≥ 1.18，
所以同时重建字体栈。FreeType/HarfBuzz 的依赖环先使用 SDK 引导，再将 FreeType
针对最终 HarfBuzz 重新编译。Cairo 保留字体、PNG、PDF/SVG 等离屏功能，禁用 X11/XCB 后端。
上游完整测试套件尚未执行，下面的功能检查不等于上游全部测试通过。

## 构建配方

构建基线是 Ubuntu 24.04 x86-64、GCC 13 和常规编译工具。Rust 1.98.1、Meson 1.12.0、
cargo-c 0.10.25、Node 24.21.0 及 113 个 Ubuntu SDK 包均按固定哈希下载，
只解压到专用工作目录，不安装到宿主系统。node-gyp 13.0.2 / node-addon-api 7.1.1
使用独立 npm 锁文件，禁用 npm lifecycle 脚本；Canvas 编译显式使用固定 Node SDK 头文件。
librsvg 沿用上游 `cargo cbuild --locked` 配方。

[`build_linux_canvas.py`](../scripts/native/build_linux_canvas.py) 与
[`linux_canvas_steps.sh`](../scripts/native/linux_canvas_steps.sh) 提供仓库内的构建入口：

```sh
python3 scripts/native/build_linux_canvas.py \
  --work-dir "$HOME/.cache/neomusicbot-native-build/build-1" \
  --cache-dir "$HOME/.cache/neomusicbot-native-build/downloads" --jobs 4
```

基础工具需有 `gcc`、`g++`、`make`、`ninja`、`pkg-config`、`dpkg-deb`、`readelf`、
Python 3.11+、Bash 及常规 Unix 工具；隔离验收另需可用的 bubblewrap。
构建使用专用 HOME/npm/Cargo 缓存，pkg-config 只读取 SDK 元数据。
此配方依赖指定 OS 和编译器基线，不宣称整个编译过程完全隔离或逐字节可复现。
输入改变时必须选择新的工作目录；中断的准备过程也不会被静默当成准备完成。

首次从全新目录复建发现两处原型环境依赖，现已修正：Cairo 应先于 HarfBuzz 构建，
避免读取 Ubuntu Cairo 的 XCB 开发依赖；GdkPixbuf 的 `shared-mime-info.pc` 也必须包含在
固定 SDK 中，不能借用宿主文件。修正后在另一个全新目录完成构建，仅复用逐一校验哈希
的下载文件，未复制原型的编译产物；日志为 `tools/native-recipe-clean-build-sdk-fixed.log`。

## 运行与搬迁验证

升级原型已在 Node 与发行包内的 Deno 2.9.7 下通过像素绘制、PNG/JPEG 编解码、
SVG 输入、PDF/SVG 输出。随后复制 addon 和完整非 glibc 动态库集合，共 37 个文件，
逐个设定 `$ORIGIN` 加载路径，并在 bubblewrap 内再次通过格式检查及显式字体检查：

- 不挂载构建目录、宿主图形库、宿主字体或用户目录。
- 提供 Deno 所需的普通 OS 运行库、加载器及 glibc gconv 模块，不挂载整个 `/usr`。
- Deno 使用 `--cached-only --deny-net`，同时隔离网络，不设置 `LD_LIBRARY_PATH`。
- 字体来自哈希核对的 Pango 源码夹具，检查注册、比例字体宽度差异及非空文字像素。

字体检查不代表已验证所有语言的排版正确性。glibc 的 gconv 不能省略：Canvas 读取字体名
时使用 iconv，第一次未提供这些 OS 模块的隔离检查已真实失败，补齐后通过。

七个共享组件的版本直接读取库的函数或导出变量。Canvas 的 `rsvgVersion`、
`pangoVersion` 等编译时头文件常量不能单独证明实际加载版本。
[`package_linux_canvas.py`](../scripts/native/package_linux_canvas.py) 将这些步骤变成独立入口，
拒绝来自 SDK 外的图形库或变化的文件集合，并使用生产安装器回读归档：

```sh
python3 scripts/native/package_linux_canvas.py \
  --work-dir "$HOME/.cache/neomusicbot-native-build/build-1" \
  --deno /path/to/verified-deno-2.9.7 \
  --output-dir /path/to/new-native-output
```

输出目录必须是新目录；Deno 可执行文件和字体均有固定哈希校验。报告包含输入哈希、
37 个文件的原始/搬迁后哈希、实际共享库版本、隔离结果及 ELF 符号需求。
仓库配方的全新构建产物已通过实际共享库版本、隔离格式/字体和生产安装器归档回读检查。
输出归档 SHA-256 为 `f1e2871ccd694708cd291538a977f3d2f740edbb2277941cc1108afc2a9c12fb`，
报告与日志分别是 `tools/native-recipe-output-2/native-build-report.json`、
`tools/native-recipe-package-fixed.log`。重新分析 37 个文件，符号需求上限仍为
GLIBC 2.38 / GLIBCXX 3.4.31 / CXXABI 1.3.9；符号分析不能代替独立旧系统运行测试。
隔离环境中 Deno 所需的 libgcc 使用归档内的固定副本，避免先加载宿主同名库。

## 来源材料与后续工作

原型的 37 个文件已逐一对应到八个源码构建组件或固定 Ubuntu 二进制包，
其中 Ubuntu 文件通过归档内字节哈希匹配，而不是只按文件名猜测来源。
20 个对应 Ubuntu 源码包的 `.dsc` 及所列归档已下载并验证哈希/大小。
librsvg 2.63.2 原始 Cargo.lock 的完整图共 357 个原始 crate，已收集 641 份包内原始声明。
11 个缺少常规许可文件的包已关联 8 份原始父仓库许可或明确标注的标准参考正文；
定义与来源说明见 [`linux-librsvg-rust`](../src/license/canvas/linux-librsvg-rust/README.md)。
`package_linux_cargo.py` 已在新目录完成全量材料收集、提取和回读，5 项专项回归通过。
该图包括可选、构建、测试及其他平台依赖，不是实际链接的 crate 数量；
其余材料审查及与最终二进制的绑定尚未结束。

本地原型证据保存在忽略提交的目录中：

- `tools/linux-native-relocation-final-sdk.log`
- `tools/canvas-native-audit/linux-modern-experiment/relocation-result.json`
- 同目录的 `shared-library-versions.json`、`native-provenance.json`
- 同目录的 `source-materials/ubuntu/manifest.json`、`source-materials/librsvg-cargo/manifest.json`

新增 `.github/workflows/native-linux.yml` 在 Ubuntu 24.04 上从固定输入构建，
检查 Cargo 材料、执行隔离功能验收并回读归档；当前仅保存构建证据，不发布或选择发行原生资产。
云端执行与实物核验的最新结果见下文；工作流成功不能代替发行资产读回。
首次云端运行 `8a5b49a` / 35638229942 在 bubblewrap 创建 loopback 地址时被拒绝，
尚未开始编译。Ubuntu 24.04 的应用级 userns 配置见
[官方说明](https://documentation.ubuntu.com/release-notes/24.04/)；工作流为专用 bubblewrap
副本加载 `ci-bwrap.apparmor`，随后执行相同的完整命名空间预检。
该配置只在临时托管执行器使用，本地 WSL 配置不变。
`b500d6e` / 35638761216 已通过该命名空间预检、完整源码编译及 Cargo 材料检查，
最终门禁因原生文件集合与预期不同而拒绝产物。源码复核发现 Canvas 的 GIF 自动探测
直接搜索宿主目录，忽略已经提供 GIF 的 SDK；现将 GIF/JPEG/librsvg 三个后端显式启用，
并添加隔离 GIF 解码/像素检查及缺失/多余文件诊断。修正后的本地八项图形/字体功能、
版本和归档回读均通过，归档哈希仍为上述 `f1e2871c…`。

2026-09-22 读回 `329c95b` / [35640374408](https://github.com/huaaudio/NeoMusicBot/actions/runs/35640374408)
的成功 CI artifact，外层 SHA-256 和大小与 GitHub 元数据一致。报告的三个输入哈希、
Cargo 锁、37 个文件集合、七个共享库版本、格式/字体/GIF 隔离检查均与仓库定义一致。
云端报告的原生归档 SHA-256 为 `947c3dc70536187f264830939a8e08124774b0be2f5ddc3128cd71923c966182`，
与本地 `f1e2871c…` 不同；当前配方不承诺逐字节可复现，不能用本地归档替代云端文件。
该次工作流只保留报告，未上传实际原生归档，因此尚未在下载后复核其二进制。
随后 `ea3182d` / [35675601359](https://github.com/huaaudio/NeoMusicBot/actions/runs/35675601359)
已保留实际归档；下载 artifact 的哈希和大小核对通过，37 个原生文件经独立回读全部匹配。
该次实际归档 SHA-256 为 `6c10ee3ea0f297bd3459e4035cc68a744821a68712dfc535f2bb5fff350b0cbf`，
证据为 `tools/native-ci-ea3182d/verified-evidence.json`。
核验记录为 `tools/native-ci-329c95b/verified-evidence.json`。

源码与原生来源映射现已从 `tools` 实验脚本移入
[`linux-sources`](../src/license/canvas/linux-sources/README.md) 的固定定义和
`package_linux_sources.py`。实际收集核对 20 个 Ubuntu 源码包的 62 个文件、21 份原始版权文本，
以及 12 个上游源码/SDK 归档中的 203 份原始声明。SDK 的 Node/Rust 归档明确标注范围，
不冒充完整上游源码或实际链接组件清单；Ubuntu 包只用 `dpkg-deb` 读取，不安装。
每次收集重新核对二进制包的版本、Source 字段、原始库和声明字节，并绑定实际原生报告及归档。
声明路径、源包身份、旧二进制绑定与内容篡改回归已加入 22 项原生 Python 测试。

`package_linux_material_bundle.py` 将此集合、357 个 Cargo 源码包及 641 份原始声明/8 份补充材料，
与实际原生归档组装为完整 ZIP，并在干净目录读回。Windows 实际回读通过，ZIP 共 1,327 个文件，
SHA-256 为 `51ee69f8764a0116cd1141777755125ec2ce9f08d028b8b406b1117e07617cec`；
本地证据为 `tools/native-materials-ea3182d.zip`。新增工作流会保留相同范围的云端材料包，
该工作流随后在 `a10d3f4` 的 [原生 CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35678485785) 成功。
实际下载的完整云端材料 ZIP 在 Windows 干净目录回读通过，SHA-256 为
`08938729952fdb8fb8a3090b21e1064a2fd9f6c55f5b51af38a35b6e24653f46`，
其中原生归档 SHA-256 为 `fc4a83a8570387cbdaf22fab8139563331367f900f28f536b5138043e753a6d9`。
37 个原生文件、源码绑定和 Cargo 材料均通过独立核对；证据为
`tools/native-ci-a10d3f4/complete-material-readback.log`。这些材料包不是已发布的运行资产。

还需完成剩余内嵌组件/许可审查，以及 Windows 对应升级评估。
之后才能生成并固定新 native 资产，替换发行定义，再对最终同一提交执行完整应用验收。
目前这些实验产物不是已发布资产，也没有改变用户下载到的发行运行库。

### Rust 标准库对应源码补充（2026-09-22）

固定材料定义新增与构建 SDK 匹配的 Rust 1.98.1 完整源码归档，官方 SHA-256 为
`be1816e7f6c40abb90245ad6e024bed2a7e88d7dda4561e4d5470207df616b9f`。
归档中的版本与提交 `48a229ceaefd4985c50990b14116b6d856af0985`
匹配现有构建输入，新增保留 3,841 份原始声明。收集和回读都检查实际归档中的四个身份文件；
遗漏源码、版本不符、身份文件重复或被替换均被拒绝，相关原生 Python 测试共 25 项通过。
当前定义共 13 个上游归档、4,044 份原始声明；这里包含编译器、测试和其他平台组件，
不能把整个源码树当作实际链接组件清单。此项不改变 native 二进制或运行资产选择，
也不代表 Deno、yt-dlp 等独立工具的材料审查已关闭。

## Windows 对应升级评估与本轮选择（2026-09-22）

本轮保留固定的官方 Canvas 3.2.3 Windows 资产及其整套配套 DLL。对应来源记录中的
Cairo 为 1.18.4-4、Fontconfig 2.17.1-1、FreeType 2.14.3-1、GLib 2.88.0-1、
HarfBuzz 13.2.1-1、Pango 1.56.4-3、librsvg 2.62.0-1，不能套用 Linux 升级版本表。
实际 Windows 发行包已通过像素读回、PNG/JPEG 编解码、SVG 输入和 PDF/SVG 输出检查；
其来源包和原始声明也已绑定。选择依据是已有整套二进制、来源映射和实际应用验证，
而 Windows 新工具链重建与整套 ABI 验证尚未建立；本轮不单独替换其中一个 DLL。

这不是认定旧版本不受上游缺陷影响。[Cairo 1.18.6 发布说明](https://www.cairographics.org/news/cairo-1.18.6/)
包含 Windows/DirectWrite 线程安全、裁剪崩溃及 CFF 边界修复。当前测试没有覆盖全部相关路径，
也没有证明 provider 中这些路径不可达。Windows 仍携带旧 Cairo 的事实列为测试版已知限制；
后续升级应整体重建/验证字体图形栈，并重新绑定材料。此选择完成本轮“升级或保留依据”的评估，
不等于该平台全部依赖缺陷已经消除。

## 已验证原生产物的应用包导入

`src/provider-runtime/native-linux/asset-candidate.json` 固定来自 `5c2c3da` / CI 35683045284
的实际产物：完整材料 ZIP SHA-256 为 `311c72bd6b34f5fe5029c0bda208c54784f6dc8f092110931685efbfee773782`，
原生归档为 `6c10ee3ea0f297bd3459e4035cc68a744821a68712dfc535f2bb5fff350b0cbf`。
`scripts/ci/package_linux_native_materials.py` 校验整个材料 ZIP、原始构建证据、来源与 Cargo 材料，
再导入应用包布局；逐一检查 provider 实际安装的 37 个文件与固定报告相符。

实际云端归档已在新目录导入并全部回读通过，记录 `tools/linux-native-candidate-import.log`。
新增五项回归涵盖正确绑定、库被替换、多余旧库、错误构建关联、保留归档篡改；
整个 CI Python 测试集 77 项通过（`tools/linux-native-import-tests.log`）。
该结果仍是候选资产的集成验证；远程固定资产发布和发行定义切换尚待完成，
不会因为存在候选定义就自动改变当前运行库选择。

## 固定资产发布与最终选择（2026-09-22）

[原生资产 r2](https://github.com/huaaudio/NeoMusicBot/releases/tag/native-linux-canvas-3.2.3-r2)
已公开为不可变 Pre-release，GitHub 返回 `immutable=true`，标签指向构建提交
`5c2c3da732869abcd3353ea8ef16a4759d1fa4ef`。原生归档、完整材料 ZIP、SHA256SUMS
均在草稿上传后下载验证，并在公开后匿名下载再次逐字节哈希比较通过。
记录为 `tools/native-release-r2/public-readback.json`；r1 保留为先前验证资产，发行选择使用 r2。

`canvas-native.json` 已固定 r2 URL、归档哈希和 37 个文件，Linux 组装器从同一 release
导入固定材料 ZIP。`provider.native.materials=passed` 要求实际安装字节与构建报告、
源码、原始声明和 Cargo 材料相符；该字段也是发布前必需字段。
Windows 沿用其已有材料核验并报告同一门禁。82 项 CI 脚本测试通过；
实际 Linux 安装库与材料的绑定通过（`tools/native-selected-materials-verify.log`）。
最终完整应用包仍需在同一提交执行运行与解压验证，不能把原生资产发布当作应用测试版完成。
