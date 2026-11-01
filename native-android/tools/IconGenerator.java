import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public final class IconGenerator {
    private static final int[][] TARGETS = {
            {48, 72, 96, 144, 192}
    };
    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};

    public static void main(String[] args) throws IOException {
        File resRoot = new File(args.length == 0 ? "res" : args[0]);
        for (int densityIndex = 0; densityIndex < DENSITIES.length; densityIndex++) {
            int size = TARGETS[0][densityIndex];
            File folder = new File(resRoot, "mipmap-" + DENSITIES[densityIndex]);
            if (!folder.isDirectory() && !folder.mkdirs()) {
                throw new IOException("Cannot create " + folder.getAbsolutePath());
            }
            writeIcon(new File(folder, "ic_launcher.png"), size);
            writeIcon(new File(folder, "ic_launcher_round.png"), size);
        }
        System.out.println("Generated launcher PNG icons under " + resRoot.getAbsolutePath());
    }

    private static void writeIcon(File output, int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(0x2a, 0x5d, 0x4f));
            graphics.fill(new RoundRectangle2D.Double(0, 0, size, size, size * 0.19, size * 0.19));

            double cardInset = size * 0.19;
            double cardWidth = size - cardInset * 2;
            double cardHeight = size * 0.60;
            double cardY = (size - cardHeight) / 2;
            graphics.setColor(new Color(0xf7, 0xf5, 0xef));
            graphics.fill(new RoundRectangle2D.Double(cardInset, cardY, cardWidth, cardHeight,
                    size * 0.08, size * 0.08));

            graphics.setColor(new Color(0xc6, 0x5d, 0x45));
            graphics.setStroke(new BasicStroke((float) (size * 0.07),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.drawLine((int) (size * 0.35), (int) (size * 0.57),
                    (int) (size * 0.47), (int) (size * 0.69));
            graphics.drawLine((int) (size * 0.47), (int) (size * 0.69),
                    (int) (size * 0.70), (int) (size * 0.43));
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG writer unavailable for " + output.getAbsolutePath());
        }
    }
}
