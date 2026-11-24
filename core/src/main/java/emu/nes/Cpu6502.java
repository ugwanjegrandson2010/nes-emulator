package emu.nes;

/**
 * Cycle-counted NMOS 6502 core (including the commonly used undocumented opcodes).
 *
 * <p>The instruction table is packed into {@link #TABLE}: 16 rows of 16 entries, each entry
 * {@code {operation, addressing mode, base cycles}}. Page-crossing penalties, branch penalties
 * and read-modify-write penalties are added at run time by the executor.
 *
 * <p>Verified against Klaus Dormann's 6502 functional test (all official opcodes, decimal mode
 * included) and nestest.nes (official + undocumented opcodes, cycle counts).
 */
public final class Cpu6502 {

    public interface Bus {
        int read(int address);

        void write(int address, int value);
    }

    // Status register bits
    public static final int C = 0x01;
    public static final int Z = 0x02;
    public static final int I = 0x04;
    public static final int D = 0x08;
    public static final int B = 0x10;
    public static final int U = 0x20;
    public static final int V = 0x40;
    public static final int N = 0x80;

    // Addressing modes
    static final int IMP = 0, ACC = 1, IMM = 2, ZP = 3, ZPX = 4, ZPY = 5, ABS = 6, ABX = 7,
            ABY = 8, IND = 9, IZX = 10, IZY = 11, REL = 12;

    // Operations
    static final int ADC = 0, AND = 1, ASL = 2, BCC = 3, BCS = 4, BEQ = 5, BIT = 6, BMI = 7,
            BNE = 8, BPL = 9, BRK = 10, BVC = 11, BVS = 12, CLC = 13, CLD = 14, CLI = 15,
            CLV = 16, CMP = 17, CPX = 18, CPY = 19, DEC = 20, DEX = 21, DEY = 22, EOR = 23,
            INC = 24, INX = 25, INY = 26, JMP = 27, JSR = 28, LDA = 29, LDX = 30, LDY = 31,
            LSR = 32, NOP = 33, ORA = 34, PHA = 35, PHP = 36, PLA = 37, PLP = 38, ROL = 39,
            ROR = 40, RTI = 41, RTS = 42, SBC = 43, SEC = 44, SED = 45, SEI = 46, STA = 47,
            STX = 48, STY = 49, TAX = 50, TAY = 51, TSX = 52, TXA = 53, TXS = 54, TYA = 55,
            // undocumented
            SLO = 56, RLA = 57, SRE = 58, RRA = 59, SAX = 60, LAX = 61, DCP = 62, ISC = 63,
            ANC = 64, ALR = 65, ARR = 66, AXS = 67, LAS = 68, AHX = 69, SHX = 70, SHY = 71,
            TAS = 72, XAA = 73, KIL = 74;

    static final String[] OPCODE_NAMES = {
            "ADC", "AND", "ASL", "BCC", "BCS", "BEQ", "BIT", "BMI", "BNE", "BPL", "BRK", "BVC",
            "BVS", "CLC", "CLD", "CLI", "CLV", "CMP", "CPX", "CPY", "DEC", "DEX", "DEY", "EOR",
            "INC", "INX", "INY", "JMP", "JSR", "LDA", "LDX", "LDY", "LSR", "NOP", "ORA", "PHA",
            "PHP", "PLA", "PLP", "ROL", "ROR", "RTI", "RTS", "SBC", "SEC", "SED", "SEI", "STA",
            "STX", "STY", "TAX", "TAY", "TSX", "TXA", "TXS", "TYA", "SLO", "RLA", "SRE", "RRA",
            "SAX", "LAX", "DCP", "ISC", "ANC", "ALR", "ARR", "AXS", "LAS", "AHX", "SHX", "SHY",
            "TAS", "XAA", "KIL"
    };

