import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneGeometryTest {
    @Test void livingActorsFaceTheTargetForEveryCameraAngle() {
        for (float yaw : new float[]{0, 90, 180, 270, -45}) {
            for (float pitch : new float[]{-89, -45, 0, 45, 89}) {
                Vector forward = new Location(null, 0, 0, 0, yaw, pitch).getDirection();
                double angle = Math.toRadians(yaw);
                Vector right = new Vector(-Math.cos(angle), 0, -Math.sin(angle));
                Vector up = right.clone().crossProduct(forward).normalize();
                Vector from = forward.clone().multiply(3).subtract(right.clone());
                Vector target = forward.clone().multiply(3).add(right).add(up.clone().multiply(.2));
                var facing = SceneGeometry.facing(from, target, yaw);
                Vector actual = new Location(null, 0, 0, 0, facing.yaw(), facing.pitch()).getDirection();
                assertTrue(actual.distance(target.clone().subtract(from).normalize()) < 1e-6);
            }
        }
    }

    @Test void coincidentTargetsDoNotProduceUndefinedAngles() {
        var facing = SceneGeometry.facing(new Vector(1, 2, 3), new Vector(1, 2, 3), 123);
        assertEquals(123, facing.yaw());
        assertEquals(0, facing.pitch());
    }

    @Test void pistonFrontPointsTowardTargetIncludingUpAndDown() {
        for (Vector direction : new Vector[]{new Vector(1, 0, 0), new Vector(0, 1, 0),
                new Vector(0, -1, 0), new Vector(0, 0, 1), new Vector(-2, .5, -3)}) {
            Vector3f front = SceneGeometry.blockRotation(direction).transform(new Vector3f(0, 0, -1));
            Vector expected = direction.clone().normalize();
            assertTrue(new Vector(front.x, front.y, front.z).distance(expected) < 1e-6);
        }
    }

    @Test void rotatingBlockKeepsItsCentreFixedInsteadOfOrbitingACorner() {
        var rotation = SceneGeometry.blockRotation(new Vector(1, .7, -.3));
        double scale = .55;
        Vector3f center = rotation.transform(new Vector3f(.5f, .5f, .5f).mul((float) scale))
                .add(SceneGeometry.centeredTranslation(rotation, scale));
        assertEquals(0, center.length(), 1e-6);
    }

    @Test void projectileAndLungeReachContactAtTheImpactFrameWithoutMutatingInputs() {
        Vector start = new Vector(-.75, -.2, 3), target = new Vector(.35, -.2, 3);
        assertEquals(start, SceneGeometry.between(start, target, SceneGeometry.attackProgress(0)));
        assertEquals(target, SceneGeometry.between(start, target, SceneGeometry.attackProgress(.55)));
        assertEquals(new Vector(-.75, -.2, 3), start);
        assertEquals(.55, SceneGeometry.lunge(.55), 1e-10);
        assertEquals(0, SceneGeometry.lunge(0));
        assertEquals(0, SceneGeometry.lunge(1));
    }
}
