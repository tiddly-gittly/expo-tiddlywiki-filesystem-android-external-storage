# Tar PAX Extended Header Support

## Problem

When extracting archives created by `git archive`, files with long paths
(>100 bytes UTF-8) were extracted with wrong filenames. This caused two
symptoms in git status:

- **False "add"**: files like `{sha}.data` appeared in the wiki root
- **False "delete"**: the original long-path tiddlers were missing on disk

## Root Cause

`git archive --format=tar` uses POSIX pax extended headers (type `x`) for
entries whose path exceeds the 100-byte tar name field. The pax header
contains a `path=<long name>` record. The actual file entry that follows
uses a **placeholder name**: `{blob-sha}.data`.

Our `extractTar` implementation (Kotlin) only handled GNU long-name
extensions (type `L`). Pax headers (type `x`) were silently skipped as
"unknown entry types". The subsequent file entry was then extracted using
its placeholder `{sha}.data` name instead of the real long path.

## Which files are affected

Any file whose full relative path is >100 bytes in UTF-8. Common cases:

- Chinese/CJK filenames (3 bytes per character in UTF-8)
- Deeply nested paths
- TiddlyWiki system tiddler titles encoded in filenames (e.g.
  `$:/config/ViewToolbar...` mapped to `$__config_ViewToolbar...`)

## Diagnosis Technique

```python
# List files that use pax path headers in a tar:
import tarfile
with tarfile.open("archive.tar") as tf:
    for m in tf.getmembers():
        if "path" in m.pax_headers:
            print(m.name, m.pax_headers["path"])
```

```bash
# Inspect raw tar headers for pax placeholder names:
# pax header entry: rawName = "{sha}.paxheader", type = 'x'
# actual file entry: rawName = "{sha}.data",      type = '0'
```

The SHA in the placeholder matches the git blob SHA of the file content.
You can verify with `git cat-file -t <sha>` (returns "blob") and
`git ls-tree -r HEAD | grep <sha>` to find the real path.

## Fix (v2.2.12)

Added parsing of pax extended headers (type `x` and `g`) in `extractTar`:

1. Read the pax data block
2. Parse `<length> <key>=<value>\n` records
3. Extract the `path` key as `longName`
4. The next entry uses `longName` instead of its truncated `rawName`

This is the same mechanism already used for GNU long-name (type `L`).

## iOS Note

When adding iOS support, the same pax parsing logic will be needed in
whatever tar extraction code is used on the iOS side. Apple's `Archive`
framework handles pax headers natively, but if implementing a custom tar
reader, remember to handle type `x`/`g` entries.
