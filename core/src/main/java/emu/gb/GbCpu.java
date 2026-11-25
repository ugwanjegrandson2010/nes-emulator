package emu.gb;

/**
 * Sharp LR35902 (Game Boy CPU): complete opcode set including the CB prefix, the hardware cycle
 * counts games and the timing tests depend on, the EI delay and the HALT bug.
 */
public final class GbCpu {

    private static final int Z = 0x80;
    private static final int N = 0x40;
    private static final int H = 0x20;
    private static final int C = 0x10;

    private final GbMachine bus;

    int a, f, b, c, d, e, h, l, sp, pc;
    boolean halted, haltBug, ime, imePending, stopped;

    GbCpu(GbMachine bus) {
        this.bus = bus;
        reset();
    }

    void reset() {
        // register values a real DMG has after its boot ROM has run
        a = 0x01;
        f = 0xB0;
        b = 0x00;
        c = 0x13;
        d = 0x00;
        e = 0xD8;
        h = 0x01;
        l = 0x4D;
        sp = 0xFFFE;
        pc = 0x0100;
        halted = false;
        haltBug = false;
        ime = false;
        imePending = false;
        stopped = false;
    }

    public int af() {
        return (a << 8) | f;
    }

    public String dump() {
        return String.format("A:%02X F:%02X B:%02X C:%02X D:%02X E:%02X H:%02X L:%02X SP:%04X PC:%04X",
                a, f, b, c, d, e, h, l, sp, pc);
    }

    // ------------------------------------------------------------------ stepping

    void step() {
        if (halted) {
            tick(4);
            if ((bus.ie & bus.ifRegister & 0x1F) != 0) {
                halted = false;
            }
            return;
        }
        if (ime && (bus.ie & bus.ifRegister & 0x1F) != 0) {
            dispatchInterrupt();
            return;
        }
        boolean enableAfter = imePending;
        imePending = false;
        execute();
        if (enableAfter) {
            ime = true;
        }
    }

    private void dispatchInterrupt() {
        int pending = bus.ie & bus.ifRegister & 0x1F;
        if (pending == 0) {
            return;
        }
        ime = false;
        imePending = false;
        int bit = Integer.numberOfTrailingZeros(pending);
        bus.ifRegister &= ~(1 << bit);
        push(pc);
        pc = 0x40 + bit * 8;
        tick(20);
    }

    // ------------------------------------------------------------------ helpers

    private void tick(int cycles) {
        bus.tick(cycles);
    }

    private int fetch() {
        if (haltBug) {
            haltBug = false;
            return bus.read(pc) & 0xFF;
        }
        int value = bus.read(pc) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return value;
    }

    private int fetch16() {
        int low = fetch();
        int high = fetch();
        return (high << 8) | low;
    }

    int hl() {
        return (h << 8) | l;
    }

    private int bc() {
        return (b << 8) | c;
    }

    private int de() {
        return (d << 8) | e;
    }

    private void setHl(int value) {
        h = (value >> 8) & 0xFF;
        l = value & 0xFF;
    }

    private void setBc(int value) {
        b = (value >> 8) & 0xFF;
        c = value & 0xFF;
    }

    private void setDe(int value) {
        d = (value >> 8) & 0xFF;
        e = value & 0xFF;
    }

    int reg(int index) {
        switch (index) {
            case 0: return b;
            case 1: return c;
            case 2: return d;
            case 3: return e;
            case 4: return h;
            case 5: return l;
            case 6: return bus.read(hl()) & 0xFF;
            default: return a;
        }
    }

    private void setReg(int index, int value) {
        switch (index) {
            case 0: b = value & 0xFF; break;
            case 1: c = value & 0xFF; break;
            case 2: d = value & 0xFF; break;
            case 3: e = value & 0xFF; break;
            case 4: h = value & 0xFF; break;
            case 5: l = value & 0xFF; break;
            case 6: bus.write(hl(), value & 0xFF); break;
            default: a = value & 0xFF; break;
        }
    }

    private void push(int value) {
        sp = (sp - 1) & 0xFFFF;
        bus.write(sp, (value >> 8) & 0xFF);
        sp = (sp - 1) & 0xFFFF;
        bus.write(sp, value & 0xFF);
    }

