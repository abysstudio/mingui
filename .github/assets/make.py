"""Renders the README artwork (banner, download cards, screenshot strip) with headless Chrome.

usage: python3 .github/assets/make.py RAW_SCREENSHOT_DIR
RAW_SCREENSHOT_DIR holds raw_en_*.png phone captures (1080x2400), e.g. from the store screenshot run.
"""
import base64
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
RAW = pathlib.Path(sys.argv[1])

BUDDY = """<svg viewBox="0 -14 120 136" width="{w}" height="{h}" style="overflow:visible"><defs><linearGradient id="g{i}" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="#9a80ff"/><stop offset="1" stop-color="#5b3fd9"/></linearGradient></defs>
<ellipse cx="60" cy="112" rx="34" ry="6" fill="#000" opacity=".35"/>
<ellipse cx="60" cy="68" rx="46" ry="42" fill="url(#g{i})"/><ellipse cx="44" cy="46" rx="14" ry="7" fill="#fff" opacity=".18"/>
<circle cx="42" cy="64" r="10" fill="#fff"/><circle cx="78" cy="64" r="10" fill="#fff"/>
<circle cx="43.5" cy="65.5" r="5.5" fill="#140f2e"/><circle cx="79.5" cy="65.5" r="5.5" fill="#140f2e"/>
<circle cx="41.5" cy="63" r="2" fill="#fff"/><circle cx="77.5" cy="63" r="2" fill="#fff"/>
<path d="M50 82q10 9 20 0" stroke="#140f2e" stroke-width="4" fill="none" stroke-linecap="round"/>
<ellipse cx="30" cy="78" rx="6" ry="3.5" fill="#ff8fc0" opacity=".55"/><ellipse cx="90" cy="78" rx="6" ry="3.5" fill="#ff8fc0" opacity=".55"/></svg>"""

HEAD = """<!doctype html><html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@500;600;700;800&display=block" rel="stylesheet">
<style>html,body{margin:0;background:transparent}body{font-family:Inter,sans-serif;color:#fff;overflow:hidden}
.phone{position:absolute;border-radius:40px;padding:7px;background:linear-gradient(145deg,#5a5a66,#16161c 40%,#2e2e37);
 box-shadow:0 40px 80px rgba(0,0,0,.55),0 0 0 2px #62626e inset}.phone img{display:block;width:100%;border-radius:34px}
</style></head><body>"""


def uri(p):
    return "data:image/png;base64," + base64.b64encode(pathlib.Path(p).read_bytes()).decode()


def render(name, w, h, body):
    f = HERE / "_tmp.html"
    f.write_text(HEAD + body + "</body></html>")
    out = HERE / name
    subprocess.run(["google-chrome", "--headless=new", "--hide-scrollbars", "--default-background-color=00000000",
                    "--virtual-time-budget=5000", f"--window-size={w},{h}", f"--screenshot={out}", f.as_uri()],
                   capture_output=True, check=True)
    f.unlink()
    print("wrote", out)


shot = lambda n: uri(RAW / f"raw_en_{n}.png")

# Banner, 1280x640: buddy + name + tagline on the left, two tilted phones on the right.
render("banner.png", 1280, 640, f"""
<div style="position:absolute;inset:0;border-radius:28px;overflow:hidden;
 background:radial-gradient(700px 520px at 78% 30%,#3b2a8c 0%,transparent 65%),radial-gradient(600px 500px at 10% 110%,#1c5a48 0%,transparent 60%),#0b0918">
 <div style="position:absolute;left:84px;top:96px;width:560px">
  {BUDDY.format(w=120, h=136, i=1)}
  <div style="font-size:112px;font-weight:800;letter-spacing:-4px;margin-top:6px;line-height:1">Mingui</div>
  <div style="font-size:36px;font-weight:700;line-height:1.2;margin-top:22px;letter-spacing:-.5px;color:#d4cff0">Finish your routine.<br>
   <span style="background:linear-gradient(90deg,#c4b8ff,#7c5cff);-webkit-background-clip:text;color:transparent">Then your apps unlock.</span></div>
  <div style="display:flex;gap:12px;margin-top:34px">
   <span style="padding:10px 20px;border-radius:999px;border:2px solid rgba(47,209,128,.45);color:#2fd180;font-size:20px;font-weight:700;background:rgba(47,209,128,.1)">100% offline</span>
   <span style="padding:10px 20px;border-radius:999px;border:2px solid rgba(196,184,255,.35);color:#c4b8ff;font-size:20px;font-weight:700;background:rgba(124,92,255,.12)">No accounts</span>
   <span style="padding:10px 20px;border-radius:999px;border:2px solid rgba(255,159,67,.4);color:#ff9f43;font-size:20px;font-weight:700;background:rgba(255,159,67,.1)">Open source</span>
  </div>
 </div>
 <div style="position:absolute;left:0;top:0;width:1280px;height:640px;perspective:2000px">
  <div class="phone" style="left:760px;top:70px;width:250px;transform:rotateY(18deg) rotateZ(-5deg)"><img src="{shot('checklist')}"></div>
  <div class="phone" style="left:965px;top:120px;width:250px;transform:rotateY(-14deg) rotateZ(5deg)"><img src="{shot('celebrate2')}"></div>
 </div>
</div>""")


def card(name, eyebrow, title, sub, accent, icon):
    render(name, 440, 132, f"""
<div style="position:absolute;inset:0;border-radius:22px;background:linear-gradient(135deg,#1a1630,#0f0d1f);
 border:2px solid {accent}55;display:flex;align-items:center;gap:20px;padding:0 26px;box-sizing:border-box">
 <div style="width:76px;height:76px;border-radius:20px;flex:none;display:flex;align-items:center;justify-content:center;background:{accent}22">{icon}</div>
 <div><div style="font-size:14px;font-weight:700;letter-spacing:1.6px;color:{accent};text-transform:uppercase">{eyebrow}</div>
 <div style="font-size:26px;font-weight:800;letter-spacing:-.5px;margin-top:2px">{title}</div>
 <div style="font-size:16px;color:#a9a3c9;margin-top:3px">{sub}</div></div></div>""")


DL = """<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#c4b8ff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12m0 0l-5-5m5 5l5-5M4 19h16"/></svg>"""
PLAY = """<svg width="38" height="38" viewBox="0 0 24 24"><path d="M6 3.5v17l14-8.5z" fill="#2fd180"/></svg>"""
card("card-root.png", "GitHub · rooted phones", "Mingui Root", "Download the latest APK", "#7c5cff", DL)
card("card-play.png", "Google Play · every phone", "Mingui", "Coming soon", "#2fd180", PLAY)

# Screenshot strip, 1600x740: five phones in a row.
names = ["checklist", "blocked_2", "celebrate2", "personalize", "stats"]
phones = "".join(f'<div class="phone" style="left:{40 + i * 312}px;top:{30 if i % 2 else 70}px;width:280px"><img src="{shot(n)}"></div>'
                 for i, n in enumerate(names))
render("screens.png", 1600, 740, f'<div style="position:absolute;inset:0;overflow:hidden">{phones}</div>')

# Small buddy for the README footer.
render("buddy.png", 128, 128, f'<div style="position:absolute;left:4px;top:0">{BUDDY.format(w=120, h=136, i=2)}</div>')
