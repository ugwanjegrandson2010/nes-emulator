import emu.nes.Cartridge;
import emu.nes.NesMachine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Headless play test: boots a ROM, presses START, walks right and jumps, and writes frames to PNG.
 * Used to confirm real games respond to controller input and keep rendering correct scenes.
 */
public final class DemoPlay {

    public static void main(String[] args) throws Exception {
        byte[] rom = Files.readAllBytes(Paths.get(args[0]));
        String prefix = args[1];

        NesMachine machine = new NesMachine(Cartridge.load(rom));
        int total = args.length > 2 ? Integer.parseInt(args[2]) : 1500;

        long start = System.currentTimeMillis();
        for (int frame = 0; frame < total; frame++) {
            int buttons = 0;
            if (frame >= 700 && frame < 720) {
                buttons |= NesMachine.BUTTON_START;   // leave the title screen
            }
            if (frame >= 760) {
                buttons |= NesMachine.BUTTON_RIGHT;   // walk right
            }
            if (frame >= 760 && frame % 48 < 14) {
                buttons |= NesMachine.BUTTON_A;       // jump repeatedly
            }
            machine.setButtons(0, buttons);
            machine.stepFrame();

            if (frame == 760 || frame == 900 || frame == 1200 || frame == total - 1) {
                write(machine, prefix + "-f" + frame + ".png");
            }
        }
        long millis = Math.max(1, System.currentTimeMillis() - start);
        System.out.printf("%s: %d frames in %d ms (%.0f fps), %s%n",
                args[0], total, millis, total * 1000.0 / millis, machine.cart.summary());
    }

    private static void write(NesMachine machine, String path) throws Exception {
        BufferedImage image = new BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 256, 240, machine.ppu.framebuffer, 0, 256);
        ImageIO.write(image, "png", new File(path));
    }
}
