package emu.nes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** The mapper implementations this core ships with. */
public final class Mappers {

    private Mappers() {
    }

    /** Mapper 0: no banking at all. Covers a large share of homebrew. */
    public static final class Nrom extends Mapper {
        public Nrom(byte[] prg, byte[] chr, boolean chrRam, int mirroring) {
            super(prg, chr, chrRam, mirroring);
        }

        @Override
        public int cpuRead(int address) {
            if (address >= 0x8000) {
                return readPrg(address - 0x8000);
            }
            if (address >= 0x6000) {
                return prgRam[(address - 0x6000) & 0x1FFF];
            }
            return 0;
        }

        @Override
        public void cpuWrite(int address, int value) {
            if (address >= 0x6000 && address < 0x8000) {
                prgRam[(address - 0x6000) & 0x1FFF] = (byte) value;
            }
        }

        @Override
        public String name() {
            return "NROM";
        }
    }

    /** Mapper 1: Nintendo MMC1, used by many early and homebrew titles. */
    public static final class Mmc1 extends Mapper {
        public Mmc1(byte[] prg, byte[] chr, boolean chrRam, int mirroring) {
            super(prg, chr, chrRam, mirroring);
            state[2] = 0x0C;
        }

        @Override
        public int cpuRead(int address) {
            if (address >= 0x8000) {
                int mode = (state[2] >> 2) & 3;
                int reg = state[5] & 0x0F;
                int prgCount = Math.max(1, prg.length / 0x4000);
                int bank;
                if (address < 0xC000) {
                    if (mode <= 1) {
                        bank = reg & 0x0E;
                    } else if (mode == 2) {
                        bank = 0;
                    } else {
                        bank = reg;
                    }
                    return readPrg(bank * 0x4000 + (address - 0x8000));
                }
                if (mode <= 1) {
                    bank = (reg & 0x0E) + 1;
                } else {
                    bank = prgCount - 1;
                }
                return readPrg(bank * 0x4000 + (address - 0xC000));
            }
            if (address >= 0x6000) {
                return prgRam[(address - 0x6000) & 0x1FFF];
            }
            return 0;
        }

        @Override
        public void cpuWrite(int address, int value) {
            if (address < 0x8000) {
                if (address >= 0x6000) {
                    prgRam[(address - 0x6000) & 0x1FFF] = (byte) value;
                }
                return;
            }
            if ((value & 0x80) != 0) {
                state[0] = 0;
                state[1] = 0;
                state[2] |= 0x0C;
                mirroringMode = MIRROR_SINGLE_LOW;
                return;
            }
            state[0] = ((state[0] >> 1) | ((value & 1) << 4)) & 0x1F;
            state[1]++;
            if (state[1] != 5) {
                return;
            }
            int data = state[0] & 0x1F;
            switch ((address >> 13) & 3) {
                case 0:
                    state[2] = data;
                    switch (data & 3) {
                        case 0:
                            mirroringMode = MIRROR_SINGLE_LOW;
                            break;
                        case 1:
                            mirroringMode = MIRROR_SINGLE_HIGH;
                            break;
                        case 2:
                            mirroringMode = MIRROR_VERTICAL;
                            break;
                        default:
                            mirroringMode = MIRROR_HORIZONTAL;
                            break;
                    }
                    break;
                case 1:
                    state[3] = data;
                    break;
                case 2:
                    state[4] = data;
                    break;
                default:
                    state[5] = data;
                    break;
            }
            state[0] = 0;
            state[1] = 0;
        }

        @Override
        public int chrRead(int address) {
            int bank;
            if ((state[2] & 0x10) == 0) {
                bank = (state[3] & 0x1E) * 0x1000 + (address & 0x1FFF);
            } else if (address < 0x1000) {
                bank = state[3] * 0x1000 + address;
            } else {
                bank = state[4] * 0x1000 + (address & 0x0FFF);
            }
            return chr[bank % chr.length] & 0xFF;
        }

        @Override
        public void chrWrite(int address, int value) {
            if (!chrRam) {
                return;
            }
            int bank;
            if ((state[2] & 0x10) == 0) {
                bank = (state[3] & 0x1E) * 0x1000 + (address & 0x1FFF);
            } else if (address < 0x1000) {
                bank = state[3] * 0x1000 + address;
            } else {
                bank = state[4] * 0x1000 + (address & 0x0FFF);
            }
            chr[bank % chr.length] = (byte) value;
        }

        @Override
        protected void applyState() {
            switch (state[2] & 3) {
                case 0:
                    mirroringMode = MIRROR_SINGLE_LOW;
                    break;
                case 1:
                    mirroringMode = MIRROR_SINGLE_HIGH;
                    break;
                case 2:
                    mirroringMode = MIRROR_VERTICAL;
                    break;
                default:
                    mirroringMode = MIRROR_HORIZONTAL;
                    break;
            }
        }

