package emu.test;

import emu.nds.NdsCore;
import emu.nds.NdsCpu;
import emu.nds.NdsMachine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verification of the Nintendo DS core.
 *
 * <p>There is no public conformance suite for the DS the way there is for the NES and Game Boy, so
 * this runs hand-assembled ARM/Thumb programs through the real core and checks their results, plus
 * complete DS ROM images (header and all, built here) that drive the cartridge loader, the memory
 * map and both picture engines. Every instruction word is written explicitly so these tests check
 * the decoder rather than themselves.
 */
public final class NdsTests {

    public interface Reporter {
        void check(String name, boolean ok, String detail);
    }

    private NdsTests() {
    }

    public static void run(Reporter reporter) throws Exception {
        armDataProcessing(reporter);
        armFlagsAndConditions(reporter);
        armMultiply(reporter);
        armLoadStore(reporter);
        armBlockTransfer(reporter);
        thumbInstructions(reporter);
        armSoftwareInterrupt(reporter);
        armInterruptDispatch(reporter);
        dsCartridgeHeader(reporter);
        dsMemoryMap(reporter);
        dsTextBackground(reporter);
        dsSecondScreen(reporter);
        dsSprites(reporter);
        dsBitmapMode(reporter);
        dsIpcFifo(reporter);
        dsSaveState(reporter);
    }

    private static void check(Reporter reporter, String name, boolean ok, String detail) {
        reporter.check(name, ok, detail);
    }

    // ------------------------------------------------------------------ test harness

    /** A flat 64 KiB memory for CPU-level tests. */
    static final class TestBus implements NdsCpu.Bus {
        final byte[] ram = new byte[0x10000];
        boolean irq;
        int swiComment = -1;
        int cp15Control;

        @Override
        public int read32(int address) {
            int offset = address & 0xFFFF;
            return (ram[offset] & 0xFF) | ((ram[offset + 1] & 0xFF) << 8)
                    | ((ram[offset + 2] & 0xFF) << 16) | ((ram[offset + 3] & 0xFF) << 24);
        }

        @Override
        public int read16(int address) {
            int offset = address & 0xFFFF;
            return (ram[offset] & 0xFF) | ((ram[offset + 1] & 0xFF) << 8);
        }

        @Override
        public int read8(int address) {
            return ram[address & 0xFFFF] & 0xFF;
        }

        @Override
        public void write32(int address, int value) {
            int offset = address & 0xFFFF;
            ram[offset] = (byte) value;
            ram[offset + 1] = (byte) (value >> 8);
            ram[offset + 2] = (byte) (value >> 16);
            ram[offset + 3] = (byte) (value >> 24);
        }

        @Override
        public void write16(int address, int value) {
            int offset = address & 0xFFFF;
            ram[offset] = (byte) value;
            ram[offset + 1] = (byte) (value >> 8);
        }

        @Override
        public void write8(int address, int value) {
            ram[address & 0xFFFF] = (byte) value;
        }

        @Override
        public boolean irqAsserted() {
            return irq;
        }

        @Override
        public void swi(NdsCpu cpu, int comment) {
            swiComment = comment;
        }

        @Override
        public int cp15Read(int cn, int cm, int opcode2) {
            return cn == 1 ? cp15Control : 0;
        }

        @Override
        public void cp15Write(int cn, int cm, int opcode2, int value) {
            if (cn == 1) {
                cp15Control = value;
            }
        }

        void emit(int address, int... words) {
            for (int i = 0; i < words.length; i++) {
                write32(address + i * 4, words[i]);
            }
        }

        void emitHalf(int address, int... halves) {
            for (int i = 0; i < halves.length; i++) {
                write16(address + i * 2, halves[i]);
            }
        }
    }

    private static NdsCpu cpu(TestBus bus, int pc) {
        NdsCpu cpu = new NdsCpu(bus, true);
        cpu.reset(pc);
        return cpu;
    }

    private static void run(NdsCpu cpu, int instructions) {
        for (int i = 0; i < instructions; i++) {
            cpu.step();
        }
    }

    // ------------------------------------------------------------------ ARM encoders

    private static boolean encodable(int value) {
        for (int rotate = 0; rotate < 16; rotate++) {
            if ((Integer.rotateLeft(value, rotate * 2) & ~0xFF) == 0) {
                return true;
            }
        }
        return false;
    }

    private static int encodeImm(int value) {
        for (int rotate = 0; rotate < 16; rotate++) {
            int candidate = Integer.rotateLeft(value, rotate * 2);
            if ((candidate & ~0xFF) == 0) {
                return (rotate << 8) | candidate;
            }
        }
        throw new IllegalArgumentException("constant not encodable: 0x" + Integer.toHexString(value));
    }

