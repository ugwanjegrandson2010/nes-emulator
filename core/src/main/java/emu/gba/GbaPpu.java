package emu.gba;

/**
 * Game Boy Advance picture processor: turns the four tiled background layers, the 128 sprites and
 * the three bitmap modes into an ARGB scanline. All state lives in plain arrays so a save state is
 * just the bytes of those arrays.
 */
final class GbaPpu {

    static final int WIDTH = 240;
    static final int HEIGHT = 160;

    private static final int[] TILE_X = new int[64];
    private static final int[] TILE_Y = new int[64];

    final byte[] vram = new byte[0x18000];
    final short[] palette = new short[512];
    final short[] oam = new short[512];
    final int[] line = new int[WIDTH];
    final int[] framebuffer = new int[WIDTH * HEIGHT];
    final int[] priority = new int[WIDTH];

    int dispcnt;
    int dispstat;
    int vcount;
    final int[] bgcnt = new int[4];
    final int[] bgofs = new int[8];
    final int[] bgAffine = new int[4 * 8];   // pa, pb, pc, pd, refX, refY (32.8 fixed point)
    int bldcnt;
    int bldalpha;
    int bldy;
    int mosaic;

    private int scrollX;
    private int scrollY;

    GbaPpu() {
        for (int i = 0; i < 64; i++) {
            TILE_X[i] = (i & 7) * 8;
            TILE_Y[i] = (i >> 3) * 8;
        }
    }

    void reset() {
        java.util.Arrays.fill(vram, (byte) 0);
        java.util.Arrays.fill(palette, (short) 0);
        java.util.Arrays.fill(oam, (short) 0);
        dispcnt = 0x0080;
        dispstat = 0;
        vcount = 0;
        java.util.Arrays.fill(bgcnt, 0);
        java.util.Arrays.fill(bgofs, 0);
        java.util.Arrays.fill(bgAffine, 0);
        bldcnt = 0;
        bldalpha = 0;
        bldy = 0;
        mosaic = 0;
    }

    // ---------------------------------------------------------------- register access

    int read16(int offset) {
        switch (offset) {
            case 0x00: return dispcnt & 0xFFFF;
            case 0x04: return dispstat | (vblankFlag ? 1 : 0)
                    | ((vcount == ((dispstat >> 8) & 0xFF)) ? 4 : 0);
            case 0x06: return vcount & 0xFF;
            case 0x08: case 0x0A: case 0x0C: case 0x0E: return bgcnt[(offset - 8) >> 1];
            case 0x10: case 0x12: case 0x14: case 0x16: return bgofs[(offset - 0x10) >> 1];
            case 0x18: case 0x1A: case 0x1C: case 0x1E: return bgofs[4 + ((offset - 0x18) >> 1)];
            default:
                break;
        }
        if (offset >= 0x20 && offset < 0x40) {
            return bgAffine[(offset - 0x20) >> 1];
        }
        if (offset >= 0x40 && offset < 0x48) {
            return 0;
        }
        switch (offset) {
            case 0x48: return 0;
            case 0x4A: return bldalpha;
            case 0x4C: return 0;
            case 0x4E: return 0;
            case 0x50: return bldcnt;
            case 0x52: return bldy;
            default: return 0;
        }
    }

    void write16(int offset, int value) {
        switch (offset) {
            case 0x00: dispcnt = value & 0xFFFF; return;
            case 0x04: dispstat = (dispstat & 0x0007) | (value & 0xFFF8); return;
            case 0x08: case 0x0A: case 0x0C: case 0x0E: bgcnt[(offset - 8) >> 1] = value & 0xFFFF; return;
            case 0x10: case 0x12: case 0x14: case 0x16:
                bgofs[(offset - 0x10) >> 1] = value & 0x1FF;
                return;
            case 0x18: case 0x1A: case 0x1C: case 0x1E:
                bgofs[4 + ((offset - 0x18) >> 1)] = value & 0x1FF;
                return;
            case 0x4A: bldalpha = value & 0xFFFF; return;
            case 0x50: bldcnt = value & 0xFFFF; return;
            case 0x52: bldy = value & 0x1F; return;
            default: break;
        }
        if (offset >= 0x20 && offset < 0x40) {
            bgAffine[(offset - 0x20) >> 1] = (short) value;
            return;
        }
        if (offset == 0x4C || offset == 0x48) {
            mosaic = value & 0xFFFF;
        }
    }

    int read8(int offset) {
        int half = read16(offset & ~1);
        return (offset & 1) == 0 ? half & 0xFF : (half >> 8) & 0xFF;
    }