        @Override
        public String name() {
            return "MMC1";
        }
    }

    /** Mapper 2: UxROM (UNROM/UOROM). */
    public static final class UxRom extends Mapper {
        public UxRom(byte[] prg, byte[] chr, boolean chrRam, int mirroring) {
            super(prg, chr, chrRam, mirroring);
        }

        @Override
        public int cpuRead(int address) {
            if (address >= 0x8000) {
                int banks = Math.max(1, prg.length / 0x4000);
                int bank = address < 0xC000 ? (state[0] & 0x0F) : banks - 1;
                return readPrg((bank % banks) * 0x4000 + (address & 0x3FFF));
            }
            if (address >= 0x6000) {
                return prgRam[(address - 0x6000) & 0x1FFF];
            }
            return 0;
        }

        @Override
        public void cpuWrite(int address, int value) {
            if (address >= 0x8000) {
                state[0] = value & 0x0F;
            } else if (address >= 0x6000) {
                prgRam[(address - 0x6000) & 0x1FFF] = (byte) value;
            }
        }

        @Override
        public String name() {
            return "UxROM";
        }
    }

    /** Mapper 3: CNROM, CHR ROM banking. */
    public static final class CNrom extends Mapper {
        public CNrom(byte[] prg, byte[] chr, boolean chrRam, int mirroring) {
            super(prg, chr, chrRam, mirroring);
        }

        @Override
        public int cpuRead(int address) {
            if (address >= 0x8000) {
                return readPrg(address - 0x8000);
            }
            if (address >= 0x6000) {
                return prgRam[(address - 0x6000) & 0x1FFF];
            }
            return 0;
        }

        @Override
        public void cpuWrite(int address, int value) {
            if (address >= 0x8000) {
                state[0] = value & 0x03;
            } else if (address >= 0x6000) {
                prgRam[(address - 0x6000) & 0x1FFF] = (byte) value;
            }
        }

        @Override
        public int chrRead(int address) {
            int banks = Math.max(1, chr.length / 0x2000);
            int bank = state[0] % banks;
            return chr[(bank * 0x2000 + (address & 0x1FFF)) % chr.length] & 0xFF;
        }

        @Override
        public String name() {
            return "CNROM";
        }
    }

    /** Mapper 4: Nintendo MMC3 with scanline IRQ (supervisor/memory-manager chip). */
    public static final class Mmc3 extends Mapper {
        private final int[] bankRegs = new int[8];
        private int bankSelect;
        private int irqLatch;
        private int irqCounter;
        private boolean irqEnabled;
        private boolean irqReload;
        private boolean irqPending;

        public Mmc3(byte[] prg, byte[] chr, boolean chrRam, int mirroring) {
            super(prg, chr, chrRam, mirroring);
        }

        @Override
        public int cpuRead(int address) {
            if (address >= 0x8000) {
                int banks = Math.max(1, prg.length / 0x2000);
                int slot = (address - 0x8000) >> 13;
                int bank;
                boolean swapped = (bankSelect & 0x40) != 0;
                int r6 = bankRegs[6] % banks;
                int r7 = bankRegs[7] % banks;
                if (!swapped) {
                    bank = slot == 0 ? r6 : slot == 1 ? r7 : slot == 2 ? banks - 2 : banks - 1;
                } else {
                    bank = slot == 0 ? banks - 2 : slot == 1 ? r7 : slot == 2 ? r6 : banks - 1;
                }
                return readPrg(bank * 0x2000 + (address & 0x1FFF));
            }
            if (address >= 0x6000) {
                return prgRam[(address - 0x6000) & 0x1FFF];
            }
            return 0;
        }

        @Override
        public void cpuWrite(int address, int value) {
            if (address < 0x8000) {
                if (address >= 0x6000) {
                    prgRam[(address - 0x6000) & 0x1FFF] = (byte) value;
                }
                return;
            }
            switch (address & 0xE001) {
                case 0x8000:
                    bankSelect = value;
                    break;
                case 0x8001:
                    bankRegs[bankSelect & 0x07] = value;
                    break;
                case 0xA000:
                    mirroringMode = (value & 1) != 0 ? MIRROR_HORIZONTAL : MIRROR_VERTICAL;
                    break;
                case 0xC000:
                    irqLatch = value;
                    break;
                case 0xC001:
                    irqCounter = 0;
                    irqReload = true;
                    break;
                case 0xE000:
                    irqEnabled = false;
                    irqPending = false;
                    break;
                case 0xE001:
                    irqEnabled = true;
                    break;
                default:
                    break;
            }
        }

