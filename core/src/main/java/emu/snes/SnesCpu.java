package emu.snes;

/**
 * 65C816 interpreter: the SNES CPU. Full opcode map including the 16-bit
 * accumulator / index modes, decimal mode and the block-move instructions.
 */
public final class SnesCpu {

    public interface Bus {
        int read(int address);

        void write(int address, int value);
    }

    public static final int N = 0x80;
    public static final int V = 0x40;
    public static final int M = 0x20;
    public static final int X = 0x10;
    public static final int D = 0x08;
    public static final int I = 0x04;
    public static final int Z = 0x02;
    public static final int C = 0x01;

    private final Bus bus;

    public int a;
    public int x;
    public int y;
    public int sp = 0x01FF;
    public int d;
    public int dbr;
    public int pbr;
    public int pc;
    public int p = 0x34;   // I | M | X set, the state after reset
    public boolean e = true;

    public long cycles;
    public int lastOpcode;

    public SnesCpu(Bus bus) {
        this.bus = bus;
    }

    public void reset() {
        a = 0;
        x = 0;
        y = 0;
        sp = 0x01FF;
        d = 0;
        dbr = 0;
        pbr = 0;
        p = 0x34;
        e = true;
        cycles = 0;
        // reset vector
        pc = bus.read(0xFFFC) | (bus.read(0xFFFD) << 8);
    }

    // ---------------------------------------------------------------- flags

    private boolean m() {
        return (p & M) != 0;
    }

    private boolean xf() {
        return (p & X) != 0;
    }

    private boolean flag(int bit) {
        return (p & bit) != 0;
    }

    private void setFlag(int bit, boolean on) {
        if (on) {
            p |= bit;
        } else {
            p &= ~bit;
        }
    }

    private int read(int address) {
        return bus.read(address & 0xFFFFFF);
    }

    private void write(int address, int value) {
        bus.write(address & 0xFFFFFF, value & 0xFF);
    }

    private int read16(int address) {
        return read(address) | (read(address + 1) << 8);
    }

    private void write16(int address, int value) {
        write(address, value & 0xFF);
        write(address + 1, (value >>> 8) & 0xFF);
    }

    /** Operand fetch honouring the M flag. */
    private int readA(int address) {
        return m() ? read(address) : read16(address);
    }

    private void writeA(int address, int value) {
        if (m()) {
            write(address, value & 0xFF);
        } else {
            write16(address, value);
        }
    }

    private int fetch8() {
        int value = bus.read((pbr << 16) | pc);
        pc = (pc + 1) & 0xFFFF;
        return value;
    }

    private int fetch16() {
        int low = fetch8();
        return low | (fetch8() << 8);
    }

    private int fetch24() {
        int low = fetch16();
        return low | (fetch8() << 16);
    }

    // ------------------------------------------------------- effective addr

    private int dpAddr() {
        return (d + fetch8()) & 0xFFFF;
    }

    private int dpXAddr() {
        return (d + fetch8() + x) & 0xFFFF;
    }

    private int dpYAddr() {
        return (d + fetch8() + y) & 0xFFFF;
    }

    private int absAddr() {
        return fetch16();
    }

    private int absXAddr() {
        return (fetch16() + x) & 0xFFFF;
    }

    private int absYAddr() {
        return (fetch16() + y) & 0xFFFF;
    }

    private int longAddr() {
        return fetch24();
    }

    private int longXAddr() {
        return (fetch24() + x) & 0xFFFFFF;
    }

    private int indDpXAddr() {
        int pointer = (d + fetch8() + x) & 0xFFFF;
        return read16(pointer);
    }

    private int indDpAddr() {
        int pointer = (d + fetch8()) & 0xFFFF;
        return read16(pointer);
    }

    private int indDpYAddr() {
        int pointer = (d + fetch8()) & 0xFFFF;
        return ((read16(pointer) + y) & 0xFFFF) | (dbr << 16);
    }

    private int indLongAddr() {
        int pointer = (d + fetch8()) & 0xFFFF;
        return read(pointer) | (read(pointer + 1) << 8) | (read(pointer + 2) << 16);
    }

    private int indLongYAddr() {
        int pointer = (d + fetch8()) & 0xFFFF;
        int base = read(pointer) | (read(pointer + 1) << 8) | (read(pointer + 2) << 16);
        return (base + y) & 0xFFFFFF;
    }

