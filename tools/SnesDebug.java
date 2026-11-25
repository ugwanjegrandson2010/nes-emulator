import emu.snes.SnesCore;
import emu.snes.SnesCpu;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Steps the SNES CPU one instruction at a time so a derailed reset can be traced. */
public final class SnesDebug {

    public static void main(String[] args) throws Exception {
        byte[] rom = Files.readAllBytes(Paths.get(args[0]));
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        SnesCore core = new SnesCore();
        core.loadRom(rom);
        SnesCpu cpu = core.machine().cpu();
        for (int i = 0; i < steps; i++) {
            int at = cpu.pc;
            int bank = cpu.pbr;
            int opcode = rom.length > at ? (rom[at] & 0xFF) : -1;
            System.out.printf("%03x:%04x op=%02x a=%04x x=%04x p=%02x sp=%04x%n",
                    bank, at, opcode, cpu.a, cpu.x, cpu.p, cpu.sp);
            cpu.step();
            if (cpu.pc == 0 && cpu.pbr == 0) {
                System.out.println("!! jumped to 0 after " + (i + 1) + " instructions");
                break;
            }
        }
        System.out.println("final pc=" + Integer.toHexString(cpu.pbr) + ":"
                + Integer.toHexString(cpu.pc));
    }
}
