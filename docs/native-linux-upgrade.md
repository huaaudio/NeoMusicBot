# Linux 原生库源码构建

2026-09-21 已完成升级原型以及仓库配方的全新源码构建。**新原生库尚未进入发行包**，
当前发行构建仍使用 `canvas-native.json` 中固定的上游资产。

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
工作流语法检查通过，云端实际执行仍待完成。
首次云端运行 `8a5b49a` / 35638229942 在 bubblewrap 创建 loopback 地址时被拒绝，
尚未开始编译。Ubuntu 24.04 的应用级 userns 配置见
[官方说明](https://documentation.ubuntu.com/release-notes/24.04/)；工作流为专用 bubblewrap
副本加载 `ci-bwrap.apparmor`，随后执行相同的完整命名空间预检。
该配置只在临时托管执行器使用，本地 WSL 配置不变；修正后的云端结果仍待验证。

还需完成云端源码构建、其余材料及最终二进制绑定，以及 Windows 对应升级评估。
之后才能生成并固定新 native 资产，替换发行定义，再对最终同一提交执行完整应用验收。
目前这些实验产物不是已发布资产，也没有改变用户下载到的发行运行库。
