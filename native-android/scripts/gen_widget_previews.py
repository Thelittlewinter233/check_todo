"""Generate widget preview PNGs (no PIL — pure zlib/struct)."""
import struct, zlib, os

W, H = 500, 360
R = 28  # corner radius

def px(x, y):
    # rounded-rect coverage (anti-aliased edge via distance to corner circle)
    inside = True
    cx = min(max(x, R), W - 1 - R)
    cy = min(max(y, R), H - 1 - R)
    if (x < R or x >= W - R) and (y < R or y >= H - R):
        d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
        if d > R + 0.5:
            return None
        inside = d <= R
    return inside

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

HEADER = (76, 140, 106)     # green accent
HEADER2 = (60, 116, 88)
BG = (255, 255, 255)
LINE = (232, 238, 234)
LINE2 = (216, 226, 220)
CHIP = (238, 244, 240)

def make(path, header_color, rows, chips):
    rows_data = []
    for y in range(H):
        row = bytearray()
        row.append(0)  # filter type 0
        for x in range(W):
            inside = px(x, y)
            if inside is None:
                row += bytes((0, 0, 0, 0))
                continue
            c = BG
            if y < 84:  # header band
                t = y / 84.0
                c = lerp(header_color, HEADER2, t)
            else:
                # content lines
                for (lx, ly, lw, lh, col) in rows:
                    if lx <= x < lx + lw and ly <= y < ly + lh:
                        c = col
                        break
                else:
                    for (cx2, cy2, cw, ch, col) in chips:
                        if cx2 <= x < cx2 + cw and cy2 <= y < cy2 + ch:
                            c = col
                            break
            row += bytes((c[0], c[1], c[2], 255))
        rows_data.append(bytes(row))

    raw = b''.join(rows_data)
    def chunk(tag, data):
        out = struct.pack('>I', len(data)) + tag + data
        out += struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
        return out

    ihdr = struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', ihdr)
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)
    print(path, os.path.getsize(path), 'bytes')

# 卡片一（三视图卡）：标题条 + 3 行条目（左侧勾选方块 + 两行文字）
lines1 = [
    (36, 116, 428, 14, LINE),
    (36, 156, 360, 14, LINE),
    (36, 196, 396, 14, LINE),
    (36, 264, 428, 14, LINE),
    (36, 304, 340, 14, LINE),
]
boxes1 = [(36, 108, 24, 24, HEADER), (36, 148, 24, 24, CHIP), (36, 256, 24, 24, HEADER)]
make('res/drawable-nodpi/widget_checktodo_preview.png', HEADER, lines1, boxes1)

# 卡片二（今日课程卡）：时间轴 + 两条课程块
lines2 = [
    (96, 130, 340, 20, LINE2),
    (96, 160, 260, 14, LINE),
    (96, 236, 340, 20, LINE2),
    (96, 266, 300, 14, LINE),
]
blocks2 = [
    (32, 118, 40, 68, CHIP),
    (32, 224, 40, 68, CHIP),
]
make('res/drawable-nodpi/widget_today_course_preview.png', HEADER2, lines2, blocks2)
