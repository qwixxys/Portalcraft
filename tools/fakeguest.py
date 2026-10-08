"""Stand-in for the Minecraft mod: reads Portal 2's camera and publishes a test frame.

    python fakeguest.py camera          # print the host camera a few times
    python fakeguest.py frame [secs]    # publish a green square at 3 blocks (+ an overlay bar) for N seconds
"""
import mmap, struct, sys, time
import numpy as np

HOST = mmap.mmap(-1, 65536, tagname="Local\\PortalcraftHost")
GUEST = mmap.mmap(-1, 65536, tagname="Local\\PortalcraftGuest")
MAX_W, MAX_H = 2560, 1440
FRAME = mmap.mmap(-1, 4096 + 3 * MAX_W * MAX_H * 12, tagname="Local\\PortalcraftFrame")


def host():
    magic, seq, flags, beat = struct.unpack_from("<4I", HOST, 0)
    eye = struct.unpack_from("<3f", HOST, 16)
    ang = struct.unpack_from("<3f", HOST, 28)
    fov, = struct.unpack_from("<f", HOST, 40)
    w, h = struct.unpack_from("<2i", HOST, 44)
    feet = struct.unpack_from("<3f", HOST, 52)
    health, = struct.unpack_from("<i", HOST, 76)
    name = bytes(HOST[88:152]).split(b"\0")[0].decode()
    return dict(magic=hex(magic), flags=flags, beat=beat, eye=eye, ang=ang, fov=fov, size=(w, h), feet=feet,
                health=health, map=name)


def guest_beat(i):
    struct.pack_into("<4I", GUEST, 0, 0x31474350, 0, 1, i)


def publish(w, h, near=0.05, far=1024.0, dist_blocks=3.0, serial=1):
    color = np.zeros((h, w, 4), np.uint8)
    depth = np.zeros((h, w), np.float32)           # 0 = nothing (reversed depth)
    overlay = np.zeros((h, w, 4), np.uint8)
    y0, y1, x0, x1 = h // 3, 2 * h // 3, w // 3, 2 * w // 3
    color[y0:y1, x0:x1] = (40, 220, 60, 255)       # RGBA
    wv = (far * near / dist_blocks - near) / (far - near)  # depth mode 1 (0..1 reversed)
    depth[y0:y1, x0:x1] = wv
    overlay[20:60, w // 2 - 200:w // 2 + 200] = (128, 0, 0, 128)  # premultiplied red, half transparent (row 20 = bottom)
    slot = serial % 3
    base = 4096 + slot * w * h * 12
    plane = w * h * 4
    FRAME[base:base + plane] = color.tobytes()
    FRAME[base + plane:base + 2 * plane] = depth.tobytes()
    FRAME[base + 2 * plane:base + 3 * plane] = overlay.tobytes()
    struct.pack_into("<2I", FRAME, 64 + 64 * slot, serial, 0)
    struct.pack_into("<6I2f", FRAME, 0, 0x31464350, slot, w, h, serial, 1, near, far)


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "camera"
    if mode == "camera":
        for _ in range(3):
            print(host())
            time.sleep(0.5)
    else:
        secs = float(sys.argv[2]) if len(sys.argv) > 2 else 20
        h = host()
        w, hh = h["size"]
        print("host", h)
        t0, i = time.time(), 0
        while time.time() - t0 < secs:
            i += 1
            guest_beat(i)
            if i % 30 == 1:
                publish(w, hh, serial=i)
            time.sleep(1 / 60)
