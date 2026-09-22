# 发行包第三方材料审查

本页记录 AUD-005 的未完成部分，不能用 Maven 许可校验通过来代替整份发行包的材料审查。
Windows/Linux 发行包的 `NeoMusicBot.cdx.json` / `.xml` 描述 Java 依赖；
`provider-dependencies.json` 另外记录实际携带的 provider npm 包，包括安装目录和 Deno 缓存中的副本。

## 本轮材料审查边界

以实际随 ZIP 分发的程序、库、WASM 和运行包为范围。优先保留对应版本上游提供的
LICENSE、NOTICE、第三方声明及源码交付材料；上游已经覆盖的内嵌组件不另行重做声明。
对自行修改/替换的组件、实际缺失声明，以及许可要求的对应源码或再分发材料补齐缺口。
直接或传递依赖层级本身不是排除依据，纯构建/测试依赖也不因出现在锁文件中就自动成为运行组件。

此前收集的完整源码和锁图作为可追溯材料保留，但不要求对未分发的工具链依赖无限递归审计，
也不把逐字节可重构性或上游全部测试当作许可证材料交付的通用前提。
仍需按实际平台包检查适用声明是否随包保存，源码等额外义务是否得到满足；
已有哈希与内容检查继续保留，不能用这项范围说明绕过明确缺失项。

## 已有证据与范围

| 组成部分 | 已确认材料 | 尚待完成 |
| --- | --- | --- |
| Java 运行时依赖 | 44 个 SBOM 组件均有已保存的许可证；31 份 JAR 内原始 LICENSE/NOTICE 在 Shade 后保留 | 最终发行提交仍须通过相同检查；原生文件的内嵌依赖需独立核对 |
| yt-dlp 2026.08.19 独立程序 | 上游 Unlicense、第三方声明汇总及校验过的 yt-dlp 源码包均已收录 | 逐平台确认嵌入组件的版本、对应源码及构建材料覆盖；核心源码压缩包不等于第三方源码全集 |
| Deno 2.9.7 | 上游 MIT 许可证及固定哈希的官方二进制 | Rust/V8 等内嵌组件声明覆盖；Cargo.lock 的 1,128 项含构建/测试等依赖，不能直接认定为二进制内组件清单 |
| bgutil provider 2.0.0 | 固定提交的完整上游源码、GPL-3.0 文本、原始配置/锁文件及经核对的运行锁文件、安装目录与离线缓存 | npm 包的基本许可证、嵌入源码声明、native/WASM 子组件的逐项审查及缺失材料补齐 |

