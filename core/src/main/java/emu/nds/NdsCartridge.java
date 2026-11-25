package emu.nds;

import java.io.IOException;

/**
 * An NDS ROM image: header parsing and the two program images. Retail cartridges store the first
 * part of the ARM9 binary encrypted with the KEY1 stream cipher; that decryption needs Nintendo's
 * Blowfish tables, so this core loads plain (homebrew/unencrypted) images and detects the rest.
 */
public final class NdsCartridge {

    public static final int HEADER_SIZE = 0x200;

    public final byte[] rom;
    public final String title;
    public final String gameCode;
    public final String makerCode;

    public final int arm9RomOffset;
    public final int arm9Entry;
    public final int arm9RamAddress;
    public final int arm9Size;
    public final int arm7RomOffset;
    public final int arm7Entry;
    public final int arm7RamAddress;
    public final int arm7Size;
    public final int bannerOffset;

    private NdsCartridge(byte[] rom) {
        this.rom = rom;
        this.title = ascii(rom, 0x00, 12).trim();
        this.gameCode = ascii(rom, 0x0C, 4);
        this.makerCode = ascii(rom, 0x10, 2);
        this.arm9RomOffset = read32(rom, 0x20);
        this.arm9Entry = read32(rom, 0x24);
        this.arm9RamAddress = read32(rom, 0x28);
        this.arm9Size = read32(rom, 0x2C);
        this.arm7RomOffset = read32(rom, 0x30);
        this.arm7Entry = read32(rom, 0x34);
        this.arm7RamAddress = read32(rom, 0x38);
        this.arm7Size = read32(rom, 0x3C);
        this.bannerOffset = read32(rom, 0x68);
    }

    public static NdsCartridge load(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException("not a DS ROM: image is shorter than the 512 byte header");
        }
        NdsCartridge cartridge = new NdsCartridge(data);
        if (cartridge.validate() != null) {
            throw new IOException(cartridge.validate());
        }
        return cartridge;
    }

    /** Returns a human readable reason the image cannot be loaded, or null when it looks usable. */
    public String validate() {
        if (arm9Size <= 0 || arm9RomOffset <= 0) {
            return "not a DS ROM: the ARM9 program image is missing from the header";
        }
        if ((long) arm9RomOffset + (long) arm9Size > rom.length) {
            return "corrupt DS ROM: the ARM9 image runs past the end of the file";
        }
        if (arm7Size > 0 && (long) arm7RomOffset + (long) arm7Size > rom.length) {
            return "corrupt DS ROM: the ARM7 image runs past the end of the file";
        }
        if (likelyEncrypted()) {
            return "encrypted retail DS ROM: this core runs unencrypted (homebrew) images only";
        }
        return null;
    }

    /**
     * Retail images start the ARM9 binary with an encrypted secure-area block, which decodes as
     * high-entropy garbage. Unencrypted images start with a branch or a NOP-like prologue.
     */
    public boolean likelyEncrypted() {
        int start = arm9RomOffset;
        if (start + 0x800 > rom.length) {
            return false;
        }
        int first = read32(rom, start);
        int topByte = (first >>> 24) & 0xFF;
        if (topByte == 0xEA || topByte == 0xEB || topByte == 0xE9 || topByte == 0xE8) {
            return false;
        }
        if ((first & 0x0E000000) == 0x0A000000) {
            return false;
        }
        // No branch: encrypted blocks are near-random bytes, real code has a much flatter spread.
        int[] histogram = new int[256];
        int samples = Math.min(512, rom.length - start);
        for (int i = 0; i < samples; i++) {
            histogram[rom[start + i] & 0xFF]++;
        }
        int distinct = 0;
        for (int count : histogram) {
            if (count != 0) {
                distinct++;
            }
        }
        return distinct > 210;
    }

    public byte[] arm9Image() {
        return slice(arm9RomOffset, arm9Size);
    }

    public byte[] arm7Image() {
        return arm7Size <= 0 ? new byte[0] : slice(arm7RomOffset, arm7Size);
    }

    private byte[] slice(int offset, int size) {
        if (offset < 0 || offset >= rom.length) {
            return new byte[0];
        }
        int length = Math.min(size, rom.length - offset);
        byte[] out = new byte[length];
        System.arraycopy(rom, offset, out, 0, length);
        return out;
    }

    public int read8(int address) {
        int offset = address - 0x08000000;
        if (offset < 0 || offset >= rom.length) {
            // cartridge ROM mirrors every 32 MiB
            if (rom.length == 0) {
                return 0xFF;
            }
            offset %= rom.length;
        }
        return rom[offset] & 0xFF;
    }

    public String summary() {
        return title + " [" + gameCode + "] by " + makerCode + "  ·  ARM9 entry "
                + String.format("0x%08X", arm9Entry) + "  ·  " + (rom.length / 1024) + " KiB";
    }

    private static int read32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static String ascii(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int value = data[offset + i] & 0xFF;
            if (value == 0) {
                continue;
            }
            sb.append(value >= 0x20 && value < 0x7F ? (char) value : '.');
        }
        return sb.toString();
    }
}
