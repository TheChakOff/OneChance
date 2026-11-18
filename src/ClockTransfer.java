import java.util.function.LongConsumer;
import java.util.function.LongSupplier;



final class ClockTransfer {
    static boolean copy(LongSupplier read, LongConsumer write) {
        try {
            write.accept(read.getAsLong());
            return true;
        } catch (IllegalArgumentException noClock) {
            // Paper 26.3 rejects setFullTime for dimensions without a world clock.
            return false;
        }
    }
}
