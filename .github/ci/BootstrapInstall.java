import de.agentcodi.storage.PackageBootstrap;
import de.agentcodi.storage.WorkspaceLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.InputStream;

public final class BootstrapInstall {
    public static void main(String[] args) throws Exception {
        Path directory = Paths.get(args[0]);
        WorkspaceLayout layout = WorkspaceLayout.create(directory.resolve("root").toFile());
        try (InputStream archive = Files.newInputStream(directory.resolve("bootstrap-aarch64.zip"));
             InputStream manifest = Files.newInputStream(directory.resolve("BOOTSTRAP-MANIFEST"))) {
            PackageBootstrap.prepare(layout, archive, manifest, prefix -> { });
        }
    }
}
