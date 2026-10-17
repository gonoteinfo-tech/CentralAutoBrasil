#!/usr/bin/env python3
"""Check a short live audio sample; never retain or redistribute radio audio."""
import concurrent.futures
import datetime
import json
import pathlib
import shutil
import subprocess
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]


class SecureRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise ValueError("Redirect requires cleartext HTTP")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def check(station):
    result = {"id": station["id"], "name": station["name"], "url": station["url"]}
    try:
        opener = urllib.request.build_opener(SecureRedirect())
        request = urllib.request.Request(station["url"], headers={"User-Agent": "RoadstarConnect/0.1.1"})
        with opener.open(request, timeout=12) as response:
            sample = response.read(32768)
            result["final_url"] = response.url
            result["content_type"] = response.headers.get("Content-Type", "")
        hls = sample.lstrip().startswith(b"#EXTM3U")
        args = ["ffprobe", "-v", "error", "-show_entries", "stream=codec_name,codec_type", "-of", "json"]
        if hls:
            args += ["-rw_timeout", "12000000", "-protocol_whitelist", "https,tls,tcp,crypto",
                     "-read_intervals", "%+1", "-i", result["final_url"]]
        else:
            args += ["-i", "pipe:0"]
        probe = subprocess.run(args, input=None if hls else sample, capture_output=True, timeout=30)
        streams = json.loads(probe.stdout or b"{} ").get("streams", [])
        codecs = sorted({s["codec_name"] for s in streams if s.get("codec_type") == "audio"})
        if not codecs:
            raise ValueError("No audio detected: " + probe.stderr.decode(errors="replace")[:180])
        result.update(ok=True, codecs=codecs, format="HLS" if hls else "audio")
    except Exception as error:
        result.update(ok=False, error=str(error)[:300])
    return result


def main():
    if shutil.which("ffprobe") is None:
        raise SystemExit("Install FFmpeg/ffprobe before checking live radio audio.")
    stations = json.loads((ROOT / "app/src/main/res/raw/radio_catalog.json").read_text())
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(check, stations))
    report = {"checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "stations": results}
    target = ROOT / "reports/radio-stream-check.json"
    target.parent.mkdir(exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    for item in results:
        print(("PASS " if item["ok"] else "FAIL ") + item["name"] + " | "
              + (", ".join(item["codecs"]) if item["ok"] else item["error"]))
    print(f"Audio detected: {sum(item['ok'] for item in results)}/{len(results)}")
    raise SystemExit(0 if all(item["ok"] for item in results) else 1)


if __name__ == "__main__":
    main()
