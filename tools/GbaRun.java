import emu.gba.GbaCore;
import emu.nds.NdsCpu;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Runs a .gba image headlessly and writes its frame to a PNG (same job as NdsRun for the DS). */
public final class GbaRun {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: GbaRun <rom.gba> [frames] [out.png]");
            return;
        }
        byte[] rom = Files.readAllBytes(Paths.get(args[0]));
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        String out = args.length > 2 ? args[2] : "artifacts/gba-screen.png";

        GbaCore core = new GbaCore();
        core.loadRom(rom);
        System.out.printf("rom %d bytes, entry %08x%n", rom.length, core.machine().cpu().r[15]);

        if (System.getenv("GBA_STEPS") != null) {
            int steps = Integer.parseInt(System.getenv("GBA_STEPS"));
            NdsCpu cpu = core.machine().cpu();
            for (int i = 0; i < steps; i++) {
                int pc = cpu.r[15];
                System.out.printf("%5d pc=%08x insn=%08x mode=%02x cpsr=%08x sp=%08x lr=%08x%n",
                        i, pc, core.machine().read32(pc), cpu.cpsr & 0x1F, cpu.cpsr, cpu.r[13], cpu.r[14]);
                cpu.step();
            }
            return;
        }
        boolean trace = System.getenv("GBA_TRACE") != null;
        java.util.Map<Integer, Integer> keys = new java.util.HashMap<Integer, Integer>();
        String keysEnv = System.getenv("GBA_KEYS");
        if (keysEnv != null) {
            for (String pair : keysEnv.split(",")) {
                String[] parts = pair.split("=");
                keys.put(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim(), 16));
            }
        }
        long start = System.currentTimeMillis();
        int lastPc = 0;
        for (int i = 0; i < frames; i++) {
            if (keys.containsKey(i)) {
                core.setButtons(0, keys.get(i));
            }
            core.stepFrame();
            if (System.getenv("NDS_LAST") != null) {
                int pc = core.machine().cpu().r[15];
                if (i > 1 && pc == lastPc) {
                    core.machine().cpu().dumpLast("stuck at frame " + i);
                }
                lastPc = pc;
            }
            if (trace) {
                NdsCpu cpu = core.machine().cpu();
                System.out.printf("frame %3d pc=%08x mode=%02x thumb=%s count=%d r0=%08x r1=%08x%n",
                        i, cpu.r[15], cpu.cpsr & 0x1F, cpu.thumb(), cpu.instructionCount,
                        cpu.r[0], cpu.r[1]);
            }
        }
        long millis = System.currentTimeMillis() - start;
        NdsCpu cpu = core.machine().cpu();
        System.out.printf("cpu pc=%08x mode=%02x thumb=%s instructions=%d%n",
                cpu.r[15], cpu.cpsr & 0x1F, cpu.thumb(), cpu.instructionCount);
        System.out.printf("%d frames in %d ms (%.1f fps)%n", frames, millis,
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
}
