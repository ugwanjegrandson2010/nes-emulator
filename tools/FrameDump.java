import emu.nes.Cartridge;
import emu.nes.NesMachine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Headless helper: run a ROM for N frames and write the framebuffer to a PNG. */
public final class FrameDump {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: FrameDump <rom> <out.png> [frames]");
            return;
        }
        byte[] rom = Files.readAllBytes(Paths.get(args[0]));
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 180;

        NesMachine machine = new NesMachine(Cartridge.load(rom));
        long start = System.currentTimeMillis();
        for (int i = 0; i < frames; i++) {
            machine.stepFrame();
        }
        long millis = Math.max(1, System.currentTimeMillis() - start);

        int width = 256;
        int height = 240;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, machine.ppu.framebuffer, 0, width);
        ImageIO.write(image, "png", new File(args[1]));

        System.out.printf("%s: %d frames in %d ms (%.1f fps), cartridge: %s%n",
                args[0], frames, millis, frames * 1000.0 / millis, machine.cart.summary());
    }
}