    private static final int[][] TABLE = {
            /* 00 */ {BRK, IMP, 7}, {ORA, IZX, 6}, {KIL, IMP, 2}, {SLO, IZX, 8}, {NOP, ZP, 3}, {ORA, ZP, 3}, {ASL, ZP, 5}, {SLO, ZP, 5},
                     {PHP, IMP, 3}, {ORA, IMM, 2}, {ASL, ACC, 2}, {ANC, IMM, 2}, {NOP, ABS, 4}, {ORA, ABS, 4}, {ASL, ABS, 6}, {SLO, ABS, 6},
            /* 10 */ {BPL, REL, 2}, {ORA, IZY, 5}, {KIL, IMP, 2}, {SLO, IZY, 8}, {NOP, ZPX, 4}, {ORA, ZPX, 4}, {ASL, ZPX, 6}, {SLO, ZPX, 6},
                     {CLC, IMP, 2}, {ORA, ABY, 4}, {NOP, IMP, 2}, {SLO, ABY, 7}, {NOP, ABX, 4}, {ORA, ABX, 4}, {ASL, ABX, 7}, {SLO, ABX, 7},
            /* 20 */ {JSR, ABS, 6}, {AND, IZX, 6}, {KIL, IMP, 2}, {RLA, IZX, 8}, {BIT, ZP, 3}, {AND, ZP, 3}, {ROL, ZP, 5}, {RLA, ZP, 5},
                     {PLP, IMP, 4}, {AND, IMM, 2}, {ROL, ACC, 2}, {ANC, IMM, 2}, {BIT, ABS, 4}, {AND, ABS, 4}, {ROL, ABS, 6}, {RLA, ABS, 6},
            /* 30 */ {BMI, REL, 2}, {AND, IZY, 5}, {KIL, IMP, 2}, {RLA, IZY, 8}, {NOP, ZPX, 4}, {AND, ZPX, 4}, {ROL, ZPX, 6}, {RLA, ZPX, 6},
                     {SEC, IMP, 2}, {AND, ABY, 4}, {NOP, IMP, 2}, {RLA, ABY, 7}, {NOP, ABX, 4}, {AND, ABX, 4}, {ROL, ABX, 7}, {RLA, ABX, 7},
            /* 40 */ {RTI, IMP, 6}, {EOR, IZX, 6}, {KIL, IMP, 2}, {SRE, IZX, 8}, {NOP, ZP, 3}, {EOR, ZP, 3}, {LSR, ZP, 5}, {SRE, ZP, 5},
                     {PHA, IMP, 3}, {EOR, IMM, 2}, {LSR, ACC, 2}, {ALR, IMM, 2}, {JMP, ABS, 3}, {EOR, ABS, 4}, {LSR, ABS, 6}, {SRE, ABS, 6},
            /* 50 */ {BVC, REL, 2}, {EOR, IZY, 5}, {KIL, IMP, 2}, {SRE, IZY, 8}, {NOP, ZPX, 4}, {EOR, ZPX, 4}, {LSR, ZPX, 6}, {SRE, ZPX, 6},
                     {CLI, IMP, 2}, {EOR, ABY, 4}, {NOP, IMP, 2}, {SRE, ABY, 7}, {NOP, ABX, 4}, {EOR, ABX, 4}, {LSR, ABX, 7}, {SRE, ABX, 7},
            /* 60 */ {RTS, IMP, 6}, {ADC, IZX, 6}, {KIL, IMP, 2}, {RRA, IZX, 8}, {NOP, ZP, 3}, {ADC, ZP, 3}, {ROR, ZP, 5}, {RRA, ZP, 5},
                     {PLA, IMP, 4}, {ADC, IMM, 2}, {ROR, ACC, 2}, {ARR, IMM, 2}, {JMP, IND, 5}, {ADC, ABS, 4}, {ROR, ABS, 6}, {RRA, ABS, 6},
            /* 70 */ {BVS, REL, 2}, {ADC, IZY, 5}, {KIL, IMP, 2}, {RRA, IZY, 8}, {NOP, ZPX, 4}, {ADC, ZPX, 4}, {ROR, ZPX, 6}, {RRA, ZPX, 6},
                     {SEI, IMP, 2}, {ADC, ABY, 4}, {NOP, IMP, 2}, {RRA, ABY, 7}, {NOP, ABX, 4}, {ADC, ABX, 4}, {ROR, ABX, 7}, {RRA, ABX, 7},
            /* 80 */ {NOP, IMM, 2}, {STA, IZX, 6}, {NOP, IMM, 2}, {SAX, IZX, 6}, {STY, ZP, 3}, {STA, ZP, 3}, {STX, ZP, 3}, {SAX, ZP, 3},
                     {DEY, IMP, 2}, {NOP, IMM, 2}, {TXA, IMP, 2}, {XAA, IMM, 2}, {STY, ABS, 4}, {STA, ABS, 4}, {STX, ABS, 4}, {SAX, ABS, 4},
            /* 90 */ {BCC, REL, 2}, {STA, IZY, 6}, {KIL, IMP, 2}, {AHX, IZY, 6}, {STY, ZPX, 4}, {STA, ZPX, 4}, {STX, ZPY, 4}, {SAX, ZPY, 4},
                     {TYA, IMP, 2}, {STA, ABY, 5}, {TXS, IMP, 2}, {TAS, ABY, 5}, {SHY, ABX, 5}, {STA, ABX, 5}, {SHX, ABY, 5}, {AHX, ABY, 5},
            /* A0 */ {LDY, IMM, 2}, {LDA, IZX, 6}, {LDX, IMM, 2}, {LAX, IZX, 6}, {LDY, ZP, 3}, {LDA, ZP, 3}, {LDX, ZP, 3}, {LAX, ZP, 3},
                     {TAY, IMP, 2}, {LDA, IMM, 2}, {TAX, IMP, 2}, {LAX, IMM, 2}, {LDY, ABS, 4}, {LDA, ABS, 4}, {LDX, ABS, 4}, {LAX, ABS, 4},
            /* B0 */ {BCS, REL, 2}, {LDA, IZY, 5}, {KIL, IMP, 2}, {LAX, IZY, 5}, {LDY, ZPX, 4}, {LDA, ZPX, 4}, {LDX, ZPY, 4}, {LAX, ZPY, 4},
                     {CLV, IMP, 2}, {LDA, ABY, 4}, {TSX, IMP, 2}, {LAS, ABY, 4}, {LDY, ABX, 4}, {LDA, ABX, 4}, {LDX, ABY, 4}, {LAX, ABY, 4},
            /* C0 */ {CPY, IMM, 2}, {CMP, IZX, 6}, {NOP, IMM, 2}, {DCP, IZX, 8}, {CPY, ZP, 3}, {CMP, ZP, 3}, {DEC, ZP, 5}, {DCP, ZP, 5},
                     {INY, IMP, 2}, {CMP, IMM, 2}, {DEX, IMP, 2}, {AXS, IMM, 2}, {CPY, ABS, 4}, {CMP, ABS, 4}, {DEC, ABS, 6}, {DCP, ABS, 6},
            /* D0 */ {BNE, REL, 2}, {CMP, IZY, 5}, {KIL, IMP, 2}, {DCP, IZY, 8}, {NOP, ZPX, 4}, {CMP, ZPX, 4}, {DEC, ZPX, 6}, {DCP, ZPX, 6},
                     {CLD, IMP, 2}, {CMP, ABY, 4}, {NOP, IMP, 2}, {DCP, ABY, 7}, {NOP, ABX, 4}, {CMP, ABX, 4}, {DEC, ABX, 7}, {DCP, ABX, 7},
            /* E0 */ {CPX, IMM, 2}, {SBC, IZX, 6}, {NOP, IMM, 2}, {ISC, IZX, 8}, {CPX, ZP, 3}, {SBC, ZP, 3}, {INC, ZP, 5}, {ISC, ZP, 5},
                     {INX, IMP, 2}, {SBC, IMM, 2}, {NOP, IMP, 2}, {SBC, IMM, 2}, {CPX, ABS, 4}, {SBC, ABS, 4}, {INC, ABS, 6}, {ISC, ABS, 6},
            /* F0 */ {BEQ, REL, 2}, {SBC, IZY, 5}, {KIL, IMP, 2}, {ISC, IZY, 8}, {NOP, ZPX, 4}, {SBC, ZPX, 4}, {INC, ZPX, 6}, {ISC, ZPX, 6},
                     {SED, IMP, 2}, {SBC, ABY, 4}, {NOP, IMP, 2}, {ISC, ABY, 7}, {NOP, ABX, 4}, {SBC, ABX, 4}, {INC, ABX, 7}, {ISC, ABX, 7}
    };

