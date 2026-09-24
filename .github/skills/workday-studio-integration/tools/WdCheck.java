import javax.tools.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * WdCheck: offline check of a Workday Studio project, using Workday Studio's own XSD schemas, MVEL runtime and
 * diagram reconciler (loaded from the local Studio installation), plus the house conventions of this skill.
 *
 *   java WdCheck.java <project-dir> [--studio <Studio install or plugins dir>] [--ids a,b,c] [--verbose]
 *
 * Steps: 1 XSD  2 MVEL  3 diagram references  4 Studio reconciler (what the editor changes on open)
 *        5 swimlanes (one lane = one flow; arrows stay in their lane)  6 calls, parameters and files.
 * Exit code 0 = no problems (warnings allowed), 1 = problems found, 2 = usage error.
 * Studio location: --studio, env WORKDAY_STUDIO_HOME, or the default install path. Without Studio, steps 1, 2, 4 are skipped.
 * In StarterKit (SSK) projects steps 5-6 check the integration's own code: everything reachable from local-in "Main".
 */
public class WdCheck {
    static final String CC = "http://www.capeclear.com/assembly/10";
    static final String CLOUD = "urn:com.workday/esb/cloud/10.0";
    static final int MAX_LINES = 15;
    static boolean verbose;
    static int problemsTotal, warningsTotal;