    private int srAddr() {
        return 0x0100 | ((sp + fetch8()) & 0xFF);
    }

    private int srYAddr() {
        return 0x0100 | ((sp + fetch8() + y) & 0xFF);
    }

    private int absIndXAddr() {
        int pointer = (fetch16() + x) & 0xFFFF;
        return pointer | (pbr << 16);
    }

    // ------------------------------------------------------------ stack ops

    private void push8(int value) {
        write(0x0100 | sp, value);
        sp = (sp - 1) & 0xFFFF;
    }

    private int pull8() {
        sp = (sp + 1) & 0xFFFF;
        return read(0x0100 | sp);
    }

    private void push16(int value) {
        push8((value >>> 8) & 0xFF);
        push8(value & 0xFF);
    }

    private int pull16() {
        int low = pull8();
        return low | (pull8() << 8);
    }

    // ----------------------------------------------------------- flag utils

    private int width() {
        return m() ? 0xFF : 0xFFFF;
    }

    private int signBit() {
        return m() ? 0x80 : 0x8000;
    }

    private void setZN(int value) {
        int mask = signBit();
        setFlag(Z, (value & width()) == 0);
        setFlag(N, (value & mask) != 0);
    }

    private void setIndexZN(int value) {
        int mask = xf() ? 0x80 : 0x8000;
        setFlag(Z, (value & (xf() ? 0xFF : 0xFFFF)) == 0);
        setFlag(N, (value & mask) != 0);
    }

    private void compare(int reg, int value) {
        int diff = (reg & width()) - (value & width());
        setFlag(C, diff >= 0);
        setZN(diff);
    }

    // ----------------------------------------------------------------- ADC

    private void adc(int value) {
        int carry = flag(C) ? 1 : 0;
        if (flag(D) && !e) {
            int lowA = a & 0x0F;
            int lowV = value & 0x0F;
            int lowSum = lowA + lowV + carry;
            int highA = (a & 0xFF0) >> 4;
            int highV = (value & 0xFF0) >> 4;
            int highSum = highA + highV + (lowSum > 9 ? 1 : 0);
            if (lowSum > 9) {
                lowSum += 6;
            }
            if (highSum > 9) {
                highSum += 6;
            }
            int result = ((highSum << 4) | (lowSum & 0x0F)) & 0xFF;
            setFlag(C, highSum > 0x0F);
            setFlag(V, (((a ^ result) & 0x80) != 0) && (((a ^ value) & 0x80) == 0));
            a = (a & 0xFF00) | result;
            setZN(a);
            return;
        }
        int sum = (a & width()) + (value & width()) + carry;
        boolean overflow = ((a ^ sum) & (value ^ sum) & signBit()) != 0;
        setFlag(C, sum > width());
        setFlag(V, overflow);
        a = m() ? ((a & 0xFF00) | (sum & 0xFF)) : (sum & 0xFFFF);
        setZN(a);
    }

    private void sbc(int value) {
        int borrow = flag(C) ? 0 : 1;
        if (flag(D) && !e) {
            int lowA = a & 0x0F;
            int lowV = value & 0x0F;
            int lowDiff = lowA - lowV - borrow;
            int highDiff = ((a & 0xFF0) >> 4) - ((value & 0xFF0) >> 4);
            if (lowDiff < 0) {
                lowDiff -= 6;
                highDiff--;
            }
            if (highDiff < 0) {
                highDiff -= 6;
            }
            int result = ((highDiff << 4) | (lowDiff & 0x0F)) & 0xFF;
            setFlag(C, highDiff >= 0);
            a = (a & 0xFF00) | result;
            setZN(a);
            return;
        }
        int diff = (a & width()) - (value & width()) - borrow;
        boolean overflow = ((a ^ value) & (a ^ diff) & signBit()) != 0;
        setFlag(C, diff >= 0);
        setFlag(V, overflow);
        a = m() ? ((a & 0xFF00) | (diff & 0xFF)) : (diff & 0xFFFF);
        setZN(a);
    }

    // ------------------------------------------------------------- shifting

    private int aslValue(int value) {
        int mask = m() ? 0xFF : 0xFFFF;
        value &= mask;
        setFlag(C, (value & signBit()) != 0);
        int result = (value << 1) & mask;
        setZN(result);
        return result;
    }

