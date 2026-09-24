import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * SskApi: lists the callable local-ins (API) of a StarterKit (SSK) based project, read from its assembly.xml.
 * Nothing about the StarterKit is stored in the skill; the catalog always matches the project's SSK version.
 *
 *   java SskApi.java                        index of the API, from the first SSK project found in the current folder
 *   java SskApi.java <Name>                 parameters of one local-in (e.g. CallRaaS, CreateLogEntry, HandleError)
 *   java SskApi.java --all                  every local-in with its parameters
 *   options: --project <project-dir>        SSK project to read (default: BASE_SSK_Template, else the first SSK project)
 * Call an entry with <cc:local-out endpoint="vm://<ProjectName>/<Name>"> and one <cc:set name="inX" value="MVEL"/> per parameter.
 */
public class SskApi {
    static final String CC = "http://www.capeclear.com/assembly/10";

    public static void main(String[] args) throws Exception {
        String projectArg = null, name = null; boolean all = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--project")) projectArg = args[++i];
            else if (args[i].equals("--all")) all = true;
            else name = args[i];
        }
        Path project = projectArg != null ? Paths.get(projectArg) : findSskProject(Paths.get("."));
        if (project == null) { System.err.println("no StarterKit project found here; use --project <dir> (a project whose assembly has local-in InitializeFrameworkThenRunMain)"); System.exit(2); }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        Document d = f.newDocumentBuilder().parse(project.resolve("ws/WSAR-INF/assembly.xml").toFile());
        Map<String, Element> api = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        NodeList nl = d.getElementsByTagNameNS(CC, "local-in");
        for (int i = 0; i < nl.getLength(); i++) {
            Element li = (Element) nl.item(i);
            if (!li.getAttribute("icon").isEmpty() || "public".equals(li.getAttribute("access"))) api.put(li.getAttribute("id"), li);
        }
        System.out.println("StarterKit API of project " + project.toAbsolutePath().normalize().getFileName() + " (" + api.size() + " callable local-ins)");
        System.out.println("Call: <cc:local-out endpoint=\"vm://<ProjectName>/<Name>\"> + <cc:set name=\"inX\" value=\"MVEL\"/> per parameter. * = required (set it explicitly; use the default if you have nothing better).");
        if (name != null) {
            Element li = api.get(name);
            if (li == null) {
                Element any = null;
                for (int i = 0; i < nl.getLength(); i++) if (((Element) nl.item(i)).getAttribute("id").equalsIgnoreCase(name)) any = (Element) nl.item(i);
                if (any == null) { System.out.println("no local-in '" + name + "'. Available: " + String.join(", ", api.keySet())); System.exit(1); }
                li = any;
                System.out.println("(note: '" + li.getAttribute("id") + "' is internal to the StarterKit, not marked as public API)");
            }
            print(li, Integer.MAX_VALUE);
        } else if (all) api.values().forEach(li -> print(li, 240));
        else {
            System.out.println();
            for (Element li : api.values()) {
                List<Element> ps = params(li);
                String req = ps.stream().filter(p -> "true".equals(p.getAttribute("required"))).map(p -> p.getAttribute("name")).collect(Collectors.joining(", "));
                System.out.println("- " + li.getAttribute("id") + (li.getAttribute("access").equals("public") ? "" : " (internal)") + ": " + ps.size() + " parameters"
                        + (req.isEmpty() ? "" : "; required: " + req));
            }
            System.out.println("\nDetails: java SskApi.java <Name>");
        }
    }

    static void print(Element li, int maxDoc) {
        System.out.println("\n## " + li.getAttribute("id") + (li.getAttribute("tooltip").isEmpty() ? "" : "  - " + clean(li.getAttribute("tooltip"))));
        List<Element> ps = params(li);
        if (ps.isEmpty()) { System.out.println("No parameters."); return; }
        System.out.println("| Parameter | Req | Type | Default | Notes |\n|---|---|---|---|---|");
        for (Element p : ps) {
            String doc = clean(p.getAttribute("documentation"));
            if (doc.length() > maxDoc) doc = doc.substring(0, maxDoc - 1) + "…";
            String val = clean(p.getAttribute("validation"));
            if (!val.isEmpty() && val.length() < 120) doc = (doc.isEmpty() ? "" : doc + " ") + "Validation: `" + val + "`";
            String r = p.getAttribute("required"); r = r.equals("true") ? "*" : (r.isEmpty() || r.equals("false")) ? "" : "`" + clean(r) + "`";
            String def = p.getAttribute("default");
            System.out.println("| `" + p.getAttribute("name") + "` | " + r + " | " + p.getAttribute("type") + " | " + (def.isEmpty() ? "" : "`" + clean(def) + "`") + " | " + doc.replace("|", "\\|") + " |");
        }
        List<String> outs = new ArrayList<>();
        for (Node n = li.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && "out-parameter".equals(e.getLocalName())) outs.add("`" + e.getAttribute("name") + "`");
        if (!outs.isEmpty()) System.out.println("Out-parameters: " + String.join(", ", outs));
    }
    static List<Element> params(Element li) {
        List<Element> r = new ArrayList<>();
        for (Node n = li.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && "parameter".equals(e.getLocalName())) r.add(e);
        return r;
    }
    static String clean(String s) { return s.replaceAll("\\s+", " ").trim(); }

    static Path findSskProject(Path dir) throws Exception {
        List<Path> candidates = new ArrayList<>();
        Path preferred = dir.resolve("BASE_SSK_Template");
        if (isSsk(preferred)) return preferred;
        try (Stream<Path> s = Files.list(dir)) { s.filter(Files::isDirectory).sorted().forEach(candidates::add); }
        for (Path p : candidates) if (isSsk(p)) return p;
        return isSsk(dir) ? dir : null;
    }
    static boolean isSsk(Path p) {
        Path a = p.resolve("ws/WSAR-INF/assembly.xml");
        if (!Files.isRegularFile(a)) return false;
        try { return Files.readString(a).contains("id=\"InitializeFrameworkThenRunMain\""); } catch (Exception e) { return false; }
    }
}