        @Override
        public int chrRead(int address) {
            int sized = Math.max(1, chr.length / 0x400);
            int index = (address >> 10) & 7;
            int bank;
            if ((bankSelect & 0x80) == 0) {
                if (index < 2) {
                    bank = (bankRegs[0] & 0xFE) + index;
                } else if (index < 4) {
                    bank = (bankRegs[1] & 0xFE) + (index - 2);
                } else {
                    bank = bankRegs[index - 2];
                }
            } else {
                if (index < 4) {
                    bank = bankRegs[index + 2];
                } else if (index < 6) {
                    bank = (bankRegs[0] & 0xFE) + (index - 4);
                } else {
                    bank = (bankRegs[1] & 0xFE) + (index - 6);
                }
            }
            return chr[((bank % sized) * 0x400 + (address & 0x3FF)) % chr.length] & 0xFF;
        }

        @Override
        public void chrWrite(int address, int value) {
            if (!chrRam) {
                return;
            }
            int sized = Math.max(1, chr.length / 0x400);
            int index = (address >> 10) & 7;
            int bank;
            if ((bankSelect & 0x80) == 0) {
                if (index < 2) {
                    bank = (bankRegs[0] & 0xFE) + index;
                } else if (index < 4) {
                    bank = (bankRegs[1] & 0xFE) + (index - 2);
                } else {
                    bank = bankRegs[index - 2];
                }
            } else {
                if (index < 4) {
                    bank = bankRegs[index + 2];
                } else if (index < 6) {
                    bank = (bankRegs[0] & 0xFE) + (index - 4);
                } else {
                    bank = (bankRegs[1] & 0xFE) + (index - 6);
                }
            }
            chr[((bank % sized) * 0x400 + (address & 0x3FF)) % chr.length] = (byte) value;
        }

        @Override
        public void scanline(int line) {
            if (irqCounter == 0 || irqReload) {
                irqCounter = irqLatch;
                irqReload = false;
            } else {
                irqCounter--;
                if (irqCounter == 0 && irqEnabled) {
                    irqPending = true;
                }
            }
        }

        @Override
        public boolean irq() {
            return irqPending;
        }

        @Override
        public void reset() {
            bankSelect = 0;
            irqEnabled = false;
            irqPending = false;
            irqReload = false;
            irqCounter = 0;
        }

        @Override
        public void saveState(DataOutputStream out) throws IOException {
            super.saveState(out);
            for (int v : bankRegs) {
                out.writeInt(v);
            }
            out.writeInt(bankSelect);
            out.writeInt(irqLatch);
            out.writeInt(irqCounter);
            out.writeBoolean(irqEnabled);
            out.writeBoolean(irqReload);
            out.writeBoolean(irqPending);
        }

        @Override
        public void loadState(DataInputStream in) throws IOException {
            super.loadState(in);
            for (int i = 0; i < bankRegs.length; i++) {
                bankRegs[i] = in.readInt();
            }
            bankSelect = in.readInt();
            irqLatch = in.readInt();
            irqCounter = in.readInt();
            irqEnabled = in.readBoolean();
            irqReload = in.readBoolean();
            irqPending = in.readBoolean();
        }

        @Override
        public String name() {
            return "MMC3";
        }
    }

    /** Mapper 7: AxROM, 32 KB switchable PRG and one-screen mirroring. */
    public static final class AxRom extends Mapper {
        public AxRom(byte[] prg, byte[] chr, boolean chrRam, int mirroring) {
            super(prg, chr, chrRam, mirroring);
        }

        @Override
        public int cpuRead(int address) {
            if (address >= 0x8000) {
                return readPrg((state[0] % Math.max(1, prg.length / 0x8000)) * 0x8000 + (address - 0x8000));
            }
            return 0;
        }

        @Override
        public void cpuWrite(int address, int value) {
            if (address >= 0x8000) {
                state[0] = value & 0x07;
                mirroringMode = (value & 0x10) != 0 ? MIRROR_SINGLE_HIGH : MIRROR_SINGLE_LOW;
            }
        }

        @Override
        protected void applyState() {
            mirroringMode = (state[0] & 0x10) != 0 ? MIRROR_SINGLE_HIGH : MIRROR_SINGLE_LOW;
        }

        @Override
        public String name() {
            return "AxROM";
        }
    }

    public static Mapper create(int id, byte[] prg, byte[] chr, boolean chrRam, int mirroring) {
        switch (id) {
            case 1:
                return new Mmc1(prg, chr, chrRam, mirroring);
            case 2:
                return new UxRom(prg, chr, chrRam, mirroring);
            case 3:
                return new CNrom(prg, chr, chrRam, mirroring);
            case 4:
                return new Mmc3(prg, chr, chrRam, mirroring);
            case 7:
                return new AxRom(prg, chr, chrRam, mirroring);
            case 0:
            default:
                return new Nrom(prg, chr, chrRam, mirroring);
        }
    }
}
