import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * StudioDocs: reads the Workday Studio documentation and assembly schema from the LOCAL Studio installation
 * (nothing is copied into the repository; the text always matches the installed Studio version).
 *
 *   java StudioDocs.java element <name>          help page(s) for an assembly element + its schema (attributes, defaults, children)
 *   java StudioDocs.java search <words...>       ranked help topics with a snippet
 *   java StudioDocs.java show <title | id>       plain text of one help topic
 *   java StudioDocs.java toc [filter]            table of contents (optionally filtered)
 *   java StudioDocs.java elements                all element/component names that have context help
 * Options: --studio <Studio install or plugins dir> (or env WORKDAY_STUDIO_HOME), --max <chars> (default 12000)
 */
public class StudioDocs {
    static final String XS = "http://www.w3.org/2001/XMLSchema";
    static int max = 12000;
    static Path help, plugins;

    public static void main(String[] args) throws Exception {
        List<String> a = new ArrayList<>();
        String studio = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--studio")) studio = args[++i];
            else if (args[i].equals("--max")) max = Integer.parseInt(args[++i]);
            else a.add(args[i]);
        }
        if (a.isEmpty()) { usage(); return; }
        plugins = findPlugins(studio);
        if (plugins == null) { System.err.println("Workday Studio not found; pass --studio <dir> or set WORKDAY_STUDIO_HOME"); System.exit(2); }
        help = glob(plugins, "com.workday.studio.help_");
        String cmd = a.get(0), arg = String.join(" ", a.subList(1, a.size())).trim();
        switch (cmd) {
            case "element" -> element(arg);
            case "search" -> search(arg);
            case "show" -> show(arg);
            case "toc" -> toc(arg);
            case "elements" -> elements();
            default -> usage();
        }
    }
    static void usage() {
        System.out.println("usage: java StudioDocs.java element <name> | search <words> | show <title|id> | toc [filter] | elements   [--studio <dir>] [--max <chars>]");
        System.exit(2);
    }

    // ------------------------------------------------------------------ commands
    static void element(String name) throws Exception {
        if (name.isEmpty()) usage();
        Map<String, Element> ctx = contexts();
        Element c = ctx.get(name.toLowerCase(Locale.ROOT));
        if (c == null) {   // tolerate "PutIntegrationMessage", "http out", "cc:http-out"
            String n = name.toLowerCase(Locale.ROOT).replace("cc:", "").replace(' ', '-');
            c = ctx.get(n) != null ? ctx.get(n) : ctx.get(n.replace("-", ""));
        }
        if (c != null) {
            System.out.println("# " + c.getAttribute("title") + "  (context help: " + c.getAttribute("id") + ")");
            NodeList d = c.getElementsByTagName("description");
            if (d.getLength() > 0) System.out.println(clean(d.item(0).getTextContent()));
            NodeList topics = c.getElementsByTagName("topic");
            List<String> hrefs = new ArrayList<>();
            for (int i = 0; i < topics.getLength(); i++) {
                Element t = (Element) topics.item(i);
                String href = t.getAttribute("href").replaceAll("^.*cmswdstudio/", "");
                hrefs.add(href);
                System.out.println("  related topic: " + t.getAttribute("label") + "  [" + href.replace(".html", "") + "]");
            }
            // Prefer the page titled "Reference: <title> Properties" (some context links in Studio's help point to a neighbouring page)
            String want = ("Reference: " + c.getAttribute("title") + " Properties").toLowerCase(Locale.ROOT);
            String ref = pages().stream().filter(f -> title(f).toLowerCase(Locale.ROOT).equals(want)).findFirst()
                .orElse(hrefs.stream().filter(h -> title(h).startsWith("Reference")).findFirst().orElse(hrefs.isEmpty() ? null : hrefs.get(0)));
            if (ref != null) { System.out.println(); printPage(ref); }
        } else System.out.println("(no context help named '" + name + "'; try: java StudioDocs.java search " + name + ")");
        String schema = schemaSummary(name.replace("cc:", ""));
        if (schema != null) { System.out.println(); System.out.println(schema); }
    }

    static void search(String q) throws IOException {
        if (q.isEmpty()) usage();
        List<String> terms = Arrays.stream(q.toLowerCase(Locale.ROOT).split("\\s+")).filter(t -> !t.isEmpty()).collect(Collectors.toList());
        record Hit(String file, String title, int score, boolean all, String snippet) { }
        List<Hit> hits = new ArrayList<>();
        for (String f : pages()) {
            String title = title(f), full = text(f), text = full.startsWith(title) ? full.substring(title.length()) : full;
            String lt = text.toLowerCase(Locale.ROOT), ltitle = title.toLowerCase(Locale.ROOT);
            int score = 0; boolean all = true; int firstPos = -1;
            for (String t : terms) {
                int inTitle = count(ltitle, t), inBody = Math.min(count(lt, t), 20);
                if (inTitle + inBody == 0) all = false;
                score += inTitle * 15 + inBody;
                int p = lt.indexOf(t); if (p >= 0 && (firstPos < 0 || p < firstPos)) firstPos = p;
            }
            if (score == 0) continue;
            String snippet = firstPos < 0 ? "" : text.substring(Math.max(0, firstPos - 60), Math.min(text.length(), firstPos + 140)).replaceAll("\\s+", " ");
            hits.add(new Hit(f, title, score, all, snippet));
        }
        hits.sort(Comparator.comparing((Hit h) -> !h.all()).thenComparing(h -> -h.score()));
        hits.stream().limit(12).forEach(h -> System.out.println("- " + h.title() + "  [" + h.file().replace(".html", "") + "]\n    …" + h.snippet() + "…"));
        if (hits.isEmpty()) System.out.println("no topic matches: " + q);
        else System.out.println("\nRead one with: java StudioDocs.java show <id in brackets>");
    }

    static void show(String q) throws IOException {
        if (q.isEmpty()) usage();
        List<String> pages = pages();
        String exact = pages.stream().filter(f -> f.replace(".html", "").equalsIgnoreCase(q) || title(f).equalsIgnoreCase(q)).findFirst().orElse(null);
        if (exact != null) { printPage(exact); return; }
        List<String> m = pages.stream().filter(f -> title(f).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        if (m.size() == 1) printPage(m.get(0));
        else if (m.isEmpty()) System.out.println("no topic titled like '" + q + "'; try: java StudioDocs.java search " + q);
        else { System.out.println("several topics match, pick one:"); m.forEach(f -> System.out.println("- " + title(f) + "  [" + f.replace(".html", "") + "]")); }
    }

    static void toc(String filter) throws Exception {
        Element tocDecl = (Element) xml(help.resolve("plugin.xml")).getElementsByTagName("toc").item(0);
        Document d = xml(help.resolve(tocDecl.getAttribute("file")));
        walkToc(d.getDocumentElement(), "", filter.toLowerCase(Locale.ROOT));
    }
    static void walkToc(Element e, String path, String filter) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element t) || !t.getTagName().equals("topic")) continue;
            String label = t.getAttribute("label"), p = path.isEmpty() ? label : path + " > " + label;
            String id = t.getAttribute("href").replaceAll("^.*/", "").replace(".html", "");
            if (filter.isEmpty() || p.toLowerCase(Locale.ROOT).contains(filter)) {
                int depth = path.isEmpty() ? 0 : path.split(" > ").length;
                System.out.println("  ".repeat(filter.isEmpty() ? depth : 0) + (filter.isEmpty() ? label : p) + (id.isEmpty() ? "" : "  [" + id + "]"));
            }
            walkToc(t, p, filter);
        }
    }

    static void elements() throws Exception {
        Set<String> names = new TreeSet<>();
        NodeList nl = xml(help.resolve("help_contexts_assembly.xml")).getElementsByTagName("context");
        for (int i = 0; i < nl.getLength(); i++) names.add(((Element) nl.item(i)).getAttribute("id"));
        System.out.println(names.size() + " assembly elements/components with context help (use: java StudioDocs.java element <name>):");
        System.out.println(String.join(", ", names));
    }

    // ------------------------------------------------------------------ help files
    static Map<String, Element> contexts() throws Exception {
        Map<String, Element> m = new LinkedHashMap<>();
        for (String f : new String[]{"help_contexts_assembly.xml", "help_contexts_general.xml"}) {
            Path p = help.resolve(f);
            if (!Files.isRegularFile(p)) continue;
            NodeList nl = xml(p).getElementsByTagName("context");
            for (int i = 0; i < nl.getLength(); i++) { Element c = (Element) nl.item(i); m.putIfAbsent(c.getAttribute("id").toLowerCase(Locale.ROOT), c); }
        }
        return m;
    }
    static List<String> pages() throws IOException {
        try (Stream<Path> s = Files.list(help.resolve("cmswdstudio"))) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".html")).sorted().collect(Collectors.toList());
        }
    }
    static final Map<String, String> titles = new HashMap<>(), texts = new HashMap<>();
    static String raw(String file) {
        try { return Files.readString(help.resolve("cmswdstudio").resolve(file), StandardCharsets.UTF_8); } catch (IOException e) { return ""; }
    }
    static String title(String file) {
        return titles.computeIfAbsent(file, f -> { Matcher m = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL).matcher(raw(f)); return m.find() ? unescape(m.group(1)).trim() : f; });
    }
    static String text(String file) { return texts.computeIfAbsent(file, f -> htmlToText(raw(f))); }
    static void printPage(String file) {
        String t = text(file);
        if (t.startsWith(title(file))) t = t.substring(title(file).length()).trim();   // the h1 repeats the title
        System.out.println("## " + title(file) + "  [" + file.replace(".html", "") + "]");
        System.out.println(t.length() > max ? t.substring(0, max) + "\n… (truncated; use --max to see more)" : t);
    }

    /** XHTML help page -> compact plain text; tables become "cell | cell | cell" rows. */
    static String htmlToText(String h) {
        h = h.replaceAll("(?is)<head.*?</head>", "").replaceAll("(?is)<(script|style)\\b.*?</\\1>", "");
        h = h.replaceAll("(?is)<pre\\b[^>]*>(.*?)</pre>", "\n```\n$1\n```\n");
        h = h.replaceAll("(?i)<code\\b[^>]*>", "`").replaceAll("(?i)</code>", "`");
        h = h.replaceAll("(?i)<h1\\b[^>]*>", "\n").replaceAll("(?i)<h[2-6]\\b[^>]*>", "\n### ").replaceAll("(?i)</h[1-6]>", "\n");
        h = replaceEach(h, "(?is)<tr\\b[^>]*>(.*?)</tr>", tr -> "\n" + Pattern.compile("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>").matcher(tr).results()
                .map(c -> inline(c.group(1))).collect(Collectors.joining(" | ")) + "\n");
        h = replaceEach(h, "(?is)<li\\b[^>]*>(.*?)</li>", li -> "\n- " + inline(li) + "\n");
        h = h.replaceAll("(?i)<(br|p|div|dt|dd|table|ul|ol)\\b[^>]*>", "\n").replaceAll("(?i)</(p|div|dt|dd|table|ul|ol)>", "\n");
        h = unescape(h.replaceAll("(?s)<[^>]+>", ""));
        StringBuilder out = new StringBuilder();
        for (String line : h.split("\n")) {
            String l = line.replaceAll("[ \\t\\u00a0]+", " ").trim();
            if (l.isEmpty()) continue;
            out.append(l).append('\n');
        }
        return out.toString().trim();
    }
    static String unescape(String s) {
        Matcher m = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String e = m.group(1), r;
            if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            else r = switch (e) { case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">"; case "quot" -> "\""; case "apos" -> "'"; case "nbsp" -> " "; case "rsquo", "lsquo" -> "'"; case "ldquo", "rdquo" -> "\""; case "ndash" -> "-"; case "mdash" -> "—"; default -> m.group(); };
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }
    /** One-line text of an HTML fragment (used for table cells and list items). */
    static String inline(String html) {
        String t = html.replaceAll("(?i)<code\\b[^>]*>\\s*", "`").replaceAll("(?i)\\s*</code>", "`").replaceAll("(?i)<li\\b[^>]*>", "; ").replaceAll("(?s)<[^>]+>", " ");
        return unescape(t).replaceAll("[\\s\\u00a0]+", " ").replaceAll("^[; ]+", "").trim();
    }
    static String replaceEach(String s, String regex, java.util.function.Function<String, String> f) {
        Matcher m = Pattern.compile(regex).matcher(s); StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(f.apply(m.group(1))));
        m.appendTail(b); return b.toString();
    }
    static String clean(String s) { return s.replaceAll("\\s+", " ").trim(); }
    static int count(String s, String t) { int c = 0, i = 0; while ((i = s.indexOf(t, i)) >= 0) { c++; i += t.length(); } return c; }

    // ------------------------------------------------------------------ schema (assembly-core.xsd = what cloud assemblies may use)
    static Map<String, Element> xsdElements = new HashMap<>(), xsdTypes = new HashMap<>(), xsdGroups = new HashMap<>(), xsdAttrGroups = new HashMap<>(), xsdSimple = new HashMap<>();
    static String schemaSummary(String name) throws Exception {
        Path schemas = glob(plugins, "com.capeclear.wtp.facet.assembly_").resolve("schemas");
        for (String f : new String[]{"assembly-core.xsd", "integration-system.xsd"}) {
            Path p = schemas.resolve(f);
            if (!Files.isRegularFile(p)) continue;
            for (Node n = xml(p).getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element e) || !XS.equals(e.getNamespaceURI())) continue;
                String nm = e.getAttribute("name");
                switch (e.getLocalName()) {
                    case "element" -> xsdElements.putIfAbsent(nm, e);
                    case "complexType" -> xsdTypes.putIfAbsent(nm, e);
                    case "group" -> xsdGroups.putIfAbsent(nm, e);
                    case "attributeGroup" -> xsdAttrGroups.putIfAbsent(nm, e);
                    case "simpleType" -> xsdSimple.putIfAbsent(nm, e);
                    default -> { }
                }
            }
        }
        Element el = xsdElements.get(name);
        List<String> members = xsdElements.values().stream().filter(x -> strip(x.getAttribute("substitutionGroup")).equals(name)).map(x -> x.getAttribute("name")).sorted().collect(Collectors.toList());
        if (el == null && members.isEmpty()) {
            boolean nonCloud = Files.readString(schemas.resolve("assembly.xsd")).contains("name=\"" + name + "\"");
            return nonCloud ? "## Schema: '" + name + "' exists only in the non-cloud assembly.xsd - NOT allowed in Workday cloud assemblies." : null;
        }
        StringBuilder b = new StringBuilder("## Schema (assembly-core.xsd, installed Studio)");
        if (el != null) {
            Map<String, String[]> attrs = new LinkedHashMap<>(); Map<String, String> kids = new LinkedHashMap<>();
            if (!el.getAttribute("type").isEmpty()) collect(xsdTypes.get(strip(el.getAttribute("type"))), attrs, kids);
            for (Element c : children(el)) if (c.getLocalName().equals("complexType")) collect(c, attrs, kids);
            b.append("\n<").append(name).append(">");
            if (!el.getAttribute("substitutionGroup").isEmpty()) b.append("  (a kind of ").append(strip(el.getAttribute("substitutionGroup"))).append(")");
            List<String> req = new ArrayList<>(), opt = new ArrayList<>();
            attrs.forEach((k, v) -> ("required".equals(v[1]) ? req : opt).add(k + " : " + v[0] + enumValues(v[0]) + (v[2].isEmpty() ? "" : " = " + v[2])));
            b.append("\nrequired attributes: ").append(req.isEmpty() ? "-" : String.join(", ", req));
            b.append("\noptional attributes: ").append(opt.isEmpty() ? "-" : String.join(", ", opt));
            b.append("\nchild elements (in this order): ").append(kids.isEmpty() ? "-" : kids.entrySet().stream().map(e -> e.getKey() + e.getValue()).collect(Collectors.joining(", ")));
            b.append("\n(attribute type MVELExpressionType = MVEL code; MVELTemplateType = text with @{...}; IDREF = id of a top-level component)");
        }
        if (!members.isEmpty()) b.append("\nelements that can be used where <").append(name).append("> is expected: ").append(String.join(", ", members));
        return b.toString();
    }
    static void collect(Element ct, Map<String, String[]> attrs, Map<String, String> kids) {
        if (ct == null) return;
        for (Element n : children(ct)) {
            switch (n.getLocalName()) {
                case "complexContent", "simpleContent" -> {
                    for (Element ext : children(n)) {
                        if (!ext.getLocalName().equals("extension") && !ext.getLocalName().equals("restriction")) continue;
                        collect(xsdTypes.get(strip(ext.getAttribute("base"))), attrs, kids);
                        collect(ext, attrs, kids);
                    }
                }
                case "attribute" -> {
                    String nm = !n.getAttribute("name").isEmpty() ? n.getAttribute("name") : n.getAttribute("ref");
                    attrs.put(nm, new String[]{n.getAttribute("type").isEmpty() ? "inline" : strip(n.getAttribute("type")), n.getAttribute("use"), n.getAttribute("default")});
                }
                case "attributeGroup" -> collect(xsdAttrGroups.get(strip(n.getAttribute("ref"))), attrs, kids);
                case "sequence", "choice", "all" -> walkParticles(n, kids);
                default -> { }
            }
        }
    }
    static void walkParticles(Element node, Map<String, String> kids) {
        for (Element n : children(node)) {
            switch (n.getLocalName()) {
                case "element" -> {
                    String nm = !n.getAttribute("name").isEmpty() ? n.getAttribute("name") : strip(n.getAttribute("ref"));
                    String mn = n.getAttribute("minOccurs").isEmpty() ? "1" : n.getAttribute("minOccurs"), mx = n.getAttribute("maxOccurs").isEmpty() ? "1" : n.getAttribute("maxOccurs");
                    kids.put(nm, "(" + mn + ".." + (mx.equals("unbounded") ? "n" : mx) + ")");
                }
                case "sequence", "choice", "all" -> walkParticles(n, kids);
                case "group" -> { Element g = xsdGroups.get(strip(n.getAttribute("ref"))); if (g != null) walkParticles(g, kids); }
                case "any" -> kids.put("<any>", "");
                default -> { }
            }
        }
    }
    /** " {a|b|c}" for simple types built from enumerations (also through unions), else "". */
    static String enumValues(String type) {
        Set<String> vals = new LinkedHashSet<>(); collectEnums(type, vals, 0);
        return vals.isEmpty() ? "" : " {" + String.join("|", vals) + "}";
    }
    static void collectEnums(String type, Set<String> vals, int depth) {
        Element st = xsdSimple.get(type);
        if (st == null || depth > 5) return;
        NodeList en = st.getElementsByTagNameNS(XS, "enumeration");
        for (int i = 0; i < en.getLength(); i++) vals.add(((Element) en.item(i)).getAttribute("value"));
        NodeList un = st.getElementsByTagNameNS(XS, "union");
        for (int i = 0; i < un.getLength(); i++)
            for (String m : ((Element) un.item(i)).getAttribute("memberTypes").trim().split("\\s+")) if (!m.isEmpty()) collectEnums(strip(m), vals, depth + 1);
    }
    static List<Element> children(Element e) {
        List<Element> r = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element x) r.add(x);
        return r;
    }
    static String strip(String q) { return q == null ? "" : q.substring(q.indexOf(':') + 1); }

    // ------------------------------------------------------------------ infrastructure
    static Document xml(Path p) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return f.newDocumentBuilder().parse(p.toFile());
    }
    static Path findPlugins(String hint) {
        List<Path> c = new ArrayList<>();
        if (hint != null) c.add(Paths.get(hint));
        else {
            String env = System.getenv("WORKDAY_STUDIO_HOME"); if (env != null) c.add(Paths.get(env));
            c.add(Paths.get("/Applications/WorkdayStudio"));
            String pf = System.getenv("ProgramFiles"); if (pf != null) { c.add(Paths.get(pf, "WorkdayStudio")); c.add(Paths.get(pf, "Workday Studio")); }
            c.add(Paths.get(System.getProperty("user.home"), "WorkdayStudio")); c.add(Paths.get("C:\\WorkdayStudio"));
        }
        for (Path p : c) {
            if (!Files.isDirectory(p)) continue;
            try (Stream<Path> s = Files.find(p, 5, (x, a) -> a.isDirectory() && x.getFileName() != null && x.getFileName().toString().equals("plugins")
                    && glob(x, "com.workday.studio.help_") != null)) {
                Optional<Path> f = s.findFirst(); if (f.isPresent()) return f.get();
            } catch (IOException ignored) { }
        }
        return null;
    }
    static Path glob(Path dir, String prefix) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        } catch (IOException e) { return null; }
    }
}
