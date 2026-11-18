import java.util.*;



/**
 * A small camera-facing drawing made of dust particles, in local face coordinates.
 */
final class ClownShape {
    record Dot(double x, double y, double layer, int rgb, float size) {
    }

    static final int WHITE = 0xFFF4DD, RED = 0xEF3340, BLUE = 0x35A8E0, DARK = 0x182238;
    static final List<Dot> DOTS = create();

    private static List<Dot> create() {
        List<Dot> dots = new ArrayList<>();
        for (int row = -3; row <= 3; row++)
            for (int col = -3; col <= 3; col++) {
                double x = col * .18, y = row * .18;
                if (x * x + y * y < .36) dots.add(new Dot(x, y, 0, WHITE, .75f));
            }
        circle(dots, 0, 0, .68, .68, 44, WHITE, 0, .85f);
        for (double side : new double[]{-1, 1}) {
            circle(dots, side * .66, .24, .17, .19, 10, RED, .01, .95f);
            circle(dots, side * .69, -.08, .17, .18, 10, RED, .01, .95f);
            for (int i = 0; i < 4; i++) dots.add(new Dot(side * .25, .10 - i * .055, .035, BLUE, .7f));
            circle(dots, side * .25, .23, .055, .085, 6, DARK, .06, .7f);
            dots.add(new Dot(side * .25 - .015, .25, .09, WHITE, .35f));
        }
        for (double x : new double[]{-.22, 0, .22}) circle(dots, x, .66, .13, .13, 8, RED, .01, .85f);
        circle(dots, 0, .015, .12, .105, 10, RED, .12, 1f);
        dots.add(new Dot(0, .015, .12, RED, 1.2f));
        for (int i = 0; i <= 16; i++) {
            double angle = Math.PI * i / 16;
            dots.add(new Dot(Math.cos(angle) * .35, -.20 - Math.sin(angle) * .22, .07, RED, .8f));
        }
        return List.copyOf(dots);
    }

    private static void circle(List<Dot> dots, double x, double y, double rx, double ry, int count, int rgb, double layer, float size) {
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            dots.add(new Dot(x + rx * Math.cos(angle), y + ry * Math.sin(angle), layer, rgb, size));
        }
    }

    static double envelope(int age, int fadeIn, int hold, int fadeOut) {
        if (age < 0 || age >= fadeIn + hold + fadeOut) return 0;
        double progress = age < fadeIn ? (double) age / Math.max(1, fadeIn)
                : age >= fadeIn + hold ? 1 - (double) (age - fadeIn - hold) / Math.max(1, fadeOut) : 1;
        return progress * progress * (3 - 2 * progress);
    }
}
