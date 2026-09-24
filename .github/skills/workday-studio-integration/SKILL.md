---
name: workday-studio-integration
description: Build or extend Workday Studio integrations by writing ws/WSAR-INF/assembly.xml (flow) and assembly-diagram.xml (canvas layout) in an Eclipse/Workday Studio workspace. Use for any request to create, generate, extend, modify, refactor or review a Workday Studio assembly, integration flow, workday-in, local-in/local-out subassembly, mediation, splitter/aggregator, RaaS, REST (http-out), SOAP (workday-out-soap) call, error handling, or a StarterKit (SSK) based integration. Polish triggers - integracja Workday Studio, assembly, przepływ, diagram, StarterKit, SSK.
---

# Workday Studio integration builder

You write the two files that define a Workday Studio integration:

| File | Content | Who is authoritative |
|---|---|---|
| `ws/WSAR-INF/assembly.xml` | The flow: Spring `<beans>` + `<cc:assembly>` components, steps, MVEL | **You**, carefully |
| `ws/WSAR-INF/assembly-diagram.xml` | Canvas layout only (EMF "wdnm" notation) | You write a *minimal* version; Studio repairs the rest on open |

Other project files (`.project`, `.settings/*`, `ws/META-INF`, `ws-application.xml`) are created by Studio. Never create or edit them unless the user explicitly asks.

**Verification tool (mandatory):** `tools/WdCheck.java` checks a project with Workday Studio's own XSD schemas, MVEL runtime and diagram reconciler (from the local Studio install), plus this skill's conventions. Run it after every change and fix until `RESULT: OK` (Step 4). It needs only JDK 17+ (`java`), which every Studio machine has.

## Facts this skill relies on (reverse-engineered from Studio 2026.24 and verified by running Studio's own reconciler on these files)

- Every time the assembly editor opens, Studio **reconciles** the diagram with assembly.xml: it deletes views of elements that no longer exist, adds views for new top-level components, and **rebuilds every `routes-to` / `routes-response-to` connection** (also those starting in nested `send-error` and `sub-route`). So a diagram without `<connections>` is fine, and a broken diagram only affects looks, never runtime.
- Inserting a component in the *middle* of an existing assembly.xml silently breaks the stored index paths of everything after it (one insert at the top of the StarterKit broke all 188). Hence the append-only rule.
- Elements inside a swimlane are auto-laid-out (their x/y is ignored). Put components in swimlanes and you never need coordinates.
- Diagram references `assembly.xml#<id>` work only for **top-level components**. Nested elements use index paths (`#//@beans/@mixed.1/@mixed.7/...`) that count whitespace and comments. **Never write these paths yourself.** The only exception is in `references/diagram.md`.
- Studio validates assembly.xml against `assembly-core.xsd` (cloud subset). `routes-to`/`routes-response-to` are XSD IDREFs, so they must point to an existing component `id`.

## Step 0: decide the mode and the scenario

Ask only what you cannot infer. One short question round at most.

1. **Mode**
   - **Plain Studio**: standard components only, and `vm://wcc/...` common components for event messages. Default for small integrations.
   - **StarterKit (SSK)**: logging, error handling, RaaS, SOAP and output go through SSK local-ins (`CreateLogEntry`, `HandleError`, `CallRaaS`, …). Read `references/ssk.md` first.
2. **Scenario**
   - **A. Fill a project the user created in Studio** (File > New > Workday Project). assembly.xml exists with an empty `<cc:assembly>`. Keep its `version` attribute and namespace header.
   - **B. Extend an existing assembly.** Follow the append-only rules below. Large files (SSK is ~570 KB) must be navigated with search, never read whole.
3. **Requirements to pin down**
   - trigger and launch parameters
   - integration attributes (URLs, credentials)
   - data source: RaaS report alias, SOAP operation, or event documents
   - transformation (XSLT, eval, JSON)
   - target (http-out, sftp-out, workday-out-soap, document on the event)
   - per-record or batch processing
   - error policy: abort, or continue and report

## Step 1: design before writing

Write a compact flow table in the chat (id | type | purpose | routes to). Keep it short and don't dump XML at this point. Then read only the references you need:

| Need | Read |
|---|---|
| File skeleton, execution model, allowed elements, ids, MVEL-vs-template attributes | `references/assembly-xml.md` (always) |
| Component XML (workday-in, local-in/out, http-out, workday-out-rest/soap, mediations, route, splitter, aggregator, sftp-out) | `references/components.md` |
| Which built-in element exists for a job (all ~120 cloud elements, one line each) | `references/element-catalog.md` |
| Full Studio documentation of one element: all attributes, semantics, child order | `java .github/skills/workday-studio-integration/tools/StudioDocs.java element <name>` |
| Studio concepts and how-tos (MVEL, error handling, subassemblies, paging, …) | `java .github/skills/workday-studio-integration/tools/StudioDocs.java search <words>`, then `show <id>` |
| Steps inside mediations (eval, write, xslt-plus, store, copy, set-headers, cloud-log, validate-exp, json/xml converters) | `references/steps.md` |
| Integration system: launch params, attributes, report/delivery services | `references/integration-system.md` |
| MVEL objects (`props`, `vars`, `lp`, `intsys`, `context`, `message`, `parts`, `util`) | `references/mvel.md` |
| Error handling patterns | `references/error-handling.md` |
| assembly-diagram.xml rules (new and extend) | `references/diagram.md` (always) |
| Verification | `tools/WdCheck.java` (always, Step 4); `references/validation-checklist.md` for the logic part |
| New SSK project (rebranding a copy of `BASE_SSK_Template`) | `tools/RebrandSsk.java`, see `references/ssk.md` |
| StarterKit mode | `references/ssk.md`; the SSK API (callable local-ins and their parameters) with `java .github/skills/workday-studio-integration/tools/SskApi.java` (index) and `java .github/skills/workday-studio-integration/tools/SskApi.java <Name>` |
| Worked example | `references/recipes/outbound-rest.md`. A complete validated example project may exist in `examples/` of your local copy; it is not part of the public repository. |

## Step 2: write assembly.xml

- **Scenario A:** start from `templates/assembly.xml` or the user's generated file. Put components inside `<cc:assembly>` in flow order. Put Spring `<bean>`s (custom Java) after `</cc:assembly>`.
- **Scenario B (append-only):**
  - Add new top-level components **immediately before `</cc:assembly>`**. Never insert between existing components, never reorder, never reformat, never add XML comments above existing elements. Inserting shifts the index paths Studio stores in the diagram.
  - You may change attributes of existing elements (e.g. repoint `routes-to` to your new first component).
  - Files saved by Studio are re-serialized: quotes inside element text become `&quot;` and attribute order can change. Locate elements by `id="…"` and edit around them; never match long literal text copied from an earlier version.
  - You may append steps inside an existing `<cc:steps>`.
  - You may append a `send-error` as the **last** child of a mediation that has none.
  - Deleting or renaming an existing component: see `references/diagram.md` § "Deleting or renaming".
- Put XSLT and other resources under `ws/WSAR-INF/` (e.g. `ws/WSAR-INF/xslt/Name.xsl`) and reference them relative to WSAR-INF (`url="xslt/Name.xsl"`).

## Step 3: write assembly-diagram.xml

Follow `references/diagram.md`:
- a `visualProperties` per top-level component (no x/y),
- one root swimlane with x/y,
- **one child swimlane per flow**: the main flow, plus one lane per `local-in` subflow,
- no connections.

In scenario B, append only.

## Step 4: verify with WdCheck, then hand over

1. Run from the workspace root (the folder that contains `.github/`):
   ```bash
   java .github/skills/workday-studio-integration/tools/WdCheck.java <ProjectFolder>
   ```
   It runs six checks:
   1. XSD
   2. MVEL compile
   3. diagram references
   4. Studio's reconciler (what the editor changes on open)
   5. swimlanes (one lane = one flow, arrows stay in their lane)
   6. `vm://` calls, required `in*` parameters, launch-param/attribute/alias names, XSLT files
2. **Fix every `ERROR` and re-run until `RESULT: OK`.** Read `warn` lines and fix them when they are about your components.
   - StarterKit projects: only your own code (everything reachable from `Main`) is checked for steps 5–6.
   - `--ids a,b,c` restricts steps 5–6 to the listed components.
   - `--verbose` lists every item.
   - `--studio <dir>` is for a non-default Studio location (or set `WORKDAY_STUDIO_HOME`). Without Studio, steps 1, 2 and 4 are skipped: say so to the user.
   - The output is short, so prefer re-running WdCheck over re-reading big files to find a problem.