    private static int dpImm(int opcode, int rd, int rn, int value) {
        return 0xE2000000 | (opcode << 21) | (rn << 16) | (rd << 12) | encodeImm(value);
    }

    private static int dpImmS(int opcode, int rd, int rn, int value) {
        return dpImm(opcode, rd, rn, value) | (1 << 20);
    }

    private static int mov(int rd, int value) {
        return dpImm(13, rd, 0, value);
    }

    private static int mvn(int rd, int value) {
        return dpImm(15, rd, 0, value);
    }

    private static int add(int rd, int rn, int value) {
        return dpImm(4, rd, rn, value);
    }

    private static int sub(int rd, int rn, int value) {
        return dpImm(2, rd, rn, value);
    }

    private static int rsb(int rd, int rn, int value) {
        return dpImm(3, rd, rn, value);
    }

    private static int and(int rd, int rn, int value) {
        return dpImm(0, rd, rn, value);
    }

    private static int orrImm(int rd, int rn, int value) {
        return dpImm(12, rd, rn, value);
    }

    private static int eor(int rd, int rn, int value) {
        return dpImm(1, rd, rn, value);
    }

    private static int cmp(int rn, int value) {
        return dpImmS(10, 0, rn, value);
    }

    /** Data processing with a register operand and an optional immediate shift. */
    private static int dpReg(int opcode, int rd, int rn, int rm, int shiftType, int amount) {
        return 0xE0000000 | (opcode << 21) | (rn << 16) | (rd << 12) | (amount << 7)
                | (shiftType << 5) | rm;
    }

    private static int addReg(int rd, int rn, int rm) {
        return dpReg(4, rd, rn, rm, 0, 0);
    }

    private static int lsl(int rd, int rm, int amount) {
        return dpReg(13, rd, 0, rm, 0, amount);
    }

    private static int lsr(int rd, int rm, int amount) {
        return dpReg(13, rd, 0, rm, 1, amount);
    }

    private static int asr(int rd, int rm, int amount) {
        return dpReg(13, rd, 0, rm, 2, amount);
    }

    private static int ror(int rd, int rm, int amount) {
        return dpReg(13, rd, 0, rm, 3, amount);
    }

    private static int mul(int rd, int rm, int rs) {
        return 0xE0000090 | (rd << 16) | (rs << 8) | rm;
    }

    private static int mla(int rd, int rm, int rs, int rn) {
        return 0xE0200090 | (rd << 16) | (rn << 12) | (rs << 8) | rm;
    }

    private static int umull(int rdLo, int rdHi, int rm, int rs) {
        return 0xE0800090 | (rdHi << 16) | (rdLo << 12) | (rs << 8) | rm;
    }

    private static int mem(int load, int byteOp, int rd, int rn, int offset) {
        return 0xE4000000 | (1 << 24) | (1 << 23) | (load != 0 ? 1 << 20 : 0)
                | (byteOp != 0 ? 1 << 22 : 0) | (rn << 16) | (rd << 12) | (offset & 0xFFF);
    }

    private static int ldr(int rd, int rn, int offset) {
        return mem(1, 0, rd, rn, offset);
    }

    private static int str(int rd, int rn, int offset) {
        return mem(0, 0, rd, rn, offset);
    }

    private static int ldrb(int rd, int rn, int offset) {
        return mem(1, 1, rd, rn, offset);
    }

    private static int strb(int rd, int rn, int offset) {
        return mem(0, 1, rd, rn, offset);
    }

    private static int ldrWriteback(int rd, int rn, int offset) {
        return ldr(rd, rn, offset) | (1 << 21);
    }

    private static int halfMem(int load, int rd, int rn, int offset) {
        return 0xE1C000B0 | (load != 0 ? 1 << 20 : 0) | (rn << 16) | (rd << 12)
                | (offset & 0xF) | ((offset & 0xF0) << 4);
    }

    private static int ldrh(int rd, int rn, int offset) {
        return halfMem(1, rd, rn, offset);
    }

    private static int strh(int rd, int rn, int offset) {
        return halfMem(0, rd, rn, offset);
    }

    private static int ldmiaWriteback(int rn, int list) {
        return 0xE8900000 | (1 << 21) | (rn << 16) | list;
    }

    private static int stmiaWriteback(int rn, int list) {
        return 0xE8800000 | (1 << 21) | (rn << 16) | list;
    }

