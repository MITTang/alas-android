import os
import shutil
import time
import urllib.request

sha = "1ed81bac5f2590b0d987504c9620561cd573776e"
url = "https://codeload.github.com/LmeSzinc/AzurLaneAutoScript/zip/" + sha
dst = "/tmp/alas_src_test.zip"

t0 = time.time()
req = urllib.request.Request(url, headers={"User-Agent": "ALAS-Android-Console"})
try:
    with urllib.request.urlopen(req, timeout=900) as r:
        with open(dst, "wb") as f:
            shutil.copyfileobj(r, f)
    size = os.path.getsize(dst)
    print("OK bytes=%s MB=%.1f sec=%.1f" % (size, size / 1048576.0, time.time() - t0))
except Exception as e:
    print("FAIL %s: %s" % (type(e).__name__, str(e)[:200]))
