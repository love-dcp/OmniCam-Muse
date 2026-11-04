import sys, zipfile, os
from pathlib import Path

src, out = Path(sys.argv[1]), Path(sys.argv[2])
if out.exists():
    out.unlink()

# KernelSU extracts a module with the permissions recorded in the zip. zipfile.write() records
# none, so every script landed as 0644 and post-fs-data.sh / service.sh / action.sh could not be
# executed after a reboot -- the module looked installed and did nothing. Record real unix modes
# (0755 for scripts, 0644 for data). customize.sh and selfheal.sh also chmod explicitly, because
# some unzip implementations ignore external_attr; this is the belt to their braces.
SCRIPT_SUFFIXES = {".sh"}

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(src.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(src).as_posix()
        info = zipfile.ZipInfo.from_file(p, rel)
        mode = 0o755 if p.suffix in SCRIPT_SUFFIXES else 0o644
        info.external_attr = (0o100000 | mode) << 16
        info.create_system = 3  # unix, so the mode above is honoured
        info.compress_type = zipfile.ZIP_DEFLATED
        with open(p, "rb") as f:
            z.writestr(info, f.read())

with zipfile.ZipFile(out) as z:
    assert z.testzip() is None
    assert all("\\" not in n for n in z.namelist())
print(out, out.stat().st_size)
