package emu.nes;

/**
 * 2C02 PPU: 256x240 output, background + sprite rendering, loopy scrolling, NMI,
 * sprite-0 hit and sprite overflow.
 *
 * <p>Rendering is performed once per scanline (dot 250) using the loopy v/t/x registers,
 * with the standard horizontal copy at dot 257 and vertical increment at dot 256. This is
 * the widely used "scanline renderer" model: accurate enough for real games (scrolling,
 * split screens with mid-frame $2006 writes) and cheap enough for 60 fps on a phone.
 */
public final class Ppu {

    public static final int WIDTH = 256;
    public static final int HEIGHT = 240;

    private static final int[] NTSC_PALETTE = {
            0x666666, 0x002A88, 0x1412A7, 0x3B00A4, 0x5C007E, 0x6E0040, 0x6C0600, 0x561D00,
            0x333500, 0x0B4800, 0x005200, 0x004F08, 0x00404D, 0x000000, 0x000000, 0x000000,
            0xADADAD, 0x155FD9, 0x4240FF, 0x7527FE, 0xA01ACC, 0xB71E7B, 0xB53120, 0x994E00,
            0x6B6D00, 0x388700, 0x0C9300, 0x008F32, 0x007C8D, 0x000000, 0x000000, 0x000000,
            0xFFFEFF, 0x64B0FF, 0x9290FF, 0xC676FF, 0xF36AFF, 0xFE6ECC, 0xFE8170, 0xEA9E22,
            0xBCBE00, 0x88D800, 0x5CE430, 0x45E082, 0x48CDDE, 0x4F4F4F, 0x000000, 0x000000,
            0xFFFEFF, 0xC0DFFF, 0xD3D2FF, 0xE8C8FF, 0xFBC2FF, 0xFEC4EA, 0xFECCC5, 0xF7D8A5,
            0xE4E594, 0xCFEF96, 0xBDF4AB, 0xB3F3CC, 0xB5EBF2, 0xB8B8B8, 0x000000, 0x000000
    };

    private final Mapper mapper;
    private final byte[] vram = new byte[0x1000];
    private final byte[] paletteRam = new byte[0x20];
    private final byte[] oam = new byte[0x100];
    public final int[] framebuffer = new int[WIDTH * HEIGHT];

    // registers
    private int ctrl, mask, status, oamAddr, v, t, fineX, w;
    private int readBuffer;

    private int line = 261;
    private int dot = 1;
    private boolean oddFrame;
    private boolean nmiPending;
    public boolean frameReady;

    // per-scanline scratch
    private final int[] bgPatternLo = new int[33];
    private final int[] bgPatternHi = new int[33];
    private final int[] bgAttr = new int[33];
    private final int[] bgPalette = new int[WIDTH];
    private final boolean[] bgOpaque = new boolean[WIDTH];
    private final int[] spriteX = new int[8];
    private final int[] spriteAttr = new int[8];
    private final int[] spriteLo = new int[8];
    private final int[] spriteHi = new int[8];
    private final boolean[] spriteIsZero = new boolean[8];
    private int spriteCount;

    public Ppu(Mapper mapper) {
        this.mapper = mapper;
    }

    /** ARGB value the PPU would output for a 6-bit palette entry (used by tests and tooling). */
    public static int rgbFor(int paletteEntry) {
        return 0xFF000000 | NTSC_PALETTE[paletteEntry & 0x3F];
    }

    public void reset() {
        ctrl = mask = status = oamAddr = v = t = fineX = w = readBuffer = 0;
        line = 261;
        dot = 1;
        oddFrame = false;
        nmiPending = false;
        frameReady = false;
        java.util.Arrays.fill(vram, (byte) 0);
        java.util.Arrays.fill(paletteRam, (byte) 0);
        java.util.Arrays.fill(oam, (byte) 0);
        java.util.Arrays.fill(framebuffer, 0xFF000000);
    }

    public boolean consumeNmi() {
        boolean n = nmiPending;
        nmiPending = false;
        return n;
    }

    // ---------------------------------------------------------------- bus side

