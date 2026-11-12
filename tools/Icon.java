import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Draws the Crafty Search icon: a chest with a magnifying glass, 32x32 pixel art, scaled up. Usage: java Icon.java out.png scale */
public class Icon {
    static final int N = 32;
    static int[][] px = new int[N][N];

    /** The drawing is shifted up this much, to sit in the middle. */
    static final int LIFT = 2;

    static void set(int x, int y, int c) {
        y -= LIFT;
        if (x >= 0 && y >= 0 && x < N && y < N) px[y][x] = c;
    }

    static double segment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)));
        return Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }

    static void rect(int x0, int y0, int x1, int y1, int c) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) set(x, y, c);
    }

    public static void main(String[] args) throws Exception {
        int outline = 0xFF2B1A0E;
        int woodLight = 0xFFC9913F;
        int wood = 0xFFA8722C;
        int woodDark = 0xFF7E521E;
        int band = 0xFF4A2F15;
        int latch = 0xFFD8D8D8;
        int latchDark = 0xFF8C8C8C;

        // Chest: lid y 6..13, band 14, body 15..27, x 2..25.
        rect(2, 6, 25, 27, outline);
        rect(3, 7, 24, 13, wood);
        rect(3, 7, 24, 7, woodLight);          // lid top highlight
        rect(3, 10, 24, 10, woodDark);         // lid plank line
        rect(3, 14, 24, 14, band);             // seam between lid and body
        rect(3, 15, 24, 26, wood);
        rect(3, 19, 24, 19, woodDark);         // body plank lines
        rect(3, 23, 24, 23, woodDark);
        rect(3, 15, 3, 26, woodLight);         // left edge highlight
        rect(24, 8, 24, 26, woodDark);         // right edge shade
        rect(3, 26, 24, 26, woodDark);         // bottom shade
        // Latch.
        rect(12, 11, 15, 17, outline);
        rect(13, 12, 14, 16, latch);
        rect(13, 16, 14, 16, latchDark);

        // Magnifying glass over the lower right.
        double cx = 21.5, cy = 20.5;
        int rim = 0xFF262626;
        int rimLight = 0xFF6E6E6E;
        int lens = 0xFF9FDDF5;
        int lensLight = 0xFFE6F8FF;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                double d = Math.hypot(x + 0.5 - cx - 0.5, y + 0.5 - cy - 0.5);
                if (d <= 7.2) set(x, y, rim);
                if (d <= 6.0) set(x, y, rimLight);
                if (d <= 5.0) set(x, y, lens);
            }
        }
        // Lens shine.
        set(19, 17, lensLight); set(20, 17, lensLight); set(19, 18, lensLight);
        set(18, 19, lensLight);
        // Handle, down and right from the rim: a thick line with a dark edge.
        int handle = 0xFF6B4019;
        int handleDark = 0xFF2B1A0E;
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < N; y++) {
                for (int x = 0; x < N; x++) {
                    double d = segment(x + 0.5, y + 0.5, 27.2, 27.2, 30.2, 30.2);
                    if (pass == 0 && d <= 1.6) set(x, y, handleDark);
                    if (pass == 1 && d <= 0.8) set(x, y, handle);
                }
            }
        }

        int scale = Integer.parseInt(args[1]);
        BufferedImage img = new BufferedImage(N * scale, N * scale, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < N * scale; y++) for (int x = 0; x < N * scale; x++) img.setRGB(x, y, px[y / scale][x / scale]);
        ImageIO.write(img, "png", new File(args[0]));
    }
}
