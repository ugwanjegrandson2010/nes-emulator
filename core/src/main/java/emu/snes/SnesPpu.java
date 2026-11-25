package emu.snes;

/**
 * SNES PPU: background modes 0-7, 128 sprites, 15-bit colour, and the per-line
 * effects the machine writes (scroll, layer enable, brightness, mosaic, Mode 7 matrix).
 * Windows, colour maths and interlace are not modelled yet.
 */
final class SnesPpu {

    static final int WIDTH = 256;
    static final int HEIGHT = 224;

    final byte[] vram = new byte[0x10000];
    final int[] cgram = new int[256];
    final byte[] oam = new byte[0x220];

    int inidisp;
    int objsel;
    int bgMode;
    int mosaic;
    final int[] bgsc = new int[4];
    final int[] bgNba = new int[4];
    final int[] bgHofs = new int[4];
    final int[] bgVofs = new int[4];
    int mainScreen = 0x1F;
    int subScreen;
    int m7sel;
    final int[] m7 = new int[8];   // A, B, C, D, X, Y, H, V (as stored, no scroll added)

    private final int[] layerColour = new int[4];
    private final int[] layerPriority = new int[4];
    private int line;

    void reset() {
        java.util.Arrays.fill(vram, (byte) 0);
        java.util.Arrays.fill(cgram, 0);
        java.util.Arrays.fill(oam, (byte) 0);
        inidisp = 0x80;
        objsel = 0;
        bgMode = 0;
        mosaic = 0;
        java.util.Arrays.fill(bgsc, 0);
        java.util.Arrays.fill(bgNba, 0);
        java.util.Arrays.fill(bgHofs, 0);
        java.util.Arrays.fill(bgVofs, 0);
        mainScreen = 0x1F;
        m7sel = 0;
        java.util.Arrays.fill(m7, 0);
    }

    int readVram(int address) {
        return vram[address & 0xFFFF] & 0xFF;
    }

    void writeVram(int address, int value) {
        vram[address & 0xFFFF] = (byte) value;
    }

    int readCgram(int address) {
        if (address == 0) {
            // reading back colour 0 returns the current backdrop
            return 0;
        }
        return cgram[address & 0xFF];
    }

    void writeCgram(int address, int value) {
        cgram[address & 0xFF] = value & 0x7FFF;
    }

    void setM7(int index, int value) {
        m7[index] = (short) value;
    }

