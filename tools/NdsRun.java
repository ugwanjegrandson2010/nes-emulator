import emu.nds.NdsCore;
import emu.nds.NdsCpu;
import emu.nds.NdsMachine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Runs a .nds image headlessly and writes the two screens to a PNG. */
public final class NdsRun {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: NdsRun <rom.nds> [frames] [out.png]");
            return;
        }
        byte[] rom = Files.readAllBytes(Paths.get(args[0]));
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 120;
        String out = args.length > 2 ? args[2] : "artifacts/nds-screen.png";

        NdsCore core = new NdsCore();
        core.loadRom(rom);
        System.out.println(core.cartridgeInfo());

        if (System.getenv("NDS_STEPS") != null) {
            int steps = Integer.parseInt(System.getenv("NDS_STEPS"));
            NdsCpu c9 = core.machine().cpu9;
            for (int i = 0; i < steps; i++) {
                int pc = c9.r[15];
                System.out.printf("%6d pc=%08x insn=%08x sp=%08x lr=%08x cpsr=%08x%n", i, pc,
                        core.machine().bus9.read32(pc & ~3), c9.r[13], c9.r[14], c9.cpsr);
                c9.step();
            }
            return;
        }
        long start = System.currentTimeMillis();
        boolean trace = System.getenv("NDS_TRACE") != null;
        for (int i = 0; i < frames; i++) {
            core.stepFrame();
            if (trace && (i < 24 || i % 50 == 0)) {
                NdsCpu c9 = core.machine().cpu9;
                NdsCpu c7 = core.machine().cpu7;
                System.out.printf("frame %3d  a9 pc=%08x lr=%08x sp=%08x  a7 pc=%08x  dispcnt=%04x bg0=%04x vcount=%d%n",
                        i, c9.r[15], c9.r[14], c9.r[13], c7.r[15],
                        core.machine().ppu.main.dispcnt(),
                        core.machine().ppu.main.bgcnt(0), core.machine().ppu.vcount);
            }
        }
        long millis = System.currentTimeMillis() - start;

        NdsMachine machine = core.machine();
        report("arm9", machine.cpu9);
        report("arm7", machine.cpu7);
        System.out.printf("%d frames in %d ms  (%.1f fps)%n", frames, millis,
                frames * 1000.0 / Math.max(1, millis));

        int[] frame = core.framebuffer();
        int lit = 0;
        for (int pixel : frame) {
            if ((pixel & 0x00FFFFFF) != 0) {
                lit++;
            }
        }
        System.out.printf("framebuffer: %d of %d pixels are not black%n", lit, frame.length);

        BufferedImage image = new BufferedImage(core.width(), core.height(), BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, core.width(), core.height(), frame, 0, core.width());
        File target = new File(out);
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        ImageIO.write(image, "png", target);
        System.out.println("wrote " + target.getPath());
    }

    private static void report(String name, NdsCpu cpu) {
        System.out.printf("%s: pc=%08x mode=%02x thumb=%s halted=%s instructions=%d r0=%08x r1=%08x%n",
                name, cpu.r[15], cpu.cpsr & 0x1F, cpu.thumb(), cpu.halted, cpu.instructionCount,
                cpu.r[0], cpu.r[1]);
    }
}
