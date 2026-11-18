import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;



/**
 * Deletes only a retired, plugin-owned directory directly inside the world container.
 */
final class WorldFiles {
    static boolean hasSavedWorld(Path container, Path level, String name) {
        return Files.isRegularFile(level.resolve("dimensions/minecraft").resolve(name).resolve("level.dat"))
                || Files.isRegularFile(container.resolve(name).resolve("level.dat"));
    }

    static void delete(Path container, Path directory, String owner, Set<Path> active, Path... dimensionRoots) throws IOException {
        Path root = container.toRealPath();
        Path folder = directory.toAbsolutePath().normalize();
        boolean approvedParent = root.equals(folder.getParent());
        for (Path dimensionRoot : dimensionRoots) {
            Path candidate = dimensionRoot.toAbsolutePath().normalize();
            if (candidate.startsWith(root) && Files.isDirectory(candidate)
                    && candidate.toRealPath().equals(candidate) && candidate.equals(folder.getParent()))
                approvedParent = true;
        }
        if (!owner.matches("[a-f0-9]{32}") || !approvedParent
                || Files.isSymbolicLink(folder) || !folder.toRealPath().equals(folder)
                || !folder.getFileName().toString().matches("hcr_" + owner + "_[0-9]+(_nether|_the_end)?")
                || active.stream().map(p -> p.toAbsolutePath().normalize()).anyMatch(folder::equals))
            throw new IOException("Unsafe world deletion refused: " + folder);
        // No FOLLOW_LINKS: links inside a world are removed without visiting their targets.
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
