# yt-dlp 2026.08.19 bundled-runtime notices

Preserve the original upstream aggregate rather than reconstructing each transitive notice.
The aggregate is byte-identical in both verified platform bundles. The executable hashes
bind this collection and the static CArchive inventories to the actual release files.
The inventory source_commit is the application extraction baseline, not the yt-dlp source commit.

Supplemental originals retain PyInstaller's bootloader exception and license, typing_extensions
4.16.0 notices, and Python 3.10.11's original Windows notice (including its legacy dependencies).
These are original archive members, not substituted generic license texts.

The upstream Microsoft redistribution conditions are preserved in the aggregate.
This notice collection does not alone establish corresponding-source delivery; that remains
an explicit distribution audit item. It does not require rebuilding every upstream component.
