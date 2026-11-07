import struct
import io
try:
    from PIL import Image, ImageDraw
    has_pil = True
except ImportError:
    has_pil = False

def create_bmp_entry(width, height, color_rgb):
    # If no PIL, create basic uncompressed BMP icon
    row_bytes = (width * 4) # 32 bpp
    image_bytes = row_bytes * height
    # BITMAPINFOHEADER (40 bytes)
    bih = struct.pack('<IIIHHIIIIII',
        40, width, height * 2, 1, 32, 0, image_bytes, 0, 0, 0, 0)
    # pixel data BGRA (bottom to top)
    pixels = bytearray()
    for y in range(height):
        for x in range(width):
            # draw violet rounded box
            r, g, b = color_rgb
            pixels.extend([b, g, r, 255])
    and_mask = bytearray(((width + 31) // 32) * 4 * height)
    return bih + bytes(pixels) + bytes(and_mask)

def main():
    path = r'E:\orq-bolsalaboral\BolsaDeployManager\resources\icono.ico'
    sizes = [16, 32, 48, 64, 128, 256]
    images_data = []

    if has_pil:
        for s in sizes:
            img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
            draw = ImageDraw.Draw(img)
            # rounded rect
            rad = max(2, int(s * 0.18))
            draw.rounded_rectangle([0, 0, s-1, s-1], radius=rad, fill=(124, 58, 237, 255))
            # stylized arrow / checkmark
            cx, cy = s / 2.0, s / 2.0
            st = s * 0.22
            points = [
                (cx - st, cy + st * 0.6),
                (cx - st * 0.1, cy + st * 1.4),
                (cx + st * 1.2, cy - st * 1.2)
            ]
            draw.line(points, fill=(255, 255, 255, 255), width=max(2, int(s * 0.09)))
            buf = io.BytesIO()
            img.save(buf, format='PNG')
            images_data.append((s, buf.getvalue()))
    else:
        for s in sizes:
            raw = create_bmp_entry(s, s, (124, 58, 237))
            images_data.append((s, raw))

    count = len(images_data)
    with open(path, 'wb') as f:
        f.write(struct.pack('<HHH', 0, 1, count))
        offset = 6 + 16 * count
        for s, data in images_data:
            w = 0 if s >= 256 else s
            h = 0 if s >= 256 else s
            f.write(struct.pack('<BBBBHHII', w, h, 0, 0, 1, 32, len(data), offset))
            offset += len(data)
        for _, data in images_data:
            f.write(data)

    print(f"Icono creado exitosamente en {path}")

if __name__ == '__main__':
    main()

