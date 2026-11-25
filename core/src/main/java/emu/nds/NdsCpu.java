package emu.nds;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * ARM9/ARM7 style 32-bit RISC core: full ARM and Thumb decoding, all 16 condition codes, banked
 * registers, exception entry/return, the ARMv5TE extras (BLX, CLZ, LDRD/STRD, halfword multiplies)
 * and a bus interface. One instance per CPU; the Nintendo DS has two of them.
 */
public final class NdsCpu {

    public interface Bus {
        int read32(int address);

        int read16(int address);

        int read8(int address);

        void write32(int address, int value);

        void write16(int address, int value);

        void write8(int address, int value);

        /** True when this CPU has an unmasked interrupt pending (IME, IE and IF all set). */
        boolean irqAsserted();

        /** Executes a BIOS software interrupt on behalf of the CPU (it may change registers). */
        void swi(NdsCpu cpu, int comment);

        int cp15Read(int cn, int cm, int opcode2);

        void cp15Write(int cn, int cm, int opcode2, int value);
    }

    public static final int MODE_USR = 0x10;
    public static final int MODE_FIQ = 0x11;
    public static final int MODE_IRQ = 0x12;
    public static final int MODE_SVC = 0x13;
    public static final int MODE_ABT = 0x17;
    public static final int MODE_UND = 0x1B;
    public static final int MODE_SYS = 0x1F;

    public static final int FLAG_N = 0x80000000;
    public static final int FLAG_Z = 0x40000000;
    public static final int FLAG_C = 0x20000000;
    public static final int FLAG_V = 0x10000000;
    public static final int FLAG_I = 0x00000080;
    public static final int FLAG_F = 0x00000040;
    public static final int FLAG_T = 0x00000020;
    public static final int FLAG_MASK = 0x1F;

    private static final int IRQ_VECTOR = 0x18;
    private static final int SWI_VECTOR = 0x08;

    private static final int[] MODE_SLOT = new int[32];
    private static final int USER_SLOT = 0;
    private static final int FIQ_SLOT = 1;
    private static final int IRQ_SLOT = 2;
    private static final int SVC_SLOT = 3;
    private static final int ABT_SLOT = 4;
    private static final int UND_SLOT = 5;

    static {
        java.util.Arrays.fill(MODE_SLOT, -1);
        MODE_SLOT[MODE_USR] = USER_SLOT;
        MODE_SLOT[MODE_SYS] = USER_SLOT;
        MODE_SLOT[MODE_FIQ] = FIQ_SLOT;
        MODE_SLOT[MODE_IRQ] = IRQ_SLOT;
        MODE_SLOT[MODE_SVC] = SVC_SLOT;
        MODE_SLOT[MODE_ABT] = ABT_SLOT;
        MODE_SLOT[MODE_UND] = UND_SLOT;
    }

    private final Bus bus;
    /** True for the ARM946E-S of the DS, false for the ARM7TDMI. */
    public final boolean arm9;

    public final int[] r = new int[16];
    public int cpsr;
    public boolean halted;
    public long instructionCount;

    private final int[] r13Bank = new int[6];
    private final int[] r14Bank = new int[6];
    private final int[] spsrBank = new int[6];
    private final int[] r8Fiq = new int[5];
    private final int[] r8User = new int[5];

    private boolean pcWritten;
    private boolean shifterCarry;

    public NdsCpu(Bus bus, boolean arm9) {
        this.bus = bus;
        this.arm9 = arm9;
        reset(0);
    }

    public void reset(int pc) {
        java.util.Arrays.fill(r, 0);
        java.util.Arrays.fill(r13Bank, 0);
        java.util.Arrays.fill(r14Bank, 0);
        java.util.Arrays.fill(spsrBank, 0);
        java.util.Arrays.fill(r8Fiq, 0);
        java.util.Arrays.fill(r8User, 0);
        cpsr = MODE_SVC | FLAG_I | FLAG_F;
        r[15] = pc;
        halted = false;
        instructionCount = 0;
    }

    public int mode() {
        return cpsr & FLAG_MASK;
    }

    public boolean thumb() {
        return (cpsr & FLAG_T) != 0;
    }

    private static int slot(int mode) {
        int index = mode < 32 ? MODE_SLOT[mode] : -1;
        return index < 0 ? USER_SLOT : index;
    }

    private void switchMode(int newMode) {
        int oldMode = cpsr & FLAG_MASK;
        if (oldMode == newMode) {
            return;
        }
        int oldSlot = slot(oldMode);
        int newSlot = slot(newMode);
        r13Bank[oldSlot] = r[13];
        r14Bank[oldSlot] = r[14];
        boolean wasFiq = oldSlot == FIQ_SLOT;
        boolean isFiq = newSlot == FIQ_SLOT;
        if (wasFiq) {
            System.arraycopy(r, 8, r8Fiq, 0, 5);
            System.arraycopy(r8User, 0, r, 8, 5);
        } else if (isFiq) {
            System.arraycopy(r, 8, r8User, 0, 5);
            System.arraycopy(r8Fiq, 0, r, 8, 5);
        }
        r[13] = r13Bank[newSlot];
        r[14] = r14Bank[newSlot];
    }

    public void writeCpsr(int value) {
        switchMode(value & FLAG_MASK);
        cpsr = value;
    }

    /** Enters an exception: banks the registers, sets the mode and jumps to the vector. */
    private void enterException(int vector, int mode, int lrOffset, int cpsrClearMask) {
        int oldCpsr = cpsr;
        int returnAddress = r[15] + lrOffset;
        switchMode(mode);
        spsrBank[slot(mode)] = oldCpsr;
        cpsr = (oldCpsr & ~(FLAG_MASK | FLAG_T)) | mode | cpsrClearMask;
        r[14] = returnAddress;
        r[15] = highVectors() ? 0xFFFF0000 + vector : vector;
    }

    private boolean highVectors() {
        return arm9 && (bus.cp15Read(1, 0, 0) & 0x2000) != 0;
    }

    private int readReg(int index) {
        if (index == 15) {
            return r[15] + (thumb() ? 2 : 4);
        }
        return r[index];
    }

    private int storeValue(int index) {
        if (index == 15) {
            return readReg(15) + 4;
        }
        return r[index];
    }

