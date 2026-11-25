package emu.nds;

/**
 * The memory map and register file seen by one of the two CPUs. Most of the hardware lives in
 * {@link NdsMachine}; this class is the translation layer plus the ARM9's TCM/CP15 state.
 */
public final class NdsBus implements NdsCpu.Bus {

    private final NdsMachine machine;
    public final boolean arm9;

    private byte[] itcm = new byte[0x8000];
    private byte[] dtcm = new byte[0x4000];
    private int itcmBase;
    private int dtcmBase = 0x027C0000;
    private int cp15Control = 0x00012078;
    private int processId;

    /**
     * The BIOS IRQ trampoline. Real hardware has a firmware routine at the vector which saves the
     * caller's registers, calls the user handler installed at 0x03FFFFFC and returns with
     * SUBS PC, LR, #4. Homebrew (libnds in particular) relies on that dispatch.
     */
    private final byte[] bios = new byte[0x1000];
    private int irqHandlerAddress;

    public NdsBus(NdsMachine machine, boolean arm9) {
        this.machine = machine;
        this.arm9 = arm9;
        installIrqStub();
    }

    private void installIrqStub() {
        writeBiosWord(0x18, 0xE92D500F);  // STMFD sp!, {r0-r3, r12, lr}
        writeBiosWord(0x1C, 0xE59FC008);  // LDR r12, [pc, #8]   -> the handler slot at 0x2C
        writeBiosWord(0x20, 0xE12FFF3C);  // BLX r12
        writeBiosWord(0x24, 0xE8BD500F);  // LDMFD sp!, {r0-r3, r12, lr}
        writeBiosWord(0x28, 0xE25EF004);  // SUBS PC, LR, #4
    }

    private void writeBiosWord(int offset, int value) {
        bios[offset] = (byte) value;
        bios[offset + 1] = (byte) (value >> 8);
        bios[offset + 2] = (byte) (value >> 16);
        bios[offset + 3] = (byte) (value >> 24);
    }

    private int readBios(int address) {
        int offset = address & 0xFFF;
        if ((offset & ~3) == 0x2C && irqHandlerAddress != 0) {
            return irqHandlerAddress;
        }
        return (bios[offset & 0xFFF] & 0xFF) | ((bios[(offset + 1) & 0xFFF] & 0xFF) << 8)
                | ((bios[(offset + 2) & 0xFFF] & 0xFF) << 16)
                | ((bios[(offset + 3) & 0xFFF] & 0xFF) << 24);
    }

    // ------------------------------------------------------------------ memory

    @Override
    public int read8(int address) {
        if (arm9) {
            if (inItcm(address)) {
                return itcm[address - itcmBase] & 0xFF;
            }
            if (inDtcm(address)) {
                return dtcm[address - dtcmBase] & 0xFF;
            }
        }
        if (address >= 0xFFFF0000 && address < 0xFFFF1000) {
            return readBios(address) >> ((address & 3) * 8) & 0xFF;
        }
        if (address >= 0x00000000 && address < 0x00001000) {
            return readBios(address) >> ((address & 3) * 8) & 0xFF;
        }
        if (address >= 0x03FFFF00 && address < 0x04000000) {
            return readHandlerRegister(address) >> ((address & 3) * 8) & 0xFF;
        }
        if (address >= 0x02000000 && address < 0x03000000) {
            return machine.mainRam[address & 0x3FFFFF] & 0xFF;
        }
        if (address >= 0x03000000 && address < 0x03800000) {
            return machine.sharedWram[address & 0x7FFF] & 0xFF;
        }
        if (address >= 0x03800000 && address < 0x03810000) {
            return machine.arm7Wram[address & 0xFFFF] & 0xFF;
        }
        if (address >= 0x04000000 && address < 0x04001000) {
            return machine.readIo(arm9, address, 1) & 0xFF;
        }
        if (address >= 0x04001000 && address < 0x04002000) {
            return machine.readIo(arm9, address, 1) & 0xFF;
        }
        if (address >= 0x04100000 && address < 0x04100010) {
            return machine.readIpcReceive(arm9, address) & 0xFF;
        }
        if (address >= 0x05000000 && address < 0x05000800) {
            return machine.ppu.palette[address & 0x7FF] & 0xFF;
        }
        if (address >= 0x06000000 && address < 0x07000000) {
            return machine.ppu.readVram(address);
        }
        if (address >= 0x07000000 && address < 0x07000800) {
            return machine.oam(arm9)[address & 0x7FF] & 0xFF;
        }
        if (address >= 0x08000000 && address < 0x0A000000) {
            return machine.cart == null ? 0xFF : machine.cart.read8(address);
        }
        return 0;
    }