    public int readRegister(int reg) {
        switch (reg & 7) {
            case 2: {
                int value = (status & 0xE0) | (status & 0x1F);
                status &= ~0x80;
                w = 0;
                return value;
            }
            case 4:
                return oam[oamAddr & 0xFF] & 0xFF;
            case 7: {
                int address = v & 0x3FFF;
                int value;
                if (address >= 0x3F00) {
                    value = readPalette(address);
                    // buffered reads still refill from the mirrored nametable address
                    readBuffer = ppuRead(address - 0x1000);
                } else {
                    value = readBuffer;
                    readBuffer = ppuRead(address);
                }
                v = (v + ((ctrl & 0x04) != 0 ? 32 : 1)) & 0x7FFF;
                return value;
            }
            default:
                return 0;
        }
    }

    public void writeRegister(int reg, int value) {
        value &= 0xFF;
        switch (reg & 7) {
            case 0:
                ctrl = value;
                t = (t & 0xF3FF) | ((value & 0x03) << 10);
                if ((status & 0x80) != 0 && (value & 0x80) != 0) {
                    nmiPending = true;
                }
                break;
            case 1:
                mask = value;
                break;
            case 3:
                oamAddr = value;
                break;
            case 4:
                oam[oamAddr & 0xFF] = (byte) value;
                oamAddr = (oamAddr + 1) & 0xFF;
                break;
            case 5:
                if (w == 0) {
                    t = (t & 0xFFE0) | (value >> 3);
                    fineX = value & 0x07;
                    w = 1;
                } else {
                    t = (t & 0x8FFF) | ((value & 0x07) << 12);
                    t = (t & 0xFC1F) | ((value & 0xF8) << 2);
                    w = 0;
                }
                break;
            case 6:
                if (w == 0) {
                    t = (t & 0x00FF) | ((value & 0x3F) << 8);
                    w = 1;
                } else {
                    t = (t & 0xFF00) | value;
                    v = t;
                    w = 0;
                }
                break;
            case 7:
                ppuWrite(v & 0x3FFF, value);
                v = (v + ((ctrl & 0x04) != 0 ? 32 : 1)) & 0x7FFF;
                break;
            default:
                break;
        }
    }

    public void writeOamDma(int value) {
        oam[oamAddr & 0xFF] = (byte) value;
        oamAddr = (oamAddr + 1) & 0xFF;
    }

    public byte[] oamData() {
        return oam;
    }

    public boolean renderingEnabled() {
        return (mask & 0x18) != 0;
    }

    // ---------------------------------------------------------------- memory