版本来源：[yt-dlp 第三方声明](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/THIRD_PARTY_LICENSES.txt)、
[Deno LICENSE](https://github.com/denoland/deno/blob/v2.9.7/LICENSE.md)、
[Deno Cargo.lock](https://github.com/denoland/deno/blob/v2.9.7/Cargo.lock)、
[provider 固定提交](https://github.com/Brainicism/bgutil-ytdlp-pot-provider/tree/37169ee2656e08c5c2e5dc9df4c598c0cb4c88a8)。

## provider 清单的含义

组装脚本执行 `scripts/ci/inventory_provider.py`，遍历 hoisted、scope、嵌套安装目录和随包携带的 Deno npm 缓存。
每项包含包名、版本、锁文件中的 npm 完整性字段、package.json 的路径/哈希、声明的许可标识，
以及 LICENSE/COPYING/NOTICE 和根目录 README 候选文件的路径/哈希。
未知版本、锁信息缺失或安装/缓存副本的许可元数据不一致会使组装失败。
干净解压验证会重新生成清单并逐项比较；发布报告还必须包含 `provider.inventory=passed`。

完整性字段来自已固定的 deno.lock；清单不声称重新计算了 npm tarball 的完整性值。
候选文件可能只包含许可证名称，也可能仅适用于嵌入的小组件。
因此清单明确标记 `license_review_complete: false`，不能把“文件存在”自动视为许可审查完成。
全包逐文件 SHA256SUMS 仍独立覆盖这些文件。

发行构建现在先应用 [`src/provider-runtime`](../src/provider-runtime/README.md) 中经审查的运行配置：
只从 provider 的 `package.json` 去掉 `devDependencies`，保留所有运行根依赖及其他配置；
原文件完整另存为 `package.json.upstream` 和 `deno.lock.upstream`，并附修改说明及哈希记录。
另外校验原 `deno.json` 后清空安装脚本授权列表，原文保存为 `deno.json.upstream`；
Canvas 原生文件改用独立固定的官方资产安装，详见下文。
原始完整源码仍随包保留。实际 npm 安装和缓存使用已提交的运行锁文件与 `--frozen`，
发行构建不会临时重新选择依赖版本。

本地干净安装确认，原配置即使使用 `deno install --prod`，仍会安装 304 个包。
运行配置将实际安装降为 183 个包，移除了 121 个开发依赖；每个保留包的版本、完整性值和完整依赖记录
均与原锁文件匹配。Deno 仅对两个 CSS 包的冗余 peer-context 键作了规范化。
原锁文件 350 项包含其他平台/上下文记录，不能与实际安装的 304 个包混同。
运行配置的锁文件为 183 项；Linux/Windows 的实际数量仍由各自发行清单确认。
原始/运行配置及锁文件都纳入清单和干净解压验证，运行根依赖缺失、版本或依赖记录改变会拒绝构建。

2026-09-21 在 Windows、Deno 2.9.7、固定 provider 提交及 `--frozen` 安装下，
实际清单包含 304 个不同的 npm 名称/版本组合。最初根目录文件名检查发现 18 项没有单独的 LICENSE/COPYING/NOTICE；
进一步检查确认其中 canvas、esrecurse、https-proxy-agent 的 README 有完整许可正文，
所以“缺少独立文件”本身不能判定为“缺少许可证”。仍需审查其余包及这些包内的附属组件。

现已补入 `licenses/provider` 的 13 份材料，按精确包版本、来源和 SHA-256 固定：

- protobuf 的上游 Apache 2.0 正文及随包 varint 源文件中的完整 BSD-3-Clause 注释原文。
- saxes、pkgr/core 的完整上游许可；humanfs/types 声明的 Apache 2.0 正文取自相同提交的 core 许可文件。
- natural-compare 的 README 指向的作者 MIT 许可正文，原 README 中的版权年份继续保留。
- xxhash 与 oxc 原生 npm 包的基本 MIT 许可，取自明确列出对应版本原生包的父包；内嵌原生依赖不据此判定完成。
- quickjs-wasi 2.2.0 固定标签所用 QuickJS-NG 子模块的 MIT、内嵌 Mbed TLS 的完整双许可证，
  Ada 3.4.3 的 MIT/Apache 两种许可，以及 Ada 内嵌 tl::expected 1.1.0 的 CC0 正文。

组装和干净解压验证会核对补充文件原始字节；相关包版本发生变化时，旧映射拒绝继续使用。
canvas 的 README 内 MIT 与 BMP 子组件声明原本已随包保留。
最新运行安装不再包含 `@swc/counter`、eslint-plugin-only-warn、keyv、swc-node、xxhash 和 oxc 原生绑定；
因此此前仅由这些开发包引入的分发材料缺口不再属于新发行包的依赖范围，旧版清单和证据仍保留。
proxy-agent-negotiate 1.1.0 与 quickjs-wasi 2.2.0 包装层的原始 package.json 均明确声明 MIT，
但没有单独的包装层许可证文件。依据 [npm 的 SPDX 标识说明](https://docs.npmjs.com/cli/v11/configuring-npm/package-json/#license)，
现另附 [SPDX MIT 标准正文](https://spdx.org/licenses/MIT.html)，形成第 14 份补充材料；
明确标注它是标准文本，不冒充上游项目的版权声明，也不猜测或填入年份/权利人。
原始元数据、源码和已有署名继续完整保留，包括 proxy-agent-negotiate 的 author 字段。
这补上了两项已声明许可的可读正文，不代表 QuickJS WASM 或整个发行包的审查完成。
WASI 工具链、canvas/其他嵌入组件及上表中的 yt-dlp/Deno 材料仍在审查。
参考 [protobuf 上游许可](https://github.com/bufbuild/protobuf-es/blob/04297e762a64dbcafc299c46785dbc5621b4329f/LICENSE)、
[saxes 上游许可](https://github.com/lddubeau/saxes/blob/211fa0ebec9b628affc09219199639887174bfc3/LICENSE)、
[canvas README](https://github.com/Automattic/node-canvas/blob/v3.2.3/Readme.md)。

QuickJS 对应关系来源：[2.2.0 源码提交](https://github.com/vercel-labs/quickjs-wasi/tree/cc1fea4a6a4ac1d960e0db68d35e1459064a1a23)、
[QuickJS-NG 许可](https://github.com/quickjs-ng/quickjs/blob/dec012362bd93876449f3ecff4f835b2eba89bab/LICENSE)、
[内嵌 Mbed TLS 许可](https://github.com/vercel-labs/quickjs-wasi/blob/cc1fea4a6a4ac1d960e0db68d35e1459064a1a23/extensions/crypto/mbedtls/LICENSE)、
[Ada 3.4.3 许可](https://github.com/ada-url/ada/blob/v3.4.3/LICENSE-MIT)、
[tl::expected 1.1.0 许可](https://github.com/TartanLlama/expected/blob/v1.1.0/COPYING)。

本地证据保存在忽略目录 `tools/distribution-license-audit/`；清单可按发行脚本重新生成，
最终两平台以实际 ZIP 内材料和对应 CI 报告为准。AUD-005 仍未关闭，发布前必须完成上述材料核实。

## 实际 CI 发行包复核

已下载 `08aa25b` [成功 CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35576688077) 的三个原始 artifact，
逐一核对 GitHub 返回的 SHA-256 和字节数，并对两个平台产物重新执行发布流程使用的提交、版本、
包哈希、SBOM、工具与解压报告关联校验。媒体 artifact 的提交及三项 passed 也已读回确认。
两个 provider 清单均为 183 包，14 份补充文件中有 9 份适用于现有运行包。

真实 CI 安装结果与此前本地安装的差异必须保留：Windows 的 canvas/build/Release 含
`canvas.node` 和 44 个 DLL；Linux 含 `canvas.node` 和 25 个 `.so.*` 文件。
因此不能用此前本地目录没有这些文件来缩小许可范围；canvas README 的 MIT/BMP 文本
也不能覆盖 Cairo、GLib、Pango、字体/图像库及编译器运行库等各自的条款和对应源码。
下一步需按上游预编译资产确定版本、许可及源码材料，并复核 WASM 和独立工具的嵌入组件。
原始 artifact、实际 ZIP 和文件清单在 `tools/ci-success-08aa25b/`，包括
`verified-artifacts.json` 与 `canvas-native-inventory.json`。

Canvas 的 npm 锁条目只覆盖包本身，不覆盖安装脚本另行下载的 native 资产。
已从 [Canvas 3.2.3 官方发布](https://github.com/Automattic/node-canvas/releases/tag/v3.2.3)
核实两个资产的 SHA-256，并将所有文件与上述真实 CI ZIP 逐字节比较，一致。
Linux 压缩包共 30 个文件、Windows 共 49 个文件（包括 `build/Release` 之外的构建元数据）。
`src/provider-runtime/canvas-native.json` 记录资产哈希、逐文件大小/哈希、源码提交及
[实际上游构建](https://github.com/Automattic/node-canvas/actions/runs/23776689553) 的独立 recipe 提交。
两平台发行脚本及媒体检查改为禁用 npm lifecycle 脚本后显式安装这些固定资产，
解压验收检查完整文件集合及对应的 `neomusicbot-canvas.json`，防止只验证 npm 包而漏掉 native 文件。
这些哈希证明来源与内容一致，**不表示原生子组件的许可、对应源码及版本审查已经完成**。

## Windows Canvas 的包级材料

Windows 的全部 44 个 DLL 已与 34 个 MSYS2 官方二进制包逐文件 SHA-256 比较，全部一致。
不能只按 Canvas 构建日期猜测包版本：例如实际 Expat 对应 `2.7.5-1`，
同期仓库配方已是 `2.7.5-2`，后者的 DLL 哈希不匹配。
每个对应源码包中的 `PKGBUILD` 哈希又与二进制 `.BUILDINFO` 的记录一致。
已捕获 55 份原始许可文件；giflib 的二进制包没有携带该文件，改从已匹配源码包内
`giflib-5.2.2.tar.gz` 的 `COPYING` 取得，保留原始字节及来源路径。

完整映射在 [`src/license/canvas/windows/manifest.json`](../src/license/canvas/windows/manifest.json)，
范围说明在 [`src/license/canvas/README.md`](../src/license/canvas/README.md)。
Windows 构建将这 34 个源码包（合计 282,568,654 字节）下载、校验并放入
`sources/canvas/windows`，55 份文本与清单位于 `licenses/canvas/windows`。
干净 ZIP 验收重新核对当前仓库定义、实际 DLL、源码包和许可文件，不能只比较包内自报清单。
本地真实文件验证通过；提交 `be7c094` 的
[完整 CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35611351107) 已通过。
两个平台 artifact 与媒体报告已下载，外层哈希/大小与 GitHub 元数据一致；Windows 实际
ZIP 中的 34 个源码包和 55 份文本已再次复核。证据在 `tools/ci-success-be7c094/verified-materials.json`。

这完成了这 44 个 DLL 的包级来源和材料对应，**仍不表示所有内嵌依赖已经审查完成**。
librsvg 2.62.0 的 Cargo 依赖另行收录，见下节；不能从这份包级清单推断 Rust 子组件范围。
Linux、Canvas 自身构建配方、Deno/yt-dlp 及其他 native/WASM 子组件的工作仍在进行。

原生模块实际导出的版本也已核对：Windows 的 Cairo/FreeType/librsvg/Pango 分别为
1.18.4 / 2.14.3 / 2.62.0 / 1.56.4，Linux 分别为 1.16.0 / 2.10.4 / 2.52.8 / 1.48.0。
这些 Linux 版本与上游独立构建分支的旧 Docker 配方相符；固定 Canvas 3.2.3
不能证明其所有底层库均为最新稳定版，后续升级评估必须独立处理。
本地证据：`tools/canvas-native-audit/msys-packages/inventory.json`、
`sources/inventory.json`（相对于同一目录）及 `tools/canvas-materials-real.log`。

## Windows librsvg 的 Cargo 补充材料

已从哈希匹配的 MSYS2 源码包提取实际 `PKGBUILD`，确认它先执行
`cargo update -p windows-sys@0.61.2 --precise 0.60.2` 再 `cargo fetch --locked`。
因此仅保留 librsvg 原始锁文件不足以记录该构建配方使用的依赖。
使用 Cargo 1.93.1 执行同一命令，得到单独保存的重建锁文件；这不是上游留存的最终构建锁文件。
共同包的记录不变，移除 `windows-sys 0.61.2`，加入 `windows-sys 0.60.2` 及九个关联包。
通过 crates.io 的版本元数据核对，这十个加入版本均早于二进制包的构建时间。

[`librsvg-rust/manifest.json`](../src/license/canvas/librsvg-rust/manifest.json)
记录两份锁文件的并集：363 个原始 `.crate`，合计 54,778,318 字节。
Windows 发行包增加全部源码包、原始/重建锁文件、实际配方与 `.BUILDINFO`、642 份包内原始声明文件
以及 10 份补充许可文本。十四个缺少常规根目录许可文件的包有明确映射；其中部分补充材料为
已声明许可的 SPDX 标准正文，清楚标记为参考文本，保留原始源码与署名，不冒充作者的原始声明。

该并集包含构建、测试、可选和其他平台依赖，**不是 Windows DLL 实际链接的 crate 数量**。
`package_librsvg_materials.py` 校验完整锁文件并集、父包绑定、归档哈希/大小、Cargo 包身份及
许可元数据，并按原始字节提取许可文件；不执行 Cargo 脚本。解压后的验收再次比较仓库定义、
实际 DLL、父源码包、所有 crate 和材料文件。35 个 Python 检查通过；真实材料包在干净目录解压后，
全部 34 个父源码包、363 个 crate 及对应文本再次校验通过，证据在
`tools/librsvg-materials-roundtrip/verification.json`。该本地材料验证不包含程序启动；
本次新增材料的完整应用 CI 验证另以对应提交的日志为准。
Linux 原生库及其他工具的材料工作仍在进行，AUD-005 尚未关闭。

Linux 的新源码构建原型已升级 Cairo、librsvg、Pango、GLib、HarfBuzz、Fontconfig、FreeType，
37 个原生文件通过 Deno 的隔离搬迁、图像格式与显式字体检查，见[升级记录](native-linux-upgrade.md)。
本地已收集并校验 20 个 Ubuntu 对应源码包和 librsvg 完整锁图的 357 个 crate / 641 份原始声明。
11 个缺少常规许可文件的 crate 已关联 8 份补充正文：defmt-parser、mutants 使用各自 VCS 提交的
原始父仓库许可，selectors 的 MPL 声明使用明确标注的标准参考正文，其余来源按与已审查的
Windows 源码字节一致性复核。定义见
[`linux-librsvg-rust/manifest.json`](../src/license/canvas/linux-librsvg-rust/manifest.json)。
`package_linux_cargo.py` 已对完整实际材料重新收集、提取和验证，5 项回归覆盖缺包、父源码错配、
补充文本篡改和缺失引用；证据 `tools/linux-cargo-materials-validated.log`。
新库尚未替换上述发行资产，最终二进制绑定、CI 与其余材料仍需验收。

## Linux 来源材料正式收集与二进制绑定

2026-09-22 将原型目录里的来源映射正式固定到 `src/license/canvas/linux-sources`。
20 个 Ubuntu 源码包/62 个文件与固定 SDK 包的 Source/Version 和原始库字节对应，
21 份版权文本按原始字节收录；八个运行源码、node-addon-api 头文件源码，以及明确标注范围的
Node/Rust SDK 归档另保留 203 份原始声明。构建配方及锁定输入随材料包保留。
采集程序不会安装 Debian 包或读取宿主版权文件，回读也不依赖实验目录。

已用 `ea3182d` 成功原生 CI 的实际归档完成绑定，再合并完整 Linux librsvg Cargo 材料，
组装 1,327 文件的 ZIP 并在 Windows 干净目录通过回读。详见 [构建记录](native-linux-upgrade.md)。
这证明已收集材料与该次实际二进制相符，不证明所有内嵌依赖或整个发行包审查完成；
`a10d3f4` 的云端完整材料包已通过下载后干净目录实物回读，详见上述构建记录；AUD-005 保持开放。


## 独立工具材料审查进度

Deno 2.9.7 固定源码提交 `0c071246a412575e07423263404a5d13e7ed6aa2` 的锁文件包含
1,046 个 registry 包，原始归档均按锁定 SHA-256 校验，保留 1,662 份包内声明。
82 个工作区包均在固定 Deno 源码快照中按名称、版本和 Cargo.toml 对应；Deno、rusty_v8
及其固定子模块另收集 22 个源码归档和 532 份原始声明。
这是包含构建、测试、可选及其他平台依赖的完整来源集合，不是实际链接组件清单。

117 个 registry 包未找到常规声明文件，其中 51 个已取得固定提交的补充候选；
沿父目录查找并核对 Git blob 后另取得 17 个包的候选。候选适用范围尚未逐项确认，
不得视为材料审查完成。证据为 `tools/deno-material-audit/summary.json` 和
`ancestor-supplements.json`（相对于同一目录）。完整集合尚未接入最终两个平台 ZIP。

实际 Windows yt-dlp 发行文件在禁用配置和插件、不输入媒体 URL 的情况下报告：
Python 3.10.11、OpenSSL 1.1.1t、Cryptodome 3.23.0、brotli 1.2.0、curl_cffi 0.16.0，
以及其他 Python/EJS 组件；证据为 `tools/yt-dlp-binary-runtime.log`。
这些嵌入组件仍需对应来源和升级评估，不能以 yt-dlp 主程序版本替代其依赖审查。


2026-09-22 继续对 `5c0a3d2` 实际两平台 ZIP 内的 yt-dlp 做静态盘点：Windows
150 个 CArchive 条目/1,584 个 Python 模块，Linux 158 个条目/1,644 个模块，
逐项保留字节哈希，未执行或反序列化内嵌模块代码。仅报告版本的本地探针补充了
cffi、charset-normalizer、idna、pycparser 等缺少发行元数据的组件版本。
Linux 实测 Python 3.14.7/OpenSSL 3.5.7，与 Windows 的 3.10.11/1.1.1t 不同。

已按实测版本采集 17 个 PyPI 原始源码包，核对 PyPI SHA-256 并保留 28 份原始声明；
另保存两版 CPython 官方固定提交源码，以及 yt-dlp 固定提交
`594bd50c2c78ac432f81600d309fdc4e0a92d82c` 的原始工作流及带哈希依赖输入。
Windows 实际嵌入的 24 个文件与官方 Python 3.10.11 嵌入发行包字节相同；
其中 libffi、libssl、libcrypto 另与 CPython 官方外部二进制仓库对应文件逐字节匹配，
配方固定的六个外部源码归档也已保留。这些比对不等于所有 native 子依赖覆盖。
本地完整索引为 `tools/ytdlp-embedded-audit/summary.json`；来源材料尚未正式接入最终 ZIP，
内嵌 curl、Rust/加密依赖、平台运行库及适用声明审查仍需完成，AUD-005 保持开放。

## QuickJS/WASI 来源与实际二进制绑定（2026-09-22）

`package_quickjs_materials.py` 现将 QuickJS WASI 2.2.0 的精确 npm 归档、固定版本主源码、
QuickJS-NG 子模块、WASI SDK 32 配方及其固定 wasi-libc/LLVM 源码纳入两个平台包。
共六份完整归档；除原始源码内的声明外，另保留 17 份原始声明文件供阅读。
现有 provider 补充材料继续提供 Ada、tl::expected、Mbed TLS 等许可文本。

实际 `7e9a242` 两平台包的主 WASM 及六个扩展与 npm 归档完全匹配；每个平台的安装目录和 Deno 缓存共两个副本均验证。
七个 WASM 的 producers 段均报告 Clang 22.1.0-wasi-sdk，LLVM 提交
`4434dabb69916856b824f68a64b029c67175e532` 与 SDK 32 子模块一致。
配方明确使用 WASI libc、编译器支持、模拟时钟/信号库，URL 扩展另外链接 libc++ 的 `string.cpp.o`。
这些对应源码、构建材料、运行库原始许可及行内声明现已保留，不再仅依赖包装层 MIT 声明。
完整 LLVM 源码包含构建、测试及其他平台代码，不把它们全部认定为运行时链接组件。

正式校验会拒绝版本/锁完整性不符、来源遗漏、WASM 文件集合改变、缓存副本损坏、编译器提交错配，
以及声明原文字节不符。两平台干净解压验证和预发布门禁新增 `provider.quickjs=passed`；
这项新增门禁仍须由新提交的实际 CI 包验证，历史包不追补该报告字段。
此项不代表 Deno、yt-dlp 或整个 AUD-005 已关闭。

两平台对应的独立 QuickJS 材料 ZIP 均已干净解压回读通过（各 125 个文件），
证据为 `tools/quickjs-platform-readback/verified.json`；55 项 CI 脚本测试通过。
这些局部实物验证不能替代包含新增门禁的完整应用 CI 与最终预发布验收。

## Deno 固定来源定义（2026-09-22）

已将收集结果整理为 `src/license/deno` 和 `validate_deno_materials.py`：
固定 1,046 个 registry crate、82 个工作区清单、22 份 Deno/V8/子模块源码，
另补入 Rust 1.95.0 完整源码及 TypeScript 6.0.3 原始 npm 包和对应提交源码。
源码归档总计 702,158,848 字节；版本、Cargo.lock、V8 子模块、原始 Cargo/VCS 元数据与声明映射分别校验。
Deno 的工具链与 Canvas 使用的 Rust 1.98.1 不同，不能共用其源码完成结论。

Windows 实际 Deno 中的 Rust 提交标识 `59807616e1fa2540724bfbac14d7976d7e4a3860`
与所收集 Rust 1.95.0 原始身份文件匹配。Linux 没有相同的内嵌路径标识，
其关联依据固定官方二进制与源码中的工具链配方，不冒充直接编译器标识比对。
`deno_core_icudata` 的 10,822,192 字节数据与固定 V8 ICU 的 `common/icudtl.dat` 完全相同；
完整 ICU 源码及原始数据声明已保留。TypeScript 的原始发布材料和第三方声明另行补齐，
Deno 修改后的 JavaScript 及更新说明仍保留在原始 Deno 源码中。

对 117 个没有常规原始声明文件的 crate，66 个已将完整原文按精确 VCS 提交及祖先目录范围关联；
其余 51 个保留完整原始 crate、Cargo 许可/作者声明、能取得的原始版权短声明，并明确标注附加的 SPDX 标准文本。
其中两个 gpu-descriptor 包的 COPYING 只有版权及许可引用，不能当作完整正文；
引用的仓库内文件返回 404，因此保留原声明并附其明确可选的 Apache-2.0 标准文本。
没有猜测或填入版权人、年份。此映射有 79 份补充文件，不能单独证明所有内嵌组件已完成审查。

原始归档声明路径共 5,728 项，包含完整源码中的构建/测试材料及 Deno 空 LICENSE 测试夹具；
空夹具不构成许可授权。正式源码校验器会核对原始字节、遗漏/重复项及错误版本映射，
新增回归与现有 CI 脚本共 69 项通过。Deno 材料已接入两平台组装和干净解压验证，
发布门禁必须包含 `runtime.deno.materials=passed`；本次提交的云端应用 ZIP 仍待验证，
整体运行时范围审查、发行集成与最终实物验证仍是未完成项，AUD-005 保持开放。

QuickJS 的新增门禁已由 `5c2c3da` [完整应用 CI](https://github.com/huaaudio/NeoMusicBot/actions/runs/35683045221)
及下载后的两平台实际 ZIP 回读确认：每个平台两份副本、七个 WASM、六份来源归档、17 份原文均匹配。
证据为 `tools/ci-success-5c2c3da/verified-materials.json`。

## yt-dlp 内嵌 curl 来源补充（2026-09-22）

实际两平台 yt-dlp 中的 curl_cffi 0.16.0 原生文件已与 PyPI 原始 wheel 逐字节对应：
Windows 的 `_wrapper.pyd` 与 libcurl DLL，以及 Linux 的 `_wrapper.abi3.so` 均完全匹配。
保留两个 wheel 的官方 SHA-256、原始声明和内嵌文件映射，证据为
`tools/ytdlp-embedded-audit/curl-native/wheel-binding.json`。

其原始构建脚本选择 curl-impersonate 2.0.0；已固定上游提交
`ec41b71ce888806bfec56ada7a7258d333eb3d19` 的完整源码、补丁和 CMake 配方。
配方中的 zlib、zstd、Brotli、BoringSSL、nghttp2、ngtcp2、nghttp3、curl 八份源码
均按上游配方 SHA-256 校验通过，另外保留 29 份原始声明。
这证明所选构建材料与固定配方对应，尚未证明官方 wheel 的逐字节可重构性；
其他内嵌组件的来源与适用声明审查、正式发行集成仍待完成。

本地 Deno 材料实物验证：从 `5c2c3da` 已核验应用包取出两平台实际 Deno，
分别组装、生成 SHA256SUMS、打包并干净解压；每个平台 6,884 个文件的完整性与来源绑定通过。
证据为 `tools/deno-platform-readback/verified.json`。此检查限定于 Deno 程序和材料，
不替代新增提交的完整应用 ZIP、运行验证或整个 AUD-005 的审查结论。

`521a0b0` 的首次云端组装被严格哈希门禁拦下：Gitiles 为相同 V8 子模块提交
生成的 tar 文件带请求时间戳。逐项比较 buildtools 的 123 个条目后，确认内容、
路径、权限、链接及其他元数据相同，差异仅为 mtime 及其 PAX 表示。
现对 17 份 Gitiles 归档排序并将 mtime 固定为零，保存无压缩 PAX tar 的完整固定哈希，
同时保留首次上游压缩包身份。所有 17 份再次在线下载后均严格匹配规范化哈希；
72 项脚本测试通过，覆盖内容/权限/链接变更仍可检出以及重复和越界路径拒绝。
该修复已由 `d5ea9b0` 的 CI 35686250282 两平台实际 ZIP 回读验证；证据为
`tools/ci-success-d5ea9b0/verified-materials.json`。当次匿名媒体检查失败，整体仍不可发布。
旧本地材料 ZIP 只证明修复前材料布局，不冒充新产物。

## yt-dlp 原始声明复用与明确补充（2026-09-22）

`src/license/ytdlp` 固定实际两平台包中逐字节相同的上游第三方声明：243,550 字节，
SHA-256 `472aefe951c7db35e1657c1d13fd337140511ed6f2b329205105ad441c5a02b7`。
保留上游已有的内嵌组件声明及 Microsoft 再分发条件，不重新编写其全文。
新增原始补充为 PyInstaller 6.22.0 许可及 bootloader 例外、typing_extensions 4.16.0
许可、Windows Python 3.10.11 原始 LICENSE。保留各自归档来源和成员哈希。

实际官方 yt-dlp 程序的 SHA-256 与静态 CArchive 清单绑定，清单内 `source_commit`
明确表示提取它的应用提交而非 yt-dlp 源提交。`package_ytdlp_notices.py` 在组装及解压后
验证程序身份、原文、补充文件集合与旧位置的上游汇总声明一致性。
新增 `runtime.ytdlp.notices=passed` 发布门禁；缺失任一报告字段的发布拒绝测试覆盖该字段。

82 项 CI Python 测试通过。两平台实际程序均与材料打包，再在全新目录解压并校验通过，
每个平台 14 个文件，记录 `tools/ytdlp-notice-readback/verified.json`。
此项完成原始声明的正式打包和字节校验，不代表整个 AUD-005 关闭；
对应源码交付仍单独列为未完成项，最终完整应用 ZIP 仍需云端验证。