    @Override
    public int read16(int address) {
        if (arm9) {
            if (inItcm(address)) {
                return read16Of(itcm, address - itcmBase);
            }
            if (inDtcm(address)) {
                return read16Of(dtcm, address - dtcmBase);
            }
        }
        if (address >= 0x00000000 && address < 0x00001000) {
            return readBios(address) >> ((address & 2) * 8) & 0xFFFF;
        }
        if (address >= 0x03FFFF00 && address < 0x04000000) {
            return readHandlerRegister(address) >> ((address & 2) * 8) & 0xFFFF;
        }
        if (address >= 0x04000000 && address < 0x04002000) {
            return machine.readIo(arm9, address, 2) & 0xFFFF;
        }
        if (address >= 0x02000000 && address < 0x03000000) {
            int offset = address & 0x3FFFFF;
            byte[] ram = machine.mainRam;
            return (ram[offset] & 0xFF) | ((ram[offset + 1] & 0xFF) << 8);
        }
        return (read8(address) & 0xFF) | ((read8(address + 1) & 0xFF) << 8);
    }

    @Override
    public int read32(int address) {
        if (arm9) {
            if (inItcm(address)) {
                return read32Of(itcm, address - itcmBase);
            }
            if (inDtcm(address)) {
                return read32Of(dtcm, address - dtcmBase);
            }
        }
        if (address >= 0x00000000 && address < 0x00001000) {
            return readBios(address);
        }
        if (address >= 0xFFFF0000 && address < 0xFFFF1000) {
            return readBios(address);
        }
        if (address >= 0x03FFFF00 && address < 0x04000000) {
            return readHandlerRegister(address);
        }
        if (address >= 0x04000000 && address < 0x04002000) {
            return machine.readIo(arm9, address, 4);
        }
        if (address >= 0x04100000 && address < 0x04100010) {
            return machine.readIpcReceive(arm9, address);
        }
        if (address >= 0x02000000 && address < 0x03000000) {
            int offset = address & 0x3FFFFF;
            byte[] ram = machine.mainRam;
            return (ram[offset] & 0xFF) | ((ram[offset + 1] & 0xFF) << 8)
                    | ((ram[offset + 2] & 0xFF) << 16) | ((ram[offset + 3] & 0xFF) << 24);
        }
        return (read16(address) & 0xFFFF) | ((read16(address + 2) & 0xFFFF) << 16);
    }

    @Override
    public void write8(int address, int value) {
        if (arm9) {
            if (inItcm(address)) {
                itcm[address - itcmBase] = (byte) value;
                return;
            }
            if (inDtcm(address)) {
                dtcm[address - dtcmBase] = (byte) value;
                return;
            }
        }
        if (address >= 0x03FFFF00 && address < 0x04000000) {
            writeHandlerRegister(address, value);
            return;
        }
        if (address >= 0x02000000 && address < 0x03000000) {
            machine.mainRam[address & 0x3FFFFF] = (byte) value;
            return;
        }
        if (address >= 0x03000000 && address < 0x03800000) {
            machine.sharedWram[address & 0x7FFF] = (byte) value;
            return;
        }
        if (address >= 0x03800000 && address < 0x03810000) {
            machine.arm7Wram[address & 0xFFFF] = (byte) value;
            return;
        }
        if (address >= 0x04000000 && address < 0x04002000) {
            machine.writeIo(arm9, address, value & 0xFF, 1);
            return;
        }
        if (address >= 0x04100000 && address < 0x04100010) {
            machine.writeIpcSend(arm9, value & 0xFF);
            return;
        }
        if (address >= 0x05000000 && address < 0x05000800) {
            machine.ppu.palette[address & 0x7FF] = (byte) value;
            return;
        }
        if (address >= 0x06000000 && address < 0x07000000) {
            machine.ppu.writeVram(address, value);
            return;
        }
        if (address >= 0x07000000 && address < 0x07000800) {
            machine.oam(arm9)[address & 0x7FF] = (byte) value;
        }
    }

    @Override
    public void write16(int address, int value) {
        if (arm9) {
            if (inItcm(address)) {
                write16Of(itcm, address - itcmBase, value);
                return;
            }
            if (inDtcm(address)) {
                write16Of(dtcm, address - dtcmBase, value);
                return;
            }
        }
        if (address >= 0x03FFFF00 && address < 0x04000000) {
            writeHandlerRegister(address, value << ((address & 2) * 8));
            return;
        }
        if (address >= 0x04000000 && address < 0x04002000) {
            machine.writeIo(arm9, address, value & 0xFFFF, 2);
            return;
        }
        if (address >= 0x02000000 && address < 0x03000000) {
            int offset = address & 0x3FFFFF;
            byte[] ram = machine.mainRam;
            ram[offset] = (byte) value;
            ram[offset + 1] = (byte) (value >> 8);
            return;
        }
        write8(address, value);
        write8(address + 1, value >> 8);
    }