    private static final int[] OP = new int[256];
    private static final int[] MODE = new int[256];
    private static final int[] CYCLES = new int[256];

    static {
        for (int opcode = 0; opcode < 256; opcode++) {
            int[] entry = TABLE[opcode];
            OP[opcode] = entry[0];
            MODE[opcode] = entry[1];
            CYCLES[opcode] = entry[2];
        }
    }

    public int a, x, y, sp, pc, p;
    public long cycles;
    public boolean irqLine;
    public boolean nmiPending;
    /**
     * The Ricoh 2A03 used in the NES has the decimal flag but no decimal arithmetic: ADC/SBC
     * always run in binary mode. Set to false for NES emulation (true for a plain 6502).
     */
    public boolean decimalArithmetic = true;
    /** Value of the "magic" constant used by unstable undocumented opcodes. */
    public int magic = 0xFF;

    private Bus bus;
    private int addr;
    private int baseAddr;
    private boolean pageCrossed;
    private boolean jammed;

    public void setBus(Bus bus) {
        this.bus = bus;
    }

    public void reset() {
        a = x = y = 0;
        sp = 0xFD;
        p = U | I;
        pc = read16(0xFFFC);
        cycles = 0;
        irqLine = false;
        nmiPending = false;
        jammed = false;
    }