    private void writeReg(int index, int value) {
        if (index == 15) {
            r[15] = thumb() ? (value & ~1) : (value & ~3);
            pcWritten = true;
        } else {
            r[index] = value;
        }
    }

    private boolean flag(int mask) {
        return (cpsr & mask) != 0;
    }

    private int carryBit() {
        return flag(FLAG_C) ? 1 : 0;
    }

    private void setNz(int result) {
        cpsr &= ~(FLAG_N | FLAG_Z);
        if (result < 0) {
            cpsr |= FLAG_N;
        }
        if (result == 0) {
            cpsr |= FLAG_Z;
        }
    }

    private void setFlag(int mask, boolean set) {
        if (set) {
            cpsr |= mask;
        } else {
            cpsr &= ~mask;
        }
    }

    // ------------------------------------------------------------------ stepping

    /** Executes one instruction (including a pending interrupt) and returns its cycle cost. */
    public int step() {
        if (halted) {
            if (bus.irqAsserted()) {
                halted = false;
            } else {
                return 4;
            }
        }
        if (bus.irqAsserted() && !flag(FLAG_I)) {
            enterException(IRQ_VECTOR, MODE_IRQ, 4, FLAG_I);
            return 3;
        }
        instructionCount++;
        if (TRACE_LAST) {
            lastPc[lastIndex] = r[15];
            lastInsn[lastIndex] = thumb() ? bus.read16(r[15]) : bus.read32(r[15]);
            lastSp[lastIndex] = r[13];
            lastCpsr[lastIndex] = cpsr;
            lastIndex = (lastIndex + 1) % lastPc.length;
        }
        int cycles = thumb() ? stepThumb() : stepArm();
        checkCrash();
        return cycles;
    }

    private final int[] lastPc = new int[48];
    private final int[] lastInsn = new int[48];
    private final int[] lastSp = new int[48];
    private final int[] lastCpsr = new int[48];
    private int lastIndex;
    private static final boolean TRACE_LAST = System.getenv("NDS_LAST") != null;
    private boolean traceCrashed;

    /** Debug aid: report the instruction window that led into unmapped memory. */
    private void checkCrash() {
        if (!TRACE_LAST || traceCrashed) {
            return;
        }
        int pc = r[15];
        boolean bad = (pc >= 0x1000 && pc < 0x02000000) || pc >= 0x0E000000
                || (!arm9 && pc >= 0x03810000 && pc < 0x04000000);
        if (!bad && pc < 0x1000 && pc != 0x18 && (pc < 0x18 || pc >= 0x30) && bus.read32(pc & ~3) == 0) {
            bad = true;
        }
        if (bad) {
            traceCrashed = true;
            dumpLast(arm9 ? "arm9" : "arm7");
        }
    }

    public void dumpLast(String label) {
        System.err.println("--- last instructions for " + label + " pc=" + Integer.toHexString(r[15]));
        for (int i = 0; i < lastPc.length; i++) {
            int at = (lastIndex + i) % lastPc.length;
            if (lastPc[at] == 0) {
                continue;
            }
            System.err.println("  " + Integer.toHexString(lastPc[at]) + "  " + Integer.toHexString(lastInsn[at])
                    + "  sp=" + Integer.toHexString(lastSp[at])
                    + "  cpsr=" + Integer.toHexString(lastCpsr[at]));
        }
    }

    private int stepArm() {
        int pc = r[15];
        int insn = bus.read32(pc);
        r[15] = pc + 4;
        pcWritten = false;
        int cond = insn >>> 28;
        if (cond != 0xF && !condition(cond)) {
            return 1;
        }
        int cycles = 1;
        // Bits 27-26 select the group. Bit 25 must not be part of the switch: a load/store with a
        // register offset has bit 25 set and would otherwise fall into the coprocessor group.
        switch ((insn >>> 26) & 3) {
            case 0:
                // MRS/MSR share this group with data processing, and MSR with an immediate operand
                // also has bit 25 set, so they have to be recognised before that split.
                if (isPsrTransfer(insn)) {
                    cycles = armPsrTransfer(insn);
                } else if ((insn & 0x02000000) != 0) {
                    cycles = armDataProcessing(insn, true);
                } else {
                    cycles = armMisc(insn);
                }
                break;
            case 1:
                cycles = armSingleTransfer(insn);
                break;
            case 2:
                // bit 25 separates B/BL (101) from LDM/STM (100)
                if ((insn & 0x02000000) != 0) {
                    cycles = armBranch(insn, pc);
                } else {
                    cycles = armBlockTransfer(insn);
                }
                break;
            default:
                if ((insn & 0x0F000000) == 0x0F000000) {
                    // ARM SWI puts its number in bits 16-23 (Thumb uses bits 0-7)
                    bus.swi(this, (insn >>> 16) & 0xFF);
                    cycles = 3;
                } else {
                    armCoprocessor(insn);
                }
                break;
        }
        checkCrash();
        return cycles;
    }

    private boolean isPsrTransfer(int insn) {
        return (insn & 0x0FBF0FFF) == 0x010F0000       // MRS
                || (insn & 0x0FB0FFF0) == 0x0120F000   // MSR (register)
                || (insn & 0x0FB0F000) == 0x0320F000;  // MSR (immediate)
    }

    private boolean condition(int code) {
        boolean n = flag(FLAG_N);
        boolean z = flag(FLAG_Z);
        boolean c = flag(FLAG_C);
        boolean v = flag(FLAG_V);
        switch (code) {
            case 0: return z;
            case 1: return !z;
            case 2: return c;
            case 3: return !c;
            case 4: return n;
            case 5: return !n;
            case 6: return v;
            case 7: return !v;
            case 8: return c && !z;
            case 9: return !c || z;
            case 10: return n == v;
            case 11: return n != v;
            case 12: return !z && n == v;
            case 13: return z || n != v;
            default: return true;
        }
    }

    // ------------------------------------------------------------------ ARM data processing

