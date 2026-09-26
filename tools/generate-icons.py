import zlib, struct, math

BG = (15, 21, 18)
FG = (61, 220, 151)

def seg_dist(px, py, x1, y1, x2, y2):
    dx, dy = x2 - x1, y2 - y1
    l2 = dx * dx + dy * dy
    t = 0.0 if l2 == 0 else max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / l2))
    cx, cy = x1 + t * dx, y1 + t * dy
    return math.hypot(px - cx, py - cy)

def render(size, radius_ratio, glyph_scale, ss=3):
    S = size * ss
    r = radius_ratio * S
    # ¥ の座標（0-1 の正規化空間）
    g = glyph_scale
    c = 0.5
    def P(x, y):
        return (c + (x - 0.5) * g) * S, (c + (y - 0.5) * g) * S
    top_l, top_r = P(0.18, 0.18), P(0.82, 0.18)
    mid = P(0.5, 0.52)
    bot = P(0.5, 0.86)
    bar1_l, bar1_r = P(0.22, 0.60), P(0.78, 0.60)
    bar2_l, bar2_r = P(0.22, 0.72), P(0.78, 0.72)
    strokes = [
        (top_l, mid), (top_r, mid), (mid, bot),
        (bar1_l, bar1_r), (bar2_l, bar2_r),
    ]
    w = 0.058 * g * S  # 線の半分の太さ

    rows = []
    for y in range(S):
        row = bytearray()
        for x in range(S):
            px, py = x + 0.5, y + 0.5
            # 角丸の外側は透明
            inside = True
            if r > 0:
                cx = min(max(px, r), S - r)
                cy = min(max(py, r), S - r)
                if math.hypot(px - cx, py - cy) > r:
                    inside = False
            if not inside:
                row += bytes((0, 0, 0, 0))
                continue
            d = min(seg_dist(px, py, a[0], a[1], b[0], b[1]) for a, b in strokes)
            col = FG if d <= w else BG
            row += bytes((col[0], col[1], col[2], 255))
        rows.append(bytes(row))

    # ダウンサンプル
    out = []
    for y in range(size):
        line = bytearray()
        for x in range(size):
            rs = gs = bs = as_ = 0
            for dy in range(ss):
                src = rows[y * ss + dy]
                for dx in range(ss):
                    i = ((x * ss + dx) * 4)
                    rs += src[i]; gs += src[i+1]; bs += src[i+2]; as_ += src[i+3]
            n = ss * ss
            line += bytes((rs // n, gs // n, bs // n, as_ // n))
        out.append(bytes(line))
    return out

def write_png(path, rows, size):
    raw = b''.join(b'\x00' + r for r in rows)
    def chunk(tag, data):
        c = tag + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    open(path, 'wb').write(png)

for size, rr, gs, name in [
    (192, 0.22, 0.62, 'icons/icon-192.png'),
    (512, 0.22, 0.62, 'icons/icon-512.png'),
    (512, 0.0, 0.46, 'icons/icon-maskable-512.png'),
]:
    write_png(name, render(size, rr, gs), size)
    print('wrote', name)