    public boolean isJammed() {
        return jammed;
    }

    /** Executes one instruction (or services one interrupt) and returns the cycles used. */
    public int step() {
        if (jammed) {
            return 1;
        }
        if (nmiPending) {
            nmiPending = false;
            interrupt(0xFFFA, false);
            return 7;
        }
        if (irqLine && (p & I) == 0) {
            interrupt(0xFFFE, false);
            return 7;
        }

        int opcode = read(pc++);
        int op = OP[opcode];
        int mode = MODE[opcode];
        int used = CYCLES[opcode];
        pageCrossed = false;

        switch (op) {
            // ---- memory reads with page-cross cycling ----
            case LDA:
                resolve(mode);
                a = read(addr);
                setZN(a);
                if (pageCrossed) used++;
                break;
            case LDX:
                resolve(mode);
                x = read(addr);
                setZN(x);
                if (pageCrossed) used++;
                break;
            case LDY:
                resolve(mode);
                y = read(addr);
                setZN(y);
                if (pageCrossed) used++;
                break;
            case EOR:
                resolve(mode);
                a ^= read(addr);
                setZN(a);
                if (pageCrossed) used++;
                break;
            case AND:
                resolve(mode);
                a &= read(addr);
                setZN(a);
                if (pageCrossed) used++;
                break;
            case ORA:
                resolve(mode);
                a |= read(addr);
                setZN(a);
                if (pageCrossed) used++;
                break;
            case ADC:
                resolve(mode);
                adc(read(addr));
                if (pageCrossed) used++;
                break;
            case SBC:
                resolve(mode);
                sbc(read(addr));
                if (pageCrossed) used++;
                break;
            case CMP:
                resolve(mode);
                compare(a, read(addr));
                if (pageCrossed) used++;
                break;
            case CPX:
                resolve(mode);
                compare(x, read(addr));
                if (pageCrossed) used++;
                break;
            case CPY:
                resolve(mode);
                compare(y, read(addr));
                if (pageCrossed) used++;
                break;
            case BIT:
                resolve(mode);
                bit(read(addr));
                if (pageCrossed) used++;
                break;
            case LAX:
                resolve(mode);
                a = x = read(addr);
                setZN(a);
                if (pageCrossed) used++;
                break;
            case LAS:
                resolve(mode);
                a = x = sp = (read(addr) & sp);
                setZN(a);
                if (pageCrossed) used++;
                break;
            case ANC:
                resolve(mode);
                a &= read(addr);
                setZN(a);
                p = (p & ~C) | ((a & 0x80) != 0 ? C : 0);
                break;
            case ALR:
                resolve(mode);
                a &= read(addr);
                p = (p & ~C) | (a & 1);
                a = (a >>> 1) & 0xFF;
                setZN(a);
                break;
            case ARR:
                resolve(mode);
                arr(read(addr));
                break;
            case AXS:
                resolve(mode);
                axs(read(addr));
                break;
            case XAA:
                resolve(mode);
                a = ((a | magic) & x) & read(addr);
                setZN(a);
                break;
            case NOP:
                if (mode != IMP) {
                    resolve(mode);
                    read(addr); // dummy read, keeps side effects realistic
                    if (pageCrossed) used++;
                }
                break;

            // ---- stores (never take a page penalty on ABX/ABY/IZY: the fixed cost is in the table) ----
            case STA:
                resolve(mode);
                write(addr, a);
                break;
            case STX:
                resolve(mode);
                write(addr, x);
                break;
            case STY:
                resolve(mode);
                write(addr, y);
                break;
            case SAX:
                resolve(mode);
                write(addr, a & x);
                break;
            case AHX:
                resolve(mode);
                storeUnstable(a & x);
                break;
            case SHX:
                resolve(mode);
                storeUnstable(x);
                break;
            case SHY:
                resolve(mode);
                storeUnstable(y);
                break;
            case TAS:
                resolve(mode);
                sp = a & x;
                storeUnstable(sp);
                break;

            // ---- read-modify-write (always 1 extra cycle on indexed modes) ----
            case ASL:
                rmw(mode, 0);
                break;
            case LSR:
                rmw(mode, 1);
                break;
            case ROL:
                rmw(mode, 2);
                break;
            case ROR:
                rmw(mode, 3);
                break;
            case INC:
                rmw(mode, 4);
                break;
            case DEC:
                rmw(mode, 5);
                break;
            case SLO:
                rmw(mode, 6);
                break;
            case RLA:
                rmw(mode, 7);
                break;
            case SRE:
                rmw(mode, 8);
                break;
            case RRA:
                rmw(mode, 9);
                break;
            case DCP:
                rmw(mode, 10);
                break;
            case ISC:
                rmw(mode, 11);
                break;

            // ---- branches ----
            case BPL:
            case BMI:
            case BVC:
            case BVS:
            case BCC:
            case BCS:
            case BNE:
            case BEQ: {
                int offset = read(pc++);
                if (offset >= 0x80) {
                    offset -= 0x100;
                }
                if (branchTaken(op)) {
                    int target = (pc + offset) & 0xFFFF;
                    used++;
                    if ((target & 0xFF00) != (pc & 0xFF00)) {
                        used++;
                    }
                    pc = target;
                }
                break;
            }

            // ---- jumps / calls ----
            case JMP:
                if (mode == ABS) {
                    pc = read16(pc);
                } else {
                    int ptr = read16(pc);
                    // 6502 page-wrap bug: the high byte is fetched within the same page
                    int high = read((ptr & 0xFF00) | ((ptr + 1) & 0xFF));
                    pc = (read(ptr) | (high << 8)) & 0xFFFF;
                }
                break;
            case JSR: {
                int target = read16(pc);
                pc += 2;
                int ret = pc - 1; // 6502 pushes the address of the last operand byte
                push((ret >> 8) & 0xFF);
                push(ret & 0xFF);
                pc = target;
                break;
            }
            case RTS:
                pc = (pull() | (pull() << 8)) & 0xFFFF;
                pc = (pc + 1) & 0xFFFF;
                break;
            case RTI:
                p = (pull() & ~B) | U;
                pc = (pull() | (pull() << 8)) & 0xFFFF;
                break;
            case BRK:
                pc++; // skip the padding byte
                push((pc >> 8) & 0xFF);
                push(pc & 0xFF);
                push(p | B | U);
                p |= I;
                pc = read16(0xFFFE);
                break;

            // ---- stack ----
            case PHA:
                push(a);
                break;
            case PHP:
                push(p | B | U);
                break;
            case PLA:
                a = pull();
                setZN(a);
                break;
            case PLP:
                p = (pull() & ~B) | U;
                break;

            // ---- implied ----
            case CLC:
                p &= ~C;
                break;
            case SEC:
                p |= C;
                break;
            case CLI:
                p &= ~I;
                break;
            case SEI:
                p |= I;
                break;
            case CLD:
                p &= ~D;
                break;
            case SED:
                p |= D;
                break;
            case CLV:
                p &= ~V;
                break;
            case TAX:
                x = a;
                setZN(x);
                break;
            case TAY:
                y = a;
                setZN(y);
                break;
            case TXA:
                a = x;
                setZN(a);
                break;
            case TYA:
                a = y;
                setZN(a);
                break;
            case TSX:
                x = sp;
                setZN(x);
                break;
            case TXS:
                sp = x;
                break;
            case DEX:
                x = (x - 1) & 0xFF;
                setZN(x);
                break;
            case DEY:
                y = (y - 1) & 0xFF;
                setZN(y);
                break;
            case INX:
                x = (x + 1) & 0xFF;
                setZN(x);
                break;
            case INY:
                y = (y + 1) & 0xFF;
                setZN(y);
                break;
            case KIL:
                jammed = true;
                break;
            default:
                throw new IllegalStateException("unhandled opcode " + opcode);
        }

        cycles += used;
        return used;
    }