    public static void main(String[] args) throws Exception {
        String projectArg = null, studioArg = null, idsArg = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--studio" -> studioArg = args[++i];
                case "--ids" -> idsArg = args[++i];
                case "--verbose" -> verbose = true;
                default -> projectArg = args[i];
            }
        }
        if (projectArg == null) { System.err.println("usage: java WdCheck.java <project-dir> [--studio <dir>] [--ids a,b,c] [--verbose]"); System.exit(2); }
        Path project = Paths.get(projectArg).toAbsolutePath().normalize();
        Path wsar = project.resolve("ws").resolve("WSAR-INF");
        Path asmFile = wsar.resolve("assembly.xml"), diagFile = wsar.resolve("assembly-diagram.xml");
        if (!Files.isRegularFile(asmFile)) { System.err.println("not a Studio project (no ws/WSAR-INF/assembly.xml): " + project); System.exit(2); }

        Path plugins = findPlugins(studioArg);
        Document asm, diag = null;
        try { asm = parse(asmFile); }
        catch (Exception e) { System.out.println("[0] assembly.xml is not well-formed: " + e.getMessage()); System.out.println("RESULT: PROBLEMS FOUND"); System.exit(1); return; }
        try { if (Files.isRegularFile(diagFile)) diag = parse(diagFile); }
        catch (Exception e) { System.out.println("[0] assembly-diagram.xml is not well-formed: " + e.getMessage()); System.out.println("RESULT: PROBLEMS FOUND"); System.exit(1); }

        final Document asmF = asm, diagF = diag;
        Model m = new Model(project, asmF, diagF, idsArg);
        System.out.println("WdCheck " + project.getFileName() + (m.ssk ? " (StarterKit project; own code = " + m.own.size() + " components reachable from Main)" : "")
                + (plugins == null ? "  [Workday Studio not found: steps 1, 2, 4 skipped; use --studio <dir>]" : ""));

        step("1/6 XSD (Studio schemas)", () -> plugins == null ? null : xsd(plugins, asmFile));
        step("2/6 MVEL (Studio runtime)", () -> plugins == null ? null : mvel(plugins, asmF));
        step("3/6 Diagram references", () -> diagF == null ? Result.warn("no assembly-diagram.xml (Studio creates one, all components at 0,0)") : refs(m));
        step("4/6 Studio reconciler", () -> plugins == null || diagF == null ? null : reconcile(plugins, asmFile, diagFile));
        step("5/6 Swimlanes (one lane = one flow)", () -> diagF == null ? null : lanes(m));
        step("6/6 Calls, parameters, files", () -> calls(m, wsar));
        System.out.println(problemsTotal == 0 ? "RESULT: OK" + (warningsTotal > 0 ? " (" + warningsTotal + " warnings)" : "")
                : "RESULT: PROBLEMS FOUND (" + problemsTotal + " problems, " + warningsTotal + " warnings)");
        System.exit(problemsTotal == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- reporting
    static class Result {
        final List<String> problems = new ArrayList<>(), warnings = new ArrayList<>(); String info = "";
        static Result warn(String w) { Result r = new Result(); r.warnings.add(w); return r; }
    }
    interface StepFn { Result run() throws Exception; }
    static void step(String name, StepFn fn) {
        Result r;
        try { r = fn.run(); }
        catch (Throwable t) {
            Throwable root = t; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            r = new Result(); r.problems.add("check crashed: " + oneLine(root.toString()) + (verbose ? "" : " (use --verbose for the stack trace)"));
            if (verbose) t.printStackTrace(System.out);
        }
        String dots = ".".repeat(Math.max(2, 40 - name.length()));
        if (r == null) { System.out.println("[" + name + "] " + dots + " SKIPPED"); return; }
        String status = !r.problems.isEmpty() ? "PROBLEMS (" + r.problems.size() + ")" : !r.warnings.isEmpty() ? "OK, " + r.warnings.size() + " warning(s)" : "OK";
        System.out.println("[" + name + "] " + dots + " " + status + (r.info.isEmpty() ? "" : "  " + r.info));
        print("   ERROR ", r.problems); print("   warn  ", r.warnings);
        problemsTotal += r.problems.size(); warningsTotal += r.warnings.size();
    }
    static void print(String prefix, List<String> lines) {
        int max = verbose ? Integer.MAX_VALUE : MAX_LINES;
        lines.stream().limit(max).forEach(l -> System.out.println(prefix + l));
        if (lines.size() > max) System.out.println(prefix + "... and " + (lines.size() - max) + " more (use --verbose)");
    }
    static String oneLine(String s) { s = s == null ? "null" : s.replaceAll("\\s+", " ").trim(); return s.length() > 220 ? s.substring(0, 220) + "…" : s; }

    // ---------------------------------------------------------------- Studio install
    static Path findPlugins(String hint) {
        List<Path> candidates = new ArrayList<>();
        if (hint != null) candidates.add(Paths.get(hint));   // an explicit --studio is authoritative
        else addDefaults(candidates);
        for (Path c : candidates) {
            if (!Files.isDirectory(c)) continue;
            try (Stream<Path> s = Files.find(c, 5, (p, a) -> a.isDirectory() && p.getFileName() != null && p.getFileName().toString().equals("plugins")
                    && glob(p, "com.capeclear.wtp.facet.assembly_") != null)) {
                Optional<Path> found = s.findFirst();
                if (found.isPresent()) return found.get();
            } catch (IOException ignored) { }
        }
        if (hint != null) System.err.println("WARNING: no Workday Studio plugins found under " + hint);
        return null;
    }
    static void addDefaults(List<Path> candidates) {
        String env = System.getenv("WORKDAY_STUDIO_HOME"); if (env != null) candidates.add(Paths.get(env));
        candidates.add(Paths.get("/Applications/WorkdayStudio"));
        String pf = System.getenv("ProgramFiles"); if (pf != null) { candidates.add(Paths.get(pf, "WorkdayStudio")); candidates.add(Paths.get(pf, "Workday Studio")); }
        String home = System.getProperty("user.home"); candidates.add(Paths.get(home, "WorkdayStudio")); candidates.add(Paths.get("C:\\WorkdayStudio"));
    }
    /** Newest plugin folder or jar in the plugins dir whose name starts with prefix. */
    static Path glob(Path plugins, String prefix) {
        try (Stream<Path> s = Files.list(plugins)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        } catch (IOException e) { return null; }
    }
    static List<Path> jarsIn(Path dir) throws IOException {
        if (dir == null) return List.of();
        if (Files.isRegularFile(dir)) return List.of(dir);
        try (Stream<Path> s = Files.walk(dir)) { return s.filter(p -> p.toString().endsWith(".jar")).collect(Collectors.toList()); }
    }

    // ---------------------------------------------------------------- XML helpers
    static Document parse(Path f) throws Exception {
        DocumentBuilderFactory f2 = DocumentBuilderFactory.newInstance();
        f2.setNamespaceAware(true); f2.setCoalescing(false); f2.setExpandEntityReferences(true);
        return f2.newDocumentBuilder().parse(f.toFile());
    }
    static List<Element> childElements(Node n) {
        List<Element> r = new ArrayList<>();
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element) r.add((Element) c);
        return r;
    }
    static List<Element> descendants(Element e) {
        List<Element> r = new ArrayList<>(); r.add(e);
        NodeList nl = e.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) r.add((Element) nl.item(i));
        return r;
    }
    static String local(Node n) { return n.getLocalName() != null ? n.getLocalName() : n.getNodeName(); }

    // ---------------------------------------------------------------- model shared by steps 3, 5, 6
    static class Model {
        final Path project; final Document asm, diag; final Element assembly;
        final Map<String, Element> components = new LinkedHashMap<>();   // top-level components by id
        final Map<String, Element> localIns = new HashMap<>();
        final String projectName; final boolean ssk; final Set<String> own = new LinkedHashSet<>();
        Model(Path project, Document asm, Document diag, String ids) throws IOException {
            this.project = project; this.asm = asm; this.diag = diag;
            Element a = null;
            for (Element e : childElements(asm.getDocumentElement())) if (CC.equals(e.getNamespaceURI()) && local(e).equals("assembly")) a = e;
            assembly = a;
            if (a != null) for (Element c : childElements(a)) if (!c.getAttribute("id").isEmpty()) {
                components.putIfAbsent(c.getAttribute("id"), c);
                if (local(c).equals("local-in")) localIns.put(c.getAttribute("id"), c);
            }
            Path dotProject = project.resolve(".project");
            String pn = project.getFileName().toString();
            if (Files.isRegularFile(dotProject)) {
                Matcher mm = Pattern.compile("<name>([^<]+)</name>").matcher(Files.readString(dotProject));
                if (mm.find()) pn = mm.group(1).trim();
            }
            projectName = pn;
            ssk = localIns.containsKey("InitializeFrameworkThenRunMain") && localIns.containsKey("Main");
            if (ids != null) own.addAll(Arrays.asList(ids.split(",")));
            else if (!ssk) own.addAll(components.keySet());
            else {   // own code of an SSK project: reachable from Main through routes and vm:// calls into non-framework local-ins
                Deque<String> todo = new ArrayDeque<>(List.of("Main"));
                while (!todo.isEmpty()) {
                    String id = todo.pop(); Element c = components.get(id);
                    if (c == null || !own.add(id)) continue;
                    for (Element d : descendants(c)) for (String at : new String[]{"routes-to", "routes-response-to"}) if (!d.getAttribute(at).isEmpty()) todo.push(d.getAttribute(at));
                    String target = vmTarget(c);
                    if (target != null) { Element li = localIns.get(target); if (li != null && !isFrameworkLocalIn(li)) todo.push(target); }
                }
            }
        }
        /** local-in id called by a local-out with a literal vm://<this project>/<id> endpoint */
        String vmTarget(Element c) {
            if (!local(c).equals("local-out")) return null;
            Matcher mm = Pattern.compile("^vm://([^/@{}]+)/([^/@{}]+)$").matcher(c.getAttribute("endpoint"));
            return mm.matches() && mm.group(1).equals(projectName) ? mm.group(2) : null;
        }
        static boolean isFrameworkLocalIn(Element li) { return !li.getAttribute("icon").isEmpty() || "public".equals(li.getAttribute("access")); }
    }

    // ---------------------------------------------------------------- 1 XSD
    static Result xsd(Path plugins, Path asmFile) throws Exception {
        Result r = new Result();
        Path schemas = glob(plugins, "com.capeclear.wtp.facet.assembly_").resolve("schemas");
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = sf.newSchema(new StreamSource[]{
            new StreamSource(schemas.resolve("spring-beans-2.5.xsd").toFile()), new StreamSource(schemas.resolve("assembly-core.xsd").toFile())});
        Validator v = schema.newValidator();
        v.setErrorHandler(new ErrorHandler() {
            public void warning(SAXParseException e) { }
            public void error(SAXParseException e) { r.problems.add("assembly.xml line " + e.getLineNumber() + ": " + oneLine(e.getMessage())); }
            public void fatalError(SAXParseException e) { error(e); }
        });
        try { v.validate(new StreamSource(asmFile.toFile())); } catch (SAXException e) { if (r.problems.isEmpty()) r.problems.add(oneLine(e.getMessage())); }
        return r;
    }

    // ---------------------------------------------------------------- 2 MVEL
    static final Set<String> EXPR_ATTRS = Set.of("execute-when", "execute-steps-when", "collate-when", "force-batch-when", "condition", "init", "increment", "validation", "split-until");
    static Result mvel(Path plugins, Document asm) throws Exception {
        Result r = new Result();
        Path lib = glob(plugins, "com.workday.sh.capeconnect.libs_").resolve("lib");
        Path jar;
        try (Stream<Path> s = Files.list(lib)) { jar = s.filter(p -> p.getFileName().toString().startsWith("mvel")).findFirst().orElseThrow(); }
        URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Method compile = cl.loadClass("org.mvel.MVEL").getMethod("compileExpression", String.class);
        int[] ok = {0};
        for (Element e : descendants(asm.getDocumentElement())) {
            if (!CC.equals(e.getNamespaceURI())) continue;
            String ln = local(e), where = ln + (e.getAttribute("id").isEmpty() ? "" : "#" + e.getAttribute("id")) + ownerLabel(e);
            if (ln.equals("expression") || ln.equals("condition-expression")) {
                String parent = local(e.getParentNode());
                if (!parent.equals("xpath-strategy") && !parent.equals("regex-strategy")) expr(compile, e.getTextContent(), where, r, ok);
            }
            NamedNodeMap at = e.getAttributes();
            for (int i = 0; i < at.getLength(); i++) {
                Attr a = (Attr) at.item(i); String n = local(a), v = a.getValue();
                boolean isExpr = EXPR_ATTRS.contains(n) || (n.equals("value") && ln.equals("set")) || (n.equals("default") && ln.equals("parameter"))
                        || (n.equals("level") && ln.equals("cloud-log") && !v.matches("debug|info|warn|warning|error|fatal|critical")) || (n.equals("reference-id") && ln.equals("cloud-log"))
                        || (n.equals("expression") && ln.equals("choose-route") && local(e.getParentNode()).equals("mvel-strategy"));
                if (isExpr) { if (!v.isBlank()) expr(compile, v, where + "@" + n, r, ok); }
                else if (v.contains("@{")) template(compile, v, where + "@" + n, r, ok);
            }
            if (ln.equals("text") && e.getTextContent().contains("@{")) template(compile, e.getTextContent(), where + " text", r, ok);
        }
        r.info = "(" + ok[0] + " expressions)";
        return r;
    }
    static String ownerLabel(Element e) {
        for (Node n = e.getParentNode(); n instanceof Element; n = n.getParentNode()) {
            Element p = (Element) n;
            if (p.getParentNode() instanceof Element && local(p.getParentNode()).equals("assembly") && !p.getAttribute("id").isEmpty()) return " in " + p.getAttribute("id");
        }
        return "";
    }
    static void expr(Method compile, String s, String where, Result r, int[] ok) {
        try { compile.invoke(null, s); ok[0]++; }
        catch (Exception ex) {
            Throwable c = ex.getCause() != null ? ex.getCause() : ex;
            String msg = oneLine(c.getMessage());
            if (msg.contains("class not found")) r.warnings.add(where + ": " + msg + " (fine if the class is in this project's src/)");
            else r.problems.add(where + ": " + msg + " :: " + oneLine(s));
        }
    }
    static void template(Method compile, String s, String where, Result r, int[] ok) {
        int depth = 0;
        for (char ch : s.toCharArray()) { if (ch == '{') depth++; if (ch == '}') depth--; if (depth < 0) break; }
        if (depth != 0) { r.problems.add(where + ": unbalanced { } in MVEL template :: " + oneLine(s)); return; }
        int i = 0;
        while ((i = s.indexOf("@{", i)) >= 0) {
            int j = i + 2, d = 1;
            while (j < s.length() && d > 0) { if (s.charAt(j) == '{') d++; else if (s.charAt(j) == '}') d--; j++; }
            expr(compile, s.substring(i + 2, j - 1), where + " template", r, ok);
            i = j;
        }
    }

    // ---------------------------------------------------------------- 3 diagram references
    static final Pattern SEG = Pattern.compile("@([A-Za-z]+)(?:\\.(\\d+))?");
    /** EMF feature-map entries: merged text runs, CDATA, comments, PIs and elements. */
    static List<Node> mixed(Node parent) {
        List<Node> out = new ArrayList<>(); boolean lastText = false;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            short t = c.getNodeType();
            if (t == Node.TEXT_NODE) { if (!lastText) out.add(c); lastText = true; continue; }
            lastText = false;
            if (t == Node.CDATA_SECTION_NODE || t == Node.COMMENT_NODE || t == Node.ELEMENT_NODE || t == Node.PROCESSING_INSTRUCTION_NODE) out.add(c);
        }
        return out;
    }
    static String camel(String s) { StringBuilder b = new StringBuilder(); boolean up = false; for (char c : s.toCharArray()) { if (c == '-') { up = true; continue; } b.append(up ? Character.toUpperCase(c) : c); up = false; } return b.toString(); }
    static Element resolve(Model m, String frag) {
        if (!frag.startsWith("//")) return m.components.get(frag) != null ? m.components.get(frag) : "WorkdayAssembly".equals(frag) || (m.assembly != null && frag.equals(m.assembly.getAttribute("id"))) ? m.assembly : null;
        Node cur = null;
        for (String s : frag.substring(2).split("/")) {
            Matcher mm = SEG.matcher(s);
            if (!mm.matches()) return null;
            String feat = mm.group(1); int idx = mm.group(2) == null ? 0 : Integer.parseInt(mm.group(2));
            if (cur == null) { if (!feat.equals("beans")) return null; cur = m.asm.getDocumentElement(); continue; }
            if (feat.equals("mixed")) {
                List<Node> mc = mixed(cur);
                if (idx >= mc.size() || mc.get(idx).getNodeType() != Node.ELEMENT_NODE) return null;
                cur = mc.get(idx);
            } else {
                String f = feat; List<Element> els = childElements(cur).stream().filter(e -> camel(local(e)).equals(f)).collect(Collectors.toList());
                if (idx >= els.size()) return null;
                cur = els.get(idx);
            }
        }
        return (Element) cur;
    }
    static Result refs(Model m) {
        Result r = new Result();
        Element root = m.diag.getDocumentElement();
        int lanes = 0, decorations = 0, ids = 0, paths = 0;
        for (Element e : childElements(root)) { if (local(e).equals("swimlanes")) lanes++; if (local(e).equals("decorations")) decorations++; }
        Set<Element> withView = new HashSet<>(), inLane = new HashSet<>();
        for (Element e : descendants(root)) {
            List<String> refs = new ArrayList<>();
            if (!e.getAttribute("href").isEmpty()) refs.add(e.getAttribute("href"));
            if (local(e).equals("swimlanes") && !e.getAttribute("elements").isEmpty()) for (String t : e.getAttribute("elements").trim().split("\\s+")) refs.add("#" + t);
            for (String h : refs) {
                if (h.startsWith("assembly.xml#")) {
                    String frag = h.substring("assembly.xml#".length());
                    Element t = resolve(m, frag);
                    if (t == null) { r.problems.add("unresolved " + h + " (in <" + local(e) + ">)"); continue; }
                    if (frag.startsWith("//")) paths++; else ids++;
                    String owner = local(e.getParentNode() instanceof Element ? (Element) e.getParentNode() : e);
                    if (local(e).equals("element") && owner.equals("visualProperties")) withView.add(t);
                    if (local(e).equals("elements")) inLane.add(t);
                } else if (h.startsWith("#//@swimlanes.") || h.startsWith("#//@decorations.")) {
                    int n = Integer.parseInt(h.substring(h.lastIndexOf('.') + 1));
                    if (n >= (h.contains("swimlanes") ? lanes : decorations)) r.problems.add("index out of range: " + h + " (file has " + lanes + " swimlanes, " + decorations + " decorations)");
                } else r.warnings.add("unexpected reference " + h);
            }
        }
        for (Map.Entry<String, Element> c : m.components.entrySet()) {
            if (!m.own.contains(c.getKey())) continue;
            if (!withView.contains(c.getValue())) r.warnings.add(c.getKey() + " has no <visualProperties> (Studio adds it at x=0,y=0)");
            else if (!inLane.contains(c.getValue())) r.warnings.add(c.getKey() + " is in no swimlane (it keeps its own x/y, default 0,0)");
        }
        r.info = "(" + ids + " id refs, " + paths + " index paths)";
        return r;
    }

    // ---------------------------------------------------------------- 4 Studio reconciler
    static Result reconcile(Path plugins, Path asmFile, Path diagFile) throws Exception {
        Result r = new Result();
        List<Path> cp = new ArrayList<>();
        for (String g : new String[]{"org.eclipse.emf.common_", "org.eclipse.emf.ecore_", "org.eclipse.emf.ecore.xmi_", "org.eclipse.draw2d_", "org.eclipse.core.runtime_",
                "org.eclipse.equinox.common_", "org.eclipse.osgi_", "org.eclipse.equinox.preferences_", "org.osgi.service.prefs_", "org.eclipse.jface_", "org.eclipse.jface.text_",
                "org.eclipse.core.commands_", "org.eclipse.core.resources_", "org.eclipse.core.jobs_", "org.eclipse.equinox.registry_", "org.eclipse.gef_",
                "org.eclipse.ui.workbench_", "org.eclipse.text_", "org.eclipse.swt_"}) {
            Path p = glob(plugins, g); if (p != null && p.toString().endsWith(".jar")) cp.add(p);
        }
        try (Stream<Path> s = Files.list(plugins)) { s.filter(p -> p.getFileName().toString().matches("org\\.eclipse\\.swt\\.(cocoa|win32|gtk)\\..*\\.jar")).forEach(cp::add); }
        cp.add(glob(plugins, "org.scala-ide.scala.library_").resolve("scala-library.jar"));
        cp.add(glob(plugins, "com.capeclear.wtp.assembly.model_").resolve("assemblymodel.jar"));
        cp.add(glob(plugins, "com.workday.esb.cloud.model_").resolve("com.workday.esb.cloud.model.jar"));
        cp.add(glob(plugins, "com.workday.sh.editors.notation.model_").resolve("code.jar"));
        cp.add(glob(plugins, "com.workday.sh.sa.editor.assembly_").resolve("code.jar"));
        cp.addAll(jarsIn(glob(plugins, "com.workday.sh.sa.core_")));
        cp.addAll(jarsIn(glob(plugins, "com.workday.sh.common.ui_")));
        cp.addAll(jarsIn(glob(plugins, "com.workday.emfgef.utils_")));

        Path tmp = Files.createTempDirectory("wdcheck");
        try {
            Path src = tmp.resolve("src"), out = tmp.resolve("classes");
            Files.createDirectories(src.resolve("com/workday/sh/sa/editor/assembly/impl/model")); Files.createDirectories(out);
            List<File> files = new ArrayList<>();
            for (String[] s : RUNNER_SOURCES) { Path f = src.resolve(s[0]); Files.writeString(f, s[1]); files.add(f.toFile()); }
            JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
            if (jc == null) { r.warnings.add("no Java compiler available (run with a JDK, not a JRE) - step skipped"); return r; }
            StringWriter diagOut = new StringWriter();
            try (StandardJavaFileManager fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                String classpath = cp.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
                boolean ok = jc.getTask(diagOut, fm, null, List.of("-nowarn", "-proc:none", "-d", out.toString(), "-cp", classpath), null, fm.getJavaFileObjectsFromFiles(files)).call();
                if (!ok) { r.problems.add("could not compile the reconciler harness against this Studio version: " + oneLine(diagOut.toString())); return r; }
            }
            List<URL> urls = new ArrayList<>(); urls.add(out.toUri().toURL());
            for (Path p : cp) urls.add(p.toUri().toURL());
            ClassLoader old = Thread.currentThread().getContextClassLoader();
            String report;
            try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
                Thread.currentThread().setContextClassLoader(cl);
                System.setProperty("java.awt.headless", "true");
                report = (String) cl.loadClass("WdReconcileRunner").getMethod("run", String.class, String.class).invoke(null, asmFile.toString(), diagFile.toString());
            } finally { Thread.currentThread().setContextClassLoader(old); }
            Map<String, Integer> k = new HashMap<>();
            List<String> details = new ArrayList<>();
            for (String line : report.split("\n")) {
                if (line.startsWith("SUMMARY")) for (String kv : line.substring(8).trim().split(" ")) { String[] p = kv.split("="); k.put(p[0], Integer.parseInt(p[1])); }
                else details.add(line);
            }
            if (k.get("unresolvedViews") > 0) r.problems.add(k.get("unresolvedViews") + " <visualProperties> point to nothing (Studio deletes them)");
            if (k.get("unresolvedLaneRefs") > 0) r.problems.add(k.get("unresolvedLaneRefs") + " swimlane members point to nothing");
            if (k.get("removed") > 0) r.problems.add("Studio would delete " + k.get("removed") + " stale connection(s): diagram index paths no longer match assembly.xml (was something inserted or deleted mid-file?)");
            for (String d : details) if (d.startsWith("- ")) r.problems.add("stale connection " + d.substring(2));
            if (k.get("viewsAdded") > 0) r.warnings.add(k.get("viewsAdded") + " component(s) without visualProperties: Studio adds them at x=0,y=0");
            if (k.get("viewsRemoved") > 0) r.warnings.add(k.get("viewsRemoved") + " visualProperties removed by Studio");
            r.info = "(keeps " + k.get("kept") + " connections" + (k.get("added") > 0 ? ", draws " + k.get("added") + " new ones on open" : "") + ")";
            if (verbose) for (String d : details) if (d.startsWith("+ ")) r.warnings.add("will draw " + d.substring(2));
        } finally {
            try (Stream<Path> s = Files.walk(tmp)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
        return r;
    }

    // ---------------------------------------------------------------- 5 swimlanes
    static Result lanes(Model m) {
        Result r = new Result();
        List<Element> lanes = childElements(m.diag.getDocumentElement()).stream().filter(e -> local(e).equals("swimlanes")).collect(Collectors.toList());
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Element, Integer> laneOf = new HashMap<>();
        Map<Integer, List<Element>> members = new HashMap<>();
        for (int i = 0; i < lanes.size(); i++) {
            Element s = lanes.get(i);
            List<String> refs = new ArrayList<>();
            if (!s.getAttribute("elements").isEmpty()) for (String t : s.getAttribute("elements").trim().split("\\s+")) refs.add("#" + t);
            for (Element c : childElements(s)) if (local(c).equals("elements")) refs.add(c.getAttribute("href"));
            for (String h : refs) {
                if (h.startsWith("#//@swimlanes.")) parent.put(Integer.parseInt(h.substring(h.lastIndexOf('.') + 1)), i);
                else if (h.startsWith("assembly.xml#")) {
                    Element t = resolve(m, h.substring("assembly.xml#".length()));
                    if (t == null) continue;
                    if (laneOf.containsKey(t) && m.own.contains(t.getAttribute("id"))) r.warnings.add(t.getAttribute("id") + " is listed in two swimlanes");
                    laneOf.put(t, i); members.computeIfAbsent(i, x -> new ArrayList<>()).add(t);
                }
            }
        }
        int same = 0, nested = 0;
        for (String id : m.own) {
            Element c = m.components.get(id);
            if (c == null) continue;
            for (Element d : descendants(c)) for (String at : new String[]{"routes-to", "routes-response-to"}) {
                String t = d.getAttribute(at);
                if (t.isEmpty()) continue;
                Element te = m.components.get(t);
                Integer ls = laneOf.get(c), lt = te == null ? null : laneOf.get(te);
                String arrow = id + (d == c ? "" : " (" + local(d) + ")") + " -> " + t;
                if (ls == null || lt == null) { r.warnings.add(arrow + ": " + (ls == null ? id : t) + " is in no swimlane"); continue; }
                if (ls.equals(lt)) same++;
                else if (ancestors(parent, ls).contains(lt) || ancestors(parent, lt).contains(ls)) nested++;
                else r.problems.add(arrow + " crosses from lane '" + lanes.get(ls).getAttribute("name") + "' to '" + lanes.get(lt).getAttribute("name")
                        + "' (call the other flow with a local-out to its local-in, or move the element)");
            }
        }
        // each flow lane of own code starts with an in-transport; nested VERTICAL lanes are Try/Catch lanes
        for (Map.Entry<Integer, List<Element>> e : members.entrySet()) {
            List<Element> ms = e.getValue();
            if (ms.stream().noneMatch(x -> m.own.contains(x.getAttribute("id")))) continue;
            Element lane = lanes.get(e.getKey());
            boolean tryCatch = parent.containsKey(e.getKey()) && "VERTICAL".equals(lane.getAttribute("orientation"));
            if (tryCatch) continue;
            String first = local(ms.get(0));
            if (!first.equals("local-in") && !first.equals("workday-in"))
                r.warnings.add("lane '" + lane.getAttribute("name") + "' starts with " + first + " '" + ms.get(0).getAttribute("id") + "' - a flow lane should start with a local-in (or workday-in)");
        }
        r.info = "(" + same + " arrows in-lane, " + nested + " via Try/Catch lanes)";
        return r;
    }
    static Set<Integer> ancestors(Map<Integer, Integer> parent, int i) {
        Set<Integer> s = new HashSet<>();
        while (parent.containsKey(i) && s.add(parent.get(i))) i = parent.get(i);
        return s;
    }

    // ---------------------------------------------------------------- 6 calls, parameters, files
    static Result calls(Model m, Path wsar) {
        Result r = new Result();
        Set<String> declared = new HashSet<>();
        for (Element e : descendants(m.asm.getDocumentElement()))
            if (CLOUD.equals(e.getNamespaceURI()) && !e.getAttribute("name").isEmpty()) declared.add(e.getAttribute("name"));
        Pattern named = Pattern.compile("(lp\\.(?:getSimpleData|getReferenceData|getReferenceDataList|getDate|exists|getParameterData)|intsys\\.(?:getAttribute|getAttributeAsBoolean|getAttributeReferenceData)|getExtrapath|integrationMap(?:Reverse)?Lookup)\\(\\s*'([^']+)'");
        Path workspace = m.project.getParent();
        int checkedCalls = 0;
        for (String id : m.own) {
            Element c = m.components.get(id);
            if (c == null) continue;
            if (local(c).equals("local-out")) {
                String ep = c.getAttribute("endpoint");
                Matcher mm = Pattern.compile("^vm://([^/@{}]+)/([^/@{}]+)$").matcher(ep);
                if (mm.matches() && !mm.group(1).equals("wcc")) {
                    checkedCalls++;
                    if (!mm.group(1).equals(m.projectName)) {
                        if (workspace == null || !Files.isDirectory(workspace.resolve(mm.group(1))))
                            r.problems.add(id + ": endpoint " + ep + " targets project '" + mm.group(1) + "' but this project is '" + m.projectName + "' and no such project is in the workspace");
                        else r.warnings.add(id + ": calls another project (" + ep + "); both must be in the same cloud collection");
                    } else {
                        Element li = m.localIns.get(mm.group(2));
                        if (li == null) r.problems.add(id + ": endpoint " + ep + " - no local-in '" + mm.group(2) + "' in this assembly");
                        else {
                            Set<String> sets = childElements(c).stream().filter(x -> local(x).equals("set")).map(x -> x.getAttribute("name")).collect(Collectors.toSet());
                            Set<String> params = childElements(li).stream().filter(x -> local(x).equals("parameter")).map(x -> x.getAttribute("name")).collect(Collectors.toSet());
                            for (Element p : childElements(li))
                                if (local(p).equals("parameter") && "true".equals(p.getAttribute("required")) && !sets.contains(p.getAttribute("name")))
                                    r.problems.add(id + ": required parameter '" + p.getAttribute("name") + "' of local-in " + mm.group(2) + " is not set (<cc:set name=\"" + p.getAttribute("name") + "\" value=\"...\"/>)");
                            for (String s : sets) if (!params.isEmpty() && !params.contains(s)) r.warnings.add(id + ": sets '" + s + "' which local-in " + mm.group(2) + " does not declare");
                        }
                    }
                }
            }
            for (Element d : descendants(c)) {
                String ln = local(d);
                for (String at : new String[]{"url", "input-file", "schema"}) {
                    String v = d.getAttribute(at);
                    if (v.isEmpty() || v.contains("@{") || v.startsWith("mctx:") || v.contains("://") || (at.equals("schema") && !ln.equals("validate"))) continue;
                    if (!Files.isRegularFile(wsar.resolve(v))) r.problems.add(id + ": " + ln + " " + at + "=\"" + v + "\" - file not found under ws/WSAR-INF/");
                }
                NamedNodeMap attrs = d.getAttributes();
                List<String> texts = new ArrayList<>();
                for (int i = 0; i < attrs.getLength(); i++) texts.add(attrs.item(i).getNodeValue());
                if (ln.equals("expression") || ln.equals("text")) texts.add(d.getTextContent());
                for (String t : texts) {
                    Matcher nm = named.matcher(t);
                    while (nm.find()) if (!declared.contains(nm.group(2)))
                        r.problems.add(id + ": " + nm.group(1) + "('" + nm.group(2) + "') - no launch parameter / attribute / report alias / map with this exact name in the integration system");
                }
            }
        }
        r.info = "(" + checkedCalls + " vm:// calls)";
        return r;
    }

    // ---------------------------------------------------------------- reconciler harness (compiled at run time against the Studio jars)
    static final String[][] RUNNER_SOURCES = {
        {"WdReconcileRunner.java", """
            import org.eclipse.emf.common.util.URI;
            import org.eclipse.emf.ecore.*;
            import org.eclipse.emf.ecore.resource.*;
            import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
            import org.eclipse.emf.ecore.xmi.XMLResource;
            import org.eclipse.emf.ecore.xmi.impl.XMLResourceFactoryImpl;
            import com.workday.sh.editors.notation.model.*;
            import java.io.File;
            import java.util.*;
            /** Runs Workday Studio's own ViewReconciler (what the assembly editor does on open) on a copy in memory. Nothing is saved. */
            public class WdReconcileRunner {
                public static String run(String asmPath, String diagPath) throws Exception {
                    EPackage.Registry.INSTANCE.put(com.capeclear.wtp.assembly.model.AssemblyPackage.eNS_URI, com.capeclear.wtp.assembly.model.AssemblyPackage.eINSTANCE);
                    EPackage.Registry.INSTANCE.put(org.springframework.schema.model.BeansPackage.eNS_URI, org.springframework.schema.model.BeansPackage.eINSTANCE);
                    EPackage.Registry.INSTANCE.put(com.workday.esb.cloud.emf.model.CloudPackage.eNS_URI, com.workday.esb.cloud.emf.model.CloudPackage.eINSTANCE);
                    EPackage.Registry.INSTANCE.put(NotationPackage.eNS_URI, NotationPackage.eINSTANCE);
                    Resource asmRes = com.workday.sh.sa.editor.assembly.impl.model.EcoreXml$.MODULE$.xmlResource(URI.createFileURI(new File(asmPath).getAbsolutePath()));
                    Map<String, Object> opts = new HashMap<>(); opts.put(XMLResource.OPTION_RECORD_UNKNOWN_FEATURE, Boolean.TRUE);
                    asmRes.load(opts);
                    ResourceSet rs = new ResourceSetImpl(); rs.getResources().add(asmRes);
                    XMLResource diagRes = (XMLResource) new XMLResourceFactoryImpl().createResource(URI.createFileURI(new File(diagPath).getAbsolutePath()));
                    rs.getResources().add(diagRes); diagRes.setEncoding("UTF-8"); diagRes.load(null);
                    com.capeclear.wtp.assembly.model.AssemblyType assembly = null;
                    for (Iterator<EObject> it = asmRes.getAllContents(); it.hasNext();) { EObject o = it.next(); if (o instanceof com.capeclear.wtp.assembly.model.AssemblyType) { assembly = (com.capeclear.wtp.assembly.model.AssemblyType) o; break; } }
                    Diagram diagram = (Diagram) diagRes.getContents().get(0);
                    int unresolvedViews = 0, laneRefs = 0, unresolvedLaneRefs = 0;
                    for (VisualProperty v : diagram.getVisualProperties()) if (v.getElement() == null || v.getElement().eIsProxy()) unresolvedViews++;
                    for (Swimlane s : diagram.getSwimlanes()) for (EObject e : s.getElements()) { laneRefs++; if (e.eIsProxy()) unresolvedLaneRefs++; }
                    Set<Object> connBefore = new HashSet<>(diagram.getConnections()), vpBefore = new HashSet<>(diagram.getVisualProperties());
                    new com.workday.sh.sa.editor.assembly.impl.model.ViewReconciler().reconcileDiagram(assembly, diagram);
                    List<String> lines = new ArrayList<>();
                    int kept = 0, added = 0, removed = 0, viewsAdded = 0, viewsRemoved = 0;
                    for (Connection c : diagram.getConnections()) if (connBefore.contains(c)) kept++; else { added++; lines.add("+ " + c.getType() + ": " + label(c.getSource()) + " -> " + label(c.getTarget())); }
                    for (Object o : connBefore) if (!diagram.getConnections().contains(o)) { removed++; Connection c = (Connection) o; lines.add("- " + c.getType() + ": " + label(c.getSource()) + " -> " + label(c.getTarget())); }
                    for (VisualProperty v : diagram.getVisualProperties()) if (!vpBefore.contains(v)) viewsAdded++;
                    for (Object v : vpBefore) if (!diagram.getVisualProperties().contains(v)) viewsRemoved++;
                    lines.add(0, "SUMMARY unresolvedViews=" + unresolvedViews + " laneRefs=" + laneRefs + " unresolvedLaneRefs=" + unresolvedLaneRefs + " kept=" + kept
                        + " added=" + added + " removed=" + removed + " viewsAdded=" + viewsAdded + " viewsRemoved=" + viewsRemoved);
                    return String.join("\\n", lines);
                }
                static String label(EObject o) {
                    if (o == null) return "null";
                    EStructuralFeature f = o.eClass().getEStructuralFeature("id"), n = o.eClass().getEStructuralFeature("name");
                    Object id = f == null ? null : o.eGet(f), name = n == null ? null : o.eGet(n);
                    return o.eClass().getName().replace("Type", "") + (id != null ? "#" + id : name != null ? "[" + name + "]" : "") + (o.eIsProxy() ? "(unresolved)" : "");
                }
            }
            """},
        // The editor's package object reads Eclipse preferences and images at class init, which needs a running OSGi platform.
        // These stand-ins return what the reconciler needs; none of them affects the reconcile logic.
        {"com/workday/sh/sa/editor/assembly/impl/FontPreferences$.java", """
            package com.workday.sh.sa.editor.assembly.impl;
            public final class FontPreferences$ {
                public static FontPreferences$ MODULE$ = new FontPreferences$();
                public org.eclipse.swt.graphics.Font Default() { return null; }
                public org.eclipse.swt.graphics.Font Step() { return null; }
                public org.eclipse.swt.graphics.Font TooltipHeader() { return null; }
                public org.eclipse.swt.graphics.Font TooltipContent() { return null; }
            }
            """},
        {"com/workday/sh/sa/editor/assembly/impl/ColorPreferences$.java", """
            package com.workday.sh.sa.editor.assembly.impl;
            public final class ColorPreferences$ {
                public static ColorPreferences$ MODULE$ = new ColorPreferences$();
                public org.eclipse.swt.graphics.Color Bg() { return null; }
                public org.eclipse.swt.graphics.Color Line() { return null; }
                public org.eclipse.swt.graphics.Color Text() { return null; }
                public org.eclipse.swt.graphics.Color Header() { return null; }
                public org.eclipse.swt.graphics.Color Step() { return null; }
            }
            """},
        {"com/workday/sh/sa/editor/assembly/impl/model/ConnectionType$.java", """
            package com.workday.sh.sa.editor.assembly.impl.model;
            /** Only isNotationConnection is used by ViewReconciler; the real object loads palette images. */
            public final class ConnectionType$ {
                public static ConnectionType$ MODULE$ = new ConnectionType$();
                private final scala.collection.immutable.Set<String> notation = create();
                @SuppressWarnings("unchecked")
                private static scala.collection.immutable.Set<String> create() {
                    try {   // Set$Set2 via reflection: Scala's generic signatures do not match their erasure when called from Java
                        return (scala.collection.immutable.Set<String>) Class.forName("scala.collection.immutable.Set$Set2")
                            .getConstructor(Object.class, Object.class).newInstance("Annotation", "Notation");
                    } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                }
                public scala.collection.immutable.Set<String> isNotationConnection() { return notation; }
            }
            """}
    };
}
