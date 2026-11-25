#!/usr/bin/env python3
"""Render Lucid's home screen in four candidate beige families.

Not part of the build: a design aid. Run from the repo root, then screenshot
design/app-screens.html with headless Chrome.
"""
import io

CREAM100 = '#F8F2E7'
CREAM50 = '#FCF8F1'
CREAM200 = '#F1E9DA'
INK900 = '#191814'
INK800 = '#23221E'
INK700 = '#2E2C27'
STONE600 = '#6A6254'
STONE500 = '#857B6C'
CLAY400 = '#B9825F'
CLAY600 = '#8A5A3C'
SAND400 = '#C2B9A6'

CANDS = [
    ("A · Greige", "No yellow at all — a warm grey.",
     {"200": "#E0DBD1", "300": "#D0C9BD", "400": "#C3BBAE", "500": "#A69C8D"}, False),
    ("B · Rosy taupe", "Warmth goes red, like the clay accent.",
     {"200": "#E4D8CE", "300": "#D3C2B5", "400": "#C7B2A4", "500": "#AA8F7F"}, True),
    ("C · Oat", "Still a little gold, much less of it.",
     {"200": "#E1D9CA", "300": "#D2C7B2", "400": "#C6BBA4", "500": "#A99E87"}, False),
    ("D · Cool bone", "Neutral greys with a hint of stone.",
     {"200": "#E2E0DA", "300": "#D2CFC7", "400": "#C6C2B8", "500": "#A9A49A"}, False),
]


def phone(name, why, ramp, pick=False):
    b200, b300, b400, b500 = ramp['200'], ramp['300'], ramp['400'], ramp['500']
    badge = '<span class="pick">my pick</span>' if pick else ''
    return f"""
<div class="col">
  <div class="head"><h3>{name}</h3>{badge}</div>
  <p class="why">{why}</p>
  <div class="phone">
    <div class="screen" style="background:{CREAM100}">
      <div class="statusbar"><span>9:41</span><span>▮▮▮ ▮</span></div>
      <div class="topbar">
        <div class="mark"><span class="lens" style="background:{CREAM50};border-color:{b400}"></span>Lucid</div>
      </div>
      <div class="body">
        <h4 style="color:{INK900}">Screen translator</h4>
        <p class="lede" style="color:{STONE600}">Drag the lens over any app. It reads the words underneath and translates them where you left it.</p>

        <div class="shell" style="background:{b300}">
          <div class="core" style="background:{CREAM50}">
            <div class="row"><span class="lbl">FROM</span><span class="val" style="color:{INK900}">Auto · detect</span></div>
            <div class="div" style="background:{b400}"></div>
            <div class="row"><span class="lbl">TO</span><span class="val" style="color:{INK900}">English</span></div>
            <div class="div" style="background:{b400}"></div>
            <div class="row"><span class="lbl">ENGINE</span><span class="val2" style="color:{CLAY600}">Online</span></div>
          </div>
        </div>

        <div class="shell" style="background:{b300}">
          <div class="core" style="background:{CREAM50}">
            <div class="eyebrow" style="background:{b200};color:{INK900}">READY</div>
            <p class="small" style="color:{STONE600}">Text access is on. The lens is not running.</p>
            <div class="slider" style="background:{CREAM200}">
              <div class="slide-shadow"></div>
              <span class="slabel" style="color:{STONE500}">Start the lens</span>
              <span class="knob"><span class="arrow" style="border-color:{INK900}"></span></span>
            </div>
          </div>
        </div>

        <div class="lensdemo">
          <div class="darkapp" style="background:{INK800}">
            <div class="darkline" style="color:#F3ECDE">¿Dónde está la estación?</div>
            <div class="darkhl" style="background:{b500}"></div>
          </div>
          <div class="card" style="background:{INK700}">
            <div class="cardhead" style="color:{SAND400}">SPANISH → ENGLISH</div>
            <div class="cardbody" style="color:#F3ECDE">Where is the station?</div>
            <div class="cardfoot" style="color:{CLAY400}">Copy</div>
          </div>
        </div>
      </div>
    </div>
  </div>
  <div class="swatches">
    <span style="background:{b200}"></span><span style="background:{b300}"></span>
    <span style="background:{b400}"></span><span style="background:{b500}"></span>
  </div>
  <div class="hexes">{b200} · {b300} · {b400} · {b500}</div>
</div>"""


