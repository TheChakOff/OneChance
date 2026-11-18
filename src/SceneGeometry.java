import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Camera-independent geometry for aiming actors and centring rotating block models. */
final class SceneGeometry {
    record Facing(float yaw, float pitch) {}

    static Facing facing(Vector from, Vector to, float fallbackYaw) {
        Vector direction = to.clone().subtract(from);
        if (direction.lengthSquared() < 1e-12) return new Facing(fallbackYaw, 0);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.getY(), Math.hypot(direction.getX(), direction.getZ())));
        return new Facing(yaw, pitch);
    }

    static Vector between(Vector start, Vector end, double progress) {
        return start.clone().multiply(1 - Math.clamp(progress, 0, 1))
                .add(end.clone().multiply(Math.clamp(progress, 0, 1)));
    }

    static Quaternionf blockRotation(Vector direction) {
        if (direction.lengthSquared() < 1e-12) return new Quaternionf();
        Vector unit = direction.clone().normalize();
        return new Quaternionf().rotationTo(0, 0, -1, (float) unit.getX(), (float) unit.getY(), (float) unit.getZ());
    }

    static Vector3f centeredTranslation(Quaternionf rotation, double scale) {
        return rotation.transform(new Vector3f((float) (-scale / 2), (float) (-scale / 2), (float) (-scale / 2)));
    }

    static double attackProgress(double time) { return Math.clamp((time - .15) / .4, 0, 1); }

    static double lunge(double time) {
        // Contact coincides with the shared impact at 55%; return only after the strike.
        if (time <= .55) return .55 * attackProgress(time);
        return .55 * (1 - Math.clamp((time - .55) / .25, 0, 1));
    }

    private SceneGeometry() {}
}
