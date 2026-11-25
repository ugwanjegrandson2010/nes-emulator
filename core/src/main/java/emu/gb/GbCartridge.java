package emu.gb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Game Boy cartridge: header parsing plus the memory bank controllers that real cartridges use
 * (ROM-only, MBC1, MBC2, MBC3 and MBC5). Bank registers and battery-less cartridge RAM live in
 * save states.
 */
public final class GbCartridge {

    /** Nintendo logo bytes at $0104, used to reject files that are not Game Boy ROMs. */
    private static final int[] LOGO = {
            0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
            0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
            0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E
    };

    public final byte[] rom;
    private byte[] ram;

    public int type;
    public String title = "";
    public boolean colorOnly;
    public boolean colorCompatible;
    public int romBanks;
    public int ramBanks;

    private int bankLow = 1;      // MBC1/3/5 lower bank bits
    private int bankHigh;         // MBC1 upper bits / MBC5 bit 8
    private int ramBank;          // MBC1 or MBC3/MBC5 RAM bank
    private boolean ramEnabled;
    private int mode;             // MBC1 mode select
    private int rtcRegister = -1;
    private final int[] rtc = new int[5];

    private GbCartridge(byte[] rom) {
        this.rom = rom;
    }

    public static GbCartridge load(byte[] raw) throws IOException {
        if (raw == null || raw.length < 0x150) {
            throw new IOException("not a Game Boy ROM (file is too small)");
        }
        int mismatches = 0;
        for (int i = 0; i < LOGO.length; i++) {
            if ((raw[0x104 + i] & 0xFF) != LOGO[i]) {
                mismatches++;
            }
        }
        if (mismatches > 8) {
            throw new IOException("not a Game Boy ROM (Nintendo logo is missing)");
        }
        GbCartridge cart = new GbCartridge(raw);
        cart.parseHeader();
        return cart;
    }

    private void parseHeader() {
        StringBuilder name = new StringBuilder();
        for (int i = 0x134; i < 0x144; i++) {
            int ch = rom[i] & 0xFF;
            if (ch == 0) {
                break;
            }
            if (ch >= 32 && ch < 127) {
                name.append((char) ch);
            }
        }
        title = name.toString().trim();

        int cgb = rom[0x143] & 0xFF;
        colorCompatible = cgb == 0x80;
        colorOnly = cgb == 0xC0;

        type = rom[0x147] & 0xFF;
        int ramCode = rom[0x149] & 0xFF;
        ramBanks = ramCode == 0 ? 0 : ramCode == 1 ? 1 : ramCode == 2 ? 1 : ramCode == 3 ? 4
                : ramCode == 4 ? 16 : 8;
        if (type == 0x05 || type == 0x06) {
            ramBanks = 1;  // MBC2 has its own 512 x 4 bit memory
        }
        if (ramBanks > 0) {
            ram = new byte[ramBanks * 0x2000];
        }
        romBanks = Math.max(2, rom.length / 0x4000);
    }

    public boolean hasBattery() {
        return type == 0x03 || type == 0x06 || type == 0x09 || type == 0x0F
                || type == 0x10 || type == 0x13 || type == 0x1B || type == 0x1E;
    }

    public String summary() {
        String mbc = type == 0 ? "ROM only" : type <= 0x03 ? "MBC1" : type <= 0x06 ? "MBC2"
                : type <= 0x09 ? "MBC3" : "MBC5";
        return title + " (" + mbc + ", " + romBanks + " x 16K ROM"
                + (ramBanks == 0 ? "" : ", " + ramBanks + " x 8K RAM") + ")";
    }

    // ------------------------------------------------------------------ bank selection

    private int selectedRomBank() {
        int bank;
        switch (type) {
            case 0x00:
            case 0x08:
            case 0x09:
                bank = 1;
                break;
            case 0x05:
            case 0x06:
                bank = bankLow & 0x0F;
                break;
            case 0x0F:
            case 0x10:
            case 0x11:
            case 0x12:
            case 0x13:
                bank = (bankLow & 0xFF) | ((bankHigh & 1) << 8);
                break;
            case 0x19:
            case 0x1A:
            case 0x1B:
            case 0x1C:
            case 0x1D:
            case 0x1E:
                bank = (bankLow & 0xFF) | ((bankHigh & 1) << 8);
                break;
            default:
                bank = (bankLow & 0x1F) | (mode == 1 ? 0 : ((bankHigh & 0x03) << 5));
                if ((bank & 0x1F) == 0) {
                    bank++;
                }
                break;
        }
        if (bank <= 0) {
            bank = 1;
        }
        return bank % Math.max(1, romBanks);
    }

    private int romByte(int bank, int offset) {
        int index = bank * 0x4000 + offset;
        return index >= 0 && index < rom.length ? rom[index] & 0xFF : 0xFF;
    }

    // ------------------------------------------------------------------ bus access