    void write8(int offset, int value) {
        int half = read16(offset & ~1);
        if ((offset & 1) == 0) {
            half = (half & 0xFF00) | (value & 0xFF);
        } else {
            half = (half & 0x00FF) | ((value & 0xFF) << 8);
        }
        write16(offset & ~1, half);
    }

    // ---------------------------------------------------------------- palette

    int paletteHalf(int index) {
        return palette[index & 0x1FF] & 0xFFFF;
    }

    void writePalette(int index, int value, boolean wide) {
        int slot = index & 0x1FF;
        palette[slot] = (short) (value & 0xFFFF);
    }

    // ---------------------------------------------------------------- OAM

    int oamHalf(int index) {
        return oam[index & 0x1FF] & 0xFFFF;
    }

    void writeOam(int index, int value) {
        oam[index & 0x1FF] = (short) (value & 0xFFFF);
    }

    // ---------------------------------------------------------------- frame state

    private boolean vblankFlag;

    void beginFrame() {
        vblankFlag = false;
    }

    void startVBlank() {
        vblankFlag = true;
    }

    int color(int index) {
        int c = palette[index & 0x1FF] & 0xFFFF;
        int red = c & 0x1F;
        int green = (c >> 5) & 0x1F;
        int blue = (c >> 10) & 0x1F;
        return 0xFF000000 | (red << 19) | (green << 11) | (blue << 3);
    }

    /** Renders one visible scanline into {@link #line}. */
    void renderLine(int y) {
        this.vcount = y;
        int mode = dispcnt & 7;
        java.util.Arrays.fill(priority, 4);
        int background = color(0);
        if ((dispcnt & 0x0080) == 0 && (dispcnt & 0x0080) == 0) {
            // forced blank
        }
        for (int x = 0; x < WIDTH; x++) {
            line[x] = background;
        }
        if ((dispcnt & 0x0080) != 0) {
            return;   // forced blank: white/blank screen
        }
        if (mode >= 3) {
            renderBitmap(mode, y);
        } else {
            renderBackgrounds(mode, y);
            renderSprites(y);
        }
        System.arraycopy(line, 0, framebuffer, y * WIDTH, WIDTH);
    }

    private void renderBackgrounds(int mode, int y) {
        for (int bg = 3; bg >= 0; bg--) {
            if (((dispcnt >> (8 + bg)) & 1) == 0) {
                continue;
            }
            boolean affine = (mode == 1 && bg == 2) || (mode == 2 && (bg >= 2));
            if (affine) {
                renderAffineBg(bg, y);
            } else {
                renderTextBg(bg, y);
            }
        }
    }

    private void renderTextBg(int bg, int y) {
        int cnt = bgcnt[bg];
        int size = (cnt >> 14) & 3;
        int charBase = ((cnt >> 2) & 3) * 0x4000;
        int screenBase = ((cnt >> 8) & 0x1F) * 0x800;
        boolean color256 = (cnt & 0x80) != 0;
        int priorityValue = cnt & 3;
        int width = (size & 1) != 0 ? 512 : 256;
        int height = (size & 2) != 0 ? 512 : 256;
        int scrollXValue = bgofs[bg] & 0x1FF;
        int scrollYValue = bgofs[4 + bg] & 0x1FF;
        int row = (y + scrollYValue) & (height - 1);
        int tileRow = row >> 3;
        int insideRow = row & 7;
        for (int x = 0; x < WIDTH; x++) {
            int column = (x + scrollXValue) & (width - 1);
            int tileColumn = column >> 3;
            int screenBlock = screenBase + ((tileRow >> 5) * 0x800) + (((tileColumn >> 5) & 1) * 0x800)
                    + ((tileRow & 31) * 64) + ((tileColumn & 31) * 2);
            if (screenBlock + 1 >= vram.length) {
                continue;
            }
            int mapEntry = (vram[screenBlock] & 0xFF) | ((vram[screenBlock + 1] & 0xFF) << 8);
            int tile = mapEntry & 0x3FF;
            int paletteBank = (mapEntry >> 12) & 0xF;
            int fineX = column & 7;
            if ((mapEntry & 0x0400) != 0) {   // horizontal flip
                fineX = 7 - fineX;
            }
            int pixel;
            if (color256) {
                int address = charBase + tile * 64 + insideRow * 8 + fineX;
                pixel = address < vram.length ? vram[address] & 0xFF : 0;
            } else {
                int address = charBase + tile * 32 + insideRow * 4 + (fineX >> 1);
                int packed = address < vram.length ? vram[address] & 0xFF : 0;
                pixel = ((fineX & 1) == 0 ? packed & 0xF : packed >> 4) | (paletteBank << 4);
            }
            if (pixel == 0) {
                continue;
            }
            blend(bg, x, pixel, priorityValue);
        }
    }