    private int lsrValue(int value) {
        int mask = m() ? 0xFF : 0xFFFF;
        value &= mask;
        setFlag(C, (value & 1) != 0);
        int result = (value >>> 1) & mask;
        setZN(result);
        return result;
    }

    private int rolValue(int value) {
        int mask = m() ? 0xFF : 0xFFFF;
        boolean carry = flag(C);
        value &= mask;
        setFlag(C, (value & signBit()) != 0);
        int result = ((value << 1) | (carry ? 1 : 0)) & mask;
        setZN(result);
        return result;
    }

    private int rorValue(int value) {
        int mask = m() ? 0xFF : 0xFFFF;
        boolean carry = flag(C);
        value &= mask;
        setFlag(C, (value & 1) != 0);
        int result = (value >>> 1) | (carry ? signBit() : 0);
        setZN(result & mask);
        return result & mask;
    }

    // --------------------------------------------------------------- branch

    private void branch(boolean taken) {
        int offset = fetch8();
        if (taken) {
            if (offset >= 0x80) {
                offset -= 0x100;
            }
            pc = (pc + offset) & 0xFFFF;
            cycles += 2;
        }
    }

    /** Pushes the interrupt frame (NMI/IRQ) and clears the B flag in the pushed P. */
    public void pushInstructionStateForInterrupt() {
        push8(pbr);
        push16(pc);
        push8(p & ~0x10);
    }

    // ----------------------------------------------------------------- step

    /** Executes one instruction and returns the number of cycles it took. */
    public int step() {
        long before = cycles;
        int opcode = fetch8();
        lastOpcode = opcode;
        cycles += 2;
        execute(opcode);
        return (int) Math.max(1, cycles - before);
    }