    private static int swiCall(int comment) {
        return 0xEF000000 | comment;
    }

    /** SUBS PC, LR, #4: the standard return from an exception handler. */
    private static final int SUBS_PC_LR_4 = 0xE25EF004;

    // ------------------------------------------------------------------ a tiny program builder

    /** Instruction words with named labels so branches need no hand-computed offsets. */
    static final class Asm {
        private final List<Integer> words = new ArrayList<Integer>();
        private final Map<String, Integer> labels = new HashMap<String, Integer>();
        private final List<String> fixupLabels = new ArrayList<String>();
        private final List<Integer> fixupIndex = new ArrayList<Integer>();
        private final List<Integer> fixupKind = new ArrayList<Integer>();
        private final List<Integer> fixupCond = new ArrayList<Integer>();

        Asm w(int... instructions) {
            for (int instruction : instructions) {
                words.add(instruction);
            }
            return this;
        }

        /** Loads any 32-bit constant into a register, using as many instructions as needed. */
        Asm load(int rd, int value) {
            if (encodable(value)) {
                return w(mov(rd, value));
            }
            int shift = 0;
            while (shift < 24 && ((value >>> (24 - shift)) & 0xFF) == 0) {
                shift += 8;
            }
            w(mov(rd, (value >>> (24 - shift)) & 0xFF));
            for (int step = shift + 8; step < 32; step += 8) {
                int byteValue = (value >>> (24 - step)) & 0xFF;
                w(lsl(rd, rd, 8));
                if (byteValue != 0) {
                    w(add(rd, rd, byteValue));
                }
            }
            return this;
        }

        Asm label(String name) {
            labels.put(name, words.size());
            return this;
        }

        Asm b(String label) {
            return branch(label, 0, 0);
        }

        Asm bl(String label) {
            return branch(label, 1, 0);
        }

        Asm bcond(int condition, String label) {
            return branch(label, 2, condition);
        }

        private Asm branch(String label, int kind, int condition) {
            fixupLabels.add(label);
            fixupIndex.add(words.size());
            fixupKind.add(kind);
            fixupCond.add(condition);
            words.add(0);
            return this;
        }

        int[] build(int baseAddress) {
            int[] code = new int[words.size()];
            for (int i = 0; i < code.length; i++) {
                code[i] = words.get(i);
            }
            for (int i = 0; i < fixupIndex.size(); i++) {
                Integer target = labels.get(fixupLabels.get(i));
                if (target == null) {
                    throw new IllegalStateException("undefined label " + fixupLabels.get(i));
                }
                int index = fixupIndex.get(i);
                int offset = (target - (index + 2)) * 4;
                int kind = fixupKind.get(i);
                if (kind == 0) {
                    code[index] = 0xEA000000 | (offset >> 2 & 0xFFFFFF);
                } else if (kind == 1) {
                    code[index] = 0xEB000000 | (offset >> 2 & 0xFFFFFF);
                } else {
                    code[index] = (fixupCond.get(i) << 28) | 0x0A000000 | (offset >> 2 & 0xFFFFFF);
                }
            }
            return code;
        }
    }

    // ------------------------------------------------------------------ CPU checks

    private static void armDataProcessing(Reporter reporter) {
        TestBus bus = new TestBus();
        bus.emit(0x100,
                mov(0, 0xFF),
                mov(1, 0x10),
                addReg(2, 0, 1),
                sub(3, 2, 0x1F),
                and(4, 0, 0x0F),
                orrImm(5, 1, 0x20),
                eor(6, 0, 0xFF),
                rsb(7, 1, 0x30),
                mvn(8, 0),
                mov(9, 0x00),
                lsl(9, 0, 4));
        NdsCpu cpu = cpu(bus, 0x100);
        run(cpu, 12);
        check(reporter, "DS ARM data processing: ADD/SUB", cpu.r[2] == 0x10F && cpu.r[3] == 0xF0,
                "add=" + Integer.toHexString(cpu.r[2]) + " sub=" + Integer.toHexString(cpu.r[3]));
        check(reporter, "DS ARM data processing: AND/ORR/EOR/RSB",
                cpu.r[4] == 0x0F && cpu.r[5] == 0x30 && cpu.r[6] == 0 && cpu.r[7] == 0x20,
                "and=" + cpu.r[4] + " orr=" + cpu.r[5] + " eor=" + cpu.r[6] + " rsb=" + cpu.r[7]);
        check(reporter, "DS ARM data processing: MVN and LSL", cpu.r[8] == -1 && cpu.r[9] == 0xFF0,
                "mvn=" + cpu.r[8] + " lsl=" + Integer.toHexString(cpu.r[9]));

        TestBus shiftBus = new TestBus();
        shiftBus.emit(0x100, mvn(0, 0), lsr(1, 0, 4), mvn(2, 0), asr(3, 2, 4), mov(4, 0x81), ror(5, 4, 1));
        NdsCpu shiftCpu = cpu(shiftBus, 0x100);
        run(shiftCpu, 6);
        check(reporter, "DS ARM shifts: LSR/ASR/ROR",
                shiftCpu.r[1] == 0x0FFFFFFF && shiftCpu.r[3] == 0xFFFFFFFF && shiftCpu.r[5] == 0x80000040,
                "lsr=" + Integer.toHexString(shiftCpu.r[1]) + " asr=" + Integer.toHexString(shiftCpu.r[3])
                        + " ror=" + Integer.toHexString(shiftCpu.r[5]));
    }

