package emu.nds;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The DS picture unit: two independent 2D engines (main and sub), palette, OAM and the shared
 * framebuffer. Text backgrounds (16 and 256 colour), 15-bit bitmap backgrounds and OAM sprites are
 * rendered; the 3D engine and blending/effects are not modelled.
 *
 * <p>VRAM is kept as four linear windows (main BG, main OBJ, sub BG, sub OBJ) which is the default
 * mapping every homebrew loader sets up.
 */
public final class NdsPpu {

    public static final int WIDTH = 256;
    public static final int HEIGHT = 192;

    public final byte[] bgVramMain = new byte[0x80000];
    public final byte[] objVramMain = new byte[0x40000];
    public final byte[] bgVramSub = new byte[0x20000];
    public final byte[] objVramSub = new byte[0x20000];
    public final byte[] palette = new byte[0x800];
    public final byte[] oamMain = new byte[0x800];
    public final byte[] oamSub = new byte[0x800];
    public final int[] framebuffer = new int[WIDTH * HEIGHT];

    public final Engine main = new Engine(true);
    public final Engine sub = new Engine(false);

    public int vcount;

    /** One 2D engine: its own register block, its own pixel buffer. */
    public static final class Engine {
        public final boolean primary;
        public final int[] reg = new int[0x80];
        public final int[] pixels = new int[WIDTH * HEIGHT];

        Engine(boolean primary) {
            this.primary = primary;
        }

        public int dispcnt() {
            return reg[0] & 0xFFFF;
        }

        public int bgcnt(int index) {
            return reg[0x08 + index * 2] & 0xFFFF;
        }

        public int layerEnabled(int layer) {
            return (dispcnt() >> (8 + layer)) & 1;
        }

        public int read16(int offset) {
            return reg[offset & 0x7F] & 0xFFFF;
        }

        public void write16(int offset, int value) {
            reg[offset & 0x7F] = value & 0xFFFF;
        }

        public int read32(int offset) {
            return read16(offset) | (read16(offset + 2) << 16);
        }

        public void write32(int offset, int value) {
            write16(offset, value & 0xFFFF);
            write16(offset + 2, (value >>> 16) & 0xFFFF);
        }
    }

    public void reset() {
        vcount = 0;
        java.util.Arrays.fill(framebuffer, 0xFF000000);
        for (Engine engine : new Engine[]{main, sub}) {
            java.util.Arrays.fill(engine.reg, 0);
            java.util.Arrays.fill(engine.pixels, 0);
            engine.reg[0x6C] = 0;
            // power-on display control: forced blank, engine enabled
            engine.reg[0] = 0x0080;
        }
        java.util.Arrays.fill(bgVramMain, (byte) 0);
        java.util.Arrays.fill(objVramMain, (byte) 0);
        java.util.Arrays.fill(bgVramSub, (byte) 0);
        java.util.Arrays.fill(objVramSub, (byte) 0);
        java.util.Arrays.fill(palette, (byte) 0);
        java.util.Arrays.fill(oamMain, (byte) 0);
        java.util.Arrays.fill(oamSub, (byte) 0);
    }

    // ------------------------------------------------------------------ VRAM access

    public int readVram(int address) {
        if (address >= 0x06800000) {
            // ARM9 VRAM windows are mirrored every 8 MiB (bit 27)
            address &= ~0x00800000;
        }
        if (address >= 0x06000000 && address < 0x06080000) {
            return bgVramMain[address - 0x06000000] & 0xFF;
        }
        if (address >= 0x06200000 && address < 0x06220000) {
            return bgVramSub[address - 0x06200000] & 0xFF;
        }
        if (address >= 0x06400000 && address < 0x06440000) {
            return objVramMain[address - 0x06400000] & 0xFF;
        }
        if (address >= 0x06600000 && address < 0x06620000) {
            return objVramSub[address - 0x06600000] & 0xFF;
        }
        return 0xFF;
    }

    public void writeVram(int address, int value) {
        if (address >= 0x06800000) {
            address &= ~0x00800000;
        }
        if (address >= 0x06000000 && address < 0x06080000) {
            bgVramMain[address - 0x06000000] = (byte) value;
            return;
        }
        if (address >= 0x06200000 && address < 0x06220000) {
            bgVramSub[address - 0x06200000] = (byte) value;
            return;
        }
        if (address >= 0x06400000 && address < 0x06440000) {
            objVramMain[address - 0x06400000] = (byte) value;
            return;
        }
        if (address >= 0x06600000 && address < 0x06620000) {
            objVramSub[address - 0x06620000 + 0x20000] = (byte) value;
        }
    }

