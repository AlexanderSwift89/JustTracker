"""Renders store/rustore/icon-512.png from the same geometry as res/drawable/ic_launcher_foreground.xml.

The adaptive icon canvas is 108 dp; launchers show the central 72 dp, so that square is scaled to 512 px.
Run:  python store/rustore/make_icon.py   (needs Pillow)
"""
from PIL import Image, ImageDraw

SIZE = 512
SS = 4  # supersampling
CANVAS = 108.0
CROP_MIN, CROP_MAX = 18.0, 90.0  # central 72 dp
BG = (0x1B, 0x6E, 0x3A)
WHITE = (255, 255, 255)
YELLOW = (0xFF, 0xC8, 0x57)

def bezier(p0, p1, p2, p3, n=400):
    pts = []
    for i in range(n + 1):
        t = i / n
        x = (1-t)**3*p0[0] + 3*(1-t)**2*t*p1[0] + 3*(1-t)*t**2*p2[0] + t**3*p3[0]
        y = (1-t)**3*p0[1] + 3*(1-t)**2*t*p1[1] + 3*(1-t)*t**2*p2[1] + t**3*p3[1]
        pts.append((x, y))
    return pts

def to_px(p):
    s = SIZE * SS / (CROP_MAX - CROP_MIN)
    return ((p[0] - CROP_MIN) * s, (p[1] - CROP_MIN) * s)

def dp(v):
    return v * SIZE * SS / (CROP_MAX - CROP_MIN)

# M28,78 C28,62 40,64 46,56 C52,48 40,40 50,34 C58,29 66,38 72,34
path = bezier((28, 78), (28, 62), (40, 64), (46, 56)) + \
       bezier((46, 56), (52, 48), (40, 40), (50, 34))[1:] + \
       bezier((50, 34), (58, 29), (66, 38), (72, 34))[1:]

img = Image.new("RGB", (SIZE * SS, SIZE * SS), BG)
d = ImageDraw.Draw(img)
# Stroke as a dense brush of discs: PIL's wide polylines leave jagged joints on curves.
w = dp(7)
for p in path:
    x, y = to_px(p)
    d.ellipse((x - w/2, y - w/2, x + w/2, y + w/2), fill=WHITE)

def circle(center, r, fill):
    x, y = to_px(center); r = dp(r)
    d.ellipse((x - r, y - r, x + r, y + r), fill=fill)

circle((28, 78), 6, WHITE)
circle((72, 34), 11, YELLOW)
circle((72, 34), 4.5, BG)

img = img.resize((SIZE, SIZE), Image.LANCZOS)
img.save(__file__.replace("make_icon.py", "icon-512.png"), optimize=True)
print("icon-512.png written")