    private static void armFlagsAndConditions(Reporter reporter) {
        TestBus bus = new TestBus();
        Asm asm = new Asm();
        asm.w(mvn(0, 0));                    // 0xFFFFFFFF
        asm.w(dpImmS(4, 1, 0, 1));           // ADDS r1, r0, #1 -> 0 with carry and zero
        asm.w(mov(2, 0x11), cmp(2, 0x11));
        asm.bcond(0, "equal");               // BEQ taken
        asm.w(mov(3, 0x63));                 // skipped
        asm.b("done");
        asm.label("equal").w(mov(4, 0x2A));
        asm.label("done").w(mov(5, 0x55));
        bus.emit(0x100, asm.build(0x100));
        NdsCpu cpu = cpu(bus, 0x100);
        run(cpu, 7);
        boolean carry = (cpu.cpsr & NdsCpu.FLAG_C) != 0;
        boolean zero = (cpu.cpsr & NdsCpu.FLAG_Z) != 0;
        check(reporter, "DS ARM flags: ADDS sets carry and zero", cpu.r[1] == 0 && carry && zero,
                "r1=" + cpu.r[1] + " cpsr=" + Integer.toHexString(cpu.cpsr));
        check(reporter, "DS ARM conditional branch taken", cpu.r[3] == 0 && cpu.r[4] == 0x2A,
                "r3=" + cpu.r[3] + " r4=" + cpu.r[4]);

        TestBus overflow = new TestBus();
        overflow.emit(0x100, mvn(0, 0), lsr(0, 0, 1), dpImmS(4, 1, 0, 1));
        NdsCpu overflowCpu = cpu(overflow, 0x100);
        run(overflowCpu, 3);
        check(reporter, "DS ARM flags: signed overflow",
                overflowCpu.r[1] == 0x80000000
                        && (overflowCpu.cpsr & NdsCpu.FLAG_V) != 0
                        && (overflowCpu.cpsr & NdsCpu.FLAG_N) != 0
                        && (overflowCpu.cpsr & NdsCpu.FLAG_C) == 0,
                "r1=" + Integer.toHexString(overflowCpu.r[1])
                        + " cpsr=" + Integer.toHexString(overflowCpu.cpsr));
    }

    private static void armMultiply(Reporter reporter) {
        TestBus bus = new TestBus();
        bus.emit(0x100,
                mov(0, 7),
                mov(1, 6),
                mul(2, 0, 1),
                mla(3, 0, 1, 1),
                mov(4, 0x100),
                mov(5, 0x200),
                umull(6, 7, 4, 5));
        NdsCpu cpu = cpu(bus, 0x100);
        run(cpu, 7);
        check(reporter, "DS ARM multiply: MUL/MLA", cpu.r[2] == 42 && cpu.r[3] == 48,
                "mul=" + cpu.r[2] + " mla=" + cpu.r[3]);
        check(reporter, "DS ARM multiply: UMULL", cpu.r[6] == 0x20000 && cpu.r[7] == 0,
                "lo=" + Integer.toHexString(cpu.r[6]) + " hi=" + Integer.toHexString(cpu.r[7]));
    }

