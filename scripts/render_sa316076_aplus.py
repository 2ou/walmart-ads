from __future__ import annotations

from pathlib import Path
import io
import shutil

from PIL import Image, ImageDraw, ImageFont, ImageOps


ROOT = Path(r"D:\Tools\Code\walmart-ads")
OUT = ROOT / "output" / "SA316076_walmart_aplus_16x9"
BASES = OUT / "working_bases"
OUT.mkdir(parents=True, exist_ok=True)
BASES.mkdir(parents=True, exist_ok=True)

W, H = 1920, 1080
FONT_SERIF = r"C:\Windows\Fonts\georgia.ttf"
FONT_SERIF_BOLD = r"C:\Windows\Fonts\georgiab.ttf"
FONT_SERIF_ITALIC = r"C:\Windows\Fonts\georgiai.ttf"
FONT_SANS = r"C:\Windows\Fonts\arial.ttf"
FONT_SANS_BOLD = r"C:\Windows\Fonts\arialbd.ttf"

GENERATED = {
    "01": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-c1c19f9a-267b-4c89-b772-8aa317ee05af.png"),
    "02": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-b4afb820-d4fb-443d-8222-f86e55ad4e3c.png"),
    "03": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-d5c2343a-d5f9-499c-8e74-02ddb2ad3677.png"),
    "04": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-f6d9d685-9863-4c4b-af38-c6ef37300f32.png"),
    "05": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-11764695-90ec-48b3-84c7-35878af3afd9.png"),
    "06": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-496cec8f-6238-4f1c-8664-4ec46e5f451d.png"),
    "07": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-7313c112-545b-4063-89ee-9707892cce10.png"),
    "08": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-6c4401eb-8b04-4c5d-b9ad-e564b8259952.png"),
    "09": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-0114ced9-03c2-477c-a6e4-3967361bf3db.png"),
    "10": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-8a281a95-e386-40f6-8863-0173c6b820fa.png"),
    "11": Path(r"D:\CodexData\generated_images\01a11727-c260-7b83-9588-c0c0e56ebd18\exec-9064e60f-6d2f-4b88-93c7-de386835f565.png"),
}

OUTPUT_NAMES = {
    "01": "01_1_walmart_16x9.jpg",
    "02": "02_2_walmart_16x9.jpg",
    "03": "03_night-shirt-desktop_walmart_16x9.jpg",
    "04": "04_SA316076--pc_05_walmart_16x9.jpg",
    "05": "05_SA316076--pc_07_walmart_16x9.jpg",
    "06": "06_SA316076--pc_09_walmart_16x9.jpg",
    "07": "07_SA316076--pc_11_walmart_16x9.jpg",
    "08": "08_ytk_walmart_16x9.jpg",
    "09": "09_居家_walmart_16x9.jpg",
    "10": "10_聚会_2_walmart_16x9.jpg",
    "11": "11_豹纹_1_walmart_16x9.jpg",
}


def font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size=size)


def base(key: str) -> Image.Image:
    src = GENERATED[key]
    if not src.exists():
        raise FileNotFoundError(src)
    shutil.copy2(src, BASES / f"{key}_generated_base.png")
    return ImageOps.fit(Image.open(src).convert("RGB"), (W, H), method=Image.Resampling.LANCZOS)


def rgba_panel(im: Image.Image, box, fill, radius=24, outline=None, width=2):
    ov = Image.new("RGBA", im.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    od.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)
    return Image.alpha_composite(im.convert("RGBA"), ov).convert("RGB")


def wrap(draw: ImageDraw.ImageDraw, text: str, fnt, max_width: int) -> list[str]:
    words = text.split()
    lines, current = [], ""
    for word in words:
        probe = word if not current else current + " " + word
        if draw.textlength(probe, font=fnt) <= max_width:
            current = probe
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def centered(draw, text, xy, fnt, fill, anchor="mm", stroke_width=0, stroke_fill=None, spacing=4):
    draw.text(xy, text, font=fnt, fill=fill, anchor=anchor,
              stroke_width=stroke_width, stroke_fill=stroke_fill, spacing=spacing, align="center")


