import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class MakeJar {
    public static void main(String[] args) throws Exception {
        Path targetDir = Path.of("E:\\orq-bolsalaboral\\BolsaDeployManager\\target");
        Path classesDir = targetDir.resolve("classes");
        Path libDir = targetDir.resolve("lib");
        Path jarPath = targetDir.resolve("bolsadeploy-manager.jar");

        Manifest manifest = new Manifest();
        Attributes mainAttrs = manifest.getMainAttributes();
        mainAttrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mainAttrs.put(Attributes.Name.MAIN_CLASS, "com.bolsadeploy.ui.Launcher");

        StringBuilder cp = new StringBuilder();
        try (var s = Files.list(libDir)) {
            s.filter(p -> p.toString().endsWith(".jar"))
             .sorted()
             .forEach(p -> cp.append("lib/").append(p.getFileName().toString()).append(" "));
        }
        mainAttrs.put(Attributes.Name.CLASS_PATH, cp.toString().trim());

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()), manifest)) {
            try (var s = Files.walk(classesDir)) {
                s.forEach(p -> {
                    if (Files.isDirectory(p)) return;
                    String rel = classesDir.relativize(p).toString().replace("\\", "/");
                    try {
                        JarEntry entry = new JarEntry(rel);
                        jos.putNextEntry(entry);
                        Files.copy(p, jos);
                        jos.closeEntry();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.println("JAR construido exitosamente con Manifest oficial: " + Files.size(jarPath) + " bytes");
    }
}
