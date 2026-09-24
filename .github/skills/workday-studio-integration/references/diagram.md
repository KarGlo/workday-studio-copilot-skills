# assembly-diagram.xml: minimal, robust layout

## What Studio does with this file (why "minimal" is enough)

The Studio 2026.24 editor runs a reconciler (`ViewReconciler`) every time an assembly is opened:
1. It deletes `visualProperties` whose element no longer exists in assembly.xml.
2. It adds a `visualProperties` (at x=0, y=0) for every top-level component without one.
3. It deletes stale `connections` and **creates every missing connection** for `routes-to` and `routes-response-to`, including connections that start in nested `send-error` and `sub-route` elements.

Members of a swimlane are laid out automatically (toolbar layout: left→right for HORIZONTAL, top→bottom for VERTICAL); their x/y is ignored. Only top-level swimlanes (and components outside any lane) need coordinates.

This was verified by running Studio's `ViewReconciler` on this skill's example: from swimlanes alone it created all views and all 10 connections.

So write: the assembly reference, one `visualProperties` per component without coordinates, and a swimlane tree. Omit `connections`. When the user opens the file, Studio adds the connections and marks the diagram dirty. Saving once persists them.

## References (the only two forms you may write)

| Target | Form |
|---|---|
| Top-level component with `id` | `assembly.xml#<id>` |
| Another swimlane / decoration in this file | `//@swimlanes.N` in the `elements` attribute, or `#//@swimlanes.N` in `<elements href>`; `N` = 0-based position of that `<swimlanes>` element in the file (same for `//@decorations.N`) |

Nested assembly elements (`send-error`, `sub-route`, steps) have no id-based address. Studio writes paths like `assembly.xml#//@beans/@mixed.1/@mixed.7/@mixed.3` whose indices count whitespace and comments. **Do not write such paths.** The single allowed exception is the global error handler below.

## Template for a new diagram

```xml
<?xml version="1.0" encoding="UTF-8"?>
<wdnm:Diagram xmlns:wdnm="http://workday.com/studio/editors/notation">
  <element href="assembly.xml#WorkdayAssembly"/>
  <visualProperties>
    <element href="assembly.xml#StartHere"/>
  </visualProperties>
  <visualProperties>
    <element href="assembly.xml#Initialize"/>
  </visualProperties>
  <!-- ... one visualProperties per top-level component with an id ... -->
  <swimlanes x="40" y="40" name="INT Example" orientation="VERTICAL" elements="//@swimlanes.1 //@swimlanes.2"/>
  <swimlanes name="Main flow: read configuration, get data, split, report">
    <elements href="assembly.xml#StartHere"/>
    <elements href="#//@swimlanes.3"/>                    <!-- Try/Catch: Initialize + ReportFailure -->
    <elements href="assembly.xml#GetSourceData"/>
    <elements href="assembly.xml#SplitRecords"/>
    <elements href="assembly.xml#Call_ProcessRecord"/>    <!-- local-out vm://<Project>/ProcessRecord -->
    <elements href="assembly.xml#ReportSummary"/>
  </swimlanes>
  <swimlanes name="ProcessRecord (local-in): transform and send one record">
    <elements href="assembly.xml#ProcessRecord"/>         <!-- local-in: every subflow lane starts with one -->
    <elements href="#//@swimlanes.4"/>                    <!-- Try/Catch: PrepareRecord + RecordFailed -->
    <elements href="assembly.xml#PostRecord"/>
  </swimlanes>
  <swimlanes name="Try/Catch Swimlane" orientation="VERTICAL" topBorderColor="33023" labelAlignment="LEFT">
    <elements href="assembly.xml#Initialize"/>            <!-- mediation with handle-downstream-errors + send-error -->
    <elements href="assembly.xml#ReportFailure"/>         <!-- its send-error target, below it -->
  </swimlanes>
  <swimlanes name="Try/Catch Swimlane" orientation="VERTICAL" topBorderColor="33023" labelAlignment="LEFT">
    <elements href="assembly.xml#PrepareRecord"/>
    <elements href="assembly.xml#RecordFailed"/>
  </swimlanes>
</wdnm:Diagram>
```

Rules:
- Element order in the file: `element`, `visualProperties`*, `decorations`*, `connections`*, `swimlanes`*.
- Every top-level component with an id appears in **exactly one** swimlane, in flow order.
- **One lane = one flow, no arrow crosses lanes.**
  - The first child lane is the main flow, starting at `workday-in` (in SSK: the existing "Begin Integration Work" lane with `Main`).
  - Every other lane is a subflow whose first element is a `local-in`. Other lanes call it with a `local-out` (`vm://<Project>/<local-in id>`); vm calls draw no arrow.
  - Studio draws an arrow for every `routes-to`, `routes-response-to`, `sub-route` and `send-error`. Their source and target must be in the same lane.
  - Error handling uses a **Try/Catch lane**, in the house style of the user's projects: `<swimlanes name="Try/Catch Swimlane" orientation="VERTICAL" topBorderColor="33023" labelAlignment="LEFT">`. `Try/Catch <what>` is allowed for a more specific name. The lane holds the mediation with the `send-error` (top) and the handler it routes to (below). It is referenced from the flow lane at the mediation's position (`<elements href="#//@swimlanes.N"/>`), so the flow runs through it. Arrows into and out of this nested lane are fine. There is no separate "Error handling" lane.
  - Every flow lane that contains a mediation has one Try/Catch around its first mediation. With `handle-downstream-errors="true"`, it guards the rest of that flow. A lane made only of calls (local-in → local-outs/splitter) has none; its errors go up to the caller's Try/Catch.
  - A splitter's `sub-route` goes to a `local-out` (`Call_ProcessRecord`) in the splitter's lane, not directly into another lane.
  - An aggregator collecting the split results stays in the splitter's lane, behind that local-out (`Call_ProcessRecord routes-response-to="CollectResults"`).