    private int pop() {
        int low = bus.read(sp) & 0xFF;
        sp = (sp + 1) & 0xFFFF;
        int high = bus.read(sp) & 0xFF;
        sp = (sp + 1) & 0xFFFF;
        return (high << 8) | low;
    }

    private boolean condition(int index) {
        switch (index) {
            case 0: return (f & Z) == 0;
            case 1: return (f & Z) != 0;
            case 2: return (f & C) == 0;
            default: return (f & C) != 0;
        }
    }

    private int inc8(int value) {
        int result = (value + 1) & 0xFF;
        f = (f & C) | (result == 0 ? Z : 0) | ((value & 0x0F) == 0x0F ? H : 0);
        return result;
    }

    private int dec8(int value) {
        int result = (value - 1) & 0xFF;
        f = (f & C) | N | (result == 0 ? Z : 0) | ((value & 0x0F) == 0 ? H : 0);
        return result;
    }

    private void addHl(int value) {
        int result = hl() + value;
        f = (f & Z) | ((((hl() & 0x0FFF) + (value & 0x0FFF)) > 0x0FFF) ? H : 0)
                | (result > 0xFFFF ? C : 0);
        setHl(result & 0xFFFF);
    }

    private void alu(int op, int value) {
        int carry = (f & C) != 0 ? 1 : 0;
        int result;
        switch (op) {
            case 0:  // ADD
                result = a + value;
                f = ((result & 0xFF) == 0 ? Z : 0) | (((a & 0x0F) + (value & 0x0F)) > 0x0F ? H : 0)
                        | (result > 0xFF ? C : 0);
                a = result & 0xFF;
                break;
            case 1:  // ADC
                result = a + value + carry;
                f = ((result & 0xFF) == 0 ? Z : 0)
                        | (((a & 0x0F) + (value & 0x0F) + carry) > 0x0F ? H : 0)
                        | (result > 0xFF ? C : 0);
                a = result & 0xFF;
                break;
            case 2:  // SUB
                f = (a == value ? Z : 0) | N | ((a & 0x0F) < (value & 0x0F) ? H : 0) | (a < value ? C : 0);
                a = (a - value) & 0xFF;
                break;
            case 3:  // SBC
                result = a - value - carry;
                f = ((result & 0xFF) == 0 ? Z : 0) | N
                        | (((a & 0x0F) - (value & 0x0F) - carry) < 0 ? H : 0)
                        | (result < 0 ? C : 0);
                a = result & 0xFF;
                break;
            case 4:  // AND
                a &= value;
                f = (a == 0 ? Z : 0) | H;
                break;
            case 5:  // XOR
                a ^= value;
                f = a == 0 ? Z : 0;
                break;
            case 6:  // OR
                a |= value;
                f = a == 0 ? Z : 0;
                break;
            default:  // CP
                f = (a == value ? Z : 0) | N | ((a & 0x0F) < (value & 0x0F) ? H : 0) | (a < value ? C : 0);
                break;
        }
    }

    private void daa() {
        int correction = 0;
        boolean carry = (f & C) != 0;
        if ((f & H) != 0 || (f & N) == 0 && (a & 0x0F) > 9) {
            correction |= 0x06;
        }
        if (carry || (f & N) == 0 && a > 0x99) {
            correction |= 0x60;
            carry = true;
        }
        a = ((f & N) != 0 ? a - correction : a + correction) & 0xFF;
        f = (f & N) | (carry ? C : 0) | (a == 0 ? Z : 0);
    }

    private void halt() {
        tick(4);
        if ((bus.ie & bus.ifRegister & 0x1F) == 0) {
            halted = true;
        } else if (!ime) {
            haltBug = true;  // pending interrupt with interrupts disabled: no halt, next byte repeats
        }
    }

    // ------------------------------------------------------------------ opcodes