    // ------------------------------------------------------------------ rendering

    /** Renders one field: both engines, then composites the sub engine under the main one. */
    public void render() {
        renderEngine(main, bgVramMain, objVramMain, oamMain, 0x000, 0x200);
        renderEngine(sub, bgVramSub, objVramSub, oamSub, 0x400, 0x600);
        for (int i = 0; i < framebuffer.length; i++) {
            int top = main.pixels[i];
            if ((top & 0xFF000000) != 0) {
                framebuffer[i] = top;
            } else {
                int bottom = sub.pixels[i];
                framebuffer[i] = (bottom & 0xFF000000) != 0 ? bottom : 0xFF000000;
            }
        }
    }

    private void renderEngine(Engine engine, byte[] bgVram, byte[] objVram, byte[] oam,
                              int paletteBase, int objPaletteBase) {
        java.util.Arrays.fill(engine.pixels, 0);
        int dispcnt = engine.dispcnt();
        if ((dispcnt & 0x0080) != 0) {
            return;
        }
        int mode = dispcnt & 7;
        int[] priority = new int[WIDTH * HEIGHT];
        java.util.Arrays.fill(priority, 0xFF);

        if (mode == 5) {
            renderBitmap(engine, bgVram, paletteBase, priority);
        } else if (mode <= 2) {
            for (int bg = 3; bg >= 0; bg--) {
                if (((dispcnt >> (8 + bg)) & 1) == 0) {
                    continue;
                }
                boolean affine = (mode == 1 && bg == 2) || (mode == 2 && bg >= 2);
                if (affine) {
                    renderAffineBg(engine, bgVram, paletteBase, bg, priority);
                } else {
                    renderTextBg(engine, bgVram, paletteBase, bg, priority);
                }
            }
        }

        if ((dispcnt & 0x1000) != 0) {
            renderSprites(engine, objVram, oam, objPaletteBase, priority);
        }
    }

    private void renderTextBg(Engine engine, byte[] vram, int paletteBase, int bg, int[] priority) {
        int cnt = engine.bgcnt(bg);
        int bgPriority = cnt & 3;
        int charBase = ((cnt >> 2) & 3) * 0x10000;
        boolean color256 = (cnt & 0x80) != 0;
        int screenBase = ((cnt >> 8) & 0x1F) * 0x800;
        int size = (cnt >> 14) & 3;
        int width = (size == 1 || size == 3) ? 512 : 256;
        int height = (size == 2 || size == 3) ? 512 : 256;
        int hofs = engine.reg[0x10 + bg * 4] & 0x1FF;
        int vofs = engine.reg[0x12 + bg * 4] & 0x1FF;
        int mapStride = width / 256;

        for (int y = 0; y < HEIGHT; y++) {
            int sy = (y + vofs) & (height - 1);
            int blockRow = sy >> 8;
            int tileRow = (sy & 0xFF) >> 3;
            int fineY = sy & 7;
            for (int x = 0; x < WIDTH; x++) {
                int sx = (x + hofs) & (width - 1);
                int blockCol = sx >> 8;
                int block = blockCol + blockRow * mapStride;
                int mapAddress = screenBase + block * 0x800 + (tileRow * 32 + ((sx & 0xFF) >> 3)) * 2;
                int entry = read16(vram, mapAddress);
                int tile = entry & 0x3FF;
                int paletteBank = (entry >> 12) & 0xF;
                int fy = (entry & 0x800) != 0 ? 7 - fineY : fineY;
                int fx = (entry & 0x400) != 0 ? 7 - (sx & 7) : sx & 7;
                int color;
                if (color256) {
                    int index = vram[charBase + tile * 64 + fy * 8 + fx] & 0xFF;
                    if (index == 0) {
                        continue;
                    }
                    color = paletteColor(paletteBase + index * 2);
                } else {
                    int address = charBase + tile * 32 + fy * 4 + (fx >> 1);
                    int pair = vram[address] & 0xFF;
                    int index = (fx & 1) != 0 ? (pair >> 4) : (pair & 0xF);
                    if (index == 0) {
                        continue;
                    }
                    color = paletteColor(paletteBase + (paletteBank * 16 + index) * 2);
                }
                int pixel = y * WIDTH + x;
                if (bgPriority <= priority[pixel]) {
                    engine.pixels[pixel] = color;
                    priority[pixel] = bgPriority;
                }
            }
        }
    }

