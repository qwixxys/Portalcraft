"""Oracle for Bsp.java: voxelise a Portal 2 map's world brushes into 32-unit cells and draw z slices.
    python bspvox.py <map.bsp> out.png [z_cell ...]"""
import struct, sys
from PIL import Image

d = open(sys.argv[1], 'rb').read()
lumps = [struct.unpack_from('<2i', d, 8 + i * 16) for i in range(64)]
P, N, L, M, LB, B, BS = 1, 5, 10, 14, 17, 18, 19
head = struct.unpack_from('<i', d, lumps[M][0] + 36)[0]
world, stack = set(), [head]
while stack:
    n = stack.pop()
    if n < 0:
        leaf = lumps[L][0] + (-1 - n) * 32
        first, count = struct.unpack_from('<2H', d, leaf + 24)
        for i in range(count):
            world.add(struct.unpack_from('<H', d, lumps[LB][0] + (first + i) * 2)[0])
    else:
        c0, c1 = struct.unpack_from('<2i', d, lumps[N][0] + n * 32 + 4)
        stack += [c0, c1]
cells = set()
for bi in sorted(world):
    fs, ns, contents = struct.unpack_from('<3i', d, lumps[B][0] + bi * 12)
    if not contents & (1 | 2 | 8 | 0x10000):
        continue
    planes = []
    lo, hi = [-1e9] * 3, [1e9] * 3
    for s in range(ns):
        pn = struct.unpack_from('<H', d, lumps[BS][0] + (fs + s) * 8)[0]
        nx, ny, nz, dist = struct.unpack_from('<4f', d, lumps[P][0] + pn * 20)
        planes.append((nx, ny, nz, dist))
        for a, v in enumerate((nx, ny, nz)):
            if v == 1: hi[a] = min(hi[a], dist)
            if v == -1: lo[a] = max(lo[a], -dist)
    if any(hi[a] - lo[a] > 30000 for a in range(3)):
        continue
    rng = [range(int(lo[a] // 32), int((hi[a] - 1) // 32) + 1) for a in range(3)]
    for cx in rng[0]:
        for cy in rng[1]:
            for cz in rng[2]:
                hit = False
                for i in range(3):
                    for j in range(3):
                        for k in range(3):
                            x, y, z = cx * 32 + 2 + i * 14, cy * 32 + 2 + j * 14, cz * 32 + 2 + k * 14
                            if all(nx * x + ny * y + nz * z - dd <= 0.01 for nx, ny, nz, dd in planes):
                                hit = True; break
                        if hit: break
                    if hit: break
                if hit:
                    cells.add((cx, cy, cz))
print('world brushes', len(world), 'cells', len(cells))
xs = [c[0] for c in cells]; ys = [c[1] for c in cells]
x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
zs = [int(z) for z in sys.argv[3:]] or [-1, 0, 1]
S = 4
img = Image.new('RGB', ((x1 - x0 + 1) * S * len(zs) + 8 * len(zs), (y1 - y0 + 1) * S), (20, 20, 30))
for k, z in enumerate(zs):
    ox = k * ((x1 - x0 + 1) * S + 8)
    for (cx, cy, cz) in cells:
        if cz == z:
            for a in range(S):
                for b in range(S):
                    img.putpixel((ox + (cx - x0) * S + a, (y1 - cy) * S + b), (200, 200, 210))
print('x', x0 * 32, x1 * 32, 'y', y0 * 32, y1 * 32)
img.save(sys.argv[2])