    private int shift(int type, int value, int amount, boolean byRegister) {
        int result;
        switch (type) {
            case 0:  // LSL
                if (amount == 0) {
                    shifterCarry = flag(FLAG_C);   // no shift: carry passes through unchanged
                    return value;
                }
                if (amount < 32) {
                    // the bit that falls out of the top is bit (32 - amount) of the input
                    shifterCarry = ((value >>> (32 - amount)) & 1) != 0;
                    return value << amount;
                }
                shifterCarry = amount == 32 ? (value & 1) != 0 : false;
                return 0;
            case 1:  // LSR
                if (amount == 0 && !byRegister) {
                    amount = 32;
                }
                if (amount == 0) {
                    shifterCarry = flag(FLAG_C);
                    return value;
                }
                if (amount < 32) {
                    shifterCarry = ((value >>> (amount - 1)) & 1) != 0;
                    return value >>> amount;
                }
                shifterCarry = amount == 32 ? value < 0 : false;
                return 0;
            case 2:  // ASR
                if (amount == 0 && !byRegister) {
                    amount = 32;
                }
                if (amount == 0) {
                    shifterCarry = flag(FLAG_C);
                    return value;
                }
                if (amount < 32) {
                    shifterCarry = ((value >> (amount - 1)) & 1) != 0;
                    return value >> amount;
                }
                shifterCarry = value < 0;
                return value < 0 ? -1 : 0;
            default:  // ROR
                if (amount == 0 && !byRegister) {
                    // ROR #0 is RRX: the old carry goes in at bit 31
                    int resultRrx = (value >>> 1) | (carryBit() << 31);
                    shifterCarry = (value & 1) != 0;
                    return resultRrx;
                }
                if (amount == 0) {
                    shifterCarry = flag(FLAG_C);
                    return value;
                }
                int rot = amount & 31;
                if (rot == 0) {
                    shifterCarry = value < 0;
                    return value;
                }
                result = (value >>> rot) | (value << (32 - rot));
                shifterCarry = (result & 0x80000000) != 0;
                return result;
        }
    }

    private int shiftOperand(int insn, int value, int type, int amount, boolean byRegister) {
        if (byRegister) {
            int shiftAmount = readReg((insn >>> 8) & 0xF) & 0xFF;
            if (shiftAmount == 0) {
                shifterCarry = flag(FLAG_C);   // shift by register 0 leaves the carry alone
                return value;
            }
            return shift(type, value, shiftAmount, true);
        }
        return shift(type, value, amount, false);
    }