    private void execute() {
        int op = fetch();

        if (op >= 0x40 && op <= 0x7F) {
            if (op == 0x76) {
                halt();
                return;
            }
            int src = op & 7;
            int dst = (op >> 3) & 7;
            int value = reg(src);
            setReg(dst, value);
            tick(src == 6 || dst == 6 ? 8 : 4);
            return;
        }
        if (op >= 0x80 && op <= 0xBF) {
            int value = reg(op & 7);
            alu((op >> 3) & 7, value);
            tick((op & 7) == 6 ? 8 : 4);
            return;
        }
        if (op == 0xCB) {
            executeCb();
            return;
        }

        switch (op) {
            case 0x00: tick(4); break;
            case 0x01: setBc(fetch16()); tick(12); break;
            case 0x02: bus.write(bc(), a); tick(8); break;
            case 0x03: setBc((bc() + 1) & 0xFFFF); tick(8); break;
            case 0x04: b = inc8(b); tick(4); break;
            case 0x05: b = dec8(b); tick(4); break;
            case 0x06: b = fetch(); tick(8); break;
            case 0x07: {
                int carry = (a >> 7) & 1;
                a = ((a << 1) | carry) & 0xFF;
                f = carry != 0 ? C : 0;
                tick(4);
                break;
            }
            case 0x08: {
                int address = fetch16();
                bus.write(address, sp & 0xFF);
                bus.write((address + 1) & 0xFFFF, (sp >> 8) & 0xFF);
                tick(20);
                break;
            }
            case 0x09: addHl(bc()); tick(8); break;
            case 0x0A: a = bus.read(bc()) & 0xFF; tick(8); break;
            case 0x0B: setBc((bc() - 1) & 0xFFFF); tick(8); break;
            case 0x0C: c = inc8(c); tick(4); break;
            case 0x0D: c = dec8(c); tick(4); break;
            case 0x0E: c = fetch(); tick(8); break;
            case 0x0F: {
                int carry = a & 1;
                a = ((a >> 1) | (carry << 7)) & 0xFF;
                f = carry != 0 ? C : 0;
                tick(4);
                break;
            }
            case 0x10:  // STOP
                fetch();
                tick(4);
                break;
            case 0x11: setDe(fetch16()); tick(12); break;
            case 0x12: bus.write(de(), a); tick(8); break;
            case 0x13: setDe((de() + 1) & 0xFFFF); tick(8); break;
            case 0x14: d = inc8(d); tick(4); break;
            case 0x15: d = dec8(d); tick(4); break;
            case 0x16: d = fetch(); tick(8); break;
            case 0x17: {
                int carry = (a >> 7) & 1;
                a = ((a << 1) | ((f & C) != 0 ? 1 : 0)) & 0xFF;
                f = (carry != 0 ? C : 0) | (a == 0 ? Z : 0);
                tick(4);
                break;
            }
            case 0x18: {
                int offset = (byte) fetch();
                pc = (pc + offset) & 0xFFFF;
                tick(12);
                break;
            }
            case 0x19: addHl(de()); tick(8); break;
            case 0x1A: a = bus.read(de()) & 0xFF; tick(8); break;
            case 0x1B: setDe((de() - 1) & 0xFFFF); tick(8); break;
            case 0x1C: e = inc8(e); tick(4); break;
            case 0x1D: e = dec8(e); tick(4); break;
            case 0x1E: e = fetch(); tick(8); break;
            case 0x1F: {
                int carry = a & 1;
                a = (a >> 1) | ((f & C) != 0 ? 0x80 : 0);
                f = (carry != 0 ? C : 0) | (a == 0 ? Z : 0);
                tick(4);
                break;
            }
            case 0x20: case 0x28: case 0x30: case 0x38: {
                int offset = (byte) fetch();
                if (condition((op >> 3) & 3)) {
                    pc = (pc + offset) & 0xFFFF;
                    tick(12);
                } else {
                    tick(8);
                }
                break;
            }
            case 0x21: setHl(fetch16()); tick(12); break;
            case 0x22: bus.write(hl(), a); setHl((hl() + 1) & 0xFFFF); tick(8); break;
            case 0x23: setHl((hl() + 1) & 0xFFFF); tick(8); break;
            case 0x24: h = inc8(h); tick(4); break;
            case 0x25: h = dec8(h); tick(4); break;
            case 0x26: h = fetch(); tick(8); break;
            case 0x27: daa(); tick(4); break;
            case 0x29: addHl(hl()); tick(8); break;
            case 0x2A: a = bus.read(hl()) & 0xFF; setHl((hl() + 1) & 0xFFFF); tick(8); break;
            case 0x2B: setHl((hl() - 1) & 0xFFFF); tick(8); break;
            case 0x2C: l = inc8(l); tick(4); break;
            case 0x2D: l = dec8(l); tick(4); break;
            case 0x2E: l = fetch(); tick(8); break;
            case 0x2F: a ^= 0xFF; f |= N | H; tick(4); break;
            case 0x31: sp = fetch16(); tick(12); break;
            case 0x32: bus.write(hl(), a); setHl((hl() - 1) & 0xFFFF); tick(8); break;
            case 0x33: sp = (sp + 1) & 0xFFFF; tick(8); break;
            case 0x34: {
                int result = inc8(bus.read(hl()) & 0xFF);
                bus.write(hl(), result);
                tick(12);
                break;
            }
            case 0x35: {
                int result = dec8(bus.read(hl()) & 0xFF);
                bus.write(hl(), result);
                tick(12);
                break;
            }
            case 0x36: bus.write(hl(), fetch()); tick(12); break;
            case 0x37: f = (f & Z) | C; tick(4); break;
            case 0x39: addHl(sp); tick(8); break;
            case 0x3A: a = bus.read(hl()) & 0xFF; setHl((hl() - 1) & 0xFFFF); tick(8); break;
            case 0x3B: sp = (sp - 1) & 0xFFFF; tick(8); break;
            case 0x3C: a = inc8(a); tick(4); break;
            case 0x3D: a = dec8(a); tick(4); break;
            case 0x3E: a = fetch(); tick(8); break;
            case 0x3F: f = (f & Z) | ((f & C) != 0 ? 0 : C); tick(4); break;

            case 0xC0: case 0xC8: case 0xD0: case 0xD8: {
                if (condition((op >> 3) & 3)) {
                    pc = pop();
                    tick(20);
                } else {
                    tick(8);
                }
                break;
            }
            case 0xC1: { int value = pop(); setBc(value); tick(12); break; }
            case 0xD1: { int value = pop(); setDe(value); tick(12); break; }
            case 0xE1: setHl(pop()); tick(12); break;
            case 0xF1: {
                int value = pop();
                a = (value >> 8) & 0xFF;
                f = value & 0xF0;
                tick(12);
                break;
            }
            case 0xC2: case 0xCA: case 0xD2: case 0xDA: {
                int address = fetch16();
                if (condition((op >> 3) & 3)) {
                    pc = address;
                    tick(16);
                } else {
                    tick(12);
                }
                break;
            }
            case 0xC3: pc = fetch16(); tick(16); break;
            case 0xE9: pc = hl(); tick(4); break;
            case 0xC4: case 0xCC: case 0xD4: case 0xDC: {
                int address = fetch16();
                if (condition((op >> 3) & 3)) {
                    push(pc);
                    pc = address;
                    tick(24);
                } else {
                    tick(12);
                }
                break;
            }
            case 0xCD: {
                int address = fetch16();
                push(pc);
                pc = address;
                tick(24);
                break;
            }
            case 0xC5: push(bc()); tick(16); break;
            case 0xD5: push(de()); tick(16); break;
            case 0xE5: push(hl()); tick(16); break;
            case 0xF5: push((a << 8) | f); tick(16); break;
            case 0xC9: pc = pop(); tick(16); break;
            case 0xD9: pc = pop(); ime = true; imePending = false; tick(16); break;

            case 0xC6: alu(0, fetch()); tick(8); break;
            case 0xCE: alu(1, fetch()); tick(8); break;
            case 0xD6: alu(2, fetch()); tick(8); break;
            case 0xDE: alu(3, fetch()); tick(8); break;
            case 0xE6: alu(4, fetch()); tick(8); break;
            case 0xEE: alu(5, fetch()); tick(8); break;
            case 0xF6: alu(6, fetch()); tick(8); break;
            case 0xFE: alu(7, fetch()); tick(8); break;

            case 0xC7: case 0xCF: case 0xD7: case 0xDF:
            case 0xE7: case 0xEF: case 0xF7: case 0xFF: {
                push(pc);
                pc = op & 0x38;
                tick(16);
                break;
            }

            case 0xE0: bus.write(0xFF00 | fetch(), a); tick(12); break;
            case 0xF0: a = bus.read(0xFF00 | fetch()) & 0xFF; tick(12); break;
            case 0xE2: bus.write(0xFF00 | c, a); tick(8); break;
            case 0xF2: a = bus.read(0xFF00 | c) & 0xFF; tick(8); break;
            case 0xEA: bus.write(fetch16(), a); tick(16); break;
            case 0xFA: a = bus.read(fetch16()) & 0xFF; tick(16); break;

            case 0xE8: {
                int offset = (byte) fetch();
                int result = (sp + offset) & 0xFFFF;
                f = (((sp & 0x0F) + (offset & 0x0F)) > 0x0F ? H : 0)
                        | (((sp & 0xFF) + (offset & 0xFF)) > 0xFF ? C : 0);
                sp = result;
                tick(16);
                break;
            }
            case 0xF8: {
                int offset = (byte) fetch();
                int result = (sp + offset) & 0xFFFF;
                f = (((sp & 0x0F) + (offset & 0x0F)) > 0x0F ? H : 0)
                        | (((sp & 0xFF) + (offset & 0xFF)) > 0xFF ? C : 0);
                setHl(result);
                tick(12);
                break;
            }
            case 0xF9: sp = hl(); tick(8); break;

            case 0xF3: ime = false; imePending = false; tick(4); break;
            case 0xFB: imePending = true; tick(4); break;

            default:
                // invalid opcodes lock up a real DMG; treating them as a 4-cycle no-op keeps
                // badly patched ROMs running instead of hanging the app
                tick(4);
                break;
        }
    }