    private void renderAffineBg(Engine engine, byte[] vram, int paletteBase, int bg, int[] priority) {
        int cnt = engine.bgcnt(bg);
        int bgPriority = cnt & 3;
        int charBase = ((cnt >> 2) & 3) * 0x10000;
        int screenBase = ((cnt >> 8) & 0x1F) * 0x800;
        boolean color256 = (cnt & 0x80) != 0;
        int size = (cnt >> 14) & 3;
        int sizePixels = 128 << size;
        int base = 0x20 + (bg - 2) * 0x10;
        int pa = (short) engine.reg[base];
        int pb = (short) engine.reg[base + 2];
        int pc = (short) engine.reg[base + 4];
        int pd = (short) engine.reg[base + 6];
        int refX = (engine.reg[base + 8] | (engine.reg[base + 10] << 16)) << 4 >> 4;
        int refY = (engine.reg[base + 12] | (engine.reg[base + 14] << 16)) << 4 >> 4;
        int tilesPerRow = sizePixels >> 3;
        for (int y = 0; y < HEIGHT; y++) {
            long rowX = (long) refX + (long) pb * y;
            long rowY = (long) refY + (long) pd * y;
            for (int x = 0; x < WIDTH; x++) {
                int texX = (int) ((rowX + (long) pa * x) >> 8);
                int texY = (int) ((rowY + (long) pc * x) >> 8);
                if (texX < 0 || texY < 0 || texX >= sizePixels || texY >= sizePixels) {
                    continue;
                }
                int tileIndex = (texY >> 3) * tilesPerRow + (texX >> 3);
                int tile = vram[screenBase + tileIndex & (vram.length - 1)] & 0xFF;
                int fy = texY & 7;
                int fx = texX & 7;
                int color;
                if (color256) {
                    int index = vram[charBase + tile * 64 + fy * 8 + fx & (vram.length - 1)] & 0xFF;
                    if (index == 0) {
                        continue;
                    }
                    color = paletteColor(paletteBase + index * 2);
                } else {
                    int address = charBase + tile * 32 + fy * 4 + (fx >> 1);
                    int pair = vram[address & (vram.length - 1)] & 0xFF;
                    int index = (fx & 1) != 0 ? (pair >> 4) : (pair & 0xF);
                    if (index == 0) {
                        continue;
                    }
                    color = paletteColor(paletteBase + index * 2);
                }
                int pixel = y * WIDTH + x;
                if (bgPriority <= priority[pixel]) {
                    engine.pixels[pixel] = color;
                    priority[pixel] = bgPriority;
                }
            }
        }
    }

