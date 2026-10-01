import androidx.compose.ui.graphics.vector.ImageVector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Pattern;

class MaterialIconsSmokeTest {
    public static void main(String[] args) throws Exception {
        var iconImport = Pattern.compile(
                "^import (androidx\\.compose\\.material\\.icons\\.(?:automirrored\\.)?\\w+)\\.(\\w+)(?: as \\w+)?$");
        var imports = new TreeSet<String>();
        try (var sources = Files.walk(Path.of(args[0]))) {
            for (var source : sources.filter(path -> path.toString().endsWith(".kt")).toList()) {
                for (var line : Files.readAllLines(source)) {
                    if (iconImport.matcher(line.trim()).matches()) imports.add(line.trim());
                }
            }
        }
        if (imports.isEmpty()) throw new AssertionError("No icon imports found");
        for (var icon : imports) {
            var match = iconImport.matcher(icon);
            if (!match.matches()) throw new AssertionError(icon);
            var name = match.group(2);
            var holder = Class.forName(match.group(1) + "." + name + "Kt");
            var getter = java.util.Arrays.stream(holder.getMethods())
                    .filter(method -> method.getName().equals("get" + name) && method.getParameterCount() == 1)
                    .findFirst().orElseThrow();
            var receiver = getter.getParameterTypes()[0].getField("INSTANCE").get(null);
            var vector = (ImageVector) getter.invoke(null, receiver);
            if (vector.getRoot().getSize() == 0 || vector.getViewportWidth() <= 0 || vector.getViewportHeight() <= 0) {
                throw new AssertionError("Empty icon: " + icon);
            }
            if (match.group(1).contains(".automirrored.") && !vector.getAutoMirror()) {
                throw new AssertionError("Auto-mirroring lost: " + icon);
            }
        }
        System.out.println("Material icons: all " + imports.size() + " imported icons initialized with paths; auto-mirroring passed");
    }
}
