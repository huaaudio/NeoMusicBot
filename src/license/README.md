# Dependency license metadata corrections

`licenses.xml` fills gaps in dependency POMs without changing their license terms.
Overrides use exact inspected versions and match the upstream metadata; an upgrade
with different metadata must be reviewed again. `license:download-licenses` fails
on missing metadata or download failures, and the release verifier checks saved
license files against every component of the generated runtime SBOM.

| Component | Evidence used |
| --- | --- |
| jsoup 1.23.2 | [Original release LICENSE](https://github.com/jhy/jsoup/blob/fbc7775c462f5b023d8db54c9a73d7ec17d53300/LICENSE), including Jonathan Hedley's copyright. The annotated `jsoup-1.23.2` tag resolves to this fixed commit. Replaces the mutable website URL after a real Linux CI read timeout; download failures still fail the build. |
| nanojson 1.10 | `com/grack/nanojson/JsonParser.java` in [the Maven Central sources JAR](https://repo.maven.apache.org/maven2/com/grack/nanojson/1.10/nanojson-1.10-sources.jar) contains its Apache 2.0 header and copyright. This is version-specific; do not infer the license from the current repository's README. |
| youtube-source common/v2 1.18.2 | [The release tag's MIT license](https://github.com/lavalink-devs/youtube-source/blob/1.18.2/LICENSE), including the original copyright. |
| base64 2.3.9 | [The published POM](https://repo.maven.apache.org/maven2/net/iharder/base64/2.3.9/base64-2.3.9.pom) contains the author's public-domain dedication and attribution. The saved document is the original POM, not an invented license. |
| JNA 5.19.1 | [The release's license declaration](https://github.com/java-native-access/jna/blob/5.19.1/LICENSE) and its `LGPL2.1` / `AL2.0` files replace ambiguous or unreachable URLs. Both alternatives remain listed. |
| Logback 1.6.3 / Trove 3.1.0 LGPL 2.1 | Unreachable GNU URLs are redirected to the unmodified LGPL 2.1 text shipped by JNA 4.4.0. The names/alternatives in their own POMs are retained. |

The shaded JAR appends shared upstream LICENSE and NOTICE resources rather than
silently discarding all but one copy. External executables and their embedded
native/npm dependencies are outside the Maven SBOM; their separate release
materials still need review. A passing Maven coverage check alone does not prove
that every external distribution obligation has been addressed.
