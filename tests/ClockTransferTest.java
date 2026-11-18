import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ClockTransferTest {
    @Test void overworldClockIsPreserved() {
        AtomicLong target = new AtomicLong();
        assertTrue(ClockTransfer.copy(() -> 123456L, target::set));
        assertEquals(123456L, target.get());
    }

    @Test void netherWithoutClockDoesNotAbortReset() {
        assertFalse(ClockTransfer.copy(() -> 0L, time -> {
            throw new IllegalArgumentException("Cannot set time in world without world clock");
        }));
    }

    @Test void unrelatedErrorsStillReachResetFailureHandler() {
        assertThrows(IllegalStateException.class, () -> ClockTransfer.copy(() -> 1L, time -> {
            throw new IllegalStateException("World no longer loaded");
        }));
    }
}