    private void renderAffineBg(int bg, int y) {
        int cnt = bgcnt[bg];
        int size = (cnt >> 14) & 3;
        int charBase = ((cnt >> 2) & 3) * 0x4000;
        int screenBase = ((cnt >> 8) & 0x1F) * 0x800;
        int tiles = size == 0 ? 16 : size == 1 ? 32 : size == 2 ? 64 : 128;
        int priorityValue = cnt & 3;
        int base = bg * 8;
        int pa = bgAffine[base] & 0xFFFF;
        int pb = bgAffine[base + 1] & 0xFFFF;
        int pc = bgAffine[base + 2] & 0xFFFF;
        int pd = bgAffine[base + 3] & 0xFFFF;
        int refX = (bgAffine[base + 4] & 0xFFFF) | ((bgAffine[base + 5] & 0xFF) << 16);
        int refY = (bgAffine[base + 6] & 0xFFFF) | ((bgAffine[base + 7] & 0xFF) << 16);
        pa = (int) (short) pa;
        pb = (int) (short) pb;
        pc = (int) (short) pc;
        pd = (int) (short) pd;
        int startX = refX + pb * y;
        int startY = refY + pd * y;
        for (int x = 0; x < WIDTH; x++) {
            int texX = (startX + pa * x) >> 8;
            int texY = (startY + pc * x) >> 8;
            if (texX < 0 || texY < 0 || texX >= tiles * 8 || texY >= tiles * 8) {
                continue;
            }
            int mapAddress = screenBase + (texY >> 3) * tiles + (texX >> 3);
            if (mapAddress >= vram.length) {
                continue;
            }
            int tile = vram[mapAddress] & 0xFF;
            int address = charBase + tile * 64 + (texY & 7) * 8 + (texX & 7);
            int pixel = address < vram.length ? vram[address] & 0xFF : 0;
            if (pixel == 0) {
                continue;
            }
            blend(bg, x, pixel, priorityValue);
        }
    }

    private void renderBitmap(int mode, int y) {
        if (mode == 3) {
            for (int x = 0; x < WIDTH; x++) {
                int address = (y * WIDTH + x) * 2;
                int color = (vram[address] & 0xFF) | ((vram[address + 1] & 0xFF) << 8);
                line[x] = bgr555(color);
            }
            return;
        }
        if (mode == 5) {
            int page = (dispcnt & 0x10) != 0 ? 0xA000 : 0;
            for (int x = 0; x < WIDTH; x++) {
                int address = page + (y * WIDTH + x) * 2;
                int color = (vram[address] & 0xFF) | ((vram[address + 1] & 0xFF) << 8);
                line[x] = bgr555(color);
            }
            return;
        }
        // mode 4: 8bpp paletted bitmap with the same double buffering as mode 5
        int page = (dispcnt & 0x10) != 0 ? 0xA000 : 0;
        for (int x = 0; x < WIDTH; x++) {
            int address = page + y * WIDTH + x;
            int index = vram[address] & 0xFF;
            line[x] = index == 0 ? color(0) : color(index);
        }
    }

    private void blend(int layer, int x, int index, int priorityValue) {
        if (priorityValue > priority[x]) {
            return;
        }
        priority[x] = priorityValue;
        line[x] = color(layer == 0 ? index : index);
    }

    private int bgr555(int color) {
        int red = color & 0x1F;
        int green = (color >> 5) & 0x1F;
        int blue = (color >> 10) & 0x1F;
        return 0xFF000000 | (red << 19) | (green << 11) | (blue << 3);
    }

