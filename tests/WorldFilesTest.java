import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorldFilesTest {
    private static final String OWNER = "0123456789abcdef0123456789abcdef";
    @TempDir
    Path root;

    @Test
    void detectsSavedWorldInPaperDimensionDirectory() throws Exception {
        Path level = Files.createDirectory(root.resolve("world"));
        String name = "hcr_" + OWNER + "_4";
        assertFalse(WorldFiles.hasSavedWorld(root, level, name));
        Path world = Files.createDirectories(level.resolve("dimensions/minecraft").resolve(name));
        Files.writeString(world.resolve("level.dat"), "saved spawn");
        assertTrue(WorldFiles.hasSavedWorld(root, level, name), "Existing Paper worlds must not run a new spawn search");
        assertFalse(WorldFiles.hasSavedWorld(root, level, name + "_nether"));
    }

    @Test
    void stillRecognizesLegacyWorldDirectories() throws Exception {
        Path level = Files.createDirectory(root.resolve("world"));
        String name = "hcr_" + OWNER + "_2";
        Path world = Files.createDirectory(root.resolve(name));
        Files.writeString(world.resolve("level.dat"), "legacy");
        assertTrue(WorldFiles.hasSavedWorld(root, level, name));
    }

    @Test
    void removesRetiredWorldAndKeepsOriginal() throws Exception {
        Path original = Files.createDirectory(root.resolve("world"));
        Path old = Files.createDirectory(root.resolve("hcr_" + OWNER + "_1_nether"));
        Files.createDirectories(old.resolve("region"));
        Files.writeString(old.resolve("region/r.0.0.mca"), "test");
        WorldFiles.delete(root, old, OWNER, Set.of());
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(original));
    }

    @Test
    void refusesOriginalActiveAndForeignWorlds() throws Exception {
        for (String name : new String[]{"world", "hcr_ffffffffffffffffffffffffffffffff_1", "hcr_" + OWNER + "_2"}) {
            Path world = Files.createDirectory(root.resolve(name));
            Path sentinel = Files.writeString(world.resolve("level.dat"), "keep");
            Set<Path> active = name.endsWith("_2") ? Set.of(world) : Set.of();
            assertThrows(IOException.class, () -> WorldFiles.delete(root, world, OWNER, active));
            assertEquals("keep", Files.readString(sentinel));
        }
    }

    @Test
    void refusesParentTraversalAndNestedDirectories() throws Exception {
        Path nested = Files.createDirectories(root.resolve("other/hcr_" + OWNER + "_1"));
        assertThrows(IOException.class, () -> WorldFiles.delete(root, nested, OWNER, Set.of()));
        assertTrue(Files.exists(nested));
        assertThrows(IOException.class, () -> WorldFiles.delete(root, root.resolve(".."), OWNER, Set.of()));
    }

    @Test
    void removesRetiredDimensionInPaper263Layout() throws Exception {
        Path mainWorld = Files.createDirectory(root.resolve("world"));
        Path level = Files.writeString(mainWorld.resolve("level.dat"), "main world");
        Path dimensions = Files.createDirectories(mainWorld.resolve("dimensions/minecraft"));
        Path old = Files.createDirectories(dimensions.resolve("hcr_" + OWNER + "_1_nether/region")).getParent();
        WorldFiles.delete(root, old, OWNER, Set.of(), dimensions);
        assertFalse(Files.exists(old));
        assertEquals("main world", Files.readString(level));
    }

    @Test
    void refusesActiveOrForeignDimensionInPaper263Layout() throws Exception {
        Path dimensions = Files.createDirectories(root.resolve("world/dimensions/minecraft"));
        Path active = Files.createDirectory(dimensions.resolve("hcr_" + OWNER + "_2"));
        Path vanilla = Files.createDirectory(dimensions.resolve("the_nether"));
        assertThrows(IOException.class, () -> WorldFiles.delete(root, active, OWNER, Set.of(active), dimensions));
        assertThrows(IOException.class, () -> WorldFiles.delete(root, vanilla, OWNER, Set.of(), dimensions));
        assertTrue(Files.exists(active));
        assertTrue(Files.exists(vanilla));
    }
}