    private int armDataProcessing(int insn, boolean immediate) {
        // BX and BLX (register) share this encoding space
        if ((insn & 0x0FFFFFF0) == 0x012FFF10) {
            int target = r[insn & 0xF];
            boolean toThumb = (target & 1) != 0;
            writeCpsr(toThumb ? (cpsr | FLAG_T) : (cpsr & ~FLAG_T));
            r[15] = target & (toThumb ? ~1 : ~3);
            pcWritten = true;
            return 3;
        }
        if (arm9 && (insn & 0x0FFFFFF0) == 0x012FFF30) {
            int target = r[insn & 0xF];
            boolean toThumb = !thumb();
            r[14] = r[15] | (thumb() ? 1 : 0);
            writeCpsr(toThumb ? (cpsr | FLAG_T) : (cpsr & ~FLAG_T));
            r[15] = target & (toThumb ? ~1 : ~3);
            pcWritten = true;
            return 3;
        }
        int opcode = (insn >>> 21) & 0xF;
        boolean setFlags = (insn & (1 << 20)) != 0;
        int rn = (insn >>> 16) & 0xF;
        int rd = (insn >>> 12) & 0xF;
        int operand2;
        if (immediate) {
            int rotate = ((insn >>> 8) & 0xF) * 2;
            int value = insn & 0xFF;
            if (rotate == 0) {
                operand2 = value;
                shifterCarry = flag(FLAG_C);   // unrotated immediate: carry unchanged
            } else {
                operand2 = Integer.rotateRight(value, rotate);
                shifterCarry = (operand2 & 0x80000000) != 0;
            }
        } else {
            int rmIndex = insn & 0xF;
            int rmValue;
            if (rmIndex == 15 && (insn & 0x10) != 0) {
                // ARM7TDMI: PC reads as PC+12 when the shift amount comes from a register
                rmValue = r[15] + 8;
            } else {
                rmValue = readReg(rmIndex);
            }
            operand2 = shiftOperand(insn, rmValue, (insn >>> 5) & 3, (insn >>> 7) & 0x1F,
                    (insn & 0x10) != 0);
        }
        boolean registerShift = !immediate && (insn & 0x10) != 0;
        int a = (rn == 15 && registerShift) ? r[15] + 8 : readReg(rn);
        int result;
        boolean carry = flag(FLAG_C);
        boolean overflow = flag(FLAG_V);
        boolean logical = true;
        switch (opcode) {
            case 0: result = a & operand2; break;
            case 1: result = a ^ operand2; break;
            case 2: {
                long diff = (a & 0xFFFFFFFFL) - (operand2 & 0xFFFFFFFFL);
                result = (int) diff;
                carry = diff >= 0;
                overflow = ((a ^ operand2) & (a ^ result)) < 0;
                logical = false;
                break;
            }
            case 3: {
                long diff = (operand2 & 0xFFFFFFFFL) - (a & 0xFFFFFFFFL);
                result = (int) diff;
                carry = diff >= 0;
                overflow = ((operand2 ^ a) & (operand2 ^ result)) < 0;
                logical = false;
                break;
            }
            case 4: case 5: {
                long sum = (a & 0xFFFFFFFFL) + (operand2 & 0xFFFFFFFFL) + (opcode == 5 ? carryBit() : 0);
                result = (int) sum;
                carry = sum > 0xFFFFFFFFL;
                overflow = ((a ^ result) & (operand2 ^ result)) < 0;
                logical = false;
                break;
            }
            case 6: case 7: {
                int left = opcode == 6 ? a : operand2;
                int right = opcode == 6 ? operand2 : a;
                long diff = (left & 0xFFFFFFFFL) - (right & 0xFFFFFFFFL) - (1 - carryBit());
                result = (int) diff;
                carry = diff >= 0;
                overflow = ((left ^ right) & (left ^ result)) < 0;
                logical = false;
                break;
            }
            case 8: result = a & operand2; break;                       // TST
            case 9: result = a ^ operand2; break;                       // TEQ
            case 10: {                                                  // CMP
                long diff = (a & 0xFFFFFFFFL) - (operand2 & 0xFFFFFFFFL);
                result = (int) diff;
                carry = diff >= 0;
                overflow = ((a ^ operand2) & (a ^ result)) < 0;
                logical = false;
                break;
            }
            case 11: {                                                  // CMN
                long sum = (a & 0xFFFFFFFFL) + (operand2 & 0xFFFFFFFFL);
                result = (int) sum;
                carry = sum > 0xFFFFFFFFL;
                overflow = ((a ^ result) & (operand2 ^ result)) < 0;
                logical = false;
                break;
            }
            case 12: result = a | operand2; break;
            case 13: result = operand2; break;
            case 14: result = a & ~operand2; break;
            default: result = ~operand2; break;
        }
        if (setFlags || opcode == 8 || opcode == 9 || opcode == 10 || opcode == 11) {
            setNz(result);
            setFlag(FLAG_C, logical ? shifterCarry : carry);
            if (!logical) {
                setFlag(FLAG_V, overflow);
            }
        }
        boolean writesRegister = opcode < 8 || opcode >= 12;
        if (writesRegister) {
            if (rd == 15 && setFlags) {
                writeCpsr(spsrBank[slot(cpsr & FLAG_MASK)]);
                r[15] = result & (thumb() ? ~1 : ~3);
                pcWritten = true;
            } else {
                writeReg(rd, result);
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------ ARM multiply / swap / halfword

    private int armMisc(int insn) {
        if ((insn & 0x0FB00FF0) == 0x01000090) {
            return armSwap(insn);
        }
        if ((insn & 0x0F0000F0) == 0x00000090) {
            boolean longMultiply = (insn & (1 << 23)) != 0;
            if (!longMultiply) {
                int rd = (insn >>> 16) & 0xF;
                int rn = (insn >>> 12) & 0xF;
                int rs = (insn >>> 8) & 0xF;
                int rm = insn & 0xF;
                int result = r[rm] * r[rs];
                if ((insn & (1 << 21)) != 0) {
                    result += r[rn];
                }
                r[rd] = result;
                if ((insn & (1 << 20)) != 0) {
                    setNz(result);
                }
                return 4;
            }
            int rdHi = (insn >>> 16) & 0xF;
            int rdLo = (insn >>> 12) & 0xF;
            int rs = (insn >>> 8) & 0xF;
            int rm = insn & 0xF;
            boolean signed = (insn & (1 << 22)) != 0;
            boolean accumulate = (insn & (1 << 21)) != 0;
            if (signed) {
                long product = (long) r[rm] * (long) r[rs];
                if (accumulate) {
                    product += ((long) r[rdHi] << 32) | (r[rdLo] & 0xFFFFFFFFL);
                }
                r[rdLo] = (int) product;
                r[rdHi] = (int) (product >> 32);
            } else {
                long product = (r[rm] & 0xFFFFFFFFL) * (r[rs] & 0xFFFFFFFFL);
                if (accumulate) {
                    product += ((long) r[rdHi] << 32) | (r[rdLo] & 0xFFFFFFFFL);
                }
                r[rdLo] = (int) product;
                r[rdHi] = (int) (product >>> 32);
            }
            if ((insn & (1 << 20)) != 0) {
                setNz(r[rdHi]);
            }
            return 5;
        }
        if ((insn & 0x0FF000F0) == 0x01600010 && arm9) {
            int rd = (insn >>> 12) & 0xF;
            int rm = insn & 0xF;
            int value = r[rm];
            int count = 0;
            while (value != 0) {
                value >>>= 1;
                count++;
            }
            r[rd] = 32 - count;
            return 1;
        }
        if (arm9 && (insn & 0x0F9000F0) == 0x01000050) {
            return armHalfwordMultiply(insn);
        }
        if ((insn & 0x0FBF0FFF) == 0x010F0000            // MRS
                || (insn & 0x0FB0FFF0) == 0x0120F000      // MSR (register)
                || (insn & 0x0FB0F000) == 0x0320F000) {   // MSR (immediate)
            return armPsrTransfer(insn);
        }
        return childDataProcessingFallback(insn);
    }

    /** Bit 7 set with bits 25-26 clear means data processing, not multiply; re-dispatch it. */
    private int childDataProcessingFallback(int insn) {
        if ((insn & 0x0E000090) == 0x00000090) {
            return armHalfTransfer(insn);
        }
        return armDataProcessing(insn, (insn & 0x02000000) != 0);
    }

    private int armHalfwordMultiply(int insn) {
        int op = (insn >>> 21) & 3;
        int rd = (insn >>> 16) & 0xF;
        int rn = (insn >>> 12) & 0xF;
        int rs = (insn >>> 8) & 0xF;
        int rm = insn & 0xF;
        int x = (insn & (1 << 5)) != 0 ? (r[rm] >> 16) : (r[rm] & 0xFFFF);
        int y = (insn & (1 << 6)) != 0 ? (r[rs] >> 16) : (r[rs] & 0xFFFF);
        if ((x & 0x8000) != 0) {
            x |= 0xFFFF0000;
        }
        if ((y & 0x8000) != 0) {
            y |= 0xFFFF0000;
        }
        switch (op) {
            case 0:  // SMULxy
                r[rd] = x * y;
                break;
            case 1:  // SMLAxy
                r[rd] = r[rn] + x * y;
                break;
            case 2:  // SMULWy: top half of Rs times bottom half of Rm, 32-bit result
                r[rd] = (int) (((long) x * (r[rs] >> 16)) >> 16);
                break;
            default:  // SMLAWy
                r[rd] = r[rn] + (int) (((long) x * (r[rs] >> 16)) >> 16);
                break;
        }
        return 1;
    }

    /** MRS and MSR: reading and writing CPSR/SPSR, which every real program needs. */
    private int armPsrTransfer(int insn) {
        boolean useSpsr = (insn & (1 << 22)) != 0;
        boolean isMsr = (insn & (1 << 21)) != 0;
        if (!isMsr) {
            int rd = (insn >>> 12) & 0xF;
            r[rd] = useSpsr ? spsrBank[slot(cpsr & FLAG_MASK)] : cpsr;
            return 1;
        }
        int fieldMask = (insn >>> 16) & 0xF;
        int value;
        if ((insn & (1 << 25)) != 0) {
            int rotate = ((insn >>> 8) & 0xF) * 2;
            value = Integer.rotateRight(insn & 0xFF, rotate);
        } else {
            value = r[insn & 0xF];
        }
        int mask = 0;
        if ((fieldMask & 1) != 0) {
            mask |= 0x000000FF;
        }
        if ((fieldMask & 2) != 0) {
            mask |= 0x0000FF00;
        }
        if ((fieldMask & 4) != 0) {
            mask |= 0x00FF0000;
        }
        if ((fieldMask & 8) != 0) {
            mask |= 0xFF000000;
        }
        if (useSpsr) {
            int index = slot(cpsr & FLAG_MASK);
            spsrBank[index] = (spsrBank[index] & ~mask) | (value & mask);
        } else {
            writeCpsr((cpsr & ~mask) | (value & mask));
        }
        return 1;
    }

    private int armSwap(int insn) {
        boolean byteSwap = (insn & (1 << 22)) != 0;
        int rn = (insn >>> 16) & 0xF;
        int rd = (insn >>> 12) & 0xF;
        int rm = insn & 0xF;
        int address = r[rn];
        if (byteSwap) {
            int value = bus.read8(address);
            bus.write8(address, r[rm] & 0xFF);
            r[rd] = value;
        } else {
            int value = bus.read32(address & ~3);
            value = Integer.rotateRight(value, (address & 3) * 8);
            bus.write32(address & ~3, r[rm]);
            r[rd] = value;
        }
        return 4;
    }

    private int armHalfTransfer(int insn) {
        boolean pre = (insn & (1 << 24)) != 0;
        boolean up = (insn & (1 << 23)) != 0;
        boolean immediate = (insn & (1 << 22)) != 0;
        boolean writeback = (insn & (1 << 21)) != 0;
        boolean load = (insn & (1 << 20)) != 0;
        int rn = (insn >>> 16) & 0xF;
        int rd = (insn >>> 12) & 0xF;
        int operation = (insn >>> 5) & 3;
        int offset = immediate ? (((insn >>> 4) & 0xF0) | (insn & 0xF)) : r[insn & 0xF];
        int base = readReg(rn);
        int address = pre ? (up ? base + offset : base - offset) : base;
        boolean isLoad = load;
        if (isLoad) {
            int value;
            switch (operation) {
                case 1: value = bus.read16(address); break;                 // LDRH
                case 2: value = bus.read8(address); break;                  // LDRSB
                case 3: value = (short) bus.read16(address); break;         // LDRSH
                default: value = bus.read32(address & ~3); break;
            }
            if (!pre || writeback) {
                writeReg(rn, up ? base + offset : base - offset);
            }
            writeReg(rd, value);
        } else {
            int value = storeValue(rd);
            switch (operation) {
                case 1: bus.write16(address, value); break;
                case 2: bus.write8(address, value); break;
                default: bus.write32(address & ~3, value); break;
            }
            if (!pre || writeback) {
                writeReg(rn, up ? base + offset : base - offset);
            }
        }
        return 3;
    }

    private int armSingleTransfer(int insn) {
        boolean registerOffset = (insn & (1 << 25)) != 0;
        boolean pre = (insn & (1 << 24)) != 0;
        boolean up = (insn & (1 << 23)) != 0;
        boolean byteTransfer = (insn & (1 << 22)) != 0;
        boolean writeback = (insn & (1 << 21)) != 0;
        boolean load = (insn & (1 << 20)) != 0;
        int rn = (insn >>> 16) & 0xF;
        int rd = (insn >>> 12) & 0xF;
        int offset = registerOffset
                ? shiftOperand(insn, readReg(insn & 0xF), (insn >>> 5) & 3, (insn >>> 7) & 0x1F, (insn & 0x10) != 0)
                : (insn & 0xFFF);
        int base = readReg(rn);
        int address = pre ? (up ? base + offset : base - offset) : base;
        if (load) {
            int value;
            if (byteTransfer) {
                value = bus.read8(address);
            } else if (arm9) {
                int aligned = bus.read32(address & ~3);
                value = Integer.rotateRight(aligned, (address & 3) * 8);
            } else {
                value = bus.read32(address & ~3);
                value = Integer.rotateRight(value, (address & 3) * 8);
            }
            if (!pre || writeback) {
                if (rd != rn) {
                    writeReg(rn, up ? base + offset : base - offset);
                } else {
                    writeReg(rn, up ? base + offset : base - offset);
                }
            }
            writeReg(rd, value);
            return 3;
        }
        int value = storeValue(rd);
        if (byteTransfer) {
            bus.write8(address, value & 0xFF);
        } else {
            bus.write32(address & ~3, value);
        }
        if (!pre || writeback) {
            writeReg(rn, up ? base + offset : base - offset);
        }
        return 2;
    }

    private int armBlockTransfer(int insn) {
        boolean pre = (insn & (1 << 24)) != 0;
        boolean up = (insn & (1 << 23)) != 0;
        boolean psr = (insn & (1 << 22)) != 0;
        boolean writeback = (insn & (1 << 21)) != 0;
        boolean load = (insn & (1 << 20)) != 0;
        int rn = (insn >>> 16) & 0xF;
        int list = insn & 0xFFFF;
        int count = Integer.bitCount(list);
        if (count == 0) {
            if (load && arm9) {
                r[15] = readReg(15) + 0x40;
                pcWritten = true;
            }
            return 1;
        }
        int base = readReg(rn);
        int address;
        if (up) {
            address = pre ? base + 4 : base;
        } else {
            address = pre ? base - 4 * count : base - 4 * count + 4;
        }
        boolean exceptionReturn = false;
        for (int i = 0; i < 16; i++) {
            if ((list & (1 << i)) == 0) {
                continue;
            }
            if (load) {
                int value = bus.read32(address & ~3);
                if (i == 15) {
                    writeReg(15, value);
                    if (psr) {
                        exceptionReturn = true;
                    }
                } else {
                    r[i] = value;
                }
            } else {
                bus.write32(address & ~3, storeValue(i));
            }
            address += 4;
        }
        if (writeback) {
            writeReg(rn, up ? base + 4 * count : base - 4 * count);
        }
        if (exceptionReturn) {
            writeCpsr(spsrBank[slot(cpsr & FLAG_MASK)]);
            r[15] &= (thumb() ? ~1 : ~3);
        }
        return 2 + count;
    }

    private int armBranch(int insn, int pc) {
        if ((insn >>> 28) == 0xF) {
            // BLX (ARMv5T): switch to Thumb, pc = this + 8 + imm24<<2 + (H<<1)
            int linkOffset = signExtend24(insn & 0xFFFFFF) << 2;
            int target = pc + 8 + linkOffset + (((insn >>> 24) & 1) << 1);
            r[14] = pc + 4;
            writeCpsr((cpsr & ~FLAG_T) | FLAG_T);
            r[15] = target & ~1;
            pcWritten = true;
            return 3;
        }
        int offset = signExtend24(insn & 0xFFFFFF) << 2;
        boolean link = (insn & (1 << 24)) != 0;
        if (link) {
            r[14] = pc + 4;
        }
        writeReg(15, pc + 8 + offset);
        return 3;
    }

    private static int signExtend24(int value) {
        if ((value & 0x800000) != 0) {
            return value | 0xFF000000;
        }
        return value;
    }

    private void armCoprocessor(int insn) {
        int cp = (insn >>> 8) & 0xF;
        int cn = (insn >>> 16) & 0xF;
        int cm = insn & 0xF;
        int opcode2 = (insn >>> 5) & 7;
        int rd = (insn >>> 12) & 0xF;
        if (cp != 15) {
            return;
        }
        if ((insn & (1 << 20)) != 0) {
            int value = bus.cp15Read(cn, cm, opcode2);
            if (rd != 15) {
                r[rd] = value;
            }
        } else {
            bus.cp15Write(cn, cm, opcode2, r[rd]);
        }
    }

    // ------------------------------------------------------------------ Thumb

    private int stepThumb() {
        int pc = r[15];
        int insn = bus.read16(pc);
        r[15] = pc + 2;
        pcWritten = false;
        switch (insn >>> 12) {
            case 0:
            case 1:
                if ((insn & 0x1800) == 0x1800) {
                    thumbAddSub(insn);
                } else {
                    thumbShift(insn);
                }
                return 1;
            case 2:
            case 3:
                thumbMovCmpAddSub(insn);
                return 1;
            case 4:
                if ((insn & 0xFC00) == 0x4000) {
                    thumbAlu(insn);
                } else if ((insn & 0xFC00) == 0x4400) {
                    return thumbHiRegister(insn);
                } else {
                    thumbLoadLiteral(insn);
                }
                return 1;
            case 5:
                thumbLoadStoreRegister(insn);
                return 3;
            case 6:
            case 7:
                thumbLoadStoreImmediate(insn);
                return 3;
            case 8:
                thumbLoadStoreHalf(insn);
                return 3;
            case 9:
                return thumbSpRelative(insn);
            case 10:
                thumbLoadAddress(insn);
                return 1;
            case 11:
                return thumbAddSpOrPushPop(insn);
            case 12:
                return thumbMultiple(insn);
            case 13:
                return thumbConditionalBranch(insn, pc);
            case 14:
                return thumbUnconditionalBranch(insn, pc);
            default:
                return thumbLongBranch(insn, pc);
        }
    }

    /** MOV/CMP/ADD/SUB with an 8-bit immediate (0x2000-0x3FFF). */
    private void thumbMovCmpAddSub(int insn) {
        int op = (insn >>> 11) & 3;
        int rd = (insn >>> 8) & 7;
        int immediate = insn & 0xFF;
        int value = r[rd];
        switch (op) {
            case 0:
                r[rd] = immediate;
                setNz(immediate);
                break;
            case 1: {
                int result = value - immediate;
                setNz(result);
                setFlag(FLAG_C, (value & 0xFFFFFFFFL) >= (immediate & 0xFFFFFFFFL));
                setFlag(FLAG_V, ((value ^ immediate) & (value ^ result)) < 0);
                break;
            }
            case 2: {
                int result = value + immediate;
                setNz(result);
                setFlag(FLAG_C, (value & 0xFFFFFFFFL) + immediate > 0xFFFFFFFFL);
                setFlag(FLAG_V, ((value ^ result) & (immediate ^ result)) < 0);
                r[rd] = result;
                break;
            }
            default: {
                int result = value - immediate;
                setNz(result);
                setFlag(FLAG_C, (value & 0xFFFFFFFFL) >= (immediate & 0xFFFFFFFFL));
                setFlag(FLAG_V, ((value ^ immediate) & (value ^ result)) < 0);
                r[rd] = result;
                break;
            }
        }
    }

    /** LDR rd, [pc, #imm] (0x4800-0x4FFF). */
    private void thumbLoadLiteral(int insn) {
        int rd = (insn >>> 8) & 7;
        int address = ((r[15] + 2) & ~3) + (insn & 0xFF) * 4;
        r[rd] = bus.read32(address);
    }

    /** Conditional branch, or SWI for 0xDF. */
    private int thumbConditionalBranch(int insn, int pc) {
        int condition = (insn >>> 8) & 0xF;
        if (condition == 0xF) {
            bus.swi(this, insn & 0xFF);
            return 3;
        }
        if (condition == 0xE) {
            return 1;
        }
        if (condition(condition)) {
            int offset = (byte) (insn & 0xFF);
            writeReg(15, pc + 4 + (offset << 1));
            return 3;
        }
        return 1;
    }

    /** B, or the BLX suffix on the ARM9. */
    private int thumbUnconditionalBranch(int insn, int pc) {
        if ((insn & 0x0800) == 0) {
            int offset = signExtend11(insn & 0x7FF) << 1;
            writeReg(15, pc + 4 + offset);
            return 3;
        }
        int offset = (insn & 0x7FF) << 1;
        int target = (r[14] + offset) & ~3;
        r[14] = (pc + 2) | 1;
        writeCpsr(cpsr & ~FLAG_T);
        r[15] = target;
        pcWritten = true;
        return 3;
    }

    /** BL prefix (0xF000-0xF7FF) or suffix (0xF800-0xFFFF). */
    private int thumbLongBranch(int insn, int pc) {
        if ((insn & 0x0800) == 0) {
            int offset = signExtend11(insn & 0x7FF) << 12;
            r[14] = pc + 4 + offset;
            return 1;
        }
        int offset = (insn & 0x7FF) << 1;
        int target = (r[14] + offset) & ~1;
        r[14] = (pc + 2) | 1;
        writeReg(15, target);
        return 3;
    }

    private static int signExtend11(int value) {
        return (value & 0x400) != 0 ? value | 0xFFFFF800 : value;
    }

    private void thumbShift(int insn) {
        int rd = insn & 7;
        int rs = (insn >>> 3) & 7;
        int type = (insn >>> 11) & 3;
        int amount = (insn >>> 6) & 0x1F;
        int value = r[rs];
        int result;
        if (type == 0 && amount == 0) {
            result = value;
        } else {
            if (type == 1 && amount == 0) {
                amount = 32;
            }
            if (type == 2 && amount == 0) {
                amount = 32;
            }
            result = shift(type, value, amount, false);
        }
        r[rd] = result;
        setNz(result);
        setFlag(FLAG_C, shifterCarry);
    }

    private void thumbAddSub(int insn) {
        int rd = insn & 7;
        int rs = (insn >>> 3) & 7;
        boolean immediate = (insn & (1 << 10)) != 0;
        boolean subtract = (insn & (1 << 9)) != 0;
        int operand = immediate ? ((insn >>> 6) & 7) : r[(insn >>> 6) & 7];
        int a = r[rs];
        if (subtract) {
            long diff = (a & 0xFFFFFFFFL) - (operand & 0xFFFFFFFFL);
            r[rd] = (int) diff;
            setFlag(FLAG_C, diff >= 0);
            setFlag(FLAG_V, ((a ^ operand) & (a ^ (int) diff)) < 0);
        } else {
            long sum = (a & 0xFFFFFFFFL) + (operand & 0xFFFFFFFFL);
            r[rd] = (int) sum;
            setFlag(FLAG_C, sum > 0xFFFFFFFFL);
            setFlag(FLAG_V, ((a ^ (int) sum) & (operand ^ (int) sum)) < 0);
        }
        setNz(r[rd]);
    }

    private void thumbAlu(int insn) {
        int rd = insn & 7;
        int rs = (insn >>> 3) & 7;
        int op = (insn >>> 6) & 0xF;
        int a = r[rd];
        int b = r[rs];
        int result;
        switch (op) {
            case 0: result = a & b; r[rd] = result; setNz(result); break;
            case 1: result = a ^ b; r[rd] = result; setNz(result); break;
            case 2: result = shift(0, a, b & 0xFF, true); r[rd] = result; setNz(result); setFlag(FLAG_C, shifterCarry); break;
            case 3: result = shift(1, a, b & 0xFF, true); r[rd] = result; setNz(result); setFlag(FLAG_C, shifterCarry); break;
            case 4: result = shift(2, a, b & 0xFF, true); r[rd] = result; setNz(result); setFlag(FLAG_C, shifterCarry); break;
            case 5: {
                long sum = (a & 0xFFFFFFFFL) + (b & 0xFFFFFFFFL) + carryBit();
                result = (int) sum;
                r[rd] = result;
                setNz(result);
                setFlag(FLAG_C, sum > 0xFFFFFFFFL);
                setFlag(FLAG_V, ((a ^ result) & (b ^ result)) < 0);
                break;
            }
            case 6: {
                long diff = (a & 0xFFFFFFFFL) - (b & 0xFFFFFFFFL) - (1 - carryBit());
                result = (int) diff;
                r[rd] = result;
                setNz(result);
                setFlag(FLAG_C, diff >= 0);
                setFlag(FLAG_V, ((a ^ b) & (a ^ result)) < 0);
                break;
            }
            case 7: result = shift(3, a, b & 0xFF, true); r[rd] = result; setNz(result); setFlag(FLAG_C, shifterCarry); break;
            case 8: setNz(a & b); break;
            case 9: {
                long diff = 0L - (b & 0xFFFFFFFFL);
                r[rd] = (int) diff;
                setNz(r[rd]);
                setFlag(FLAG_C, diff >= 0);
                setFlag(FLAG_V, ((0 ^ b) & (0 ^ (int) diff)) < 0);
                break;
            }
            case 10: {
                long diff = (a & 0xFFFFFFFFL) - (b & 0xFFFFFFFFL);
                setNz((int) diff);
                setFlag(FLAG_C, diff >= 0);
                setFlag(FLAG_V, ((a ^ b) & (a ^ (int) diff)) < 0);
                break;
            }
            case 11: {
                long sum = (a & 0xFFFFFFFFL) + (b & 0xFFFFFFFFL);
                setNz((int) sum);
                setFlag(FLAG_C, sum > 0xFFFFFFFFL);
                setFlag(FLAG_V, ((a ^ (int) sum) & (b ^ (int) sum)) < 0);
                break;
            }
            case 12: result = a | b; r[rd] = result; setNz(result); break;
            case 13: result = a * b; r[rd] = result; setNz(result); break;
            case 14: result = a & ~b; r[rd] = result; setNz(result); break;
            default: result = ~b; r[rd] = result; setNz(result); break;
        }
    }

    private int thumbHiRegister(int insn) {
        int op = (insn >>> 8) & 3;
        int rd = (insn & 7) | (((insn >>> 4) & 8));
        int rs = (insn >>> 3) & 0xF;
        switch (op) {
            case 0:
                writeReg(rd, readReg(rd) + readReg(rs));
                return 1;
            case 1:
                setNz(readReg(rd) - readReg(rs));
                setFlag(FLAG_C, (readReg(rd) & 0xFFFFFFFFL) >= (readReg(rs) & 0xFFFFFFFFL));
                setFlag(FLAG_V, ((readReg(rd) ^ readReg(rs)) & (readReg(rd) ^ (readReg(rd) - readReg(rs)))) < 0);
                return 1;
            case 2:
                writeReg(rd, readReg(rs));
                return 1;
            default:
                int target = readReg(rs);
                if ((insn & (1 << 7)) != 0 && arm9) {
                    r[14] = (r[15] - 2) | 1;
                    writeCpsr(cpsr & ~FLAG_T);
                    r[15] = target & ~3;
                } else {
                    boolean toThumb = (target & 1) != 0;
                    writeCpsr(toThumb ? (cpsr | FLAG_T) : (cpsr & ~FLAG_T));
                    r[15] = target & (toThumb ? ~1 : ~3);
                }
                pcWritten = true;
                return 3;
        }
    }

    private void thumbLoadStoreImmediate(int insn) {
        int op = (insn >>> 11) & 3;
        int offset = (insn >>> 6) & 0x1F;
        int rs = (insn >>> 3) & 7;
        int rd = insn & 7;
        int address;
        switch (op) {
            case 0: address = r[rs] + (offset << 2); bus.write32(address & ~3, r[rd]); break;
            case 1: address = r[rs] + (offset << 2); r[rd] = bus.read32(address & ~3); break;
            case 2: address = r[rs] + offset; bus.write8(address, r[rd] & 0xFF); break;
            default: address = r[rs] + offset; r[rd] = bus.read8(address); break;
        }
    }

    private void thumbLoadStoreRegister(int insn) {
        int op = (insn >>> 9) & 7;
        int ro = (insn >>> 6) & 7;
        int rs = (insn >>> 3) & 7;
        int rd = insn & 7;
        int address = r[rs] + r[ro];
        switch (op) {
            case 0: bus.write32(address & ~3, r[rd]); break;
            case 1: bus.write16(address, r[rd]); break;
            case 2: bus.write8(address, r[rd] & 0xFF); break;
            case 3: r[rd] = (byte) bus.read8(address); break;
            case 4: r[rd] = bus.read32(address & ~3); break;
            case 5: r[rd] = bus.read16(address); break;
            case 6: r[rd] = bus.read8(address); break;
            default: r[rd] = (short) bus.read16(address); break;
        }
    }

    private void thumbLoadStoreHalf(int insn) {
        int offset = ((insn >>> 6) & 0x1F) << 1;
        int rs = (insn >>> 3) & 7;
        int rd = insn & 7;
        int address = r[rs] + offset;
        if ((insn & (1 << 11)) != 0) {
            r[rd] = bus.read16(address);
        } else {
            bus.write16(address, r[rd]);
        }
    }

    private int thumbSpRelative(int insn) {
        int rd = (insn >>> 8) & 7;
        int offset = (insn & 0xFF) << 2;
        if ((insn & (1 << 11)) != 0) {
            r[rd] = bus.read32((r[13] + offset) & ~3);
            return 3;
        }
        r[rd] = r[13] + offset;
        return 1;
    }

    private void thumbLoadAddress(int insn) {
        int rd = (insn >>> 8) & 7;
        int offset = (insn & 0xFF) << 2;
        r[rd] = ((insn & (1 << 11)) != 0 ? r[13] : (r[15] + 2 & ~3)) + offset;
    }

    private int thumbAddSpOrPushPop(int insn) {
        if ((insn & 0xFF00) == 0xDF00) {
            bus.swi(this, insn & 0xFF);
            return 3;
        }
        if ((insn & 0x0F00) == 0x0000) {
            boolean subtract = (insn & (1 << 7)) != 0;
            int offset = (insn & 0x7F) << 2;
            r[13] = subtract ? r[13] - offset : r[13] + offset;
            return 1;
        }
        int list = insn & 0xFF;
        boolean store = (insn & 0x0800) == 0;
        boolean extra = (insn & (1 << 8)) != 0;
        int count = Integer.bitCount(list) + (extra ? 1 : 0);
        if (store) {
            int address = r[13] - 4 * count;
            r[13] = address;
            for (int i = 0; i < 8; i++) {
                if ((list & (1 << i)) != 0) {
                    bus.write32(address & ~3, r[i]);
                    address += 4;
                }
            }
            if (extra) {
                bus.write32(address & ~3, r[14]);
            }
            return 2 + count;
        }
        int address = r[13];
        for (int i = 0; i < 8; i++) {
            if ((list & (1 << i)) != 0) {
                r[i] = bus.read32(address & ~3);
                address += 4;
            }
        }
        if (extra) {
            int value = bus.read32(address & ~3);
            address += 4;
            writeReg(15, value);
        }
        r[13] = address;
        return 3 + count;
    }

    private int thumbMultiple(int insn) {
        int base = (insn >>> 8) & 7;
        int list = insn & 0xFF;
        boolean load = (insn & (1 << 11)) != 0;
        int address = r[base];
        boolean wroteBase = false;
        for (int i = 0; i < 8; i++) {
            if ((list & (1 << i)) == 0) {
                continue;
            }
            if (load) {
                r[i] = bus.read32(address & ~3);
                if (i == base) {
                    wroteBase = true;
                }
            } else {
                bus.write32(address & ~3, r[i]);
            }
            address += 4;
        }
        if (!load || !wroteBase) {
            r[base] = address;
        }
        return 2 + Integer.bitCount(list);
    }

    // ------------------------------------------------------------------ save states

    public void saveState(DataOutputStream out) throws IOException {
        for (int value : r) {
            out.writeInt(value);
        }
        out.writeInt(cpsr);
        out.writeBoolean(halted);
        out.writeLong(instructionCount);
        for (int i = 0; i < 6; i++) {
            out.writeInt(r13Bank[i]);
            out.writeInt(r14Bank[i]);
            out.writeInt(spsrBank[i]);
        }
        for (int i = 0; i < 5; i++) {
            out.writeInt(r8Fiq[i]);
            out.writeInt(r8User[i]);
        }
    }

    public void loadState(DataInputStream in) throws IOException {
        for (int i = 0; i < 16; i++) {
            r[i] = in.readInt();
        }
        cpsr = in.readInt();
        halted = in.readBoolean();
        instructionCount = in.readLong();
        for (int i = 0; i < 6; i++) {
            r13Bank[i] = in.readInt();
            r14Bank[i] = in.readInt();
            spsrBank[i] = in.readInt();
        }
        for (int i = 0; i < 5; i++) {
            r8Fiq[i] = in.readInt();
            r8User[i] = in.readInt();
        }
    }
}
