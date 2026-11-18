import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClownShapeTest {
    @Test void drawingHasBoundedParticleBudgetAndVisibleFaceFeatures() {
        assertTrue(ClownShape.DOTS.size() < 230);
        assertTrue(ClownShape.DOTS.stream().allMatch(p -> Double.isFinite(p.x()) && Double.isFinite(p.y()) && Math.abs(p.x()) < 1 && Math.abs(p.y()) < 1));
        assertTrue(ClownShape.DOTS.stream().anyMatch(p -> p.rgb() == ClownShape.RED && Math.abs(p.x()) < .01 && p.layer() > .1));
        assertTrue(ClownShape.DOTS.stream().anyMatch(p -> p.rgb() == ClownShape.DARK && p.x() < 0));
        assertTrue(ClownShape.DOTS.stream().anyMatch(p -> p.rgb() == ClownShape.DARK && p.x() > 0));
        assertEquals(44, ClownShape.DOTS.stream().filter(p -> p.rgb() == ClownShape.WHITE && Math.abs(Math.hypot(p.x(),p.y()) - .68) < .000001).count());
    }
    @Test void animationGrowsHoldsAndDissolvesWithoutInvalidValues() {
        assertEquals(0,ClownShape.envelope(0,10,24,16));
        assertEquals(1,ClownShape.envelope(20,10,24,16));
        assertEquals(0,ClownShape.envelope(50,10,24,16));
        assertTrue(ClownShape.envelope(5,10,24,16) < ClownShape.envelope(8,10,24,16));
        assertTrue(ClownShape.envelope(40,10,24,16) > ClownShape.envelope(47,10,24,16));
        for (int age = 0; age < 60; age++) {
            double value = ClownShape.envelope(age,0,20,0);
            assertTrue(Double.isFinite(value) && value >= 0 && value <= 1);
        }
    }
}
