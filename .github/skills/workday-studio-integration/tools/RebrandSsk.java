import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Rebrands a copy of the StarterKit template project (BASE_SSK_Template) for a new integration.
 *
 *   java RebrandSsk.java <project-dir> <NewProjectName> "<New Integration System Name>" <newpackage> [--dry-run]
 *   e.g. java RebrandSsk.java ../../INT_Payroll_Export INT_Payroll_Export "INT Payroll Export" intpayrollexport
 *
 * Replaces exactly three distinctive tokens in text files (never short/common words), moves the Java packages,
 * deletes build/ and verifies that no old token is left. Works the same on macOS, Linux and Windows (JDK 17+).
 * Source tokens can be overridden: --from-project X --from-name "X Y" --from-package x
 */
public class RebrandSsk {
    static final Set<String> TEXT_EXT = Set.of(".xml", ".java", ".xsl", ".xslt", ".xsd", ".properties", ".prefs",
            ".component", ".mf", ".txt", ".json", ".csv", ".project", ".classpath", ".html");

    public static void main(String[] args) throws IOException {
        List<String> pos = new ArrayList<>();
        String fromProject = "BASE_SSK_Template", fromName = "BASE SSK Template", fromPkg = "ssktemplate";
        boolean dryRun = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run" -> dryRun = true;
                case "--from-project" -> fromProject = args[++i];
                case "--from-name" -> fromName = args[++i];
                case "--from-package" -> fromPkg = args[++i];
                default -> pos.add(args[i]);
            }
        }
        if (pos.size() != 4) {
            System.err.println("usage: java RebrandSsk.java <project-dir> <NewProjectName> \"<New Integration System Name>\" <newpackage> [--dry-run]");
            System.exit(2);
        }
        Path dir = Paths.get(pos.get(0)).toAbsolutePath().normalize();
        String project = pos.get(1), name = pos.get(2), pkg = pos.get(3);
        require(Files.isRegularFile(dir.resolve("ws/WSAR-INF/assembly.xml")), "not a Studio project (no ws/WSAR-INF/assembly.xml): " + dir);
        require(project.matches("[A-Za-z][A-Za-z0-9_]*"), "project name must match [A-Za-z][A-Za-z0-9_]* (it becomes the vm:// service name)");
        require(pkg.matches("[a-z][a-z0-9_]*"), "package must be a lowercase Java identifier segment, e.g. intpayrollexport");
        require(!name.isBlank() && name.equals(name.trim()), "integration system name must be non-empty without leading/trailing spaces");
        String assembly = Files.readString(dir.resolve("ws/WSAR-INF/assembly.xml"), StandardCharsets.ISO_8859_1);
        require(assembly.contains("vm://" + fromProject + "/"), "assembly.xml has no vm://" + fromProject + "/ endpoints - is this a copy of the SSK template (or already rebranded)?");

        String[][] tokens = {{fromProject, project}, {fromName, name}, {fromPkg, pkg}};
        Path build = dir.resolve("build");
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).filter(p -> !p.startsWith(build)).filter(RebrandSsk::isText).collect(Collectors.toList());
        }
        int changedFiles = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Path f : files) {
            // ISO-8859-1 maps bytes 1:1, so non-ASCII content survives untouched; all tokens are ASCII.
            String t = Files.readString(f, StandardCharsets.ISO_8859_1), n = t;
            for (String[] tk : tokens) {
                int c = count(n, tk[0]);
                if (c > 0) { counts.merge(tk[0], c, Integer::sum); n = n.replace(tk[0], tk[1]); }
            }
            if (!n.equals(t)) { changedFiles++; if (!dryRun) Files.writeString(f, n, StandardCharsets.ISO_8859_1); }
        }
        System.out.println((dryRun ? "[dry-run] " : "") + "files changed: " + changedFiles);
        counts.forEach((k, v) -> System.out.println("  " + v + " x '" + k + "'"));

        for (String base : new String[]{"src/com/workday/custom", "test/com/workday/custom/aunit"}) {
            Path from = dir.resolve(base).resolve(fromPkg), to = dir.resolve(base).resolve(pkg);
            if (Files.isDirectory(from)) {
                require(!Files.exists(to), "target package directory already exists: " + to);
                System.out.println("move " + dir.relativize(from) + " -> " + dir.relativize(to));
                if (!dryRun) Files.move(from, to);
            }
        }
        if (Files.isDirectory(build)) {
            System.out.println("delete build/ (compiled classes of the old package)");
            if (!dryRun) deleteTree(build);
        }
        if (dryRun) return;

        List<String> left = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(Files::isRegularFile).filter(RebrandSsk::isText).collect(Collectors.toList())) {
                String t = Files.readString(f, StandardCharsets.ISO_8859_1);
                for (String[] tk : tokens) if (t.contains(tk[0])) left.add(dir.relativize(f) + " still contains '" + tk[0] + "'");
            }
        }
        if (!left.isEmpty()) { left.forEach(System.out::println); System.out.println("RESULT: INCOMPLETE"); System.exit(1); }
        System.out.println("RESULT: OK - refresh the project in Studio (F5) and run Project > Clean");
    }

    static boolean isText(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return TEXT_EXT.contains(dot >= 0 ? n.substring(dot) : n) || n.equals(".project") || n.equals(".classpath");
    }
    static int count(String s, String t) { int c = 0, i = 0; while ((i = s.indexOf(t, i)) >= 0) { c++; i += t.length(); } return c; }
    static void require(boolean ok, String msg) { if (!ok) { System.err.println("ERROR: " + msg); System.exit(2); } }
    static void deleteTree(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(x);
        }
    }
}
