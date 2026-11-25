package emu.gb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * DMG picture unit: 160x144 output, background/window tilemap, 8x8 and 8x16 sprites with the
 * DMG priority rules, LCD mode timing (456 dots per line, 154 lines) and STAT interrupts.
 */
public final class GbPpu {

    public static final int WIDTH = 160;
    public static final int HEIGHT = 144;
    public static final int DOTS_PER_LINE = 456;
    public static final int LINES = 154;

    /** DMG green palette, lightest first. */
    private static final int[] SHADES = {0xFF9BBC0F, 0xFF8BAC0F, 0xFF306230, 0xFF0F380F};

    public final int[] framebuffer = new int[WIDTH * HEIGHT];
    public final byte[] vram = new byte[0x2000];
    public final byte[] oam = new byte[0xA0];

    public int lcdc = 0x91;
    public int stat = 0x85;
    public int scy, scx, ly, lyc, bgp = 0xFC, obp0 = 0xFF, obp1 = 0xFF, wy, wx;
    int lineDot;
    private int mode;
    private int windowLine;
    boolean frameReady;

    /** Set by the machine: raises the LCD STAT interrupt line. */
    private final GbMachine machine;

    private final int[] bgIndex = new int[WIDTH];
    private final int[] spriteColor = new int[WIDTH];
    private final boolean[] spriteCovered = new boolean[WIDTH];

    public GbPpu(GbMachine machine) {
        this.machine = machine;
    }

    public static int rgbFor(int shade) {
        return SHADES[shade & 3];
    }

    void reset() {
        lcdc = 0x91;
        stat = 0x85;
        scy = scx = ly = lyc = wy = wx = 0;
        bgp = 0xFC;
        obp0 = obp1 = 0xFF;
        lineDot = 0;
        mode = 2;
        windowLine = 0;
        frameReady = false;
        java.util.Arrays.fill(vram, (byte) 0);
        java.util.Arrays.fill(oam, (byte) 0);
        for (int i = 0; i < framebuffer.length; i++) {
            framebuffer[i] = SHADES[0];
        }
    }

    boolean isLcdOn() {
        return (lcdc & 0x80) != 0;
    }

    public int statRegister() {
        int value = stat & 0x78;
        value |= mode & 0x03;
        value |= (ly == lyc ? 0x04 : 0);
        return value | 0x80;
    }

    void writeStat(int value) {
        stat = (stat & 0x07) | (value & 0x78);
        checkCoincidence();
    }

    void writeLyc(int value) {
        lyc = value & 0xFF;
        checkCoincidence();
    }

    private void checkCoincidence() {
        boolean equal = ly == lyc;
        boolean wasSet = (stat & 0x04) != 0;
        if (equal) {
            stat |= 0x04;
            if (!wasSet && (stat & 0x40) != 0) {
                machine.requestInterrupt(GbMachine.INT_STAT);
            }
        } else {
            stat &= ~0x04;
        }
    }

    /** Advances one dot (one clock of the 4.19 MHz dot clock). */
    void step() {
        if (!isLcdOn()) {
            ly = 0;
            lineDot = 0;
            mode = 0;
            windowLine = 0;
            return;
        }
        lineDot++;
        if (lineDot >= DOTS_PER_LINE) {
            lineDot = 0;
            ly++;
            if (ly == HEIGHT) {
                frameReady = true;
                machine.requestInterrupt(GbMachine.INT_VBLANK);
            } else if (ly >= LINES) {
                ly = 0;
                windowLine = 0;
            }
            checkCoincidence();
        }

        int newMode;
        if (ly < HEIGHT) {
            newMode = lineDot < 80 ? 2 : lineDot < 252 ? 3 : 0;
        } else {
            newMode = 1;
        }
        if (lineDot == 252 && ly < HEIGHT) {
            renderLine(ly);
        }
        setMode(newMode);
    }

    private void setMode(int next) {
        if (next == mode) {
            return;
        }
        mode = next;
        int mask = next == 0 ? 0x08 : next == 1 ? 0x10 : 0x20;
        if ((stat & mask) != 0) {
            machine.requestInterrupt(GbMachine.INT_STAT);
        }
    }

    // ------------------------------------------------------------------ rendering

    private void renderLine(int y) {
        int rowBase = y * WIDTH;
        if ((lcdc & 0x01) == 0) {
            // background disabled: colour 0 from BGP, but objects still show over it
            java.util.Arrays.fill(bgIndex, 0);
            int blank = rgbFor(bgp & 3);
            for (int x = 0; x < WIDTH; x++) {
                framebuffer[rowBase + x] = blank;
            }
            if ((lcdc & 0x02) != 0) {
                drawSprites(y, rowBase);
            }
            return;
        }

        int mapBase = (lcdc & 0x08) != 0 ? 0x1C00 : 0x1800;
        int dataBase = (lcdc & 0x10) != 0 ? 0x0000 : 0x1000;
        int windowMapBase = (lcdc & 0x40) != 0 ? 0x1C00 : 0x1800;
        boolean windowEnabled = (lcdc & 0x20) != 0;
        int windowX = wx - 7;
        boolean windowVisible = windowEnabled && y >= wy && windowX < WIDTH;
        int windowPixels = 0;

        for (int x = 0; x < WIDTH; x++) {
            int tile;
            int pixelX;
            int pixelY;
            if (windowVisible && x >= windowX) {
                int wxPixel = x - windowX;
                int wyPixel = windowLine;
                tile = vram[windowMapBase + ((wyPixel >> 3) << 5) + (wxPixel >> 3)] & 0xFF;
                pixelX = wxPixel & 7;
                pixelY = wyPixel & 7;
                windowPixels++;
            } else {
                int sx = (scx + x) & 0xFF;
                int sy = (scy + y) & 0xFF;
                tile = vram[mapBase + ((sy >> 3) << 5) + (sx >> 3)] & 0xFF;
                pixelX = sx & 7;
                pixelY = sy & 7;
            }

            // $8000 mode uses unsigned tile numbers; $8800 mode treats them as signed bytes
            int tileAddress = (dataBase == 0x1000
                    ? (0x1000 + ((byte) tile) * 16)
                    : tile * 16) & 0x1FFF;
            int lo = vram[tileAddress + pixelY * 2] & 0xFF;
            int hi = vram[(tileAddress + pixelY * 2 + 1) & 0x1FFF] & 0xFF;
            int bit = 7 - pixelX;
            int color = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);

            bgIndex[x] = color;
            framebuffer[rowBase + x] = rgbFor((bgp >> (color * 2)) & 3);
        }

