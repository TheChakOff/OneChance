import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;



final class RuleTransfer {
    /**
     * Registry entries can exist even when the world's feature flags disable them.
     */
    static <F, T> boolean copy(Set<F> required, Set<F> sourceFlags, Set<F> targetFlags,
                               Supplier<T> read, Consumer<T> write) {
        if (!sourceFlags.containsAll(required) || !targetFlags.containsAll(required)) return false;
        try {
            T value = read.get();
            if (value == null) return false;
            write.accept(value);
            return true;
        } catch (IllegalArgumentException unavailable) {
            // Paper may reject an entry present in the global registry for this world.
            return false;
        }
    }
}