    private static void armLoadStore(Reporter reporter) {
        TestBus bus = new TestBus();
        Asm asm = new Asm();
        asm.load(0, 0x400);
        asm.w(mov(1, 0x12), lsl(1, 1, 8), add(1, 1, 0x34));   // 0x1234
        asm.w(str(1, 0, 0), ldr(2, 0, 0));
        asm.w(strb(1, 0, 0x10), ldrb(3, 0, 0x10));
        asm.w(strh(1, 0, 0x20), ldrh(4, 0, 0x20));
        asm.w(ldrWriteback(5, 0, 0x30));
        asm.w(mov(6, 0x55), str(6, 0, 0x40));
        bus.emit(0x100, asm.build(0x100));
        NdsCpu cpu = cpu(bus, 0x100);
        run(cpu, 13);
        check(reporter, "DS ARM load/store: word, byte and halfword",
                cpu.r[2] == 0x1234 && cpu.r[3] == 0x34 && cpu.r[4] == 0x1234,
                "word=" + Integer.toHexString(cpu.r[2]) + " byte=" + Integer.toHexString(cpu.r[3])
                        + " half=" + Integer.toHexString(cpu.r[4]));
        check(reporter, "DS ARM load/store: writeback addressing", cpu.r[0] == 0x430,
                "r0=" + Integer.toHexString(cpu.r[0]));
        check(reporter, "DS ARM load/store: store lands at the advanced base",
                bus.read32(0x470) == 0x55, "value=" + Integer.toHexString(bus.read32(0x470)));
    }

    private static void armBlockTransfer(Reporter reporter) {
        TestBus bus = new TestBus();
        bus.emit(0x100,
                mov(0, 0x800),
                mov(1, 1),
                mov(2, 2),
                mov(3, 3),
                stmiaWriteback(0, 0x000E),
                mov(4, 0),
                mov(5, 0),
                mov(6, 0),
                mov(0, 0x800),
                ldmiaWriteback(0, 0x0070));
        NdsCpu cpu = cpu(bus, 0x100);
        run(cpu, 10);
        check(reporter, "DS ARM block transfer: STMIA/LDMIA",
                cpu.r[4] == 1 && cpu.r[5] == 2 && cpu.r[6] == 3,
                "r4=" + cpu.r[4] + " r5=" + cpu.r[5] + " r6=" + cpu.r[6]);
        check(reporter, "DS ARM block transfer: writeback", cpu.r[0] == 0x80C,
                "r0=" + Integer.toHexString(cpu.r[0]));
    }

    private static void thumbInstructions(Reporter reporter) {
        TestBus bus = new TestBus();
        bus.emitHalf(0x300,
                0x2005,           // mov r0, #5
                0x2103,           // mov r1, #3
                0x1840,           // add r0, r1        -> 8
                0x3802,           // sub r0, #2        -> 6
                0x2806,           // cmp r0, #6
                0xD001,           // beq 0x310
                0x2700,           // mov r7, #0        (skipped)
                0x2700,           // mov r7, #0        (skipped)
                0x2701,           // mov r7, #1        <- target
                0xF000,           // bl prefix
                0xF803,           // bl suffix -> 0x31C
                0xE7FE,           // b .               <- return point
                0x46C0,           // nop
                0x46C0,           // nop
                0x2207,           // mov r2, #7        <- subroutine
                0x4770);          // bx lr
        NdsCpu cpu = cpu(bus, 0x300);
        cpu.writeCpsr((cpu.cpsr & ~NdsCpu.FLAG_MASK) | NdsCpu.MODE_SVC | NdsCpu.FLAG_T);
        run(cpu, 11);
        check(reporter, "DS Thumb: MOV/ADD/SUB/CMP", cpu.r[0] == 6 && cpu.r[1] == 3,
                "r0=" + cpu.r[0] + " r1=" + cpu.r[1]);
        check(reporter, "DS Thumb: conditional branch", cpu.r[7] == 1, "r7=" + cpu.r[7]);
        check(reporter, "DS Thumb: BL and BX LR", cpu.r[2] == 7 && cpu.thumb(),
                "r2=" + cpu.r[2] + " pc=" + Integer.toHexString(cpu.r[15]));
    }

    private static void armSoftwareInterrupt(Reporter reporter) {
        TestBus bus = new TestBus();
        bus.emit(0x100, mov(0, 9), swiCall(0x09), mov(1, 1));
        NdsCpu cpu = cpu(bus, 0x100);
        run(cpu, 3);
        check(reporter, "DS BIOS call dispatch", bus.swiComment == 0x09 && cpu.r[1] == 1,
                "comment=" + bus.swiComment + " pc=" + Integer.toHexString(cpu.r[15]));
    }