    public int read(int address) {
        if (address < 0x4000) {
            int bank = 0;
            if (type >= 0x01 && type <= 0x03) {
                bank = mode == 1 ? ((bankHigh & 0x03) << 5) : 0;
            }
            return romByte(bank % Math.max(1, romBanks), address);
        }
        if (address < 0x8000) {
            return romByte(selectedRomBank(), address - 0x4000);
        }
        if (address >= 0xA000 && address < 0xC000) {
            if (!ramEnabled || ram == null) {
                return 0xFF;
            }
            if (type >= 0x0F && rtcRegister >= 0x08) {
                return rtc[rtcRegister - 0x08] & 0xFF;
            }
            return ram[ramIndex(address)] & 0xFF;
        }
        return 0xFF;
    }

    public void write(int address, int value) {
        if (address < 0x8000) {
            writeControl(address, value);
            return;
        }
        if (address >= 0xA000 && address < 0xC000) {
            if (!ramEnabled || ram == null) {
                return;
            }
            if (type >= 0x0F && rtcRegister >= 0x08) {
                rtc[rtcRegister - 0x08] = value & 0xFF;
                return;
            }
            if (type == 0x05 || type == 0x06) {
                ram[address & 0x1FF] = (byte) (value & 0x0F);
                return;
            }
            ram[ramIndex(address)] = (byte) value;
        }
    }

    private int ramIndex(int address) {
        int bank;
        if (type >= 0x0F && type <= 0x13) {
            bank = ramBank & 0x0F;
        } else if (type >= 0x19) {
            bank = ramBank & 0x0F;
        } else {
            bank = mode == 1 ? (bankHigh & 0x03) : 0;
        }
        bank %= Math.max(1, ramBanks);
        return bank * 0x2000 + (address & 0x1FFF);
    }

    private void writeControl(int address, int value) {
        switch (type) {
            case 0x00:
            case 0x08:
            case 0x09:
                return;  // nothing to switch on a ROM-only cart
            case 0x05:
            case 0x06:  // MBC2
                if (address < 0x4000) {
                    if ((address & 0x0100) == 0) {
                        ramEnabled = (value & 0x0F) == 0x0A;
                    } else {
                        bankLow = value & 0x0F;
                        if (bankLow == 0) {
                            bankLow = 1;
                        }
                    }
                }
                return;
            case 0x0F:
            case 0x10:
            case 0x11:
            case 0x12:
            case 0x13:  // MBC3
                if (address < 0x2000) {
                    ramEnabled = (value & 0x0F) == 0x0A;
                } else if (address < 0x4000) {
                    bankLow = value & 0x7F;
                    if (bankLow == 0) {
                        bankLow = 1;
                    }
                } else if (address < 0x6000) {
                    ramBank = value & 0x0F;
                    rtcRegister = ramBank >= 0x08 && ramBank <= 0x0C ? ramBank : -1;
                } else {
                    // latch clock data (0 then 1); the RTC itself is not emulated
                    if (value == 0x01) {
                        rtc[0] = 0;
                    }
                }
                return;
            case 0x19:
            case 0x1A:
            case 0x1B:
            case 0x1C:
            case 0x1D:
            case 0x1E:  // MBC5
                if (address < 0x2000) {
                    ramEnabled = (value & 0x0F) == 0x0A;
                } else if (address < 0x3000) {
                    bankLow = (bankLow & 0x100) | (value & 0xFF);
                } else if (address < 0x4000) {
                    bankLow = (bankLow & 0xFF) | ((value & 1) << 8);
                } else if (address < 0x6000) {
                    ramBank = value & 0x0F;
                }
                return;
            default:  // MBC1 and friends
                if (address < 0x2000) {
                    ramEnabled = (value & 0x0F) == 0x0A;
                } else if (address < 0x4000) {
                    bankLow = value & 0x1F;
                    if (bankLow == 0) {
                        bankLow = 1;
                    }
                } else if (address < 0x6000) {
                    bankHigh = value & 0x03;
                } else {
                    mode = value & 0x01;
                }
        }
    }

    // ------------------------------------------------------------------ save states

    void saveState(DataOutputStream out) throws IOException {
        out.writeInt(type);
        out.writeInt(bankLow);
        out.writeInt(bankHigh);
        out.writeInt(ramBank);
        out.writeBoolean(ramEnabled);
        out.writeInt(mode);
        out.writeInt(rtcRegister);
        for (int value : rtc) {
            out.writeInt(value);
        }
        out.writeInt(ram == null ? -1 : ram.length);
        if (ram != null) {
            out.write(ram);
        }
    }

    void loadState(DataInputStream in) throws IOException {
        type = in.readInt();
        bankLow = in.readInt();
        bankHigh = in.readInt();
        ramBank = in.readInt();
        ramEnabled = in.readBoolean();
        mode = in.readInt();
        rtcRegister = in.readInt();
        for (int i = 0; i < rtc.length; i++) {
            rtc[i] = in.readInt();
        }
        int length = in.readInt();
        if (length > 0) {
            if (ram == null || ram.length != length) {
                ram = new byte[length];
            }
            in.readFully(ram);
        }
    }

    public byte[] ramCopy() {
        return ram == null ? new byte[0] : ram.clone();
    }
}
