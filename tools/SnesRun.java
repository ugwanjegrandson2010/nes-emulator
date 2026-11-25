import emu.snes.SnesCore;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Headless SNES runner: loads a .sfc/.smc, runs frames, dumps a PNG of the screen. */
public final class SnesRun {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: SnesRun rom.sfc [frames] [out.png]");
            System.exit(2);
        }
        byte[] rom = Files.readAllBytes(Paths.get(args[0]));
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        String outPath = args.length > 2 ? args[2] : null;

        SnesCore core = new SnesCore();
        core.loadRom(rom);
        boolean trace = System.getenv("SNES_TRACE") != null;

        for (int frame = 0; frame < frames; frame++) {
            core.stepFrame();
            if (trace && frame % 10 == 0) {
                System.out.println("frame " + frame + " pc=" + Integer.toHexString(core.machine().cpu().pc)
                        + " a=" + Integer.toHexString(core.machine().cpu().a)
                        + " pbr=" + Integer.toHexString(core.machine().cpu().pbr)
                        + " p=" + Integer.toHexString(core.machine().cpu().p)
                        + " line=" + core.machine().scanline());
            }
        }

        if (System.getenv("SNES_DUMP") != null) {
            System.out.println("state: " + core.machine().debugState());
        }

        int[] pixels = core.framebuffer();
        int lit = 0;
        for (int pixel : pixels) {
            if ((pixel & 0x00FFFFFF) != 0) {
                lit++;
            }
        }
        System.out.println("framebuffer: " + lit + " of " + pixels.length + " pixels are not black");

        if (outPath != null) {
            BufferedImage image = new BufferedImage(core.width(), core.height(), BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, core.width(), core.height(), pixels, 0, core.width());
            ImageIO.write(image, "png", new File(outPath));
            System.out.println("wrote " + outPath);
        }
    }
}