    private void renderBitmap(Engine engine, byte[] vram, int paletteBase, int[] priority) {
        int cnt = engine.bgcnt(2);
        int bgPriority = cnt & 3;
        boolean direct = (engine.dispcnt() & 0x0002) == 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int pixel = y * WIDTH + x;
                int color;
                if (direct) {
                    int address = (y * WIDTH + x) * 2;
                    int value = read16(vram, address);
                    if (value == 0) {
                        continue;
                    }
                    color = rgb555(value);
                } else {
                    int index = vram[y * WIDTH + x] & 0xFF;
                    if (index == 0) {
                        continue;
                    }
                    color = paletteColor(paletteBase + index * 2);
                }
                if (bgPriority <= priority[pixel]) {
                    engine.pixels[pixel] = color;
                    priority[pixel] = bgPriority;
                }
            }
        }
    }

    private static final int[][] SPRITE_WIDTHS = {{8, 16, 32, 64}, {16, 32, 32, 64}, {8, 8, 16, 32}};
    private static final int[][] SPRITE_HEIGHTS = {{8, 16, 32, 64}, {8, 8, 16, 32}, {16, 32, 32, 64}};

    private void renderSprites(Engine engine, byte[] vram, byte[] oam, int paletteBase, int[] priority) {
        for (int i = 127; i >= 0; i--) {
            int attr0 = read16(oam, i * 8);
            int attr1 = read16(oam, i * 8 + 2);
            int attr2 = read16(oam, i * 8 + 4);
            if ((attr0 & 0x200) != 0) {
                continue;
            }
            int y = attr0 & 0xFF;
            if (y >= HEIGHT) {
                y -= 256;
            }
            int x = attr1 & 0x1FF;
            if (x >= 256) {
                x -= 512;
            }
            int shape = (attr0 >> 14) & 3;
            int size = (attr1 >> 14) & 3;
            if (shape == 3) {
                continue;
            }
            int width = SPRITE_WIDTHS[shape][size];
            int height = SPRITE_HEIGHTS[shape][size];
            boolean color256 = (attr0 & 0x2000) != 0;
            boolean hflip = (attr1 & 0x1000) != 0;
            boolean vflip = (attr1 & 0x2000) != 0;
            int tileBase = attr2 & 0x3FF;
            int objPriority = (attr2 >> 10) & 3;
            int paletteBank = attr2 >> 12;
            int tilesPerRow = width >> 3;
            int baseTile = color256 ? tileBase * 2 : tileBase;
            for (int py = 0; py < height; py++) {
                int screenY = y + py;
                if (screenY < 0 || screenY >= HEIGHT) {
                    continue;
                }
                int fy = vflip ? height - 1 - py : py;
                for (int px = 0; px < width; px++) {
                    int screenX = x + px;
                    if (screenX < 0 || screenX >= WIDTH) {
                        continue;
                    }
                    int fx = hflip ? width - 1 - px : px;
                    int tile = baseTile + (fy >> 3) * tilesPerRow + (fx >> 3);
                    int tx = fx & 7;
                    int ty = fy & 7;
                    int color;
                    if (color256) {
                        int address = tile * 64 + ty * 8 + tx;
                        int index = vram[address & (vram.length - 1)] & 0xFF;
                        if (index == 0) {
                            continue;
                        }
                        color = paletteColor(paletteBase + index * 2);
                    } else {
                        int address = tile * 32 + ty * 4 + (tx >> 1);
                        int pair = vram[address & (vram.length - 1)] & 0xFF;
                        int index = (tx & 1) != 0 ? (pair >> 4) : (pair & 0xF);
                        if (index == 0) {
                            continue;
                        }
                        color = paletteColor(paletteBase + (paletteBank * 16 + index) * 2);
                    }
                    int pixel = screenY * WIDTH + screenX;
                    if (objPriority <= priority[pixel]) {
                        engine.pixels[pixel] = color;
                        priority[pixel] = objPriority;
                    }
                }
            }
        }
    }

    private int paletteColor(int address) {
        int value = read16(palette, address);
        return rgb555(value);
    }

    private static int rgb555(int value) {
        int r = value & 0x1F;
        int g = (value >> 5) & 0x1F;
        int b = (value >> 10) & 0x1F;
        r = (r << 3) | (r >> 2);
        g = (g << 3) | (g >> 2);
        b = (b << 3) | (b >> 2);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int read16(byte[] data, int address) {
        if (address < 0 || address + 1 >= data.length) {
            return 0;
        }
        return (data[address] & 0xFF) | ((data[address + 1] & 0xFF) << 8);
    }

    // ------------------------------------------------------------------ save states

    public void saveState(DataOutputStream out) throws IOException {
        out.writeInt(vcount);
        out.write(bgVramMain);
        out.write(objVramMain);
        out.write(bgVramSub);
        out.write(objVramSub);
        out.write(palette);
        out.write(oamMain);
        out.write(oamSub);
        for (Engine engine : new Engine[]{main, sub}) {
            for (int value : engine.reg) {
                out.writeInt(value);
            }
        }
    }

    public void loadState(DataInputStream in) throws IOException {
        vcount = in.readInt();
        in.readFully(bgVramMain);
        in.readFully(objVramMain);
        in.readFully(bgVramSub);
        in.readFully(objVramSub);
        in.readFully(palette);
        in.readFully(oamMain);
        in.readFully(oamSub);
        for (Engine engine : new Engine[]{main, sub}) {
            for (int i = 0; i < engine.reg.length; i++) {
                engine.reg[i] = in.readInt();
            }
        }
        render();
    }
}
