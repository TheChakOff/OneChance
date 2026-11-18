import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;



final class SnapshotFiles {
    static void validate(Path source) throws IOException {
        if (!Files.isDirectory(source) || Files.isSymbolicLink(source)
                || !source.toRealPath().equals(source.toAbsolutePath().normalize()))
            throw new IOException("Missing or linked snapshot directory: " + source);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther())
                    throw new IOException("Linked snapshot directory: " + dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) throw new IOException("Non-regular snapshot file: " + file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void copy(Path source, Path destination) throws IOException {
        if (Files.isSymbolicLink(source) || !source.toRealPath().equals(source.toAbsolutePath().normalize()))
            throw new IOException("Snapshot source contains a link: " + source);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Linked directory: " + dir);
                Files.createDirectories(destination.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) throw new IOException("Non-regular snapshot file: " + file);
                if (!file.getFileName().toString().equals("session.lock"))
                    Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void replace(Path source, Path target, Path safety) throws IOException {
        if (Files.isSymbolicLink(target)) throw new IOException("Linked restore destination: " + target);
        Path staged = target.resolveSibling(target.getFileName() + ".restore-" + java.util.UUID.randomUUID());
        copy(source, staged);
        if (Files.exists(target)) {
            Files.createDirectories(safety.getParent());
            Files.move(target, safety);
        }
        Files.move(staged, target);
    }
}