    private static void armInterruptDispatch(Reporter reporter) {
        TestBus bus = new TestBus();
        bus.emit(0x18, add(0, 0, 1), SUBS_PC_LR_4);   // IRQ vector
        bus.emit(0x100, mov(0, 0), 0xEAFFFFFE);
        NdsCpu cpu = cpu(bus, 0x100);
        cpu.writeCpsr(cpu.cpsr & ~NdsCpu.FLAG_I);
        bus.irq = true;
        int entry = cpu.r[15];
        cpu.step();
        boolean entered = cpu.r[15] == 0x18 && (cpu.cpsr & NdsCpu.FLAG_MASK) == NdsCpu.MODE_IRQ;
        run(cpu, 2);
        check(reporter, "DS ARM interrupt entry", entered,
                "pc=" + Integer.toHexString(cpu.r[15]) + " mode=" + (cpu.cpsr & 0x1F));
        check(reporter, "DS ARM interrupt return restores the interrupted point",
                (cpu.cpsr & NdsCpu.FLAG_MASK) == NdsCpu.MODE_SVC && cpu.r[15] == entry,
                "pc=" + Integer.toHexString(cpu.r[15]) + " mode=" + (cpu.cpsr & 0x1F));
        check(reporter, "DS ARM interrupt handler ran", cpu.r[0] == 1, "r0=" + cpu.r[0]);
    }

    // ------------------------------------------------------------------ machine checks

    /** Builds a complete DS ROM: header, ARM9 program at 0x02000000, optional ARM7 program. */
    private static byte[] buildRom(Asm arm9, Asm arm7) {
        int[] code9 = arm9.build(0x02000008);
        int[] code7 = arm7 == null ? new int[0] : arm7.build(0x02380000);
        int arm9Size = (code9.length + 2) * 4;
        int arm7Size = code7.length * 4;
        int total = 0x200 + arm9Size + arm7Size + 16;
        byte[] rom = new byte[total];
        byte[] title = "NDS TEST".getBytes();
        System.arraycopy(title, 0, rom, 0, title.length);
        System.arraycopy("TEST".getBytes(), 0, rom, 0x0C, 4);
        System.arraycopy("01".getBytes(), 0, rom, 0x10, 2);
        write32(rom, 0x20, 0x200);
        write32(rom, 0x24, 0x02000000);
        write32(rom, 0x28, 0x02000000);
        write32(rom, 0x2C, arm9Size);
        write32(rom, 0x30, 0x200 + arm9Size);
        write32(rom, 0x34, 0x02380000);
        write32(rom, 0x38, 0x02380000);
        write32(rom, 0x3C, arm7Size);
        // start with a branch so the image looks like the unencrypted ROM it is
        write32(rom, 0x200, 0xEA000000);
        write32(rom, 0x204, 0xE1A00000);
        for (int i = 0; i < code9.length; i++) {
            write32(rom, 0x208 + i * 4, code9[i]);
        }
        for (int i = 0; i < code7.length; i++) {
            write32(rom, 0x200 + arm9Size + i * 4, code7[i]);
        }
        return rom;
    }