    private void renderSprites(int y) {
        boolean oneDimensional = (dispcnt & 0x40) != 0;
        for (int sprite = 127; sprite >= 0; sprite--) {
            int base = sprite * 4;
            int attr0 = oam[base] & 0xFFFF;
            int attr1 = oam[base + 1] & 0xFFFF;
            int attr2 = oam[base + 2] & 0xFFFF;
            int spriteY = attr0 & 0xFF;
            if (spriteY >= 160) {
                spriteY -= 256;
            }
            boolean affine = (attr0 & 0x0100) != 0;
            boolean doubleSize = affine && (attr0 & 0x0200) != 0;
            int shape = (attr0 >> 14) & 3;
            int sizeCode = (attr1 >> 14) & 3;
            int width;
            int height;
            switch (shape) {
                case 0:  // square
                    width = height = new int[]{8, 16, 32, 64}[sizeCode];
                    break;
                case 1:  // wide
                    width = new int[]{16, 32, 32, 64}[sizeCode];
                    height = new int[]{8, 8, 16, 32}[sizeCode];
                    break;
                case 2:  // tall
                    width = new int[]{8, 8, 16, 32}[sizeCode];
                    height = new int[]{16, 32, 32, 64}[sizeCode];
                    break;
                default:
                    continue;
            }
            int spanX = doubleSize ? width * 2 : width;
            int spanY = doubleSize ? height * 2 : height;
            int top = doubleSize ? spriteY + height / 2 : spriteY;
            if (y < top || y >= top + spanY) {
                continue;
            }
            boolean disabled = (attr0 & 0x0200) != 0 && !affine;
            if (disabled) {
                continue;
            }
            int spriteX = attr1 & 0x1FF;
            if (spriteX >= 240) {
                spriteX -= 512;
            }
            int left = doubleSize ? spriteX + width / 2 : spriteX;
            boolean color256 = (attr0 & 0x2000) != 0;
            boolean hflip = !affine && (attr1 & 0x1000) != 0;
            boolean vflip = !affine && (attr1 & 0x2000) != 0;
            int priorityValue = (attr2 >> 10) & 3;
            int paletteBank = (attr2 >> 12) & 0xF;
            int tileIndex = attr2 & 0x3FF;
            int row = y - top;
            if (vflip) {
                row = spanY - 1 - row;
            }
            for (int column = 0; column < spanX; column++) {
                int x = left + column;
                if (x < 0 || x >= WIDTH) {
                    continue;
                }
                int sampleX = hflip ? spanX - 1 - column : column;
                int tx = sampleX >> 3;
                int ty = row >> 3;
                int tile;
                if (oneDimensional) {
                    tile = tileIndex + ty * (spanX >> 3) + tx;
                } else {
                    tile = tileIndex + (ty >> 5) * 32 + tx;
                }
                int pixel;
                if (color256) {
                    int address = tile * 64 + (row & 7) * 8 + (sampleX & 7);
                    pixel = address < vram.length ? vram[address] & 0xFF : 0;
                } else {
                    int address = tile * 32 + (row & 7) * 4 + ((sampleX & 7) >> 1);
                    int packed = address < vram.length ? vram[address] & 0xFF : 0;
                    pixel = ((sampleX & 1) == 0 ? packed & 0xF : packed >> 4) | (paletteBank << 4);
                }
                if (pixel == 0) {
                    continue;
                }
                if (priorityValue > priority[x]) {
                    continue;
                }
                priority[x] = priorityValue;
                line[x] = color(256 + pixel);
            }
        }
    }

    void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        out.write(vram);
        for (short value : palette) {
            out.writeShort(value);
        }
        for (short value : oam) {
            out.writeShort(value);
        }
        out.writeInt(dispcnt);
        out.writeInt(dispstat);
        out.writeInt(vcount);
        for (int value : bgcnt) {
            out.writeInt(value);
        }
        for (int value : bgofs) {
            out.writeInt(value);
        }
        for (int value : bgAffine) {
            out.writeInt(value);
        }
        out.writeInt(bldcnt);
        out.writeInt(bldalpha);
        out.writeInt(bldy);
        out.writeInt(mosaic);
        out.writeBoolean(vblankFlag);
    }

    void loadState(java.io.DataInputStream in) throws java.io.IOException {
        in.readFully(vram);
        for (int i = 0; i < palette.length; i++) {
            palette[i] = in.readShort();
        }
        for (int i = 0; i < oam.length; i++) {
            oam[i] = in.readShort();
        }
        dispcnt = in.readInt();
        dispstat = in.readInt();
        vcount = in.readInt();
        for (int i = 0; i < bgcnt.length; i++) {
            bgcnt[i] = in.readInt();
        }
        for (int i = 0; i < bgofs.length; i++) {
            bgofs[i] = in.readInt();
        }
        for (int i = 0; i < bgAffine.length; i++) {
            bgAffine[i] = in.readInt();
        }
        bldcnt = in.readInt();
        bldalpha = in.readInt();
        bldy = in.readInt();
        mosaic = in.readInt();
        vblankFlag = in.readBoolean();
    }
}