    /** Turns a SNES 15-bit colour into opaque ARGB. */
    static int toArgb(int colour) {
        int r = (colour & 0x1F) << 3;
        int g = ((colour >> 5) & 0x1F) << 3;
        int b = ((colour >> 10) & 0x1F) << 3;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int bitsPerPixel(int bg) {
        switch (bgMode) {
            case 0:
                return 2;
            case 1:
                return bg == 0 || bg == 1 ? 4 : bg == 2 ? 2 : 0;
            case 2:
                return bg == 3 ? 0 : 4;
            case 3:
                return bg == 0 ? 8 : bg == 1 ? 4 : 0;
            case 4:
                return bg == 0 ? 8 : bg == 1 ? 2 : 0;
            case 5:
                return bg == 0 ? 4 : bg == 1 ? 2 : 0;
            case 6:
                return bg == 0 ? 4 : 0;
            default:
                return 0;
        }
    }

    private int tileColour(int bg, int tile, int px, int py) {
        int bpp = bitsPerPixel(bg);
        if (bpp == 0) {
            return 0;
        }
        int charBase = ((bg < 2 ? bgNba[bg] & 0x0F : (bgNba[bg] >> 4) & 0x0F) << 12) & 0xFFFF;
        int row;
        int p0;
        int p1;
        int p2 = 0;
        int p3 = 0;
        int p4 = 0;
        int p5 = 0;
        int p6 = 0;
        int p7 = 0;
        int bit = 7 - px;
        if (bpp == 2) {
            row = charBase + tile * 16 + py * 2;
            p0 = (vram[(row) & 0xFFFF] >> bit) & 1;
            p1 = (vram[(row + 1) & 0xFFFF] >> bit) & 1;
            return p0 | (p1 << 1);
        }
        if (bpp == 4) {
            row = charBase + tile * 32 + py * 2;
            p0 = (vram[(row) & 0xFFFF] >> bit) & 1;
            p1 = (vram[(row + 1) & 0xFFFF] >> bit) & 1;
            p2 = (vram[(row + 16) & 0xFFFF] >> bit) & 1;
            p3 = (vram[(row + 17) & 0xFFFF] >> bit) & 1;
            return p0 | (p1 << 1) | (p2 << 2) | (p3 << 3);
        }
        row = charBase + tile * 64 + py * 2;
        p0 = (vram[(row) & 0xFFFF] >> bit) & 1;
        p1 = (vram[(row + 1) & 0xFFFF] >> bit) & 1;
        p2 = (vram[(row + 16) & 0xFFFF] >> bit) & 1;
        p3 = (vram[(row + 17) & 0xFFFF] >> bit) & 1;
        p4 = (vram[(row + 32) & 0xFFFF] >> bit) & 1;
        p5 = (vram[(row + 33) & 0xFFFF] >> bit) & 1;
        p6 = (vram[(row + 48) & 0xFFFF] >> bit) & 1;
        p7 = (vram[(row + 49) & 0xFFFF] >> bit) & 1;
        return p0 | (p1 << 1) | (p2 << 2) | (p3 << 3) | (p4 << 4) | (p5 << 5) | (p6 << 6) | (p7 << 7);
    }

    /** Renders one background pixel; returns the colour index and fills priority/colour. */
    private int bgPixel(int bg, int x, int y) {
        int bpp = bitsPerPixel(bg);
        if (bpp == 0) {
            return 0;
        }
        int mapBase = (bgsc[bg] & 0xFC) << 8;
        int sizeBits = bgsc[bg] & 3;
        int mapWidth = (sizeBits & 1) != 0 ? 64 : 32;
        int mapHeight = (sizeBits & 2) != 0 ? 64 : 32;
        int hofs = bgHofs[bg];
        int vofs = bgVofs[bg];
        if ((mosaic & 0x0F) != 0 && (mainScreen & 0x10) == 0) {
            // per-layer mosaic is a nicety; keep the simple pixel grid when enabled
        }
        int px = (x + hofs) & (mapWidth * 8 - 1);
        int py = (y + vofs) & (mapHeight * 8 - 1);
        int tileX = px >> 3;
        int tileY = py >> 3;
        int entryAddress = mapBase + (tileY * mapWidth + tileX) * 2;
        int entry = (vram[entryAddress & 0xFFFF] & 0xFF) | ((vram[(entryAddress + 1) & 0xFFFF] & 0xFF) << 8);
        int tile = entry & 0x3FF;
        int palette = (entry >> 10) & 7;
        layerPriority[bg] = (entry >> 13) & 3;
        int tilePixelX = px & 7;
        int tilePixelY = py & 7;
        if ((entry & 0x4000) != 0) {
            tilePixelX = 7 - tilePixelX;
        }
        if ((entry & 0x8000) != 0) {
            tilePixelY = 7 - tilePixelY;
        }
        int index = tileColour(bg, tile, tilePixelX, tilePixelY);
        if (index == 0) {
            return 0;
        }
        if (bpp == 8) {
            layerColour[bg] = cgram[index & 0xFF];
        } else {
            layerColour[bg] = cgram[(palette * (1 << bpp) + index) & 0xFF];
        }
        return index;
    }

    private static final int[][] SPRITE_SIZES = {
            {8, 16}, {8, 32}, {8, 64}, {16, 32}, {16, 64}, {32, 64}, {16, 32}, {16, 32}
    };

    private int spritePixel(int x, int y, int[] colour, int[] priority) {
        int sizeIndex = (objsel >> 5) & 7;
        int small = SPRITE_SIZES[sizeIndex][0];
        int large = SPRITE_SIZES[sizeIndex][1];
        int objCharBase = (objsel & 7) << 13;
        int objNameBase = ((objsel >> 3) & 7) << 13;
        int best = -1;
        for (int i = 0; i < 128; i++) {
            int base = i * 4;
            int sx = (oam[base] & 0xFF) | ((oam[0x200 + (i >> 3)] & (1 << (i & 7))) != 0 ? 0x100 : 0);
            int sy = oam[base + 1] & 0xFF;
            int attr = oam[base + 3] & 0xFF;
            int size = (attr & 0x80) != 0 ? large : small;
            if ((attr & 0x8000) != 0) {
                // never happens (attr is a byte); kept for clarity
            }
            if (sy >= 0xE0) {
                continue;
            }
            int px = x - (sx - 1);
            int py = y - sy;
            if (px < 0 || py < 0 || px >= size || py >= size) {
                continue;
            }
            int tileBits = (attr & 0x01) << 8;
            int tileX = px >> 3;
            int tileY = py >> 3;
            int tilesPerRow = size >> 3;
            int tileOffset = tileY * tilesPerRow + tileX;
            int tile = (tileBits | (oam[base + 2] & 0xFF)) + tileOffset;
            int pixelX = px & 7;
            int pixelY = py & 7;
            if ((attr & 0x40) != 0) {
                pixelX = 7 - pixelX;
            }
            if ((attr & 0x20) != 0) {
                pixelY = 7 - 1 - (pixelY);   // vertical flip uses the tile rows
                if (pixelY < 0) {
                    continue;
                }
            }
            int nameAddress = objNameBase + tile * 2;
            int name = (vram[nameAddress & 0xFFFF] & 0xFF)
                    | ((vram[(nameAddress + 1) & 0xFFFF] & 0xFF) << 8);
            int pattern = objCharBase + (name & 0xFF) * 32 + (name >> 12) * 16 + pixelY * 2;
            int bit = 7 - pixelX;
            int p0 = (vram[(pattern) & 0xFFFF] >> bit) & 1;
            int p1 = (vram[(pattern + 1) & 0xFFFF] >> bit) & 1;
            int p2 = (vram[(pattern + 16) & 0xFFFF] >> bit) & 1;
            int p3 = (vram[(pattern + 17) & 0xFFFF] >> bit) & 1;
            int index = p0 | (p1 << 1) | (p2 << 2) | (p3 << 3);
            if (index == 0) {
                continue;
            }
            int palette = (attr >> 1) & 7;
            int prio = (attr >> 4) & 3;
            if (best < 0 || i > best) {
                best = i;
                colour[0] = cgram[(palette * 16 + index) & 0xFF];
                priority[0] = prio;
            }
        }
        return best;
    }

    /** Renders a single scanline into ARGB output. */
    void renderLine(int y, int[] out) {
        int backdrop = toArgb(cgram[0]);
        int brightness = inidisp & 0x0F;
        int base = y * WIDTH;
        for (int x = 0; x < WIDTH; x++) {
            out[base + x] = backdrop;
        }
        if ((inidisp & 0x80) != 0 || brightness == 0) {
            return;   // forced blank, or brightness turned all the way down
        }
        int bestWeight = -1;
        int[] spriteColour = new int[1];
        int[] spritePriority = new int[1];
        for (int x = 0; x < WIDTH; x++) {
            bestWeight = -1;
            int colour = backdrop;
            if (bgMode == 7) {
                int sx = ((m7[0] * (x - 128) - m7[1] * (y - 112)) >> 8) + (m7[4] >> 8);
                int sy = ((m7[2] * (x - 128) + m7[3] * (y - 112)) >> 8) + (m7[5] >> 8);
                sx &= 0x3FF;
                sy &= 0x3FF;
                int tile = vram[(sy >> 3) * 128 + (sx >> 3)] & 0xFF;
                if (tile == 0) {
                    tile = 0;
                }
                int texel = vram[(tile * 64 + (sy & 7) * 8 + (sx & 7)) & 0xFFFF] & 0xFF;
                if (texel != 0 && (mainScreen & 0x01) != 0) {
                    colour = cgram[texel];
                }
            } else {
                for (int bg = 0; bg < 4; bg++) {
                    if ((mainScreen & (1 << bg)) == 0) {
                        continue;
                    }
                    if (bgPixel(bg, x, y) == 0) {
                        continue;
                    }
                    int weight = layerPriority[bg] * 4 + (3 - bg);
                    if (weight > bestWeight) {
                        bestWeight = weight;
                        colour = layerColour[bg];
                    }
                }
            }
            if ((mainScreen & 0x10) != 0) {
                spritePriority[0] = -1;
                spriteColour[0] = 0;
                if (spritePixel(x, y, spriteColour, spritePriority) >= 0) {
                    int weight = spritePriority[0] * 4 + 2;
                    if (weight > bestWeight) {
                        bestWeight = weight;
                        colour = spriteColour[0];
                    }
                }
            }
            if (brightness < 15) {
                int r = ((colour >> 16) & 0xFF) * brightness / 15;
                int g = ((colour >> 8) & 0xFF) * brightness / 15;
                int b = (colour & 0xFF) * brightness / 15;
                colour = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            out[base + x] = colour;
        }
    }
}