    private static void write32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
        target[offset + 2] = (byte) (value >> 16);
        target[offset + 3] = (byte) (value >> 24);
    }

    private static void dsCartridgeHeader(Reporter reporter) throws Exception {
        byte[] rom = buildRom(new Asm().w(mov(0, 0)).w(0xEAFFFFFE), null);
        NdsCore core = new NdsCore();
        core.loadRom(rom);
        check(reporter, "DS cartridge: header parsed",
                core.cartridge() != null
                        && "NDS TEST".equals(core.cartridge().title)
                        && core.cartridge().arm9Entry == 0x02000000
                        && core.cartridge().arm9RamAddress == 0x02000000,
                core.cartridgeInfo());

        boolean rejected = false;
        try {
            byte[] garbage = new byte[0x1000];
            for (int i = 0; i < garbage.length; i++) {
                garbage[i] = (byte) (i * 73 + 17);
            }
            write32(garbage, 0x20, 0x200);
            write32(garbage, 0x24, 0x02000000);
            write32(garbage, 0x28, 0x02000000);
            write32(garbage, 0x2C, 0x400);
            new NdsCore().loadRom(garbage);
        } catch (java.io.IOException e) {
            rejected = true;
        }
        check(reporter, "DS cartridge: encrypted retail image refused", rejected, "no exception");
    }

    private static void dsMemoryMap(Reporter reporter) throws Exception {
        Asm program = new Asm();
        program.load(0, 0x02000000);
        program.load(1, 0x11223344);
        program.w(str(1, 0, 0));
        program.load(0, 0x027C0000);          // ARM9 DTCM, private and not the same memory
        program.load(1, 0x55667788);
        program.w(str(1, 0, 0));
        program.b(".loop");
        program.label(".loop").w(0xEAFFFFFE);
        NdsCore core = new NdsCore();
        core.loadRom(buildRom(program, null));
        core.stepFrame();
        NdsMachine machine = core.machine();
        check(reporter, "DS memory map: ARM9 main RAM write",
                machine.bus9.read32(0x02000000) == 0x11223344,
                Integer.toHexString(machine.bus9.read32(0x02000000)));
        check(reporter, "DS memory map: ARM9 DTCM is private",
                machine.bus9.read32(0x027C0000) == 0x55667788
                        && machine.bus7.read32(0x027C0000) == 0,
                "arm9=" + Integer.toHexString(machine.bus9.read32(0x027C0000))
                        + " arm7=" + Integer.toHexString(machine.bus7.read32(0x027C0000)));
        check(reporter, "DS memory map: main RAM is shared",
                machine.bus7.read32(0x02000000) == 0x11223344,
                Integer.toHexString(machine.bus7.read32(0x02000000)));
    }

    /** Emits code that fills one 4bpp tile (32 bytes) at the given VRAM address. */
    private static Asm fillTile(Asm program, int address, int colourPair) {
        program.load(0, address);
        program.load(1, colourPair);
        for (int offset = 0; offset < 32; offset += 4) {
            program.w(str(1, 0, offset));
        }
        return program;
    }

    private static void dsTextBackground(Reporter reporter) throws Exception {
        Asm program = new Asm();
        program.load(0, 0x05000000);
        program.w(mov(1, 0x001F), strh(1, 0, 2));             // palette colour 1 = red
        fillTile(program, 0x06000000, 0x11111111);
        program.load(0, 0x06000800);
        program.w(mov(1, 0), strh(1, 0, 0));                   // tile map entry 0 -> tile 0
        program.load(0, 0x04000008);
        program.w(mov(1, 0x00000100), strh(1, 0, 0));          // BG0: 4bpp, map base 0x800
        program.load(0, 0x04000000);
        program.w(mov(1, 0x00000100), str(1, 0, 0));           // mode 0, BG0 enabled
        program.b(".loop");
        program.label(".loop").w(0xEAFFFFFE);
        NdsCore core = new NdsCore();
        core.loadRom(buildRom(program, null));
        core.stepFrame();
        int[] frame = core.framebuffer();
        check(reporter, "DS text background: palette colour reaches the screen",
                frame[0] == 0xFFFF0000 && frame[100] == 0xFFFF0000,
                Integer.toHexString(frame[0]) + " / " + Integer.toHexString(frame[100]));
        check(reporter, "DS video: both screens are stacked",
                core.width() == 256 && core.height() == 384 && frame.length == 256 * 384,
                core.width() + "x" + core.height());
    }

    private static void dsSecondScreen(Reporter reporter) throws Exception {
        Asm program = new Asm();
        program.load(0, 0x05000400);
        program.w(mov(1, 0x03E0), strh(1, 0, 2));             // sub palette colour 1 = green
        fillTile(program, 0x06200000, 0x11111111);
        program.load(0, 0x06200800);
        program.w(mov(1, 0), strh(1, 0, 0));
        program.load(0, 0x04001008);
        program.w(mov(1, 0x00000100), strh(1, 0, 0));
        program.load(0, 0x04001000);
        program.w(mov(1, 0x00000100), str(1, 0, 0));           // sub BG0 enabled
        program.b(".loop");
        program.label(".loop").w(0xEAFFFFFE);
        NdsCore core = new NdsCore();
        core.loadRom(buildRom(program, null));
        core.stepFrame();
        int[] frame = core.framebuffer();
        int bottom = 256 * 192;
        check(reporter, "DS second screen: sub engine renders",
                frame[bottom] == 0xFF00FF00 && frame[bottom + 50] == 0xFF00FF00,
                Integer.toHexString(frame[bottom]));
    }

    private static void dsSprites(Reporter reporter) throws Exception {
        Asm program = new Asm();
        program.load(0, 0x05000200);
        program.w(mov(1, 0x03E0), strh(1, 0, 4));             // OBJ palette colour 2 = green
        fillTile(program, 0x06400000, 0x22222222);            // sprite tile 0 in colour 2
        program.load(0, 0x07000000);
        program.w(mov(1, 0));
        program.w(strh(1, 0, 0));                             // attr0: y=0, 4bpp, square
        program.w(strh(1, 0, 2));                             // attr1: x=0, 8x8
        program.w(strh(1, 0, 4));                             // attr2: tile 0, priority 0
        program.load(0, 0x04000000);
        program.w(mov(1, 0x00001000), str(1, 0, 0));           // sprites enabled
        program.b(".loop");
        program.label(".loop").w(0xEAFFFFFE);
        NdsCore core = new NdsCore();
        core.loadRom(buildRom(program, null));
        core.stepFrame();
        int[] frame = core.framebuffer();
        check(reporter, "DS sprites: OAM sprite reaches the screen",
                frame[0] == 0xFF00FF00 && frame[7 + 7 * 256] == 0xFF00FF00,
                Integer.toHexString(frame[0]) + " / " + Integer.toHexString(frame[7 + 7 * 256]));
        check(reporter, "DS sprites: outside the sprite stays transparent (black)",
                frame[8] == 0xFF000000, Integer.toHexString(frame[8]));
    }

    private static void dsBitmapMode(Reporter reporter) throws Exception {
        Asm program = new Asm();
        program.load(0, 0x06000000);
        program.w(mov(1, 0x7C00), strh(1, 0, 0));             // first pixel = blue
        program.load(0, 0x04000000);
        program.w(mov(1, 0x00000005), str(1, 0, 0));           // mode 5 bitmap
        program.b(".loop");
        program.label(".loop").w(0xEAFFFFFE);
        NdsCore core = new NdsCore();
        core.loadRom(buildRom(program, null));
        core.stepFrame();
        int[] frame = core.framebuffer();
        check(reporter, "DS bitmap mode 5: 15-bit direct colour",
                frame[0] == 0xFF0000FF && frame[1] == 0xFF000000,
                Integer.toHexString(frame[0]) + " / " + Integer.toHexString(frame[1]));
    }

    private static void dsIpcFifo(Reporter reporter) throws Exception {
        Asm arm9 = new Asm();
        arm9.load(0, 0x04000000);
        arm9.w(mov(1, 0x8000), strh(1, 0, 0x184));            // enable the FIFO
        arm9.load(1, 0xC0DE);
        arm9.w(str(1, 0, 0x188));             // send one word
        arm9.b(".loop");
        arm9.label(".loop").w(0xEAFFFFFE);

        Asm arm7 = new Asm();
        arm7.load(0, 0x04000000);
        arm7.w(mov(1, 0x8000), strh(1, 0, 0x184));
        arm7.load(2, 0x04100000);
        arm7.label(".poll");
        arm7.w(ldr(3, 2, 0));                                 // receive a word
        arm7.w(cmp(3, 0));
        arm7.bcond(0, ".poll");                               // still empty: keep waiting
        arm7.load(4, 0x03800000);
        arm7.w(str(3, 4, 0));                                 // keep it in ARM7 WRAM
        arm7.w(mov(5, 1), str(5, 4, 4));
        arm7.b(".poll");

        NdsCore core = new NdsCore();
        core.loadRom(buildRom(arm9, arm7));
        for (int i = 0; i < 3; i++) {
            core.stepFrame();
        }
        NdsMachine machine = core.machine();
        check(reporter, "DS IPC: ARM9 word arrives in the ARM7 FIFO",
                machine.bus7.read32(0x03800000) == 0xC0DE,
                Integer.toHexString(machine.bus7.read32(0x03800000)));
        check(reporter, "DS IPC: ARM7 program keeps running",
                machine.bus7.read32(0x03800004) == 1,
                Integer.toHexString(machine.bus7.read32(0x03800004)));
    }

    private static void dsSaveState(Reporter reporter) throws Exception {
        Asm program = new Asm();
        program.load(0, 0x05000000);
        program.w(mov(1, 0x001F), strh(1, 0, 2));
        fillTile(program, 0x06000000, 0x11111111);
        program.load(0, 0x06000800);
        program.w(mov(1, 0), strh(1, 0, 0));
        program.load(0, 0x04000008);
        program.w(mov(1, 0x00000100), strh(1, 0, 0));
        program.load(0, 0x04000000);
        program.w(mov(1, 0x00000100), str(1, 0, 0));
        program.b(".loop");
        program.label(".loop").w(0xEAFFFFFE);
        NdsCore core = new NdsCore();
        core.loadRom(buildRom(program, null));
        core.stepFrame();
        byte[] first = core.saveState();
        core.stepFrame();
        core.stepFrame();
        core.loadState(first);
        byte[] second = core.saveState();
        check(reporter, "DS save state: round trip is stable",
                first.length == second.length && java.util.Arrays.equals(first, second),
                first.length + " vs " + second.length + " bytes");
        check(reporter, "DS save state: picture survives the reload",
                core.framebuffer()[0] == 0xFFFF0000,
                Integer.toHexString(core.framebuffer()[0]));
    }
}