    private boolean branchTaken(int op) {
        switch (op) {
            case BPL:
                return (p & N) == 0;
            case BMI:
                return (p & N) != 0;
            case BVC:
                return (p & V) == 0;
            case BVS:
                return (p & V) != 0;
            case BCC:
                return (p & C) == 0;
            case BCS:
                return (p & C) != 0;
            case BNE:
                return (p & Z) == 0;
            case BEQ:
                return (p & Z) != 0;
            default:
                return false;
        }
    }

    /** mode != ACC reads/writes memory at the resolved address. */
    private void rmw(int mode, int operation) {
        int value;
        if (mode == ACC) {
            value = a;
        } else {
            resolve(mode);
            value = read(addr);
        }

        int result;
        switch (operation) {
            case 0: // ASL
                p = (p & ~C) | ((value >> 7) & 1);
                result = (value << 1) & 0xFF;
                break;
            case 1: // LSR
                p = (p & ~C) | (value & 1);
                result = (value >>> 1) & 0xFF;
                break;
            case 2: // ROL
                result = ((value << 1) | (p & C)) & 0xFF;
                p = (p & ~C) | ((value >> 7) & 1);
                break;
            case 3: // ROR
                result = ((value >>> 1) | ((p & C) << 7)) & 0xFF;
                p = (p & ~C) | (value & 1);
                break;
            case 4: // INC
                result = (value + 1) & 0xFF;
                break;
            case 5: // DEC
                result = (value - 1) & 0xFF;
                break;
            case 6: // SLO
                p = (p & ~C) | ((value >> 7) & 1);
                result = (value << 1) & 0xFF;
                a |= result;
                setZN(a);
                break;
            case 7: // RLA
                result = ((value << 1) | (p & C)) & 0xFF;
                p = (p & ~C) | ((value >> 7) & 1);
                a &= result;
                setZN(a);
                break;
            case 8: // SRE
                p = (p & ~C) | (value & 1);
                result = (value >>> 1) & 0xFF;
                a ^= result;
                setZN(a);
                break;
            case 9: // RRA
                result = ((value >>> 1) | ((p & C) << 7)) & 0xFF;
                p = (p & ~C) | (value & 1);
                adc(result);
                break;
            case 10: // DCP
                result = (value - 1) & 0xFF;
                compare(a, result);
                break;
            default: // 11 ISC
                result = (value + 1) & 0xFF;
                sbc(result);
                break;
        }

        // ASL/LSR/ROL/ROR/INC/DEC set N and Z from the stored value. The undocumented
        // accumulator-modifying RMW ops (SLO/RLA/SRE/RRA/DCP/ISC) set them from the A result,
        // which the cases above already did.
        if (operation <= 5) {
            setZN(result);
        }

        if (mode == ACC) {
            a = result;
        } else {
            write(addr, result);
        }
    }