        if (windowVisible && windowPixels > 0) {
            windowLine++;
        }

        if ((lcdc & 0x02) != 0) {
            drawSprites(y, rowBase);
        }
    }

    private void drawSprites(int y, int rowBase) {
        int height = (lcdc & 0x04) != 0 ? 16 : 8;
        int[] chosen = new int[10];
        int count = 0;
        for (int i = 0; i < 40 && count < 10; i++) {
            int spriteY = (oam[i * 4] & 0xFF) - 16;
            if (y >= spriteY && y < spriteY + height) {
                chosen[count++] = i;
            }
        }

        // stable sort by x: the leftmost sprite wins, ties go to the lower OAM index
        for (int i = 1; i < count; i++) {
            int sprite = chosen[i];
            int x = oam[sprite * 4 + 1] & 0xFF;
            int j = i - 1;
            while (j >= 0 && (oam[chosen[j] * 4 + 1] & 0xFF) > x) {
                chosen[j + 1] = chosen[j];
                j--;
            }
            chosen[j + 1] = sprite;
        }

        java.util.Arrays.fill(spriteColor, 0);
        java.util.Arrays.fill(spriteCovered, false);
        for (int k = 0; k < count; k++) {
            int sprite = chosen[k];
            int base = sprite * 4;
            int spriteX = (oam[base + 1] & 0xFF) - 8;
            int spriteY = (oam[base] & 0xFF) - 16;
            int tile = oam[base + 2] & 0xFF;
            int attributes = oam[base + 3] & 0xFF;
            boolean flipY = (attributes & 0x40) != 0;
            boolean flipX = (attributes & 0x20) != 0;
            int row = y - spriteY;
            if (flipY) {
                row = height - 1 - row;
            }
            if (height == 16) {
                tile &= 0xFE;
                if (row >= 8) {
                    tile++;
                    row -= 8;
                }
            }
            int tileAddress = tile * 16 + row * 2;
            int lo = vram[tileAddress & 0x1FFF] & 0xFF;
            int hi = vram[(tileAddress + 1) & 0x1FFF] & 0xFF;
            for (int px = 0; px < 8; px++) {
                int screenX = spriteX + px;
                if (screenX < 0 || screenX >= WIDTH) {
                    continue;
                }
                if (spriteCovered[screenX]) {
                    continue;  // a higher priority sprite already covers this pixel
                }
                int bit = flipX ? px : 7 - px;
                int color = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);
                if (color == 0) {
                    continue;
                }
                spriteCovered[screenX] = true;
                boolean behind = (attributes & 0x80) != 0;
                if (behind && bgIndex[screenX] != 0) {
                    continue;  // the background wins this pixel
                }
                int palette = (attributes & 0x10) != 0 ? obp1 : obp0;
                spriteColor[screenX] = rgbFor((palette >> (color * 2)) & 3);
            }
        }

        for (int x = 0; x < WIDTH; x++) {
            if (spriteColor[x] != 0) {  // 0 = transparent; every real shade has alpha 0xFF
                framebuffer[rowBase + x] = spriteColor[x];
            }
        }
    }

    // ------------------------------------------------------------------ state

    void saveState(DataOutputStream out) throws IOException {
        out.write(lcdc);
        out.write(stat);
        out.write(scy);
        out.write(scx);
        out.write(ly);
        out.write(lyc);
        out.write(bgp);
        out.write(obp0);
        out.write(obp1);
        out.write(wy);
        out.write(wx);
        out.writeInt(lineDot);
        out.writeInt(mode);
        out.writeInt(windowLine);
        out.write(vram);
        out.write(oam);
    }

    void loadState(DataInputStream in) throws IOException {
        lcdc = in.readUnsignedByte();
        stat = in.readUnsignedByte();
        scy = in.readUnsignedByte();
        scx = in.readUnsignedByte();
        ly = in.readUnsignedByte();
        lyc = in.readUnsignedByte();
        bgp = in.readUnsignedByte();
        obp0 = in.readUnsignedByte();
        obp1 = in.readUnsignedByte();
        wy = in.readUnsignedByte();
        wx = in.readUnsignedByte();
        lineDot = in.readInt();
        mode = in.readInt();
        windowLine = in.readInt();
        in.readFully(vram);
        in.readFully(oam);
    }
}
