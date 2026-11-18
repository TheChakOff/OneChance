import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthHudTest {
    @Test void reportsHeartsRatherThanRawHitPoints() {
        assertEquals("10",HealthHud.hearts(20));
        assertEquals("9.5",HealthHud.hearts(19));
        assertEquals("0.5",HealthHud.hearts(1));
        assertEquals("15",HealthHud.hearts(30));
    }
    @Test void handlesDeadPlayersAndInvalidValues() {
        assertEquals("0",HealthHud.hearts(0));
        assertEquals("0",HealthHud.hearts(-5));
        assertEquals("0",HealthHud.hearts(Double.NaN));
        assertEquals("0",HealthHud.hearts(Double.POSITIVE_INFINITY));
    }
}
