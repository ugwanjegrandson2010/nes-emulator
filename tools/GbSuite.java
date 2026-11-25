import emu.gb.GbCartridge;
import emu.gb.GbMachine;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Runs several Game Boy ROMs and tiles their final frames into one contact sheet. */
public final class GbSuite {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: GbSuite <out.png> <frames> <rom>...");
            return;
        }
        String output = args[0];
        int frames = Integer.parseInt(args[1]);
        int count = args.length - 2;
        int columns = Math.min(4, count);
        int rows = (count + columns - 1) / columns;
        int scale = 2;
        int cellWidth = 160 * scale;
        int cellHeight = 144 * scale;
        int gap = 8;

        BufferedImage sheet = new BufferedImage(
                columns * cellWidth + (columns + 1) * gap,
                rows * cellHeight + (rows + 1) * gap,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0x202020));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());

        for (int i = 0; i < count; i++) {
            String path = args[i + 2];
            long start = System.currentTimeMillis();
            GbMachine machine = new GbMachine(GbCartridge.load(Files.readAllBytes(Paths.get(path))));
            for (int f = 0; f < frames; f++) {
                machine.stepFrame();
            }
            long millis = Math.max(1, System.currentTimeMillis() - start);

            BufferedImage frame = new BufferedImage(160, 144, BufferedImage.TYPE_INT_RGB);
            frame.setRGB(0, 0, 160, 144, machine.ppu.framebuffer, 0, 160);
            int x = gap + (i % columns) * (cellWidth + gap);
            int y = gap + (i / columns) * (cellHeight + gap);
            g.drawImage(frame, x, y, cellWidth, cellHeight, null);

            System.out.printf("[%d] %s  %.0f fps  type=0x%x  A000='%s'%n",
                    i, new File(path).getName(), frames * 1000.0 / millis,
                    machine.cart.type, signature(machine));
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(output));
        System.out.println("wrote " + output);
    }

    private static String signature(GbMachine machine) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            int c = machine.read(0xA000 + i) & 0xFF;
            if (c == 0) {
                break;
            }
            sb.append(c >= 32 && c < 127 ? (char) c : '.');
        }
        return sb.toString();
    }
}