    public int ppuRead(int address) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            return mapper.chrRead(address);
        }
        if (address < 0x3F00) {
            return vram[mirrorAddress(address & 0x0FFF)] & 0xFF;
        }
        return readPalette(address);
    }

    public void ppuWrite(int address, int value) {
        address &= 0x3FFF;
        if (address < 0x2000) {
            mapper.chrWrite(address, value);
        } else if (address < 0x3F00) {
            vram[mirrorAddress(address & 0x0FFF)] = (byte) value;
        } else {
            int p = address & 0x1F;
            if ((p & 0x13) == 0x10) {
                p &= 0x0F; // $3F10/$14/$18/$1C mirror the background entries
            }
            paletteRam[p] = (byte) (value & 0x3F);
        }
    }

    private int readPalette(int address) {
        int p = address & 0x1F;
        if ((p & 0x13) == 0x10) {
            p &= 0x0F;
        }
        return paletteRam[p] & 0x3F;
    }

    private int mirrorAddress(int address) {
        int mode = mapper.mirroring();
        switch (mode) {
            case Mapper.MIRROR_HORIZONTAL:
                return (address & 0x3FF) | ((address & 0x800) >> 1);
            case Mapper.MIRROR_VERTICAL:
                return address & 0x7FF;
            case Mapper.MIRROR_SINGLE_LOW:
                return address & 0x3FF;
            case Mapper.MIRROR_SINGLE_HIGH:
                return (address & 0x3FF) | 0x400;
            default:
                return address & 0xFFF; // four screen
        }
    }

    // ---------------------------------------------------------------- timing

    public void step() {
        // Odd frame skip: the pre-render scanline is one dot short on odd frames
        if (line == 261 && dot == 340 && oddFrame && renderingEnabled()) {
            line = 0;
            dot = 1;
            return;
        }

        if (line < 240) {
            if (dot == 250) {
                renderScanline(line);
            }
            if (dot == 256) {
                incrementY();
            }
            if (dot == 257) {
                copyHorizontal();
            }
            if (dot == 260 && renderingEnabled()) {
                mapper.scanline(line);
            }
        } else if (line == 261) {
            if (dot == 1) {
                status &= ~0xE0; // vblank, sprite 0 and overflow clear at pre-render
            }
            if (dot == 256) {
                incrementY();
            }
            if (dot == 257) {
                copyHorizontal();
            }
            if (dot == 304) {
                copyVertical();
            }
        } else if (line == 241) {
            if (dot == 1) {
                status |= 0x80;
                if ((ctrl & 0x80) != 0) {
                    nmiPending = true;
                }
                frameReady = true;
            }
        }

        dot++;
        if (dot > 341) {
            dot = 1;
            line++;
            if (line > 261) {
                line = 0;
                oddFrame = !oddFrame;
            }
        }
    }

    private void incrementY() {
        if (!renderingEnabled()) {
            return;
        }
        if ((v & 0x7000) != 0x7000) {
            v += 0x1000;
        } else {
            v &= 0x8FFF;
            int coarseY = (v >> 5) & 0x1F;
            if (coarseY == 29) {
                coarseY = 0;
                v ^= 0x0800;
            } else if (coarseY == 31) {
                coarseY = 0;
            } else {
                coarseY++;
            }
            v = (v & 0xFC1F) | (coarseY << 5);
        }
    }

    private void copyHorizontal() {
        if (renderingEnabled()) {
            v = (v & 0xFBE0) | (t & 0x041F);
        }
    }

    private void copyVertical() {
        if (renderingEnabled()) {
            v = (v & 0x841F) | (t & 0x7BE0);
        }
    }

    // ---------------------------------------------------------------- rendering

    private void renderScanline(int y) {
        int baseIndex = y * WIDTH;
        boolean bgVisible = (mask & 0x08) != 0;
        boolean spVisible = (mask & 0x10) != 0;

        if (!bgVisible && !spVisible) {
            int color = shadedColor(0);
            for (int x = 0; x < WIDTH; x++) {
                framebuffer[baseIndex + x] = color;
            }
            return;
        }

        renderBackground(bgVisible);
        if (spVisible) {
            evaluateSprites(y);
        } else {
            spriteCount = 0;
        }

        for (int x = 0; x < WIDTH; x++) {
            boolean bgOn = bgVisible && bgOpaque[x] && (x >= 8 || (mask & 0x02) != 0);
            int colorIndex = bgOn ? bgPalette[x] : 0;

            if (spriteCount > 0 && (x >= 8 || (mask & 0x04) != 0)) {
                for (int s = 0; s < spriteCount; s++) {
                    int bit = x - spriteX[s];
                    if (bit < 0 || bit > 7) {
                        continue;
                    }
                    int bitIndex = ((spriteAttr[s] & 0x40) != 0) ? bit : (7 - bit);
                    int lo = (spriteLo[s] >> bitIndex) & 1;
                    int hi = (spriteHi[s] >> bitIndex) & 1;
                    int spriteColor = lo | (hi << 1);
                    if (spriteColor == 0) {
                        continue;
                    }
                    if (spriteIsZero[s] && bgOn && x != 255) {
                        status |= 0x40; // sprite 0 hit
                    }
                    boolean behind = (spriteAttr[s] & 0x20) != 0;
                    if (!behind || !bgOn) {
                        colorIndex = 0x10 + ((spriteAttr[s] & 0x03) << 2) + spriteColor;
                    }
                    break;
                }
            }

            framebuffer[baseIndex + x] = shadedColor(colorIndex);
        }
    }

    private int shadedColor(int colorIndex) {
        int value = readPalette(0x3F00 + (colorIndex & 0x1F));
        if ((mask & 0x01) != 0) {
            value &= 0x30; // greyscale
        }
        int rgb = NTSC_PALETTE[value & 0x3F];
        return 0xFF000000 | rgb;
    }

    private void renderBackground(boolean bgVisible) {
        if (!bgVisible) {
            java.util.Arrays.fill(bgOpaque, false);
            java.util.Arrays.fill(bgPalette, 0);
            return;
        }
        int fineY = (v >> 12) & 7;
        int coarseY = (v >> 5) & 0x1F;
        int coarseX = v & 0x1F;
        int nametable = (v >> 10) & 0x03;
        int patternTable = (ctrl & 0x10) != 0 ? 0x1000 : 0x0000;

        for (int i = 0; i < 33; i++) {
            int cx = (coarseX + i) & 0x1F;
            int nt = nametable;
            if (coarseX + i > 31) {
                nt ^= 0x01; // horizontal nametable wrap
            }
            int ntBase = 0x2000 + (nt << 10);
            int tile = ppuRead(ntBase + (coarseY << 5) + cx);
            int attrByte = ppuRead(ntBase + 0x3C0 + ((coarseY >> 2) << 3) + (cx >> 2));
            int shift = ((coarseY & 0x02) << 1) | (cx & 0x02);
            bgAttr[i] = (attrByte >> shift) & 0x03;
            int patternAddress = patternTable + (tile << 4) + fineY;
            bgPatternLo[i] = ppuRead(patternAddress);
            bgPatternHi[i] = ppuRead(patternAddress + 8);
        }

        for (int x = 0; x < WIDTH; x++) {
            int sample = x + fineX;
            int tileIndex = sample >> 3;
            int bit = 7 - (sample & 7);
            int lo = (bgPatternLo[tileIndex] >> bit) & 1;
            int hi = (bgPatternHi[tileIndex] >> bit) & 1;
            int color = lo | (hi << 1);
            bgOpaque[x] = color != 0;
            bgPalette[x] = color == 0 ? 0 : ((bgAttr[tileIndex] << 2) + color);
        }
    }

    private void evaluateSprites(int y) {
        int height = (ctrl & 0x20) != 0 ? 16 : 8;
        spriteCount = 0;
        boolean overflow = false;

        for (int i = 0; i < 64; i++) {
            int base = i << 2;
            int top = (oam[base] & 0xFF) + 1;
            if (y < top || y >= top + height) {
                continue;
            }
            if (spriteCount >= 8) {
                overflow = true;
                break;
            }
            int attr = oam[base + 2] & 0xFF;
            int row = y - top;
            if ((attr & 0x80) != 0) {
                row = height - 1 - row;
            }
            int tile = oam[base + 1] & 0xFF;
            int patternAddress;
            if (height == 16) {
                int table = (tile & 0x01) != 0 ? 0x1000 : 0x0000;
                int index = tile & 0xFE;
                if (row >= 8) {
                    index |= 0x01;
                    row -= 8;
                }
                patternAddress = table + (index << 4) + row;
            } else {
                int table = (ctrl & 0x08) != 0 ? 0x1000 : 0x0000;
                patternAddress = table + (tile << 4) + row;
            }
            spriteX[spriteCount] = oam[base + 3] & 0xFF;
            spriteAttr[spriteCount] = attr;
            spriteLo[spriteCount] = ppuRead(patternAddress);
            spriteHi[spriteCount] = ppuRead(patternAddress + 8);
            spriteIsZero[spriteCount] = i == 0;
            spriteCount++;
        }

        if (overflow) {
            status |= 0x20;
        }
    }

    // ---------------------------------------------------------------- state

    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        out.write(vram);
        out.write(paletteRam);
        out.write(oam);
        out.writeInt(ctrl);
        out.writeInt(mask);
        out.writeInt(status);
        out.writeInt(oamAddr);
        out.writeInt(v);
        out.writeInt(t);
        out.writeInt(fineX);
        out.writeInt(w);
        out.writeInt(readBuffer);
        out.writeInt(line);
        out.writeInt(dot);
        out.writeBoolean(oddFrame);
        out.writeBoolean(nmiPending);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        in.readFully(vram);
        in.readFully(paletteRam);
        in.readFully(oam);
        ctrl = in.readInt();
        mask = in.readInt();
        status = in.readInt();
        oamAddr = in.readInt();
        v = in.readInt();
        t = in.readInt();
        fineX = in.readInt();
        w = in.readInt();
        readBuffer = in.readInt();
        line = in.readInt();
        dot = in.readInt();
        oddFrame = in.readBoolean();
        nmiPending = in.readBoolean();
    }
}