    @Override
    public void write32(int address, int value) {
        if (arm9) {
            if (inItcm(address)) {
                write32Of(itcm, address - itcmBase, value);
                return;
            }
            if (inDtcm(address)) {
                write32Of(dtcm, address - dtcmBase, value);
                return;
            }
        }
        if (address >= 0x03FFFF00 && address < 0x04000000) {
            writeHandlerRegister(address, value);
            return;
        }
        if (address >= 0x04000000 && address < 0x04002000) {
            machine.writeIo(arm9, address, value, 4);
            return;
        }
        if (address >= 0x04100000 && address < 0x04100010) {
            machine.writeIpcSend(arm9, value);
            return;
        }
        if (address >= 0x02000000 && address < 0x03000000) {
            int offset = address & 0x3FFFFF;
            byte[] ram = machine.mainRam;
            ram[offset] = (byte) value;
            ram[offset + 1] = (byte) (value >> 8);
            ram[offset + 2] = (byte) (value >> 16);
            ram[offset + 3] = (byte) (value >> 24);
            return;
        }
        write8(address, value);
        write8(address + 1, value >> 8);
        write8(address + 2, value >> 16);
        write8(address + 3, value >> 24);
    }

    private int readHandlerRegister(int address) {
        return (address & ~3) == 0x03FFFFFC ? irqHandlerAddress : 0;
    }

    private void writeHandlerRegister(int address, int value) {
        if ((address & ~3) == 0x03FFFFFC) {
            irqHandlerAddress = value;
        }
    }

    public int irqHandlerAddress() {
        return irqHandlerAddress;
    }

    private static int read16Of(byte[] memory, int offset) {
        if (offset < 0 || offset + 1 >= memory.length) {
            return 0;
        }
        return (memory[offset] & 0xFF) | ((memory[offset + 1] & 0xFF) << 8);
    }

    private static int read32Of(byte[] memory, int offset) {
        if (offset < 0 || offset + 3 >= memory.length) {
            return 0;
        }
        return (memory[offset] & 0xFF) | ((memory[offset + 1] & 0xFF) << 8)
                | ((memory[offset + 2] & 0xFF) << 16) | ((memory[offset + 3] & 0xFF) << 24);
    }

    private static void write16Of(byte[] memory, int offset, int value) {
        if (offset < 0 || offset + 1 >= memory.length) {
            return;
        }
        memory[offset] = (byte) value;
        memory[offset + 1] = (byte) (value >> 8);
    }

    private static void write32Of(byte[] memory, int offset, int value) {
        if (offset < 0 || offset + 3 >= memory.length) {
            return;
        }
        memory[offset] = (byte) value;
        memory[offset + 1] = (byte) (value >> 8);
        memory[offset + 2] = (byte) (value >> 16);
        memory[offset + 3] = (byte) (value >> 24);
    }

    private boolean inItcm(int address) {
        int size = itcm.length;
        return size > 0 && (address & 0xFFFFF000) == (itcmBase & 0xFFFFF000)
                && (address - itcmBase) >= 0 && (address - itcmBase) < size;
    }

    private boolean inDtcm(int address) {
        int size = dtcm.length;
        return size > 0 && address >= dtcmBase && address < dtcmBase + size;
    }

    // ------------------------------------------------------------------ interrupts / BIOS

    @Override
    public boolean irqAsserted() {
        return machine.irqAsserted(arm9);
    }

    @Override
    public void swi(NdsCpu cpu, int comment) {
        machine.biosCall(cpu, comment);
    }

    @Override
    public int cp15Read(int cn, int cm, int opcode2) {
        if (!arm9) {
            return 0;
        }
        switch (cn) {
            case 1:
                return cp15Control;
            case 9:
                return 0;
            case 13:
                return processId;
            default:
                return 0;
        }
    }

    @Override
    public void cp15Write(int cn, int cm, int opcode2, int value) {
        if (!arm9) {
            return;
        }
        switch (cn) {
            case 1:
                cp15Control = value;
                break;
            case 9: {
                int size = 0x200 << ((value >> 1) & 0x1F);
                int base = (value & 0xFFFFF000);
                if (cm == 0) {
                    resizeDtcm(size, base);
                } else {
                    resizeItcm(size, base);
                }
                break;
            }
            case 13:
                processId = value;
                break;
            default:
                break;
        }
    }

    private void resizeDtcm(int size, int base) {
        if (size <= 0 || size > 0x10000) {
            return;
        }
        byte[] fresh = new byte[size];
        System.arraycopy(dtcm, 0, fresh, 0, Math.min(dtcm.length, size));
        dtcm = fresh;
        dtcmBase = base;
    }

    private void resizeItcm(int size, int base) {
        if (size <= 0 || size > 0x8000) {
            return;
        }
        byte[] fresh = new byte[size];
        System.arraycopy(itcm, 0, fresh, 0, Math.min(itcm.length, size));
        itcm = fresh;
        itcmBase = base;
    }

    public int dtcmBase() {
        return dtcmBase;
    }

    public int itcmBase() {
        return itcmBase;
    }
}
