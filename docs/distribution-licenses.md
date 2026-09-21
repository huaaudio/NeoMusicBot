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
| bgutil provider 2.0.0 | 固定提交的完整上游源码、GPL-3.0 文本、deno.lock、安装目录与离线缓存 | npm 包的基本许可证、嵌入源码声明、native/WASM 子组件的逐项审查及缺失材料补齐 |

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

2026-09-21 在 Windows、Deno 2.9.7、固定 provider 提交及 `--frozen` 安装下，
实际清单包含 304 个不同的 npm 名称/版本组合。最初根目录文件名检查发现 18 项没有单独的 LICENSE/COPYING/NOTICE；
进一步检查确认其中 canvas、esrecurse、https-proxy-agent 的 README 有完整许可正文，
所以“缺少独立文件”本身不能判定为“缺少许可证”。仍需审查其余包及这些包内的附属组件。

优先补齐/核实的例子：`@bufbuild/protobuf@2.14.0` 同时声明 Apache-2.0 与 BSD-3-Clause，
需要核对两部分材料；`saxes@6.0.0` 上游存在 LICENSE，但 npm 安装根目录未携带；
`quickjs-wasi@2.2.0` 含 WASM 与扩展二进制，不能只看 JavaScript 包的 MIT 标识；
canvas 的 BMP 子组件声明与其基本 MIT 许可证分开保存。
参考 [protobuf 上游许可](https://github.com/bufbuild/protobuf-es/blob/04297e762a64dbcafc299c46785dbc5621b4329f/LICENSE)、
[saxes 上游许可](https://github.com/lddubeau/saxes/blob/211fa0ebec9b628affc09219199639887174bfc3/LICENSE)、
[canvas README](https://github.com/Automattic/node-canvas/blob/v3.2.3/Readme.md)。

本地证据保存在忽略目录 `tools/distribution-license-audit/`；清单可按发行脚本重新生成，
最终两平台以实际 ZIP 内材料和对应 CI 报告为准。AUD-005 仍未关闭，发布前必须完成上述材料核实。
