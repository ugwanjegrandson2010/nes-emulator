import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Turns the generated icon artwork into the launcher mipmaps the app ships with.
 * Crops the artwork to its rounded-square badge and writes every density.
 */
public final class IconGen {

    public static void main(String[] args) throws Exception {
        String source = args.length > 0 ? args[0] : "artifacts/icon-source.jpg";
        BufferedImage image = ImageIO.read(new File(source));

        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = 0;
        int maxY = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 238 && g > 238 && b > 238) {
                    continue;   // page white around the badge
                }
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        int side = Math.min(maxX - minX, maxY - minY) + 1;
        int centreX = (minX + maxX) / 2;
        int centreY = (minY + maxY) / 2;
        int left = Math.max(0, centreX - side / 2);
        int top = Math.max(0, centreY - side / 2);
        BufferedImage cropped = image.getSubimage(left, top, Math.min(side, image.getWidth() - left),
                Math.min(side, image.getHeight() - top));
        System.out.printf("artwork %dx%d, badge %dx%d at %d,%d%n",
                image.getWidth(), image.getHeight(), side, side, left, top);

        String[] densities = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] sizes = {48, 72, 96, 144, 192};
        for (int i = 0; i < densities.length; i++) {
            File target = new File("app/src/main/res/mipmap-" + densities[i]);
            target.mkdirs();
            BufferedImage scaled = new BufferedImage(sizes[i], sizes[i], BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(cropped, 0, 0, sizes[i], sizes[i], null);
            graphics.dispose();
            File out = new File(target, "ic_launcher.png");
            ImageIO.write(scaled, "png", out);
            System.out.println("wrote " + out.getPath());
        }
    }
}