def draw_pills(draw, labels, y, x0, x1, fill, text_fill, outline=None, size=24):
    fnt = font(FONT_SANS_BOLD, size)
    gap = 18
    widths = [int(draw.textlength(t, font=fnt)) + 42 for t in labels]
    total = sum(widths) + gap * (len(labels) - 1)
    x = x0 + max(0, (x1 - x0 - total) // 2)
    for label, ww in zip(labels, widths):
        draw.rounded_rectangle((x, y, x + ww, y + 50), radius=25,
                               fill=fill, outline=outline, width=2)
        centered(draw, label, (x + ww / 2, y + 26), fnt, text_fill)
        x += ww + gap


def double_arrow(draw, start, end, color="#245a51", width=5, head=14):
    x1, y1 = start
    x2, y2 = end
    draw.line((x1, y1, x2, y2), fill=color, width=width)
    if y1 == y2:
        draw.polygon([(x1, y1), (x1 + head, y1 - head // 2), (x1 + head, y1 + head // 2)], fill=color)
        draw.polygon([(x2, y2), (x2 - head, y2 - head // 2), (x2 - head, y2 + head // 2)], fill=color)
    else:
        draw.polygon([(x1, y1), (x1 - head // 2, y1 + head), (x1 + head // 2, y1 + head)], fill=color)
        draw.polygon([(x2, y2), (x2 - head // 2, y2 - head), (x2 + head // 2, y2 - head)], fill=color)


def save_jpeg(im: Image.Image, path: Path):
    quality = 92
    while True:
        buf = io.BytesIO()
        im.convert("RGB").save(buf, "JPEG", quality=quality, optimize=True, progressive=True, subsampling=0)
        if len(buf.getvalue()) <= 1_950_000 or quality <= 80:
            path.write_bytes(buf.getvalue())
            return
        quality -= 2


def render_01():
    im = base("01")
    im = rgba_panel(im, (255, 760, 1665, 1040), (248, 247, 239, 232), 34,
                    outline=(255, 255, 255, 205), width=3)
    d = ImageDraw.Draw(im)
    centered(d, "PINKSIR WOMEN'S PLUS SIZE", (960, 815), font(FONT_SANS_BOLD, 26), "#526c63")
    centered(d, "LONG NIGHTGOWN", (960, 884), font(FONT_SERIF_BOLD, 68), "#172823")
    centered(d, "Soft, stretchy and made with pockets", (960, 946), font(FONT_SERIF_ITALIC, 29), "#47635a")
    centered(d, "SOFT, STRETCHY AND MADE WITH POCKETS  ×  AVAILABLE IN XL TO 4X",
             (960, 1001), font(FONT_SANS_BOLD, 21), "#243f36")
    save_jpeg(im, OUT / OUTPUT_NAMES["01"])


def render_02():
    im = base("02")
    im = rgba_panel(im, (210, 820, 1710, 1045), (255, 252, 247, 235), 28,
                    outline=(255, 255, 255, 200), width=2)
    d = ImageDraw.Draw(im)
    centered(d, "CASUAL LOUNGE WEAR", (960, 865), font(FONT_SERIF_BOLD, 52), "#2b3934")
    draw_pills(d, ["Lightweight", "Soft", "Machine Washable", "Skin Friendly"],
               928, 270, 1650, "#eef4ee", "#334c43", outline="#9eb4aa", size=23)
    save_jpeg(im, OUT / OUTPUT_NAMES["02"])


def render_03():
    im = base("03")
    im = rgba_panel(im, (690, 32, 1240, 170), (255, 252, 247, 238), 22)
    im = rgba_panel(im, (780, 855, 1775, 1035), (255, 251, 245, 232), 24)
    d = ImageDraw.Draw(im)
    centered(d, '“NIGHT SHIRT”', (965, 95), font(FONT_SERIF_BOLD, 58), "#4b2525")
    centered(d, "VOGUE", (1850, 540), font(FONT_SERIF_BOLD, 28), "#d6a460")
    body = "Soft Sleepdress is great as sleepwear, sleep shirts,\nnight dress, loungewear and house dress."
    centered(d, body, (1278, 937), font(FONT_SANS, 29), "#563d32", spacing=10)
    save_jpeg(im, OUT / OUTPUT_NAMES["03"])


def render_04():
    im = base("04")
    im = rgba_panel(im, (150, 884, 1770, 1052), (251, 246, 236, 238), 25)
    d = ImageDraw.Draw(im)
    centered(d, "EFFORTLESS EVERYDAY STYLE", (960, 934), font(FONT_SERIF_BOLD, 46), "#39493f")
    draw_pills(d, ["DAILY", "SLEEP", "VACATION"], 975, 600, 1320,
               "#789586", "#ffffff", size=19)
    save_jpeg(im, OUT / OUTPUT_NAMES["04"])


def render_05():
    src = base("05")
    im = Image.new("RGB", (W, H), "#f7f5ef")

    # One large fabric image on the left.
    hero = ImageOps.fit(src.crop((0, 0, 1100, 1080)), (995, 1080), method=Image.Resampling.LANCZOS)
    im.paste(hero, (0, 0))

    # Four equal detail images in a clean 2 x 2 grid on the right.
    grid_x, grid_y, grid_w, grid_h, gap = 1015, 125, 905, 955, 12
    tile_w = (grid_w - gap) // 2
    tile_h = (grid_h - gap) // 2
    crops = [
        (1100, 0, 1920, 365),       # neckline
        (1100, 365, 1920, 700),     # sleeve
        (1100, 700, 1510, 1080),    # pocket
        (1510, 700, 1920, 1080),    # hem
    ]
    positions = [
        (grid_x, grid_y),
        (grid_x + tile_w + gap, grid_y),
        (grid_x, grid_y + tile_h + gap),
        (grid_x + tile_w + gap, grid_y + tile_h + gap),
    ]
    for crop_box, (xx, yy) in zip(crops, positions):
        tile = ImageOps.fit(src.crop(crop_box), (tile_w, tile_h), method=Image.Resampling.LANCZOS)
        im.paste(tile, (xx, yy))

    im = rgba_panel(im, (42, 48, 948, 355), (29, 58, 52, 226), 26,
                    outline=(255, 255, 255, 150), width=2)
    im = rgba_panel(im, (1015, 18, 1920, 112), (250, 247, 238, 244), 18)
    d = ImageDraw.Draw(im)
    centered(d, "KNIT FABRIC", (495, 110), font(FONT_SERIF_BOLD, 55), "#ffffff")
    centered(d, "BREATHABILITY  /  ELASTICITY  /  WRINKLE FREE  /  COMFORTABLE",
             (495, 184), font(FONT_SANS_BOLD, 20), "#dfece6")
    centered(d, "95% Polyester  |  5% Spandex", (495, 258), font(FONT_SANS, 30), "#ffffff")
    centered(d, "DESIGN DETAILS", (1468, 66), font(FONT_SERIF_BOLD, 44), "#3a5046")
    save_jpeg(im, OUT / OUTPUT_NAMES["05"])


def render_06():
    im = base("06")
    im = rgba_panel(im, (34, 760, 570, 1030), (255, 252, 246, 241), 28,
                    outline=(230, 219, 203, 255), width=3)
    d = ImageDraw.Draw(im)
    centered(d, "ONE DRESS", (302, 835), font(FONT_SANS_BOLD, 27), "#8a6c55")
    centered(d, "EVERY", (302, 912), font(FONT_SERIF_BOLD, 52), "#49372d")
    centered(d, "OCCASION", (302, 970), font(FONT_SERIF_BOLD, 48), "#49372d")
    save_jpeg(im, OUT / OUTPUT_NAMES["06"])


def render_07():
    portrait = Image.open(GENERATED["07"]).convert("RGB")
    shutil.copy2(GENERATED["07"], BASES / "07_generated_base.png")
    canvas = Image.new("RGB", (W, H), "#f5f3ed")
    d = ImageDraw.Draw(canvas)

    # Full-height model panel with no unused whitespace.
    model_panel = Image.new("RGB", (720, 1080), "#f4eee5")
    model = ImageOps.contain(portrait, (720, 1080), method=Image.Resampling.LANCZOS)
    model_panel.paste(model, ((720 - model.width) // 2, (1080 - model.height) // 2))
    canvas.paste(model_panel, (1200, 0))

    # Dense information area on the left.
    d = ImageDraw.Draw(canvas)
    d.rectangle((0, 0, 1200, 116), fill="#315d56")
    centered(d, "SIZE CHART (INCH)", (600, 58), font(FONT_SERIF_BOLD, 50), "#ffffff")

    x0, y0 = 45, 148
    cols = [0, 230, 460, 650, 840, 1110]
    headers = ["Size", "Bust", "Waist", "Hip", "Dress Length"]
    rows = [
        ["1X (14–16)", "46.9", "46.5", "52.6", "53.5"],
        ["2X (18–20)", "50.4", "50.0", "56.1", "54.3"],
        ["3X (22–24)", "53.9", "53.9", "59.1", "55.2"],
        ["4X (26–28)", "58.3", "57.9", "64.0", "56.0"],
    ]
    row_h = 78
    table_right = x0 + cols[-1]
    d.rounded_rectangle((x0, y0, table_right, y0 + row_h * 5), radius=18, fill="#ffffff", outline="#9eb6ae", width=3)
    d.rounded_rectangle((x0, y0, table_right, y0 + row_h), radius=18, fill="#dce8e3")
    d.rectangle((x0, y0 + 50, table_right, y0 + row_h), fill="#dce8e3")
    hf = font(FONT_SANS_BOLD, 24)
    rf = font(FONT_SANS, 25)
    for i, h in enumerate(headers):
        cx = x0 + (cols[i] + cols[i + 1]) / 2
        centered(d, h, (cx, y0 + row_h / 2), hf, "#29473f")
    for r, row in enumerate(rows):
        yy = y0 + row_h * (r + 1)
        if r % 2:
            d.rectangle((x0 + 2, yy, table_right - 2, yy + row_h), fill="#f4f7f5")
        d.line((x0, yy, table_right, yy), fill="#b7c7c1", width=2)
        for i, val in enumerate(row):
            cx = x0 + (cols[i] + cols[i + 1]) / 2
            centered(d, val, (cx, yy + row_h / 2), rf, "#273b35")
    for c in cols[1:-1]:
        xx = x0 + c
        d.line((xx, y0, xx, y0 + row_h * 5), fill="#b7c7c1", width=2)

    # Two compact panels fill the lower area and preserve the original notes.
    panel_y = 575
    d.rounded_rectangle((45, panel_y, 575, 1035), radius=22, fill="#ffffff", outline="#c3d0ca", width=2)
    d.rounded_rectangle((600, panel_y, 1155, 1035), radius=22, fill="#e4eee9", outline="#b1c5bb", width=2)
    centered(d, "HOW TO MEASURE", (310, 622), font(FONT_SERIF_BOLD, 31), "#315d56")
    measure_rows = [
        ("BUST", "Measure around the fullest part."),
        ("WAIST", "Measure around your natural waist."),
        ("HIP", "Measure around the fullest part."),
        ("DRESS LENGTH", "Measure from shoulder to hem."),
    ]
    yy = 675
    for label, desc in measure_rows:
        d.rounded_rectangle((75, yy, 238, yy + 48), radius=22, fill="#315d56")
        centered(d, label, (156, yy + 25), font(FONT_SANS_BOLD, 17), "#ffffff")
        d.text((258, yy + 4), desc, font=font(FONT_SANS, 20), fill="#4e5c57")
        yy += 82

    centered(d, "FIT NOTES", (878, 622), font(FONT_SERIF_BOLD, 31), "#315d56")
    note_f = font(FONT_SANS, 20)
    notes = [
        "1. Please allow 0.2–1 inches difference due to manual measurement.",
        "2. Please read this garment size chart, not the Amazon size chart.",
        "3. Color may appear lighter or darker on different screens.",
    ]
    yy = 676
    for txt in notes:
        lines = wrap(d, txt, note_f, 485)
        for j, line in enumerate(lines):
            d.text((635, yy + j * 29), line, font=note_f, fill="#46544f")
        yy += len(lines) * 29 + 34

    # High-contrast measurement lines anchored directly on the body.
    guide = "#1f665a"
    for label, py, x1, x2 in [
        ("BUST", 284, 1375, 1748),
        ("WAIST", 388, 1402, 1720),
        ("HIP", 505, 1370, 1755),
    ]:
        d.rounded_rectangle((1218, py - 25, 1352, py + 25), radius=22, fill="#ffffff", outline=guide, width=3)
        centered(d, label, (1285, py + 1), font(FONT_SANS_BOLD, 18), guide)
        d.line((1352, py, x1, py), fill=guide, width=4)
        double_arrow(d, (x1, py), (x2, py), color=guide, width=5, head=16)

    double_arrow(d, (1840, 165), (1840, 952), color=guide, width=5, head=17)
    tag = Image.new("RGBA", (230, 52), (255, 255, 255, 245))
    td = ImageDraw.Draw(tag)
    td.rounded_rectangle((1, 1, 229, 51), radius=22, fill=(255, 255, 255, 245), outline=guide, width=3)
    centered(td, "DRESS LENGTH", (115, 27), font(FONT_SANS_BOLD, 18), guide)
    tag = tag.rotate(90, expand=True, resample=Image.Resampling.BICUBIC)
    canvas.paste(tag, (1782, 454), tag)
    save_jpeg(canvas, OUT / OUTPUT_NAMES["07"])


def render_08():
    im = base("08")
    im = rgba_panel(im, (720, 36, 1202, 1045), (250, 210, 220, 242), 0)
    d = ImageDraw.Draw(im)
    centered(d, "Y2K FAVORITE", (961, 116), font(FONT_SANS_BOLD, 25), "#9b3153")
    centered(d, "COMFORT", (961, 205), font(FONT_SERIF_BOLD, 54), "#4a2733")
    centered(d, "MODE", (961, 263), font(FONT_SERIF_BOLD, 54), "#4a2733")
    centered(d, "SWEET DREAMS.\nBIG PLANS.", (961, 405), font(FONT_SERIF_BOLD, 48), "#8b2948", spacing=8)
    body = "Made for your sleepover, coffee run,\nor beach-day reset."
    centered(d, body, (961, 535), font(FONT_SANS, 27), "#5c3944", spacing=8)
    draw_pills(d, ["SLEEPOVER CUTE", "VACAY READY"], 625, 750, 1172,
               "#fff6f8", "#9b3153", outline="#cf7f99", size=18)
    draw_pills(d, ["POCKET ENERGY"], 695, 750, 1172,
               "#fff6f8", "#9b3153", outline="#cf7f99", size=18)
    centered(d, "CHILL  |  MOVE  |  ESCAPE", (961, 922), font(FONT_SANS_BOLD, 22), "#8e3953")
    save_jpeg(im, OUT / OUTPUT_NAMES["08"])


def render_09():
    im = base("09")
    im = rgba_panel(im, (925, 42, 1352, 1038), (255, 255, 255, 244), 0)
    d = ImageDraw.Draw(im)
    centered(d, "PLUS SIZE", (1138, 142), font(FONT_SANS_BOLD, 24), "#5b756c")
    centered(d, "LONG", (1138, 230), font(FONT_SERIF_BOLD, 53), "#243a33")
    centered(d, "NIGHTGOWN", (1138, 289), font(FONT_SERIF_BOLD, 49), "#243a33")
    centered(d, "SOFT  |  BREATHABLE  |  EASY CARE", (1138, 377), font(FONT_SANS_BOLD, 17), "#587168")
    body = "Comfort made for sleep, lounging,\nand everyday home life."
    centered(d, body, (1138, 472), font(FONT_SANS, 25), "#4b5652", spacing=8)
    draw_pills(d, ["RELAXED FIT"], 576, 950, 1327, "#eef4f0", "#365448", outline="#9eb8ad", size=18)
    draw_pills(d, ["FUNCTIONAL POCKETS"], 644, 950, 1327, "#eef4f0", "#365448", outline="#9eb8ad", size=18)
    centered(d, "SLEEP  |  LOUNGE  |  HOUSE DRESS", (1138, 900), font(FONT_SANS_BOLD, 18), "#52675f")
    save_jpeg(im, OUT / OUTPUT_NAMES["09"])


def render_10():
    im = base("10")
    im = rgba_panel(im, (400, 0, 780, 1080), (255, 250, 243, 248), 0)
    d = ImageDraw.Draw(im)
    centered(d, "CELEBRATE AT HOME", (590, 103), font(FONT_SANS_BOLD, 22), "#a0564c")
    centered(d, "HOLIDAY", (590, 190), font(FONT_SERIF_BOLD, 49), "#5d3230")
    centered(d, "HOME PARTY", (590, 247), font(FONT_SERIF_BOLD, 40), "#5d3230")
    centered(d, "COZY NIGHTS.\nHAPPY HOUR.", (590, 381), font(FONT_SERIF_BOLD, 40), "#a43e3e", spacing=7)
    body = "For gift wrapping, girls nights,\nand every warm moment in between."
    centered(d, body, (590, 520), font(FONT_SANS, 23), "#654d45", spacing=7)
    draw_pills(d, ["GIFT WRAP"], 640, 425, 755, "#f6e8dc", "#8b4b42", outline="#d9aa92", size=17)
    draw_pills(d, ["GIRLS NIGHT"], 706, 425, 755, "#f6e8dc", "#8b4b42", outline="#d9aa92", size=17)
    draw_pills(d, ["MOVIE MARATHON"], 772, 425, 755, "#f6e8dc", "#8b4b42", outline="#d9aa92", size=17)
    centered(d, "MERRY MOMENTS", (590, 962), font(FONT_SANS_BOLD, 18), "#9a5a4e")
    save_jpeg(im, OUT / OUTPUT_NAMES["10"])


def render_11():
    im = base("11")
    im = rgba_panel(im, (620, 610, 1420, 1052), (251, 239, 219, 246), 30,
                    outline=(255, 255, 255, 220), width=3)
    d = ImageDraw.Draw(im)
    centered(d, "THE ANIMAL PRINT EDIT", (1020, 662), font(FONT_SANS_BOLD, 23), "#86613d")
    centered(d, "LEOPARD", (1020, 732), font(FONT_SERIF_BOLD, 53), "#412c1f")
    centered(d, "LOUNGE EDIT", (1020, 785), font(FONT_SERIF_BOLD, 37), "#412c1f")
    centered(d, "COZY NIGHTS. BOLD ENERGY.", (1020, 842), font(FONT_SANS_BOLD, 22), "#7a4d2c")
    centered(d, "From slow mornings to celebratory moments at home.",
             (1020, 889), font(FONT_SANS, 23), "#584536")
    draw_pills(d, ["READ & RELAX", "HOST AT HOME", "PARTY READY"], 948, 660, 1380,
               "#fffaf1", "#6e482b", outline="#c9a57d", size=16)
    save_jpeg(im, OUT / OUTPUT_NAMES["11"])


def main():
    for func in [render_01, render_02, render_03, render_04, render_05, render_06,
                 render_07, render_08, render_09, render_10, render_11]:
        func()
    print(f"Rendered {len(OUTPUT_NAMES)} files to {OUT}")


if __name__ == "__main__":
    main()