3. Walk through `references/validation-checklist.md` § "Logic" (what no tool can check).
4. Tell the user how to verify, in 1–3 lines:
   - refresh the project in Studio (F5).
   - If the assembly editor was already open while the files were written, close its tab and reopen `assembly.xml`. An editor opened earlier keeps showing the old (e.g. empty) model. If it asks to save that old editor, answer **No**.
   - Open the assembly in Studio. Studio adds the connections, so the diagram shows as modified: save it.
   - check the **Problems** view,
   - *Arrange All* (Cmd/Ctrl+Shift+F) only if the layout looks wrong,
   - deploy or launch.
   - Also list anything the user must configure in the tenant: report aliases, attribute values, ISU permissions.

## Golden rules

1. Every top-level component has a unique `id`: letters, digits, `_`, `-`; it starts with a letter and must not equal the project name. Steps may reuse ids across mediations, but keep them unique per mediation.
2. Cloud assemblies may use only the elements listed in `references/assembly-xml.md` § "Allowed elements". `http-in`, `file-in`, `sftp-in`, `jms-*`, `event-in`, `custom-in` and `file-out` fail validation.
3. Child element order is fixed by the XSD:
   - `splitter`: `sub-route` before the strategy,
   - `route`: strategy before `sub-route`s,
   - `async-mediation`: `steps` before the error handler,
   - `integration-system`: `param`* → `attribute-map-service`* → … → `report-service`* (full order in `integration-system.md`).
4. Attribute kinds differ.
   - **MVEL expressions** (quote literals: `'text'`): `execute-when`, `cc:set/@value`, `cc:expression`, `choose-route/@expression`, `parameter/@default`.
   - **Templates** (literal text; dynamic parts in `@{...}`): `endpoint`, `extra-path`, `url`, `title`, `failure-message`, `cc:text`.
5. Escape XML inside MVEL: `<` → `&lt;`, `&&` → `&amp;&amp;`. Prefer single quotes inside double-quoted attributes.
6. `local-out endpoint="vm://<ProjectName>/<local-in id>"` for subroutines in the same project. `<ProjectName>` is the Eclipse project name, which is the deployed service name. `vm://wcc/<Name>` is for Workday common components (`PutIntegrationMessage`, `GetEventDocuments`, …).
7. Every flow reports its outcome to the integration event (`vm://wcc/PutIntegrationMessage` or SSK `CreateLogEntry`/`HandleError`) and has error handling on its first mediation (`handle-downstream-errors="true"` + `send-error`).
8. Keep the existing `version` of `<cc:assembly>`. Some features need a minimum version (e.g. `xslt-plus` ≥ 2017.5).
9. No secrets in assembly.xml. Credentials and URLs come from integration attributes (`intsys.getAttribute('Name')`) or launch parameters.
10. **One swimlane = one flow; no arrow may cross swimlanes.**
    - Each lane holds either the main flow (starting at `workday-in`, or at SSK `Main`) or one subflow that **starts with a `local-in`**.
    - Move between lanes only with `local-out` → `vm://<Project>/<local-in id>`. These calls draw no arrow.
    - Every `routes-to`, `routes-response-to`, `sub-route` and `send-error` target must sit in the same lane as its source.
    - Typical split: `splitter` → `sub-route` → `local-out Call_ProcessRecord` in the same lane → subflow lane `local-in ProcessRecord` → ….
    - **Try/Catch:** every flow that contains a mediation has a local `send-error` on its first mediation (with `handle-downstream-errors="true"`). That mediation and its error-handler local-out form a nested VERTICAL lane, mediation on top and handler below, placed in the flow lane at the mediation's position. Arrows may enter and leave this nested lane; nothing else may cross lanes. A subflow made only of calls (local-out, splitter) has no Try/Catch; its errors go up to the caller's. See diagram.md and error-handling.md.
11. **Never guess an element or attribute.** Before using an element that components.md/steps.md don't show, run `StudioDocs.java element <name>`. It prints Studio's own help page plus the exact schema: required attributes, defaults, child order, and whether the element is allowed in cloud assemblies. The help is read from the local Studio installation, so it matches the installed version.
12. Token discipline:
    - for files > 100 KB, locate things with search (`grep -n 'id="X"'`) and read only line ranges,
    - never load big assemblies whole; ask `SskApi.java <Name>` for one SSK entry instead of scanning the StarterKit,
    - copy patterns from the recipes (and from `examples/` when present) instead of re-deriving them.
