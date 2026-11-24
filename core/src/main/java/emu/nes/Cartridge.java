package emu.nes;

import java.io.IOException;

/** Parsed iNES / NES 2.0 image. */
public final class Cartridge {

    public final Mapper mapper;
    public final int mapperId;
    public final int prgBanks;
    public final int chrBanks;
    public final boolean chrRam;

    private Cartridge(Mapper mapper, int mapperId, int prgBanks, int chrBanks, boolean chrRam) {
        this.mapper = mapper;
        this.mapperId = mapperId;
        this.prgBanks = prgBanks;
        this.chrBanks = chrBanks;
        this.chrRam = chrRam;
    }

    public static boolean looksLikeNes(byte[] rom) {
        return rom != null && rom.length > 16 && (rom[0] & 0xFF) == 0x4E && rom[1] == 0x45
                && rom[2] == 0x53 && (rom[3] & 0xFF) == 0x1A;
    }

    public static Cartridge load(byte[] rom) throws IOException {
        if (!looksLikeNes(rom)) {
            throw new IOException("not an iNES image (missing NES\\x1A header)");
        }

        int prgBanks = rom[4] & 0xFF;
        int chrBanks = rom[5] & 0xFF;
        int flags6 = rom[6] & 0xFF;
        int flags7 = rom[7] & 0xFF;
        boolean nes2 = (flags7 & 0x0C) == 0x08;

        int mapperId = (flags6 >> 4) | (flags7 & 0xF0);
        if (nes2) {
            mapperId |= (rom[8] & 0x0F) << 8;
            int prgHigh = rom[9] & 0x0F;
            if (prgHigh != 0x0F) {
                prgBanks |= prgHigh << 8;
            }
            int chrHigh = (rom[9] & 0xF0) >> 4;
            if (chrHigh != 0x0F) {
                chrBanks |= chrHigh << 8;
            }
        }

        int offset = 16;
        if ((flags6 & 0x04) != 0) {
            offset += 512; // trainer
        }

        boolean fourScreen = (flags6 & 0x08) != 0;
        int mirroring = fourScreen
                ? Mapper.MIRROR_FOUR
                : ((flags6 & 0x01) != 0 ? Mapper.MIRROR_VERTICAL : Mapper.MIRROR_HORIZONTAL);

        int prgSize = prgBanks * 16384;
        if (prgSize <= 0) {
            throw new IOException("cartridge declares no PRG ROM");
        }
        if (offset + prgSize > rom.length) {
            throw new IOException("truncated ROM: header declares " + prgSize + " bytes of PRG");
        }
        byte[] prg = new byte[prgSize];
        System.arraycopy(rom, offset, prg, 0, prgSize);

        boolean chrRam = chrBanks == 0;
        int chrSize = chrRam ? 8192 : chrBanks * 8192;
        byte[] chr = new byte[chrSize];
        int chrAvailable = Math.max(0, Math.min(chrSize, rom.length - offset - prgSize));
        if (chrAvailable > 0) {
            System.arraycopy(rom, offset + prgSize, chr, 0, chrAvailable);
        }

        Mapper mapper = Mappers.create(mapperId, prg, chr, chrRam, mirroring);
        return new Cartridge(mapper, mapperId, prgBanks, chrBanks, chrRam);
    }

    public String summary() {
        return "mapper " + mapperId + " (" + mapper.name() + "), PRG " + prgBanks + "x16K, CHR "
                + (chrRam ? "RAM 8K" : chrBanks + "x8K");
    }
}