- Root swimlane:
  - has `x`/`y` and `orientation="VERTICAL"`,
  - lists child lanes with the `elements` attribute (space-separated `//@swimlanes.N`).
- Child lanes are HORIZONTAL (the default, so omit `orientation`) and use `<elements href="assembly.xml#Id"/>` children.
- Keep lanes readable (up to ~9 components). If the main flow gets longer, move a stage into a new `local-in` subflow lane and call it with a `local-out`.
- Lane `name`s document the flow. Use them instead of XML comments.
- Optional lane attributes:
  - `description`,
  - `alignment="MIDDLE"`,
  - `labelAlignment="LEFT|CENTER|RIGHT"`,
  - `closed="true"` (collapsed),
  - `topBorderColor`, `fgColor`, `bgColor`: RGB int, e.g. 16777215 = white,
  - `font="Arial|12|1"` (name|size|style).
- Optional note: `<decorations x="40" y="600" width="400" height="120" type="NOTE" text="..."/>` (types `TEXT`, `NOTE`, `RECTANGLE`, `ELLIPSE`), placed before `swimlanes`. To show it inside a lane, reference it as `//@decorations.N` in that lane's children or `elements` attribute.

### Global error handler (the one allowed index path)

A top-level `<cc:send-error>` has no diagram id. Two options:
- Put it as the **first** child of `<cc:assembly>` in a new file (the skeleton has exactly one whitespace text node before it). Its reference is then `assembly.xml#//@beans/@mixed.1/@mixed.1`, which you may use in `visualProperties` and in a lane.
- Otherwise leave it out of the diagram. Studio shows it at (0,0) and the user drags it once.

## Extending an existing diagram (append-only)

1. Add one `<visualProperties><element href="assembly.xml#NewId"/></visualProperties>` per new component right after the last existing `</visualProperties>`. Find it with `grep -n '</visualProperties>' ws/WSAR-INF/assembly-diagram.xml | tail -1`.
2. Place the new components in a lane:
   - Components that continue an existing flow (they are connected to it by arrows) go at the end of **that flow's lane**: append `<elements href="assembly.xml#NewId"/>` lines to it.
   - A new subflow (starting with its own `local-in`, called by a `local-out` placed in the caller's lane) gets **its own new lane**, appended at the end of the file.
   - A new top-level lane is appended at the **end of the file** (just before `</wdnm:Diagram>`). Give it coordinates above the existing content: `x` = smallest existing top-level x, `y` = smallest existing top-level y minus 300 per row of your lane. If you need child lanes, their indices are `count(existing <swimlanes) + k`. Count with `grep -c '<swimlanes' ws/WSAR-INF/assembly-diagram.xml`.
3. Never insert or remove `<swimlanes>` / `<decorations>` in the middle of the file: other lanes refer to them by index. Never edit existing `//@…` paths.
4. Do not add `<connections>`. Leave existing ones alone.

## Deleting or renaming components

- **Rename:**
  1. In assembly.xml: change the `id` and every `routes-to`, `routes-response-to`, `send-error`/`sub-route` `routes-to` and `vm://Project/<Id>` that uses it.
  2. In the diagram: replace every `assembly.xml#OldId` with `assembly.xml#NewId`.
  - Index paths stay valid, because renaming changes no structure.
- **Delete** (removing XML shifts the index paths of later siblings):
  1. Delete the component in assembly.xml.
  2. In the diagram, delete its `<visualProperties>` block, its `<elements href="assembly.xml#Id"/>` lines, and every `<connections>` block that mentions it.
  3. Delete every `<connections>` block whose `<source href>` contains `#//@beans`. Studio recreates them; only custom anchor points are lost.
  4. If any `visualProperties` or lane `elements` use a `#//@beans` path (global error handler), delete those lines too. Studio re-adds the view at (0,0).
- For large refactors (many deletions/moves) recommend doing them in Studio's editor, which rewrites all paths itself.

## Check

- Every `assembly.xml#X` in the diagram has a matching top-level `id="X"`.
- Every new top-level component has a `visualProperties` and appears in one lane.
- Every `//@swimlanes.N` index is lower than the number of `<swimlanes>` elements and points to the intended lane.
- For every `routes-to` / `routes-response-to` / `sub-route` / `send-error` in your components, the target is in the same lane as the source, or in that lane's nested Try/Catch lane. Every non-main lane starts with a `local-in`, and every flow with a mediation has a Try/Catch lane around its first mediation.
- The file is well-formed XML with the `wdnm` namespace `http://workday.com/studio/editors/notation`.
