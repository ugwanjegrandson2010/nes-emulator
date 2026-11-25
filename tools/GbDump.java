import emu.gb.GbCartridge;
import emu.gb.GbMachine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Headless helper: run a Game Boy ROM for N frames, print its status bytes and (optionally) write a PNG. */
public final class GbDump {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: GbDump <rom> <out.png|-> [frames]");
            return;
        }
        byte[] rom = Files.readAllBytes(Paths.get(args[0]));
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 300;

        GbMachine machine = new GbMachine(GbCartridge.load(rom));
        System.out.println("cartridge type=0x" + Integer.toHexString(machine.cart.type)
                + " " + machine.cart.summary());
        long start = System.currentTimeMillis();
        for (int i = 0; i < frames; i++) {
            machine.stepFrame();
        }
        long millis = Math.max(1, System.currentTimeMillis() - start);
        System.out.printf("%d frames in %d ms (%.1f fps)%n", frames, millis, frames * 1000.0 / millis);

        StringBuilder signature = new StringBuilder();
        for (int i = 0; i < 96; i++) {
            int c = machine.read(0xA000 + i) & 0xFF;
            if (c == 0) {
                break;
            }
            signature.append(c >= 32 && c < 127 || c == '\n' ? (char) c : '.');
        }
        System.out.println("A000: '" + signature.toString().replace('\n', '|') + "'");
        System.out.printf("cpu %s%n", machine.cpu.dump());

        if (!"-".equals(args[1])) {
            BufferedImage image = new BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 160, 144, machine.ppu.framebuffer, 0, 160);
            ImageIO.write(image, "png", new File(args[1]));
        }
    }
}