CSS = """*{box-sizing:border-box;margin:0;padding:0}
body{background:#141310;color:#EDE6D8;font-family:'Inter','DejaVu Sans',system-ui,sans-serif;padding:30px 26px 34px}
h1{font-size:18px;font-weight:650;letter-spacing:-.01em}
.sub{color:#948B79;font-size:12px;margin:6px 0 22px}
.grid{display:flex;gap:20px;align-items:flex-start}
.col{width:302px}
.head{display:flex;align-items:center;gap:8px}
h3{font-size:13px;font-weight:650;color:#F3ECDE}
.pick{font-size:8.5px;letter-spacing:.08em;text-transform:uppercase;background:#B9825F;color:#191814;padding:2px 6px;border-radius:4px;font-weight:700}
.why{font-size:10.5px;color:#948B79;margin:4px 0 10px;height:14px}
.phone{border-radius:26px;padding:6px;background:#000;box-shadow:0 10px 26px rgba(0,0,0,.5)}
.screen{border-radius:21px;overflow:hidden;height:604px;position:relative}
.statusbar{display:flex;justify-content:space-between;font-size:8px;color:#6A6254;padding:7px 14px 0}
.topbar{padding:10px 16px 4px}
.mark{display:flex;align-items:center;gap:7px;font-size:12px;font-weight:600;color:#191814}
.lens{width:15px;height:15px;border-radius:50%;border:2px solid;display:inline-block}
.body{padding:6px 16px}
h4{font-size:21px;font-weight:650;letter-spacing:-.02em;line-height:1.15;margin:8px 0 6px}
.lede{font-size:10.5px;line-height:1.5;margin-bottom:14px}
.shell{border-radius:16px;padding:3px;margin-bottom:10px}
.core{border-radius:13px;padding:11px 13px}
.row{display:flex;justify-content:space-between;align-items:center;padding:5px 0}
.lbl{font-size:8px;letter-spacing:.12em;color:#857B6C;font-weight:600}
.val{font-size:11px;font-weight:500}
.val2{font-size:11px;font-weight:600}
.div{height:1px;margin:2px 0;opacity:.85}
.eyebrow{display:inline-block;font-size:7.5px;letter-spacing:.14em;padding:2px 6px;border-radius:4px;font-weight:700}
.small{font-size:10px;margin:7px 0 10px;line-height:1.45}
.slider{position:relative;height:38px;border-radius:19px;display:flex;align-items:center}
.slide-shadow{position:absolute;inset:0;border-radius:19px;box-shadow:inset 0 1px 2px rgba(25,24,20,.12),inset 0 -1px 1px rgba(255,255,255,.45)}
.slabel{font-size:10.5px;font-weight:500;margin-left:52px;position:relative}
.knob{position:absolute;left:5px;width:28px;height:28px;border-radius:50%;background:#FAF6EE;box-shadow:0 1.5px 2.5px rgba(25,24,20,.16);display:flex;align-items:center;justify-content:center}
.arrow{width:5px;height:5px;border-top:1.5px solid;border-right:1.5px solid;transform:rotate(45deg);opacity:.6}
.lensdemo{margin-top:14px;display:flex;flex-direction:column;gap:6px}
.darkapp{border-radius:12px;padding:11px 12px 16px;position:relative}
.darkline{font-size:11px}
.darkhl{position:absolute;left:12px;bottom:8px;height:3px;width:96px;border-radius:2px;opacity:.5}
.card{border-radius:12px;padding:9px 11px}
.cardhead{font-size:7px;letter-spacing:.12em;font-weight:600}
.cardbody{font-size:12.5px;margin:5px 0 4px}
.cardfoot{font-size:9.5px;font-weight:600}
.swatches{display:flex;gap:6px;margin-top:10px}
.swatches span{height:20px;flex:1;border-radius:5px;border:1px solid #2A2823}
.hexes{font-size:8.5px;color:#7E7666;margin-top:6px;font-variant-numeric:tabular-nums}
"""


def main():
    cols = "".join(phone(*c) for c in CANDS)
    html = (
        '<!doctype html><html><head><meta charset="utf-8"><style>'
        + CSS
        + '</style></head><body>\n<h1>Lucid — the whole home screen, four middles</h1>\n'
        '<p class="sub">Cream paper, charcoal ink and the clay accent are identical in all four. '
        'Only the beige family moves.</p>\n<div class="grid">'
        + cols
        + "</div>\n</body></html>"
    )
    io.open('design/app-screens.html', 'w', encoding='utf-8').write(html)
    print("wrote design/app-screens.html", len(html))


if __name__ == '__main__':
    main()