    private void executeCb() {
        int op = fetch();
        int index = op & 7;
        int value = reg(index);
        int bit = (op >> 3) & 7;

        if (op >= 0x40 && op < 0x80) {  // BIT
            f = (f & C) | H | ((value & (1 << bit)) == 0 ? Z : 0);
            tick(index == 6 ? 12 : 8);
            return;
        }
        if (op >= 0x80 && op < 0xC0) {  // RES
            setReg(index, value & ~(1 << bit));
            tick(index == 6 ? 16 : 8);
            return;
        }
        if (op >= 0xC0) {  // SET
            setReg(index, value | (1 << bit));
            tick(index == 6 ? 16 : 8);
            return;
        }

        int result;
        int carry;
        switch (bit) {
            case 0:  // RLC
                carry = (value >> 7) & 1;
                result = ((value << 1) | carry) & 0xFF;
                f = (result == 0 ? Z : 0) | (carry != 0 ? C : 0);
                break;
            case 1:  // RRC
                carry = value & 1;
                result = ((value >> 1) | (carry << 7)) & 0xFF;
                f = (result == 0 ? Z : 0) | (carry != 0 ? C : 0);
                break;
            case 2: {  // RL
                int oldCarry = (f & C) != 0 ? 1 : 0;
                carry = (value >> 7) & 1;
                result = ((value << 1) | oldCarry) & 0xFF;
                f = (result == 0 ? Z : 0) | (carry != 0 ? C : 0);
                break;
            }
            case 3: {  // RR
                int oldCarry = (f & C) != 0 ? 0x80 : 0;
                carry = value & 1;
                result = ((value >> 1) | oldCarry) & 0xFF;
                f = (result == 0 ? Z : 0) | (carry != 0 ? C : 0);
                break;
            }
            case 4:  // SLA
                carry = (value >> 7) & 1;
                result = (value << 1) & 0xFF;
                f = (result == 0 ? Z : 0) | (carry != 0 ? C : 0);
                break;
            case 5:  // SRA
                carry = value & 1;
                result = ((value >> 1) | (value & 0x80)) & 0xFF;
                f = (result == 0 ? Z : 0) | (carry != 0 ? C : 0);
                break;
            case 6:  // SWAP
                result = ((value << 4) | (value >> 4)) & 0xFF;
                f = result == 0 ? Z : 0;
                break;
            default:  // SRL
                carry = value & 1;
                result = (value >> 1) & 0xFF;
                f = (result == 0 ? Z : 0) | (carry != 0 ? C : 0);
                break;
        }
        setReg(index, result);
        tick(index == 6 ? 16 : 8);
    }
}
