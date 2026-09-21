# 发行包第三方材料审查

本页记录 AUD-005 的未完成部分，不能用 Maven 许可校验通过来代替整份发行包的材料审查。
Windows/Linux 发行包的 `NeoMusicBot.cdx.json` / `.xml` 描述 Java 依赖；
`provider-dependencies.json` 另外记录实际携带的 provider npm 包，包括安装目录和 Deno 缓存中的副本。

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