    /** Binary and NMOS decimal-mode ADC. */
    private void adc(int value) {
        int carry = p & C;
        int sum = a + value + carry;
        int binaryLow = sum & 0xFF;

        // V and Z behave as in binary mode even when D is set (NMOS 6502)
        p = (p & ~V) | (((~(a ^ value) & (a ^ binaryLow)) & 0x80) != 0 ? V : 0);
        p = (p & ~Z) | (binaryLow == 0 ? Z : 0);

        int result;
        if (decimalArithmetic && (p & D) != 0) {
            int lo = (a & 0x0F) + (value & 0x0F) + carry;
            int hi = (a >> 4) + (value >> 4);
            if (lo > 9) {
                lo += 6;
                hi++;
            }
            if (hi > 9) {
                hi += 6;
            }
            p = (p & ~C) | (hi > 0x0F ? C : 0);
            result = ((hi << 4) | (lo & 0x0F)) & 0xFF;
        } else {
            p = (p & ~C) | (sum > 0xFF ? C : 0);
            result = binaryLow;
        }
        a = result;
        p = (p & ~N) | (result & N);
    }

    /** Binary and NMOS decimal-mode SBC. */
    private void sbc(int value) {
        int borrow = 1 - (p & C);
        int diff = a - value - borrow;
        int binaryLow = diff & 0xFF;

        p = (p & ~V) | ((((a ^ value) & (a ^ binaryLow)) & 0x80) != 0 ? V : 0);
        p = (p & ~Z) | (binaryLow == 0 ? Z : 0);

        int result;
        if (decimalArithmetic && (p & D) != 0) {
            int lo = (a & 0x0F) - (value & 0x0F) - borrow;
            int hi = (a >> 4) - (value >> 4);
            if (lo < 0) {
                lo -= 6;
                hi--;
            }
            if (hi < 0) {
                hi -= 6;
            }
            result = ((hi << 4) | (lo & 0x0F)) & 0xFF;
        } else {
            result = binaryLow;
        }
        p = (p & ~C) | (diff >= 0 ? C : 0);
        a = result;
        p = (p & ~N) | (result & N);
    }

