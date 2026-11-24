import json
import urllib.request

def probe(name, url, head=False):
    try:
        req = urllib.request.Request(url)
        if head:
            req.add_header("Range", "bytes=0-0")
        r = urllib.request.urlopen(req, timeout=15)
        print("%s: %s len=%s" % (name, r.status, r.headers.get("Content-Length")))
    except Exception as e:
        print("%s: FAIL %s %s" % (name, type(e).__name__, str(e)[:100]))

probe("api_commits", "https://api.github.com/repos/LmeSzinc/AzurLaneAutoScript/commits?per_page=1")
probe("codeload_zip", "https://codeload.github.com/LmeSzinc/AzurLaneAutoScript/zip/refs/heads/master", head=True)
