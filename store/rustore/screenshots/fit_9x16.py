"""Converts arbitrary phone screenshots to 1080x1920 (9:16) for RuStore: scale to width 1080, then
centre-crop or pad (with the edge colour) vertically. Usage: python fit_9x16.py in1.png in2.png ...
Output: <name>_9x16.png next to each input."""
import sys
from PIL import Image

W, H = 1080, 1920

for path in sys.argv[1:]:
    im = Image.open(path).convert("RGB")
    im = im.resize((W, round(im.height * W / im.width)), Image.LANCZOS)
    if im.height > H:
        top = (im.height - H) // 2
        im = im.crop((0, top, W, top + H))
    elif im.height < H:
        bg = im.getpixel((W // 2, 0))
        canvas = Image.new("RGB", (W, H), bg)
        canvas.paste(im, (0, (H - im.height) // 2))
        im = canvas
    out = path.rsplit(".", 1)[0] + "_9x16.png"
    im.save(out, optimize=True)
    print(out)