    private void compare(int reg, int value) {
        p = (p & ~C) | (reg >= value ? C : 0);
        setZN((reg - value) & 0xFF);
    }

    private void bit(int value) {
        p = (p & ~Z) | ((a & value) == 0 ? Z : 0);
        p = (p & ~N) | (value & N);
        p = (p & ~V) | (value & V);
    }

    private void arr(int value) {
        a &= value;
        int rotated = ((a >>> 1) | ((p & C) << 7)) & 0xFF;
        setZN(rotated);
        p = (p & ~C) | ((rotated >> 6) & 1);
        p = (p & ~V) | (((rotated ^ (rotated << 1)) & 0x40) != 0 ? V : 0);
        if (decimalArithmetic && (p & D) != 0) {
            // decimal fix-up of the rotated result
            int lo = rotated & 0x0F;
            int hi = rotated >> 4;
            if (lo > 9) {
                lo = (lo + 6) & 0x0F;
                hi = (hi + 1) & 0x0F;
            }
            if (hi > 9) {
                hi = (hi + 6) & 0x0F;
            }
            a = ((hi << 4) | lo) & 0xFF;
        } else {
            a = rotated;
        }
    }

    private void axs(int value) {
        int t = (a & x) - value;
        p = (p & ~C) | ((t & 0x100) == 0 ? C : 0);
        x = t & 0xFF;
        setZN(x);
    }