    private void execute(int opcode) {
        switch (opcode) {
            // ---- ORA
            case 0x01: a = m() ? ((a & 0xFF00) | ((a | read(indDpXAddr())) & 0xFF)) : ((a | read16(indDpXAddr())) & 0xFFFF); setZN(a); break;
            case 0x03: { int v = read(srAddr()); a = m() ? ((a & 0xFF00) | ((a | v) & 0xFF)) : ((a | v) & 0xFFFF); setZN(a); break; }
            case 0x05: { int v = readA(dpAddr()); a = m() ? ((a & 0xFF00) | ((a | v) & 0xFF)) : ((a | v) & 0xFFFF); setZN(a); break; }
            case 0x07: { int v = readA(indLongAddr()); a |= v; a &= width(); setZN(a); break; }
            case 0x09: { int v = m() ? fetch8() : fetch16(); a |= v; a &= width(); setZN(a); break; }
            case 0x0D: { int v = readA(absAddr()); a |= v; a &= width(); setZN(a); break; }
            case 0x0F: { int v = readA(longAddr()); a |= v; a &= width(); setZN(a); break; }
            case 0x11: { int v = readA(indDpYAddr()); a |= v; a &= width(); setZN(a); break; }
            case 0x12: { int v = readA(indDpAddr()); a |= v; a &= width(); setZN(a); break; }
            case 0x13: { int v = readA(srYAddr()); a |= v; a &= width(); setZN(a); break; }
            case 0x15: { int v = readA(dpXAddr()); a |= v; a &= width(); setZN(a); break; }
            case 0x17: { int v = readA(indLongYAddr()); a |= v; a &= width(); setZN(a); break; }
            case 0x19: { int v = readA(absYAddr()); a |= v; a &= width(); setZN(a); break; }
            case 0x1D: { int v = readA(absXAddr()); a |= v; a &= width(); setZN(a); break; }
            case 0x1F: { int v = readA(longXAddr()); a |= v; a &= width(); setZN(a); break; }

            // ---- AND
            case 0x21: { int v = readA(indDpXAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x23: { int v = readA(srAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x25: { int v = readA(dpAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x27: { int v = readA(indLongAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x29: { int v = m() ? fetch8() : fetch16(); a &= v; a &= width(); setZN(a); break; }
            case 0x2D: { int v = readA(absAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x2F: { int v = readA(longAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x31: { int v = readA(indDpYAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x32: { int v = readA(indDpAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x33: { int v = readA(srYAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x35: { int v = readA(dpXAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x37: { int v = readA(indLongYAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x39: { int v = readA(absYAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x3D: { int v = readA(absXAddr()); a &= v; a &= width(); setZN(a); break; }
            case 0x3F: { int v = readA(longXAddr()); a &= v; a &= width(); setZN(a); break; }

            // ---- EOR
            case 0x41: { int v = readA(indDpXAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x43: { int v = readA(srAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x45: { int v = readA(dpAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x47: { int v = readA(indLongAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x49: { int v = m() ? fetch8() : fetch16(); a ^= v; a &= width(); setZN(a); break; }
            case 0x4D: { int v = readA(absAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x4F: { int v = readA(longAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x51: { int v = readA(indDpYAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x52: { int v = readA(indDpAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x53: { int v = readA(srYAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x55: { int v = readA(dpXAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x57: { int v = readA(indLongYAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x59: { int v = readA(absYAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x5D: { int v = readA(absXAddr()); a ^= v; a &= width(); setZN(a); break; }
            case 0x5F: { int v = readA(longXAddr()); a ^= v; a &= width(); setZN(a); break; }

            // ---- ADC
            case 0x61: adc(readA(indDpXAddr())); break;
            case 0x63: adc(readA(srAddr())); break;
            case 0x65: adc(readA(dpAddr())); break;
            case 0x67: adc(readA(indLongAddr())); break;
            case 0x69: adc(m() ? fetch8() : fetch16()); break;
            case 0x6D: adc(readA(absAddr())); break;
            case 0x6F: adc(readA(longAddr())); break;
            case 0x71: adc(readA(indDpYAddr())); break;
            case 0x72: adc(readA(indDpAddr())); break;
            case 0x73: adc(readA(srYAddr())); break;
            case 0x75: adc(readA(dpXAddr())); break;
            case 0x77: adc(readA(indLongYAddr())); break;
            case 0x79: adc(readA(absYAddr())); break;
            case 0x7D: adc(readA(absXAddr())); break;
            case 0x7F: adc(readA(longXAddr())); break;

            // ---- SBC
            case 0xE1: sbc(readA(indDpXAddr())); break;
            case 0xE3: sbc(readA(srAddr())); break;
            case 0xE5: sbc(readA(dpAddr())); break;
            case 0xE7: sbc(readA(indLongAddr())); break;
            case 0xE9: sbc(m() ? fetch8() : fetch16()); break;
            case 0xED: sbc(readA(absAddr())); break;
            case 0xEF: sbc(readA(longAddr())); break;
            case 0xF1: sbc(readA(indDpYAddr())); break;
            case 0xF2: sbc(readA(indDpAddr())); break;
            case 0xF3: sbc(readA(srYAddr())); break;
            case 0xF5: sbc(readA(dpXAddr())); break;
            case 0xF7: sbc(readA(indLongYAddr())); break;
            case 0xF9: sbc(readA(absYAddr())); break;
            case 0xFD: sbc(readA(absXAddr())); break;
            case 0xFF: sbc(readA(longXAddr())); break;

            // ---- CMP
            case 0xC1: compare(a, readA(indDpXAddr())); break;
            case 0xC3: compare(a, readA(srAddr())); break;
            case 0xC5: compare(a, readA(dpAddr())); break;
            case 0xC7: compare(a, readA(indLongAddr())); break;
            case 0xC9: compare(a, m() ? fetch8() : fetch16()); break;
            case 0xCD: compare(a, readA(absAddr())); break;
            case 0xCF: compare(a, readA(longAddr())); break;
            case 0xD1: compare(a, readA(indDpYAddr())); break;
            case 0xD2: compare(a, readA(indDpAddr())); break;
            case 0xD3: compare(a, readA(srYAddr())); break;
            case 0xD5: compare(a, readA(dpXAddr())); break;
            case 0xD7: compare(a, readA(indLongYAddr())); break;
            case 0xD9: compare(a, readA(absYAddr())); break;
            case 0xDD: compare(a, readA(absXAddr())); break;
            case 0xDF: compare(a, readA(longXAddr())); break;

            // ---- CPX / CPY
            case 0xE0: { int v = xf() ? fetch8() : fetch16(); int diff = (x & (xf() ? 0xFF : 0xFFFF)) - v; setFlag(C, diff >= 0); setIndexZN(diff); break; }
            case 0xE4: { int v = xf() ? read(dpAddr()) : read16(dpAddr()); int diff = (x & (xf() ? 0xFF : 0xFFFF)) - v; setFlag(C, diff >= 0); setIndexZN(diff); break; }
            case 0xEC: { int v = xf() ? read(absAddr()) : read16(absAddr()); int diff = (x & (xf() ? 0xFF : 0xFFFF)) - v; setFlag(C, diff >= 0); setIndexZN(diff); break; }
            case 0xC0: { int v = xf() ? fetch8() : fetch16(); int diff = (y & (xf() ? 0xFF : 0xFFFF)) - v; setFlag(C, diff >= 0); setIndexZN(diff); break; }
            case 0xC4: { int v = xf() ? read(dpAddr()) : read16(dpAddr()); int diff = (y & (xf() ? 0xFF : 0xFFFF)) - v; setFlag(C, diff >= 0); setIndexZN(diff); break; }
            case 0xCC: { int v = xf() ? read(absAddr()) : read16(absAddr()); int diff = (y & (xf() ? 0xFF : 0xFFFF)) - v; setFlag(C, diff >= 0); setIndexZN(diff); break; }

            // ---- BIT
            case 0x24: case 0x2C: case 0x34: case 0x3C: {
                int address = opcode == 0x24 ? dpAddr() : opcode == 0x34 ? dpXAddr()
                        : opcode == 0x2C ? absAddr() : absXAddr();
                int v = readA(address);
                setFlag(Z, (a & v & width()) == 0);
                if (m()) {
                    setFlag(N, (v & 0x80) != 0);
                    setFlag(V, (v & 0x40) != 0);
                } else {
                    setFlag(N, (v & 0x8000) != 0);
                    setFlag(V, (v & 0x4000) != 0);
                }
                break;
            }
            case 0x89: {
                int v = m() ? fetch8() : fetch16();
                setFlag(Z, (a & v & width()) == 0);
                break;
            }

            // ---- LDA
            case 0xA1: a = readA(indDpXAddr()); a &= width(); setZN(a); break;
            case 0xA3: a = readA(srAddr()); a &= width(); setZN(a); break;
            case 0xA5: a = readA(dpAddr()); a &= width(); setZN(a); break;
            case 0xA7: a = readA(indLongAddr()); a &= width(); setZN(a); break;
            case 0xA9: a = m() ? ((a & 0xFF00) | fetch8()) : fetch16(); setZN(a); break;
            case 0xAD: a = readA(absAddr()); a &= width(); setZN(a); break;
            case 0xAF: a = readA(longAddr()); a &= width(); setZN(a); break;
            case 0xB1: a = readA(indDpYAddr()); a &= width(); setZN(a); break;
            case 0xB2: a = readA(indDpAddr()); a &= width(); setZN(a); break;
            case 0xB3: a = readA(srYAddr()); a &= width(); setZN(a); break;
            case 0xB5: a = readA(dpXAddr()); a &= width(); setZN(a); break;
            case 0xB7: a = readA(indLongYAddr()); a &= width(); setZN(a); break;
            case 0xB9: a = readA(absYAddr()); a &= width(); setZN(a); break;
            case 0xBD: a = readA(absXAddr()); a &= width(); setZN(a); break;
            case 0xBF: a = readA(longXAddr()); a &= width(); setZN(a); break;

            // ---- LDX / LDY
            case 0xA2: x = xf() ? fetch8() : fetch16(); setIndexZN(x); break;
            case 0xA6: x = xf() ? read(dpAddr()) : read16(dpAddr()); setIndexZN(x); break;
            case 0xAE: x = xf() ? read(absAddr()) : read16(absAddr()); setIndexZN(x); break;
            case 0xB6: x = xf() ? read(dpYAddr()) : read16(dpYAddr()); setIndexZN(x); break;
            case 0xBE: x = xf() ? read(absYAddr()) : read16(absYAddr()); setIndexZN(x); break;
            case 0xA0: y = xf() ? fetch8() : fetch16(); setIndexZN(y); break;
            case 0xA4: y = xf() ? read(dpAddr()) : read16(dpAddr()); setIndexZN(y); break;
            case 0xAC: y = xf() ? read(absAddr()) : read16(absAddr()); setIndexZN(y); break;
            case 0xB4: y = xf() ? read(dpXAddr()) : read16(dpXAddr()); setIndexZN(y); break;
            case 0xBC: y = xf() ? read(absXAddr()) : read16(absXAddr()); setIndexZN(y); break;

            // ---- STA / STX / STY / STZ
            case 0x81: writeA(indDpXAddr(), a); break;
            case 0x83: writeA(srAddr(), a); break;
            case 0x85: writeA(dpAddr(), a); break;
            case 0x87: writeA(indLongAddr(), a); break;
            case 0x8D: writeA(absAddr(), a); break;
            case 0x8F: writeA(longAddr(), a); break;
            case 0x91: writeA(indDpYAddr(), a); break;
            case 0x92: writeA(indDpAddr(), a); break;
            case 0x93: writeA(srYAddr(), a); break;
            case 0x95: writeA(dpXAddr(), a); break;
            case 0x97: writeA(indLongYAddr(), a); break;
            case 0x99: writeA(absYAddr(), a); break;
            case 0x9D: writeA(absXAddr(), a); break;
            case 0x9F: writeA(longXAddr(), a); break;
            case 0x84: if (xf()) write(dpAddr(), y); else write16(dpAddr(), y); break;
            case 0x8C: if (xf()) write(absAddr(), y); else write16(absAddr(), y); break;
            case 0x94: if (xf()) write(dpXAddr(), y); else write16(dpXAddr(), y); break;
            case 0x86: if (xf()) write(dpAddr(), x); else write16(dpAddr(), x); break;
            case 0x8E: if (xf()) write(absAddr(), x); else write16(absAddr(), x); break;
            case 0x96: if (xf()) write(dpYAddr(), x); else write16(dpYAddr(), x); break;
            case 0x64: case 0x74: case 0x9C: case 0x9E: {
                int address = opcode == 0x64 ? dpAddr() : opcode == 0x74 ? dpXAddr()
                        : opcode == 0x9C ? absAddr() : absXAddr();
                writeA(address, 0);
                break;
            }

            // ---- ASL / LSR / ROL / ROR (memory forms)
            case 0x06: { int address = dpAddr(); writeA(address, aslValue(readA(address))); break; }
            case 0x0E: { int address = absAddr(); writeA(address, aslValue(readA(address))); break; }
            case 0x16: { int address = dpXAddr(); writeA(address, aslValue(readA(address))); break; }
            case 0x1E: { int address = absXAddr(); writeA(address, aslValue(readA(address))); break; }
            case 0x46: { int address = dpAddr(); writeA(address, lsrValue(readA(address))); break; }
            case 0x4E: { int address = absAddr(); writeA(address, lsrValue(readA(address))); break; }
            case 0x56: { int address = dpXAddr(); writeA(address, lsrValue(readA(address))); break; }
            case 0x5E: { int address = absXAddr(); writeA(address, lsrValue(readA(address))); break; }
            case 0x26: { int address = dpAddr(); writeA(address, rolValue(readA(address))); break; }
            case 0x2E: { int address = absAddr(); writeA(address, rolValue(readA(address))); break; }
            case 0x36: { int address = dpXAddr(); writeA(address, rolValue(readA(address))); break; }
            case 0x3E: { int address = absXAddr(); writeA(address, rolValue(readA(address))); break; }
            case 0x66: { int address = dpAddr(); writeA(address, rorValue(readA(address))); break; }
            case 0x6E: { int address = absAddr(); writeA(address, rorValue(readA(address))); break; }
            case 0x76: { int address = dpXAddr(); writeA(address, rorValue(readA(address))); break; }
            case 0x7E: { int address = absXAddr(); writeA(address, rorValue(readA(address))); break; }
            case 0x0A: a = aslValue(a); a &= width(); break;
            case 0x4A: a = lsrValue(a); break;
            case 0x2A: a = rolValue(a); break;
            case 0x6A: a = rorValue(a); break;

            // ---- INC / DEC
            case 0x1A: a = (a + 1) & width(); setZN(a); break;
            case 0x3A: a = (a - 1) & width(); setZN(a); break;
            case 0xE8: x = (x + 1) & (xf() ? 0xFF : 0xFFFF); setIndexZN(x); break;
            case 0xCA: x = (x - 1) & (xf() ? 0xFF : 0xFFFF); setIndexZN(x); break;
            case 0xC8: y = (y + 1) & (xf() ? 0xFF : 0xFFFF); setIndexZN(y); break;
            case 0x88: y = (y - 1) & (xf() ? 0xFF : 0xFFFF); setIndexZN(y); break;
            case 0xE6: { int address = dpAddr(); writeA(address, (readA(address) + 1) & width()); setZN(readA(address)); break; }
            case 0xEE: { int address = absAddr(); int v = (readA(address) + 1) & width(); writeA(address, v); setZN(v); break; }
            case 0xF6: { int address = dpXAddr(); int v = (readA(address) + 1) & width(); writeA(address, v); setZN(v); break; }
            case 0xFE: { int address = absXAddr(); int v = (readA(address) + 1) & width(); writeA(address, v); setZN(v); break; }
            case 0xC6: { int address = dpAddr(); int v = (readA(address) - 1) & width(); writeA(address, v); setZN(v); break; }
            case 0xCE: { int address = absAddr(); int v = (readA(address) - 1) & width(); writeA(address, v); setZN(v); break; }
            case 0xD6: { int address = dpXAddr(); int v = (readA(address) - 1) & width(); writeA(address, v); setZN(v); break; }
            case 0xDE: { int address = absXAddr(); int v = (readA(address) - 1) & width(); writeA(address, v); setZN(v); break; }

            // ---- TSB / TRB
            case 0x04: case 0x0C: {
                int address = opcode == 0x04 ? dpAddr() : absAddr();
                int v = readA(address);
                setFlag(Z, (a & v & width()) == 0);
                writeA(address, v | a);
                break;
            }
            case 0x14: case 0x1C: {
                int address = opcode == 0x14 ? dpAddr() : absAddr();
                int v = readA(address);
                setFlag(Z, (a & v & width()) == 0);
                writeA(address, v & ~a);
                break;
            }

            // ---- jumps
            case 0x20: { int target = fetch16(); push16((pc - 1) & 0xFFFF); pc = target; break; }
            case 0x22: { int target = fetch24(); push8(pbr); push16((pc - 1) & 0xFFFF); pbr = (target >>> 16) & 0xFF; pc = target & 0xFFFF; break; }
            case 0x4C: pc = fetch16(); break;
            case 0x5C: { int target = fetch24(); pbr = (target >>> 16) & 0xFF; pc = target & 0xFFFF; break; }
            case 0x6C: { int pointer = fetch16(); pc = read16(pointer); break; }
            case 0x7C: { int pointer = absIndXAddr(); pc = read16(pointer); break; }
            case 0xDC: { int pointer = fetch16(); int target = read(pointer) | (read(pointer + 1) << 8) | (read(pointer + 2) << 16); pbr = (target >>> 16) & 0xFF; pc = target & 0xFFFF; break; }
            case 0xFC: { int pointer = absIndXAddr(); push16((pc - 1) & 0xFFFF); pc = read16(pointer); break; }
            case 0x60: pc = (pull16() + 1) & 0xFFFF; break;
            case 0x6B: pc = (pull16() + 1) & 0xFFFF; pbr = pull8(); break;
            case 0x40: {
                p = pull8();
                pc = pull16();
                pbr = pull8();
                if (e) {
                    p |= M | X;
                }
                break;
            }
            case 0x00: {   // BRK
                if (!e) {
                    push8(pbr);
                }
                push16(pc);
                push8(p | 0x10);
                setFlag(I, true);
                setFlag(D, false);
                pbr = 0;
                pc = read16(0xFFFE);
                break;
            }
            case 0x02: {   // COP
                fetch8();
                push8(pbr);
                push16(pc);
                push8(p);
                setFlag(I, true);
                setFlag(D, false);
                pbr = 0;
                pc = read16(0xFFF4);
                break;
            }
            case 0x42: fetch8(); break;   // WDM
            case 0x44: {  // MVP: src bank, dst bank, A = count-1, moves down
                int dst = fetch8();
                int src = fetch8();
                dbr = dst;
                write((dst << 16) | (y & 0xFFFF), read((src << 16) | (x & 0xFFFF)));
                x = (x - 1) & 0xFFFF;
                y = (y - 1) & 0xFFFF;
                a = (a - 1) & 0xFFFF;
                if (a != 0xFFFF) {
                    pc = (pc - 3) & 0xFFFF;
                }
                break;
            }
            case 0x54: {  // MVN: moves up
                int dst = fetch8();
                int src = fetch8();
                dbr = dst;
                write((dst << 16) | (y & 0xFFFF), read((src << 16) | (x & 0xFFFF)));
                x = (x + 1) & 0xFFFF;
                y = (y + 1) & 0xFFFF;
                a = (a - 1) & 0xFFFF;
                if (a != 0xFFFF) {
                    pc = (pc - 3) & 0xFFFF;
                }
                break;
            }

            // ---- branches
            case 0x80: branch(true); break;
            case 0x10: branch(!flag(N)); break;
            case 0x30: branch(flag(N)); break;
            case 0x50: branch(!flag(V)); break;
            case 0x70: branch(flag(V)); break;
            case 0x90: branch(!flag(C)); break;
            case 0xB0: branch(flag(C)); break;
            case 0xD0: branch(!flag(Z)); break;
            case 0xF0: branch(flag(Z)); break;
            case 0x82: { int offset = fetch16(); pc = (pc + (short) offset) & 0xFFFF; break; }
            case 0x62: { int offset = fetch16(); push16((pc + (short) offset) & 0xFFFF); break; }

            // ---- stack / transfer
            case 0x08: push8(p); break;
            case 0x28: p = pull8(); if (e) { p |= M | X; } break;
            case 0x48: if (m()) push8(a); else push16(a); break;
            case 0x68: if (m()) a = (a & 0xFF00) | pull8(); else a = pull16(); setZN(a); break;
            case 0xDA: if (xf()) push8(x); else push16(x); break;
            case 0xFA: if (xf()) x = pull8(); else x = pull16(); setIndexZN(x); break;
            case 0x5A: if (xf()) push8(y); else push16(y); break;
            case 0x7A: if (xf()) y = pull8(); else y = pull16(); setIndexZN(y); break;
            case 0x8B: push8(dbr); break;
            case 0xAB: dbr = pull8(); setZN(dbr); break;
            case 0x4B: push8(pbr); break;
            case 0x0B: push16(d); break;
            case 0x2B: d = pull16(); setZN(d); break;
            case 0xC2: { int mask = fetch8(); p &= ~mask; if (e) { p |= M | X; } break; }
            case 0xE2: { int mask = fetch8(); p |= mask; break; }
            case 0xAA: x = a & (xf() ? 0xFF : 0xFFFF); setIndexZN(x); break;
            case 0xA8: y = a & (xf() ? 0xFF : 0xFFFF); setIndexZN(y); break;
            case 0x8A: a = m() ? ((a & 0xFF00) | (x & 0xFF)) : (x & 0xFFFF); setZN(a); break;
            case 0x98: a = m() ? ((a & 0xFF00) | (y & 0xFF)) : (y & 0xFFFF); setZN(a); break;
            case 0x9A: sp = x; break;
            case 0xBA: x = sp; setIndexZN(x); break;
            case 0x9B: y = x; setIndexZN(y); break;
            case 0xBB: x = y; setIndexZN(x); break;
            case 0x5B: d = a; setZN(d); break;
            case 0x7B: a = d; setZN(a); break;
            case 0x1B: sp = a; break;
            case 0x3B: a = sp; setZN(a); break;
            case 0xEB: { int low = a & 0xFF; a = ((a & 0xFF00) >> 8) | (low << 8); setZN(a & 0xFF); break; }
            case 0xFB: { boolean carry = flag(C); e = carry; if (e) { p |= M | X; } setFlag(C, carry); break; }

            // ---- flags
            case 0x18: setFlag(C, false); break;
            case 0x38: setFlag(C, true); break;
            case 0x58: setFlag(I, false); break;
            case 0x78: setFlag(I, true); break;
            case 0xB8: setFlag(V, false); break;
            case 0xD8: setFlag(D, false); break;
            case 0xF8: setFlag(D, true); break;

            // ---- PEA / PEI / PER
            case 0xF4: push16(fetch16()); break;
            case 0xD4: { int pointer = (d + fetch8()) & 0xFFFF; push16(read16(pointer)); break; }

            case 0xEA: break;   // NOP

            // ---- WAI / STP: no interrupts modelled, just idle
            case 0xCB: pc = (pc - 1) & 0xFFFF; break;
            case 0xDB: pc = (pc - 1) & 0xFFFF; break;

            default:
                // unused / unimplemented opcode: skip it so the machine keeps running
                break;
        }
        a &= 0xFFFF;
        x &= 0xFFFF;
        y &= 0xFFFF;
        d &= 0xFFFF;
        sp &= 0xFFFF;
    }
}
