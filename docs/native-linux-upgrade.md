# Linux 原生库升级实验

2026-09-21 已完成本地源码构建和隔离验证。**新原生库尚未进入发行包**，
当前发行构建仍使用 `canvas-native.json` 中固定的上游资产。

## 已验证的升级

| 组件 | 当前 Linux 发行资产 | 本次源码构建 |
| --- | --- | --- |
| Canvas | 3.2.3 | 3.2.3，重新编译 C++ addon |
| Cairo | 1.16.0 | 1.18.6 |
| librsvg | 2.52.8 | 2.63.2，启用 pixbuf 与 AVIF |

Cairo 使用[官方 1.18.6 源码](https://cairographics.org/releases/cairo-1.18.6.tar.xz)，
SHA-256 为 `1c767308174337a74694da0f3ec069c271452163a1ef4540964c50c301f157d4`。
librsvg 使用[官方 2.63.2 源码](https://download.gnome.org/sources/librsvg/2.63/librsvg-2.63.2.tar.xz)，
SHA-256 为 `852b18e1a00b8605528825a27dc7748bff2a5dd254028f59dc22a34ea57e81b6`。
按[上游版本规则](https://gnome.pages.gitlab.gnome.org/librsvg/devel-docs/supported_versions.html)，
2.63.x 属于稳定系列；微版本 90 及以上才是下一系列的开发版。

构建环境为 Ubuntu 24.04.4 x86-64，使用固定哈希的 Rust 1.98.1、
Meson 1.12.0、cargo-c 0.10.25，以及 Node 24.21.0 / node-gyp 13.0.2。
Ubuntu 开发包和运行库仅解压到专用 SDK，没有安装到系统。
librsvg 使用上游 Meson 配方和 `cargo cbuild --locked`。
Cairo 保留字体、PNG、PDF/SVG 等离屏功能，不构建 X11/XCB 后端。
上游完整测试套件尚未执行，不能把下面的功能检查等同于上游全部测试通过。

## 运行与搬迁验证

Node 与发行包内的 Deno 2.9.7 均通过像素绘制、PNG 编解码、JPEG 编解码、
SVG 输入渲染、PDF 输出与 SVG 输出检查。Deno 使用 `--cached-only --deny-net`，
检查不依赖字体、网络或外部图片。

随后将 addon 和解析出的动态库复制到新目录，共 40 个原生文件，
把各文件的加载路径设为 `$ORIGIN`。在 bubblewrap 内再次运行 Deno：

- 不挂载构建目录、宿主图形库、用户目录或 `/usr`。
- 只提供 Deno 所需的普通系统运行库作为 OS 基线。
- 不设置 `LD_LIBRARY_PATH`，同时隔离网络并禁止 Deno 网络访问。
- 上述六类图像检查全部通过。

这证明本次实验可在该 OS 基线下搬迁，不证明任意 Linux 发行版兼容。
正式支持的 glibc/系统库下限仍需从最终 ELF 版本需求和独立运行环境确定。

版本检查直接读取共享库的 Cairo/Pango 版本函数与 librsvg 导出的版本变量。
Canvas 的 `rsvgVersion`、`pangoVersion` 使用编译时头文件常量，不能单独用来证明
运行时加载了哪个库；重编译 addon 后，它们才与本次升级头文件一致。

本地证据位于忽略提交的实验目录，不是已发布资产：

- `tools/linux-cairo-1.18.6-final.log`
- `tools/linux-librsvg-2.63.2-build.log`：构建/安装成功；末尾的首次版本断言误用了 Canvas 头文件常量。
- `tools/linux-canvas-modern-build.log`
- `tools/linux-canvas-modern-features-fixed.log`
- `tools/linux-native-relocation.log`
- `tools/canvas-native-audit/linux-modern-experiment/relocation-result.json`：逐文件原始/搬迁后哈希及隔离结果。

## 进入发行构建前的工作

继续核对并升级其余原生依赖，实验 SDK 中的 Pango 1.52.1 等 Ubuntu 版本不代表
已经对齐各项目最新稳定版。还需保存最终实际库集合的对应源码、许可和构建输入，
把可复现构建与校验接入 CI，并验证 Windows 的对应升级。
随后才能替换正式 native 定义、重新组装发行包并运行完整同提交验收。

`canvas_probe.cjs` 已将这组格式检查纳入现有两平台干净发行包验收；
当前 Windows/Linux 官方资产的本地检查均通过，云端结果以对应提交为准。