    private void resolve(int mode) {
        switch (mode) {
            case IMM:
                addr = pc++;
                break;
            case ZP:
                addr = read(pc++);
                break;
            case ZPX:
                addr = (read(pc++) + x) & 0xFF;
                break;
            case ZPY:
                addr = (read(pc++) + y) & 0xFF;
                break;
            case ABS:
                addr = read16(pc);
                pc += 2;
                break;
            case ABX: {
                int base = read16(pc);
                pc += 2;
                baseAddr = base;
                addr = (base + x) & 0xFFFF;
                pageCrossed = (base & 0xFF00) != (addr & 0xFF00);
                break;
            }
            case ABY: {
                int base = read16(pc);
                pc += 2;
                baseAddr = base;
                addr = (base + y) & 0xFFFF;
                pageCrossed = (base & 0xFF00) != (addr & 0xFF00);
                break;
            }
            case IND: {
                int ptr = read16(pc);
                pc += 2;
                int high = read((ptr & 0xFF00) | ((ptr + 1) & 0xFF));
                addr = (read(ptr) | (high << 8)) & 0xFFFF;
                break;
            }
            case IZX: {
                int zp = (read(pc++) + x) & 0xFF;
                addr = (read(zp) | (read((zp + 1) & 0xFF) << 8)) & 0xFFFF;
                break;
            }
            case IZY: {
                int zp = read(pc++);
                int base = (read(zp) | (read((zp + 1) & 0xFF) << 8)) & 0xFFFF;
                baseAddr = base;
                addr = (base + y) & 0xFFFF;
                pageCrossed = (base & 0xFF00) != (addr & 0xFF00);
                break;
            }
            case IMP:
            case ACC:
                addr = 0;
                break;
            default:
                throw new IllegalStateException("bad mode " + mode);
        }
    }

    private void interrupt(int vector, boolean brk) {
        push((pc >> 8) & 0xFF);
        push(pc & 0xFF);
        push(brk ? (p | B | U) : ((p & ~B) | U));
        p |= I;
        pc = read16(vector);
        cycles += 7;
    }

    /**
     * The unstable "store masked with (high byte + 1)" documented opcodes (SHX/SHY/AHX/TAS).
     * On a page crossing the effective address itself is partially replaced by the stored
     * value, which is what real NMOS 6502s do and what blargg's abs_xy test checks.
     */
    private void storeUnstable(int register) {
        int mask = ((baseAddr >>> 8) + 1) & 0xFF;
        int value = register & mask;
        int target = addr;
        if ((addr & 0xFF00) != (baseAddr & 0xFF00)) {
            target = ((value << 8) | (addr & 0xFF)) & 0xFFFF;
        }
        write(target, value);
    }

    private void setZN(int value) {
        p = (p & ~(Z | N)) | (value == 0 ? Z : 0) | (value & N);
    }

    private int read(int address) {
        return bus.read(address & 0xFFFF) & 0xFF;
    }

    private void write(int address, int value) {
        bus.write(address & 0xFFFF, value & 0xFF);
    }

    private int read16(int address) {
        return read(address) | (read(address + 1) << 8);
    }

    private void push(int value) {
        write(0x0100 | sp, value);
        sp = (sp - 1) & 0xFF;
    }

    private int pull() {
        sp = (sp + 1) & 0xFF;
        return read(0x0100 | sp);
    }

    // ---- state (save states) ----

    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(a);
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(sp);
        out.writeInt(pc);
        out.writeInt(p);
        out.writeLong(cycles);
        out.writeBoolean(irqLine);
        out.writeBoolean(nmiPending);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        a = in.readInt();
        x = in.readInt();
        y = in.readInt();
        sp = in.readInt();
        pc = in.readInt();
        p = in.readInt();
        cycles = in.readLong();
        irqLine = in.readBoolean();
        nmiPending = in.readBoolean();
    }

    /** Human readable single-line dump, used by the test harness for nestest comparison. */
    public String dump() {
        return String.format("%04X A:%02X X:%02X Y:%02X P:%02X SP:%02X CYC:%d",
                pc, a, x, y, p, sp, cycles);
    }
}
