import os
import shutil
import time
import zipfile

ZIP = "/tmp/alas_src_test.zip"
DEST = "/tmp/alas_extract_test"

KEEP = [
    "deploy/Termux",
    "deploy/AndroidGui",
    "alas_cli.py",
    "config/alas.json",
    "config/screen_adapt.json",
    "deploy.yaml",
]


def is_kept(rel):
    for k in KEEP:
        if rel == k or rel.startswith(k + "/"):
            return True
    return False


if not os.path.exists(ZIP):
    print("NO ZIP")
    raise SystemExit(1)

shutil.rmtree(DEST, ignore_errors=True)
os.makedirs(DEST, exist_ok=True)

t0 = time.time()
top = set()
written = 0
skipped = 0
with zipfile.ZipFile(ZIP) as z:
    names = z.namelist()
    print("entries=%d" % len(names))
    print("first=%s" % (names[0] if names else ""))
    for n in names:
        if n.endswith("/"):
            continue
        parts = n.split("/", 1)
        if len(parts) < 2 or not parts[1]:
            continue
        top.add(parts[0])
        rel = parts[1]
        if is_kept(rel):
            skipped += 1
            continue
        dst = os.path.join(DEST, rel)
        d = os.path.dirname(dst)
        if d:
            os.makedirs(d, exist_ok=True)
        with z.open(n) as src, open(dst, "wb") as f:
            shutil.copyfileobj(src, f)
        written += 1

print("top_dirs=%s" % top)
print("written=%d skipped=%d sec=%.1f" % (written, skipped, time.time() - t0))
for probe in ("module/", "deploy/", "requirements.txt", "alas.py", "gui.py"):
    p = os.path.join(DEST, probe)
    print("check %s -> %s" % (probe, os.path.exists(p)))
