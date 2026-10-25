#!/usr/bin/env python3
"""Generates OteTV's original art: block and item models, blockstates, textures, the logo and sounds.

Everything is procedural and original (no third-party images or recordings). Re-run after editing geometry:

    python3 tools/assets/generate_assets.py

Model space: pixels, front faces north (-Z), the anchor cell spans x 0..16, columns extend along +X. The JSON
block models are the Blockbench-compatible sources (Blockbench opens Java block models directly).
"""
import json
import math
import os
import random
import shutil
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RES = os.path.join(ROOT, 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'assets', 'otetv')
TEX_BLOCK = os.path.join(ASSETS, 'textures', 'block')
TEX_ITEM = os.path.join(ASSETS, 'textures', 'item')
TEX_SCREEN = os.path.join(ASSETS, 'textures', 'screen')
MODELS_BLOCK = os.path.join(ASSETS, 'models', 'block')
MODELS_ITEM = os.path.join(ASSETS, 'models', 'item')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
SOUNDS = os.path.join(ASSETS, 'sounds')
FONT_BOLD = next((p for p in ['/usr/share/fonts/liberation/LiberationSans-Bold.ttf', '/usr/share/fonts/TTF/DejaVuSans-Bold.ttf'] if os.path.exists(p)), None)

for d in (TEX_BLOCK, TEX_ITEM, TEX_SCREEN, MODELS_BLOCK, MODELS_ITEM, BLOCKSTATES, SOUNDS):
    os.makedirs(d, exist_ok=True)


def save(img, path):
    img.save(path, optimize=True)


# ---------------------------------------------------------------------------------------------------------------------
# Materials
# ---------------------------------------------------------------------------------------------------------------------

def clamp(v):
    return max(0, min(255, int(v)))


def noise_rgb(img, amount, seed):
    rnd = random.Random(seed)
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            p = px[x, y]
            n = rnd.randint(-amount, amount)
            px[x, y] = (clamp(p[0] + n), clamp(p[1] + n), clamp(p[2] + n)) + ((p[3],) if len(p) == 4 else ())
    return img


def wood(w, h, base, dark, seed, streaks=7):
    rnd = random.Random(seed)
    img = Image.new('RGBA', (w, h))
    px = img.load()
    phase = [rnd.random() * 6.28 for _ in range(4)]
    for y in range(h):
        for x in range(w):
            t = (math.sin(y * 0.55 + math.sin(x * 0.07 + phase[0]) * 2.2 + phase[1]) * 0.5 + 0.5)
            t = t ** 3
            knot = math.sin(x * 0.21 + phase[2]) * math.sin(y * 0.13 + phase[3])
            k = max(0.0, knot - 0.9) * 2.5
            f = min(1.0, t * 0.7 + k)
            px[x, y] = tuple(clamp(base[i] * (1 - f) + dark[i] * f) for i in range(3)) + (255,)
    return noise_rgb(img, 4, seed)


def plastic(w, h, color, seed, amount=5):
    img = Image.new('RGBA', (w, h), tuple(color) + (255,))
    return noise_rgb(img, amount, seed)


def brushed(w, h, color, seed, vertical=False):
    rnd = random.Random(seed)
    img = Image.new('RGBA', (w, h))
    px = img.load()
    lines = [rnd.randint(-10, 10) for _ in range(max(w, h))]
    for y in range(h):
        for x in range(w):
            n = lines[x if vertical else y]
            px[x, y] = (clamp(color[0] + n), clamp(color[1] + n), clamp(color[2] + n), 255)
    return img


