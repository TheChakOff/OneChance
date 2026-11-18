import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RuleTransferTest {
    @Test void experimentalRuleIsNeverReadWithoutItsFeature() {
        assertFalse(RuleTransfer.copy(Set.of("minecart_improvements"), Set.of(), Set.of(),
                () -> { throw new AssertionError("Unavailable rule was accessed"); }, value -> fail("write")));
    }

    @Test void targetMustAlsoSupportRule() {
        assertFalse(RuleTransfer.copy(Set.of("experimental"), Set.of("experimental"), Set.of(),
                () -> { throw new AssertionError("Target cannot accept rule"); }, value -> fail("write")));
    }

    @Test void invalidRegistryEntryDoesNotInterruptFollowingRules() {
        assertFalse(RuleTransfer.copy(Set.of(), Set.of(), Set.of(),
                () -> { throw new IllegalArgumentException("Tried to access invalid game rule minecraft:max_minecart_speed"); }, value -> fail("write")));
        AtomicInteger copied = new AtomicInteger();
        assertTrue(RuleTransfer.copy(Set.of(), Set.of(), Set.of(), () -> 42, copied::set));
        assertEquals(42, copied.get());
    }

    @Test void unavailableTargetDoesNotAbortReset() {
        assertFalse(RuleTransfer.copy(Set.of(), Set.of(), Set.of(), () -> 42,
                value -> { throw new IllegalArgumentException("Unavailable in target world"); }));
    }

    @Test void unexpectedFailuresAreNotHidden() {
        assertThrows(IllegalStateException.class, () -> RuleTransfer.copy(Set.of(), Set.of(), Set.of(),
                () -> { throw new IllegalStateException("Storage failure"); }, value -> fail("write")));
    }
}
