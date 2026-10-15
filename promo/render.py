"""Render promo.html to an MP4, frame by frame, with a headless browser.

usage: python render.py LANG [OUT.mp4] [--fps 60] [--browser msedge|chrome|chromium]
Each frame calls window.render(t) in the page and screenshots it, so motion is exact at any fps
regardless of machine speed. Frames are piped straight into ffmpeg (FF env var, or ffmpeg on PATH).
"""
import argparse
import os
import pathlib
import subprocess
import time

from playwright.sync_api import sync_playwright

ap = argparse.ArgumentParser()
ap.add_argument("lang")
ap.add_argument("out", nargs="?")
ap.add_argument("--fps", type=int, default=60)
ap.add_argument("--browser", default="msedge")
a = ap.parse_args()
here = pathlib.Path(__file__).resolve().parent
out = a.out or str(here / f"mingui-promo-{a.lang}.mp4")
ff = os.environ.get("FF", "ffmpeg")

with sync_playwright() as p:
    kw = {} if a.browser == "chromium" else {"channel": a.browser}
    b = p.chromium.launch(**kw)
    page = b.new_page(viewport={"width": 1080, "height": 1920})
    page.goto((here / "promo.html").as_uri() + f"?lang={a.lang}")
    page.evaluate("window.ready")
    dur = page.evaluate("window.DURATION")
    n = int(dur * a.fps)
    enc = subprocess.Popen([ff, "-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", str(a.fps), "-c:v", "png",
                            "-i", "-", "-c:v", "libx264", "-crf", "17", "-preset", "slow", "-pix_fmt", "yuv420p",
                            "-movflags", "+faststart", out], stdin=subprocess.PIPE)
    t0 = time.time()
    for i in range(n):
        page.evaluate(f"render({i / a.fps})")
        enc.stdin.write(page.screenshot(type="png"))
        if i % 300 == 0:
            print(f"{a.lang} frame {i}/{n} {time.time() - t0:.0f}s", flush=True)
    enc.stdin.close()
    enc.wait()
    b.close()
print(f"{a.lang} done {out} in {time.time() - t0:.0f}s")