def cloth(w, h, base=(196, 176, 140), seed=5):
    img = Image.new('RGBA', (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            v = 0
            if (x + y) % 2 == 0:
                v += 10
            if x % 4 == 0 or y % 4 == 0:
                v -= 18
            px[x, y] = (clamp(base[0] + v), clamp(base[1] + v), clamp(base[2] + v), 255)
    return noise_rgb(img, 3, seed)


def slots(w, h, base, slot, seed, spacing=2, horizontal=True):
    img = plastic(w, h, base, seed, 3)
    d = ImageDraw.Draw(img)
    if horizontal:
        for y in range(1, h - 1, spacing):
            d.line([(1, y), (w - 2, y)], fill=tuple(slot) + (255,))
    else:
        for x in range(1, w - 1, spacing):
            d.line([(x, 1), (x, h - 2)], fill=tuple(slot) + (255,))
    return img


def dots(w, h, base, dot, seed, spacing=2):
    img = plastic(w, h, base, seed, 2)
    px = img.load()
    for y in range(1, h, spacing):
        for x in range(1 + (y // spacing) % 2, w, spacing):
            px[x, y] = tuple(dot) + (255,)
    return img


def glass(w, h, top, bottom, highlight=40, curved=False):
    img = Image.new('RGBA', (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = y / max(1, h - 1)
            c = [top[i] * (1 - t) + bottom[i] * t for i in range(3)]
            hx = (x / max(1, w - 1)) + t * 0.6
            if 0.18 < hx < 0.34:
                c = [v + highlight * (1 - abs(hx - 0.26) / 0.08) for v in c]
            if curved:
                dx = (x - w / 2) / (w / 2)
                dy = (y - h / 2) / (h / 2)
                edge = max(0.0, (dx * dx + dy * dy) - 0.55)
                c = [v * (1 - edge * 0.5) for v in c]
            px[x, y] = tuple(clamp(v) for v in c) + (255,)
    return img


def knob(size, rim, face, seed):
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, size - 1, size - 1], fill=tuple(rim) + (255,))
    d.ellipse([1, 1, size - 2, size - 2], fill=tuple(face) + (255,))
    for i in range(0, 360, 30):
        a = math.radians(i)
        r = size / 2 - 1
        d.point((size / 2 + math.cos(a) * r - 0.5, size / 2 + math.sin(a) * r - 0.5), fill=tuple(rim) + (255,))
    back = plastic(size, size, rim, seed, 2)
    back.alpha_composite(img)
    return back


# ---------------------------------------------------------------------------------------------------------------------
# Textures
# ---------------------------------------------------------------------------------------------------------------------

WOOD_LIGHT = ((150, 98, 56), (104, 62, 32))
WOOD_DARK = ((92, 56, 32), (60, 34, 18))
WOODGRAIN = ((134, 88, 52), (82, 50, 28))
CREAM = (222, 214, 192)
DARK_PLASTIC = (46, 46, 50)
CHARCOAL = (34, 35, 38)
SILVER = (176, 180, 186)
BLACK_PANEL = (18, 18, 20)


def front_texture(name, w_cells, h_cells, painter):
    img = Image.new('RGBA', (w_cells * 32, h_cells * 32))
    painter(img)
    save(img, os.path.join(TEX_BLOCK, name + '_front.png'))


def px_rect(d, model_rect, w_cells, h_cells, anchor_col, fill, outline=None, s=2):
    """Fill a rectangle given in model pixels (x0,y0,x1,y1) on a front texture (viewer orientation)."""
    x0, y0, x1, y1 = model_rect
    xmax = 16 * (w_cells - anchor_col)
    ymax = 16 * h_cells
    left = (xmax - x1) * s
    right = (xmax - x0) * s - 1
    top = (ymax - y1) * s
    bottom = (ymax - y0) * s - 1
    d.rectangle([left, top, right, bottom], fill=fill, outline=outline)


def make_textures():
    save(wood(32, 32, *WOOD_LIGHT, seed=11), os.path.join(TEX_BLOCK, 'wood_cabinet.png'))
    save(wood(32, 32, *WOOD_DARK, seed=12), os.path.join(TEX_BLOCK, 'wood_dark.png'))
    save(wood(32, 32, *WOODGRAIN, seed=13, streaks=9), os.path.join(TEX_BLOCK, 'woodgrain_veneer.png'))
    save(plastic(16, 16, CREAM, 21), os.path.join(TEX_BLOCK, 'plastic_cream.png'))
    save(plastic(16, 16, DARK_PLASTIC, 22), os.path.join(TEX_BLOCK, 'plastic_dark.png'))
    save(plastic(16, 16, CHARCOAL, 23, 3), os.path.join(TEX_BLOCK, 'plastic_charcoal.png'))
    save(brushed(16, 16, SILVER, 24), os.path.join(TEX_BLOCK, 'metal_silver.png'))
    save(brushed(16, 16, (92, 94, 100), 25), os.path.join(TEX_BLOCK, 'metal_dark.png'))
    save(plastic(16, 16, BLACK_PANEL, 26, 2), os.path.join(TEX_BLOCK, 'panel_black.png'))
    save(brushed(16, 16, (205, 168, 92), 27), os.path.join(TEX_BLOCK, 'brass.png'))
    save(cloth(32, 32), os.path.join(TEX_BLOCK, 'grille_cloth.png'))
    save(dots(16, 16, (40, 40, 44), (18, 18, 20), 28), os.path.join(TEX_BLOCK, 'grille_dark.png'))
    save(slots(32, 32, DARK_PLASTIC, (20, 20, 22), 29, spacing=3), os.path.join(TEX_BLOCK, 'vents_dark.png'))
    save(slots(32, 32, (40, 41, 45), (24, 24, 26), 30, spacing=2, horizontal=False), os.path.join(TEX_BLOCK, 'panel_back.png'))
    save(knob(16, (150, 116, 54), (214, 180, 104), 31), os.path.join(TEX_BLOCK, 'knob_brass.png'))
    save(knob(16, (120, 124, 130), (196, 200, 206), 32), os.path.join(TEX_BLOCK, 'knob_silver.png'))
    save(plastic(16, 16, (70, 70, 76), 33, 3), os.path.join(TEX_BLOCK, 'button_dark.png'))
    save(brushed(16, 16, (210, 214, 220), 34, vertical=True), os.path.join(TEX_BLOCK, 'chrome.png'))
    save(glass(32, 32, (64, 72, 66), (30, 34, 32), 34, curved=True), os.path.join(TEX_BLOCK, 'glass_monochrome.png'))
    save(glass(32, 32, (44, 52, 64), (18, 22, 30), 36, curved=True), os.path.join(TEX_BLOCK, 'glass_crt.png'))
    save(glass(32, 32, (26, 28, 34), (10, 11, 14), 22), os.path.join(TEX_BLOCK, 'glass_flat.png'))

    # Silver control panel with speaker slots for the 1970s set.
    panel = brushed(32, 32, (168, 170, 172), 35)
    d = ImageDraw.Draw(panel)
    for y in range(20, 30, 2):
        d.line([(4, y), (27, y)], fill=(70, 72, 76, 255))
    save(panel, os.path.join(TEX_BLOCK, 'control_panel.png'))

    # Fronts (viewer orientation; 2 texels per model pixel).
    def mono_front(img):
        img.paste(wood(64, 64, *WOOD_LIGHT, seed=41), (0, 0))
        d = ImageDraw.Draw(img)
        d.rectangle([3, 3, 60, 52], outline=(98, 60, 32, 255))
        d.rectangle([5, 5, 58, 50], outline=(176, 128, 80, 255))
        d.line([(4, 56), (59, 56)], fill=(88, 52, 28, 255))
    front_texture('wooden_monochrome_tv', 2, 2, mono_front)

    def color70_front(img):
        img.paste(wood(64, 32, *WOODGRAIN, seed=42), (0, 0))
        d = ImageDraw.Draw(img)
        d.rectangle([1, 1, 62, 29], outline=(70, 42, 22, 255))
        d.line([(2, 27), (61, 27)], fill=(186, 188, 192, 255))
    front_texture('woodgrain_color_tv', 2, 1, color70_front)

    def portable_front(img):
        img.paste(plastic(32, 32, CREAM, 43), (0, 0))
        d = ImageDraw.Draw(img)
        px_rect(d, (2.5, 2.5, 11.5, 10.5), 1, 1, 0, (52, 52, 56, 255), (30, 30, 34, 255))
        px_rect(d, (11, 2, 14, 10.5), 1, 1, 0, (196, 188, 168, 255))
        d.line([(2, 30), (29, 30)], fill=(170, 160, 140, 255))
    front_texture('portable_tv', 1, 1, portable_front)

    def crt_front(img):
        img.paste(plastic(64, 64, DARK_PLASTIC, 44, 3), (0, 0))
        d = ImageDraw.Draw(img)
        px_rect(d, (3.25, 7.75, 28.75, 27.25), 2, 2, 0, None, (78, 78, 84, 255))
        px_rect(d, (3.5, 8, 28.5, 27), 2, 2, 0, None, (22, 22, 24, 255))
        px_rect(d, (1.5, 2, 30.5, 6.5), 2, 2, 0, (38, 38, 42, 255))
        d.line([(2, 2), (61, 2)], fill=(70, 70, 76, 255))
    front_texture('crt_tv', 2, 2, crt_front)

    def plasma_front(img):
        img.paste(plastic(96, 64, CHARCOAL, 45, 2), (0, 0))
        d = ImageDraw.Draw(img)
        d.rectangle([2, 0, 93, 51], outline=(150, 154, 160, 255))
        px_rect(d, (-14, 5.6, 30, 6.8), 3, 2, 1, (60, 62, 66, 255))
        for x in range(10, 86, 2):
            d.point((x, 51), fill=(20, 20, 22, 255))
    front_texture('plasma_tv', 3, 2, plasma_front)

    def plasma_wall_front(img):
        img.paste(plastic(96, 64, CHARCOAL, 46, 2), (0, 0))
        d = ImageDraw.Draw(img)
        d.rectangle([2, 4, 93, 55], outline=(150, 154, 160, 255))
    front_texture('plasma_tv_wall', 3, 2, plasma_wall_front)

    def oled_front(img):
        img.paste(plastic(96, 64, BLACK_PANEL, 47, 1), (0, 0))
        d = ImageDraw.Draw(img)
        d.rectangle([1, 4, 94, 58], outline=(54, 56, 60, 255))
    front_texture('oled_tv', 3, 2, oled_front)

    # Remote item: original 16x16 pixel art.
    remote = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(remote)
    d.rectangle([5, 1, 10, 14], fill=(40, 40, 46, 255), outline=(20, 20, 24, 255))
    d.point((7, 2), fill=(220, 60, 50, 255))
    d.point((8, 2), fill=(220, 60, 50, 255))
    for y in (5, 7, 9):
        for x in (6, 8):
            d.rectangle([x, y, x + 1, y], fill=(170, 172, 180, 255))
    d.rectangle([6, 11, 9, 12], fill=(90, 160, 230, 255))
    d.line([(6, 13), (9, 13)], fill=(70, 70, 78, 255))
    save(remote, os.path.join(TEX_ITEM, 'remote_control.png'))


def make_screen_textures():
    def card(w, h, path):
        img = Image.new('RGBA', (w, h))
        d = ImageDraw.Draw(img)
        for y in range(h):
            t = y / (h - 1)
            d.line([(0, y), (w, y)], fill=(clamp(28 + 20 * t), clamp(40 + 18 * t), clamp(66 + 10 * t), 255))
        cx, cy = w // 2, int(h * 0.40)
        r = int(h * 0.28)
        d.ellipse([cx - r - 3, cy - r - 3, cx + r + 3, cy + r + 3], fill=(225, 228, 232, 255))
        bars = [(196, 196, 196), (198, 190, 120), (120, 186, 190), (120, 180, 120), (180, 128, 180), (186, 120, 120), (120, 126, 186)]
        mask = Image.new('L', (w, h), 0)
        ImageDraw.Draw(mask).ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
        layer = Image.new('RGBA', (w, h))
        ld = ImageDraw.Draw(layer)
        bw = (2 * r) / len(bars)
        for i, c in enumerate(bars):
            ld.rectangle([cx - r + i * bw, cy - r, cx - r + (i + 1) * bw, cy + r * 0.35], fill=c + (255,))
        ld.rectangle([cx - r, cy + r * 0.35, cx + r, cy + r], fill=(36, 40, 52, 255))
        for i in range(8):
            g = clamp(i * 34)
            ld.rectangle([cx - r + i * (2 * r / 8), cy + r * 0.35, cx - r + (i + 1) * (2 * r / 8), cy + r * 0.62], fill=(g, g, g, 255))
        img.paste(layer, (0, 0), mask)
        d.line([(cx - r, cy), (cx + r, cy)], fill=(255, 255, 255, 90))
        d.line([(cx, cy - r), (cx, cy + r)], fill=(255, 255, 255, 90))
        if FONT_BOLD:
            size = max(10, h // 10)
            font = ImageFont.truetype(FONT_BOLD, size)
            text = 'OteTV'
            tw = d.textlength(text, font=font)
            ty = cy + r + 3 + ((h - (cy + r + 3)) - size) / 2 - size * 0.15
            d.text((cx - tw / 2, ty), text, font=font, fill=(232, 236, 240, 255))
        save(img, path)

    card(256, 192, os.path.join(TEX_SCREEN, 'standby.png'))
    card(256, 144, os.path.join(TEX_SCREEN, 'standby_wide.png'))

    scan = Image.new('RGBA', (8, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(scan)
    for y in range(0, 256, 2):
        d.line([(0, y), (8, y)], fill=(0, 0, 0, 120))
    save(scan, os.path.join(TEX_SCREEN, 'scanlines.png'))

    vig = Image.new('RGBA', (128, 96), (0, 0, 0, 0))
    px = vig.load()
    for y in range(96):
        for x in range(128):
            dx = (x - 63.5) / 64
            dy = (y - 47.5) / 48
            r = math.sqrt(dx * dx + dy * dy)
            a = max(0.0, (r - 0.62) / 0.55)
            px[x, y] = (0, 0, 0, clamp(a * a * 230))
    save(vig, os.path.join(TEX_SCREEN, 'vignette.png'))

    rnd = random.Random(7)
    grain = Image.new('RGBA', (64, 64))
    gp = grain.load()
    for y in range(64):
        for x in range(64):
            v = rnd.randint(0, 255)
            gp[x, y] = (v, v, v, 255)
    save(grain, os.path.join(TEX_SCREEN, 'grain.png'))

    save(Image.new('RGBA', (16, 16), (0, 0, 0, 255)), os.path.join(TEX_SCREEN, 'black.png'))

    def led(color):
        img = Image.new('RGBA', (8, 8), (0, 0, 0, 0))
        dd = ImageDraw.Draw(img)
        dd.ellipse([0, 0, 7, 7], fill=color + (255,))
        dd.ellipse([2, 2, 4, 4], fill=(255, 255, 255, 200))
        return img

    save(led((70, 220, 110)), os.path.join(TEX_SCREEN, 'led_on.png'))
    save(led((210, 120, 40)), os.path.join(TEX_SCREEN, 'led_standby.png'))

    pointer = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    dd = ImageDraw.Draw(pointer)
    dd.line([(8, 8), (8, 1)], fill=(40, 26, 14, 255), width=2)
    save(pointer, os.path.join(TEX_SCREEN, 'dial_pointer.png'))


def make_logo():
    img = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([16, 40, 240, 200], radius=26, fill=(52, 54, 60, 255), outline=(20, 20, 24, 255), width=6)
    scr = Image.open(os.path.join(TEX_SCREEN, 'standby.png')).resize((176, 118), Image.NEAREST)
    img.paste(scr, (40, 58))
    d.rectangle([40, 58, 215, 175], outline=(14, 14, 16, 255), width=3)
    d.line([(96, 40), (64, 6)], fill=(200, 202, 208, 255), width=5)
    d.line([(160, 40), (192, 6)], fill=(200, 202, 208, 255), width=5)
    d.rectangle([70, 200, 90, 230], fill=(40, 40, 44, 255))
    d.rectangle([166, 200, 186, 230], fill=(40, 40, 44, 255))
    d.ellipse([214, 182, 228, 196], fill=(70, 220, 110, 255))
    save(img, os.path.join(RES, 'otetv_logo.png'))


# ---------------------------------------------------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------------------------------------------------

class Model:
    def __init__(self, name, w, h, a, textures):
        self.name = name
        self.w = w
        self.h = h
        self.a = a
        self.textures = dict(textures)
        self.elements = []

    def front_uv(self, x0, y0, x1, y1):
        xmax = 16 * (self.w - self.a)
        ymax = 16 * self.h
        tw = 16 * self.w
        th = 16 * self.h
        return [round((xmax - x1) / tw * 16, 4), round((ymax - y1) / th * 16, 4),
                round((xmax - x0) / tw * 16, 4), round((ymax - y0) / th * 16, 4)]

    @staticmethod
    def mat_uv(du, dv):
        return [0, 0, round(min(16, du), 4), round(min(16, dv), 4)]

    def box(self, frm, to, tex, north=None, rotation=None, faces=None, shade=True):
        """north: None (use tex), 'front' (front texture region) or a texture key mapped across the face."""
        x0, y0, z0 = frm
        x1, y1, z1 = to
        dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
        f = {
            'east': {'texture': '#' + tex, 'uv': self.mat_uv(dz, dy)},
            'west': {'texture': '#' + tex, 'uv': self.mat_uv(dz, dy)},
            'up': {'texture': '#' + tex, 'uv': self.mat_uv(dx, dz)},
            'down': {'texture': '#' + tex, 'uv': self.mat_uv(dx, dz)},
            'south': {'texture': '#' + tex, 'uv': self.mat_uv(dx, dy)},
        }
        if north == 'front':
            f['north'] = {'texture': '#front', 'uv': self.front_uv(x0, y0, x1, y1)}
        elif north is not None:
            f['north'] = {'texture': '#' + north, 'uv': [0, 0, 16, 16]}
        else:
            f['north'] = {'texture': '#' + tex, 'uv': self.mat_uv(dx, dy)}
        if faces:
            for k, v in faces.items():
                if v is None:
                    f.pop(k, None)
                else:
                    f[k] = v
        e = {'from': [round(v, 4) for v in frm], 'to': [round(v, 4) for v in to], 'faces': f}
        if rotation:
            e['rotation'] = rotation
        if not shade:
            e['shade'] = False
        for v in list(frm) + list(to):
            if v < -16 or v > 32:
                raise SystemExit('%s: element outside model limits: %s %s' % (self.name, frm, to))
        self.elements.append(e)

    def bounds(self):
        mins = [min(e['from'][i] for e in self.elements) for i in range(3)]
        maxs = [max(e['to'][i] for e in self.elements) for i in range(3)]
        return mins, maxs

    def write(self, suffix=''):
        data = {
            'credit': 'OteTV original model',
            'ambientocclusion': False,
            'textures': self.textures,
            'elements': self.elements,
        }
        with open(os.path.join(MODELS_BLOCK, self.name + suffix + '.json'), 'w') as f:
            json.dump(data, f, indent=1)


def rot_matrix(rx, ry, rz):
    def mx(a):
        c, s = math.cos(a), math.sin(a)
        return [[1, 0, 0], [0, c, -s], [0, s, c]]

    def my(a):
        c, s = math.cos(a), math.sin(a)
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]

    def mz(a):
        c, s = math.cos(a), math.sin(a)
        return [[c, -s, 0], [s, c, 0], [0, 0, 1]]

    def mul(a, b):
        return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]

    return mul(mul(mx(math.radians(rx)), my(math.radians(ry))), mz(math.radians(rz)))


def item_model(model):
    mins, maxs = model.bounds()
    size = max(maxs[i] - mins[i] for i in range(3))
    center = [(mins[i] + maxs[i]) / 2 for i in range(3)]
    off = [center[i] - 8 for i in range(3)]

    def transform(rot, base_scale, extra=(0, 0, 0)):
        s = base_scale * min(1.0, 16.0 / size) * (1.0 if size <= 16 else 1.15)
        m = rot_matrix(*rot)
        o = [sum(m[i][k] * off[k] for k in range(3)) * s for i in range(3)]
        t = [round(-o[i] + extra[i], 3) for i in range(3)]
        t = [max(-80, min(80, v)) for v in t]
        return {'rotation': list(rot), 'translation': t, 'scale': [round(s, 4)] * 3}

    display = {
        'gui': transform((30, 225, 0), 0.625),
        'ground': transform((0, 0, 0), 0.25, (0, 3, 0)),
        'fixed': transform((0, 180, 0), 0.5),
        'head': transform((0, 180, 0), 1.0, (0, 0, 0)),
        'thirdperson_righthand': transform((75, 45, 0), 0.375, (0, 2.5, 0)),
        'thirdperson_lefthand': transform((75, 45, 0), 0.375, (0, 2.5, 0)),
        'firstperson_righthand': transform((0, 45, 0), 0.4),
        'firstperson_lefthand': transform((0, 225, 0), 0.4),
    }
    data = {'parent': 'otetv:block/' + model.name, 'display': display}
    with open(os.path.join(MODELS_ITEM, model.name + '.json'), 'w') as f:
        json.dump(data, f, indent=1)


def part_model(name, particle):
    with open(os.path.join(MODELS_BLOCK, name + '_part.json'), 'w') as f:
        json.dump({'textures': {'particle': 'otetv:block/' + particle}}, f, indent=1)


def frame(m, x0, y0, x1, y1, ix0, iy0, ix1, iy1, z0, z1, tex, north='front'):
    """A rectangular frame (outer x0..x1 / y0..y1) around an opening (ix0..ix1 / iy0..iy1)."""
    m.box((x0, iy1, z0), (x1, y1, z1), tex, north)
    m.box((x0, y0, z0), (x1, iy0, z1), tex, north)
    m.box((ix1, iy0, z0), (x1, iy1, z1), tex, north)
    m.box((x0, iy0, z0), (ix0, iy1, z1), tex, north)


def build_models():
    models = []

    # 1950s wooden console, 2x2x1, 4:3.
    m = Model('wooden_monochrome_tv', 2, 2, 0, {
        'particle': 'otetv:block/wood_cabinet', 'front': 'otetv:block/wooden_monochrome_tv_front', 'wood': 'otetv:block/wood_cabinet',
        'dark': 'otetv:block/wood_dark', 'brass': 'otetv:block/brass', 'cloth': 'otetv:block/grille_cloth',
        'glass': 'otetv:block/glass_monochrome', 'knob': 'otetv:block/knob_brass'})
    for x in (2, 28):
        for z in (3, 12):
            m.box((x, 0, z), (x + 2, 5, z + 2), 'dark')
    m.box((0.5, 4.5, 1.5), (31.5, 5.5, 15.5), 'dark')
    m.box((1, 5.5, 2), (31, 30, 15), 'wood', 'front')
    m.box((0, 30, 1), (32, 31.5, 16), 'dark')
    frame(m, 3, 12.5, 19, 26, 4, 14, 18, 24.5, 1, 2, 'brass', north='brass')
    m.box((4, 14, 1.5), (18, 24.5, 2), 'dark', 'glass')
    m.box((21, 8, 1.5), (29, 27, 2), 'dark', 'cloth')
    m.box((22, 6, 0.5), (25, 9, 2), 'brass', 'knob')
    m.box((26, 6, 0.5), (29, 9, 2), 'brass', 'knob')
    models.append(m)

    # 1970s woodgrain color set, 2x1x1, 4:3.
    m = Model('woodgrain_color_tv', 2, 1, 0, {
        'particle': 'otetv:block/woodgrain_veneer', 'front': 'otetv:block/woodgrain_color_tv_front', 'wood': 'otetv:block/woodgrain_veneer',
        'dark': 'otetv:block/wood_dark', 'silver': 'otetv:block/metal_silver', 'panel': 'otetv:block/control_panel',
        'glass': 'otetv:block/glass_crt', 'knob': 'otetv:block/knob_silver'})
    for x in (2, 28):
        for z in (3, 12):
            m.box((x, 0, z), (x + 2, 2, z + 2), 'dark')
    m.box((0.5, 2, 2), (31.5, 15.5, 15), 'wood', 'front')
    frame(m, 2, 3.5, 18, 14.5, 3, 4.5, 17, 13.5, 1.5, 2, 'silver', north='silver')
    m.box((3, 4.5, 1.75), (17, 13.5, 2), 'dark', 'glass')
    m.box((20, 3.5, 1.5), (30.5, 14.5, 2), 'silver', 'panel')
    m.box((22.5, 7.5, 1), (27.5, 12.5, 1.5), 'silver', 'knob')
    m.box((21.5, 4.5, 1), (23.5, 6.5, 1.5), 'silver', 'knob')
    m.box((27, 4.5, 1), (29, 6.5, 1.5), 'silver', 'knob')
    models.append(m)

    # 1980s portable, 1x1x1, 4:3.
    m = Model('portable_tv', 1, 1, 0, {
        'particle': 'otetv:block/plastic_cream', 'front': 'otetv:block/portable_tv_front', 'body': 'otetv:block/plastic_cream',
        'dark': 'otetv:block/plastic_dark', 'grille': 'otetv:block/grille_dark', 'glass': 'otetv:block/glass_crt',
        'chrome': 'otetv:block/chrome', 'knob': 'otetv:block/knob_silver'})
    m.box((2, 0, 4), (14, 11, 13), 'body', 'front')
    m.box((3, 3.5, 3.75), (11, 9.5, 4), 'dark', 'glass')
    m.box((11.5, 3, 3.8), (13.5, 9.5, 4), 'dark', 'grille')
    m.box((11.5, 1, 3.7), (13.5, 3, 4), 'dark', 'knob')
    m.box((4, 11, 8), (5, 13, 9), 'dark')
    m.box((11, 11, 8), (12, 13, 9), 'dark')
    m.box((4, 13, 8), (12, 14, 9), 'dark')
    m.box((11.5, 11, 10), (12.5, 11.5, 11), 'chrome')
    m.box((11.75, 11, 10.25), (12.25, 16, 10.75), 'chrome', rotation={'origin': [12, 11, 10.5], 'axis': 'z', 'angle': -22.5})
    m.box((3.5, 11, 10), (4.5, 11.5, 11), 'chrome')
    m.box((3.75, 11, 10.25), (4.25, 16, 10.75), 'chrome', rotation={'origin': [4, 11, 10.5], 'axis': 'z', 'angle': 22.5})
    models.append(m)

    # 1990s CRT, 2x2x1, 4:3.
    m = Model('crt_tv', 2, 2, 0, {
        'particle': 'otetv:block/plastic_dark', 'front': 'otetv:block/crt_tv_front', 'body': 'otetv:block/plastic_dark',
        'vents': 'otetv:block/vents_dark', 'glass': 'otetv:block/glass_crt', 'button': 'otetv:block/button_dark'})
    m.box((2, 0, 3), (6, 1, 12), 'body')
    m.box((26, 0, 3), (30, 1, 12), 'body')
    frame(m, 0.5, 1, 31.5, 30.5, 4, 8.5, 28, 26.5, 1, 4, 'body')
    m.box((4, 8.5, 0.75), (28, 26.5, 4), 'body', 'glass')
    m.box((3, 3, 4), (29, 28, 12), 'body', faces={'south': {'texture': '#vents', 'uv': [0, 0, 16, 16]}})
    m.box((6, 6, 12), (26, 25, 15.5), 'body', faces={'south': {'texture': '#vents', 'uv': [0, 0, 16, 16]}})
    for x in (6, 9, 12):
        m.box((x, 3, 0.75), (x + 2, 4.5, 1), 'button', 'button')
    models.append(m)

    # 2000s plasma, 3x2x1, 16:9; stand and wall variants.
    tex = {'particle': 'otetv:block/plastic_charcoal', 'front': 'otetv:block/plasma_tv_front', 'body': 'otetv:block/plastic_charcoal',
           'back': 'otetv:block/panel_back', 'metal': 'otetv:block/metal_silver', 'glass': 'otetv:block/glass_flat'}
    m = Model('plasma_tv', 3, 2, 1, tex)
    m.box((-2, 0, 4), (18, 1.5, 13), 'metal')
    m.box((5, 1.5, 8), (11, 6, 11), 'metal')
    frame(m, -15, 5.5, 31, 31, -12.8, 6.9, 28.8, 30.3, 6, 11, 'body')
    m.box((-12.8, 6.9, 5.8), (28.8, 30.3, 11), 'body', 'glass', faces={'south': {'texture': '#back', 'uv': [0, 0, 16, 16]}})
    models.append(m)
    w = Model('plasma_tv', 3, 2, 1, dict(tex, front='otetv:block/plasma_tv_wall_front'))
    frame(w, -15, 4, 31, 29.5, -12.8, 5.4, 28.8, 28.8, 11, 16, 'body')
    w.box((-12.8, 5.4, 10.8), (28.8, 28.8, 16), 'body', 'glass', faces={'south': {'texture': '#back', 'uv': [0, 0, 16, 16]}})
    w.write('_wall')

    # Modern OLED, 3x2x1, 16:9; stand and wall variants.
    tex = {'particle': 'otetv:block/panel_black', 'front': 'otetv:block/oled_tv_front', 'body': 'otetv:block/panel_black',
           'metal': 'otetv:block/metal_dark', 'glass': 'otetv:block/glass_flat', 'back': 'otetv:block/plastic_charcoal'}
    m = Model('oled_tv', 3, 2, 1, tex)
    m.box((-11, 0, 4), (-7, 0.5, 12), 'metal')
    m.box((23, 0, 4), (27, 0.5, 12), 'metal')
    m.box((-9.5, 0.5, 7), (-8.5, 3, 8), 'metal')
    m.box((24.5, 0.5, 7), (25.5, 3, 8), 'metal')
    m.box((-15.5, 3, 7.5), (31.5, 30, 8.5), 'back')
    frame(m, -15.5, 3, 31.5, 30, -15.2, 3.45, 31.2, 29.55, 7, 7.5, 'body')
    m.box((-15.2, 3.45, 6.95), (31.2, 29.55, 7.5), 'body', 'glass')
    models.append(m)
    w = Model('oled_tv', 3, 2, 1, tex)
    w.box((-15.5, 3, 15), (31.5, 30, 16), 'back')
    frame(w, -15.5, 3, 31.5, 30, -15.2, 3.45, 31.2, 29.55, 14.5, 15, 'body')
    w.box((-15.2, 3.45, 14.45), (31.2, 29.55, 15), 'body', 'glass')
    w.write('_wall')

    for model in models:
        model.write()
        item_model(model)
        part_model(model.name, model.textures['particle'].split('/')[-1])
    return models


def build_blockstates():
    tvs = {'wooden_monochrome_tv': (4, False), 'woodgrain_color_tv': (2, False), 'portable_tv': (1, False), 'crt_tv': (4, False),
           'plasma_tv': (6, True), 'oled_tv': (6, True)}
    anchors = {'wooden_monochrome_tv': 0, 'woodgrain_color_tv': 0, 'portable_tv': 0, 'crt_tv': 0, 'plasma_tv': 1, 'oled_tv': 1}
    yrot = {'north': 0, 'east': 90, 'south': 180, 'west': 270}
    for name, (cells, wall) in tvs.items():
        variants = {}
        for facing, y in yrot.items():
            for mount in ('stand', 'wall'):
                for part in range(cells):
                    key = 'facing=%s,mount=%s' % (facing, mount) + (',part=%d' % part if cells > 1 else '')
                    if part == anchors[name]:
                        model = 'otetv:block/' + name + ('_wall' if wall and mount == 'wall' else '')
                        v = {'model': model}
                        if y:
                            v['y'] = y
                    else:
                        v = {'model': 'otetv:block/' + name + '_part'}
                    variants[key] = v
        with open(os.path.join(BLOCKSTATES, name + '.json'), 'w') as f:
            json.dump({'variants': variants}, f, indent=1)
    with open(os.path.join(MODELS_ITEM, 'remote_control.json'), 'w') as f:
        json.dump({'parent': 'minecraft:item/handheld', 'textures': {'layer0': 'otetv:item/remote_control'}}, f, indent=1)


# ---------------------------------------------------------------------------------------------------------------------
# Sounds (synthesized with ffmpeg; original)
# ---------------------------------------------------------------------------------------------------------------------

SOUND_DEFS = {
    'tv/power_on': ('0.5*exp(-t*40)*sin(2*PI*1800*t)+0.25*sin(2*PI*(220+600*t)*t)*exp(-t*6)*lt(t,0.45)', 0.5),
    'tv/power_off': ('0.5*exp(-t*40)*sin(2*PI*1500*t)+0.25*sin(2*PI*(700-500*t)*t)*exp(-t*8)*lt(t,0.35)', 0.4),
    'tv/crt_startup': ('0.55*exp(-t*25)*sin(2*PI*90*t)+0.08*sin(2*PI*7800*t)*exp(-t*3)*lt(t,0.9)+0.2*exp(-t*60)*sin(2*PI*2400*t)', 0.9),
    'tv/channel_click': ('0.7*exp(-t*90)*sin(2*PI*2200*t)+0.3*exp(-t*140)*sin(2*PI*900*t)', 0.09),
    'tv/knob_turn': ('0.5*exp(-mod(t,0.06)*120)*sin(2*PI*1600*t)*lt(t,0.3)', 0.3),
    'tv/crt_hum': ('0.18*sin(2*PI*120*t)+0.06*sin(2*PI*240*t)+0.02*sin(2*PI*360*t)', 2.0),
    'remote/beep': ('0.35*sin(2*PI*1900*t)*lt(t,0.07)*(1-exp(-t*400))', 0.1),
    'remote/pair': ('0.35*sin(2*PI*1300*t)*lt(t,0.09)+0.35*sin(2*PI*2000*t)*gt(t,0.11)*lt(t,0.22)', 0.25),
}


def make_sounds():
    ffmpeg = shutil.which('ffmpeg')
    events = {}
    for path, (expr, dur) in SOUND_DEFS.items():
        out = os.path.join(SOUNDS, path + '.ogg')
        os.makedirs(os.path.dirname(out), exist_ok=True)
        if ffmpeg:
            subprocess.run([ffmpeg, '-y', '-loglevel', 'error', '-f', 'lavfi', '-i',
                            "aevalsrc=exprs='%s':s=48000:d=%s" % (expr, dur), '-ac', '1', '-c:a', 'libvorbis', '-q:a', '4',
                            '-map_metadata', '-1', '-fflags', '+bitexact', out], check=True)
        event = path.replace('/', '.')
        events[event] = {'subtitle': 'subtitles.otetv.' + event, 'sounds': ['otetv:' + path]}
    events['tv.crt_hum']['sounds'] = [{'name': 'otetv:tv/crt_hum', 'stream': False, 'volume': 0.5}]
    with open(os.path.join(ASSETS, 'sounds.json'), 'w') as f:
        json.dump(events, f, indent=1)


def main():
    make_textures()
    make_screen_textures()
    make_logo()
    build_models()
    build_blockstates()
    make_sounds()
    print('assets generated under', os.path.relpath(ASSETS, ROOT))


if __name__ == '__main__':
    main()
