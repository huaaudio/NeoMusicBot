# yt-dlp runtime corresponding-source companion

The manifest fixes 86 existing upstream source archives (including distribution patches)
and retained build/origin records for the exact two yt-dlp 2026.08.19 executables.
The archives retain their original source and licensing files. Platform lists describe
applicability; some Python package archives are conservatively shared by both platforms.

Included: yt-dlp and its release recipe, both Python source versions, the Windows Python
external-source recipes, runtime Python sdists, curl-impersonate plus its eight pinned
dependencies, Linux source RPMs, the immutable Linux image build recipe, cryptography
Cargo sources/lock and matching OpenSSL/Rust sources, and PyInstaller/typing_extensions.
Build and optional sources are a superset, not a claim that all are linked at runtime.

Original runtime notices are distributed separately under licenses/ytdlp. Microsoft
runtime code remains subject to the original upstream redistribution conditions;
this collection does not relicense proprietary runtime files or claim their source is open.
The companion does not contain private credentials or execute downloaded build scripts.

Generate with scripts/ci/package_ytdlp_sources.py, then scripts/ci/package_bundle.py.
Verify the packaged payload after clean extraction with package_ytdlp_sources.py --verify-only.
Publication and binary-bound source-access integration are separate steps and remain required.
