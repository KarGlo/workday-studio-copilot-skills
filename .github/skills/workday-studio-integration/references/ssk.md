# StarterKit (SSK) mode

The StarterKit ("Studio StarterKit 2020r2.03") is a framework embedded in the integration project. It is ~710 components providing logging (cloud log files), error handling, RaaS/SOAP/REST helpers, output and threading. It is called via `local-out` → `vm://<Project>/<Name>`.

- **Clean base project:** `BASE_SSK_Template` in the workspace root. It is a StarterKit copy prepared as a neutral template, with no integration-specific flow, and it is **not part of the public skill repository**. Its names use three tokens that the rebranding tool replaces: `BASE_SSK_Template` (project), `BASE SSK Template` (integration system), `ssktemplate` (Java package).
- **Don't build on a StarterKit copy that still carries another integration's names.** Its `vm://` endpoints would point to the old project name.
- **API catalog:** `java .github/skills/workday-studio-integration/tools/SskApi.java` lists the callable local-ins of the StarterKit in your workspace; `java .github/skills/workday-studio-integration/tools/SskApi.java <Name>` shows the parameters of one. Add `--project <dir>` to read a specific project.

## Architecture you must respect

```
StartHere (workday-in) → Foundation → Call_InitializeFrameworkThenRunMain ──vm://<Project>/InitializeFrameworkThenRunMain──▶ SSK init (logs, attributes, globals)
                                                                                      └──▶ vm://<Project>/Main  ◀── YOUR CODE STARTS HERE
                                                          ... after Main returns: SSK finalizes logs, stores cloud-log files, closes the event
```
- Your flow begins at `<cc:local-in id="Main"/>`. In the template it has no `routes-to`; add `routes-to="<your first component>"`.
- **Don't edit SSK components.** SSK components are those whose ids end in `_1NN`, plus the public local-ins listed by `SskApi.java` and the `StartHere`/`Foundation`/`FinalizeResults`/`*_GEH` plumbing. The one exception is adding your launch params/attributes to `StartHere`.
- Global values set during SSK init are available in your code:

| Prop | Meaning |
|---|---|
| `props['sskIsDebugMode']` | launch param "Run with Debug Logging" |
| `props['sskIsValidationMode']` | launch param "Run in Validation Mode" (don't write to Workday; `inValidateOnly`) |
| `props['globalApiVersion']` | WWS version set in `Foundation` (`'v35.1'`); change it there |
| `props['sskOutputFilename']`, `props['sskDocumentRetentionPeriod']`, `props['sskDeliveryDocTag']`, `props['sskRetrievalDocTag']` | from the "… - Functional" / "General" attribute services |

- Integration-specific configuration:
  - add `cloud:param`s after the three SSK params in `StartHere`,
  - add your own `cloud:attribute-map-service` (named `<Integration Name> Attribute Map Service - <Topic>`) after the SSK ones,
  - add a `cloud:report-service` when you use RaaS.
  - Keep the XSD child order (`integration-system.md`).
  - Read the values in your first mediation, e.g. `intsys.getAttribute('Topic URL')`.
- Conventions:
  - SSK parameters are `in*`,
  - SSK-internal props are `local*`,
  - your globals are `global*` or `p.*`,
  - local-outs calling SSK are `Call_<Api>_<Purpose>`.

## Creating a new SSK-based integration (scenario A, SSK mode)

1. The user imports `BASE_SSK_Template` into Studio once (File > Import > Existing Projects into Workspace).
2. For each new integration, the user copies it in Studio's Project Explorer (Copy, Paste, new name such as `INT_Payroll_Export`). Ask the user to do this.
3. Rebrand the copy. Run from the workspace root; it works the same on macOS, Linux and Windows:
   ```bash
   java .github/skills/workday-studio-integration/tools/RebrandSsk.java INT_Payroll_Export INT_Payroll_Export "INT Payroll Export" intpayrollexport
   ```
   Arguments: `<project folder> <ProjectName> "<Integration System Name>" <javapackage>`.
   - It replaces exactly three distinctive tokens in all text files:
     - `BASE_SSK_Template` → project name (vm:// endpoints, deploy-name, cloud collection),
     - `BASE SSK Template` → Integration System name (assembly + `MediationConstants.java`, which looks attribute services up by full name),
     - `ssktemplate` → Java package segment.
   - It also moves the Java package folders, deletes `build/` and verifies that no old token is left (`RESULT: OK`).
   - `--dry-run` shows what would change. The tool refuses a project that is not a fresh template copy.
   - **Never replace short or common words by hand:** a naive replace of the word `base` once broke `context.baseURL` and `static-base-uri()` in a StarterKit copy.
4. Tell the user to refresh the project in Studio (F5) and run Project > Clean.
5. Build the integration logic as below, then run `WdCheck` (SKILL.md Step 4). In SSK projects it checks your code: everything reachable from `Main`.

## Adding your logic (append-only, as in SKILL.md scenario B)

1. **assembly.xml**
   - Set `Main/@routes-to` to your first component.
   - Append your components just before `</cc:assembly>`.
   - Add `<bean>`s (your Java) before `</beans>`.
   - Put your Java under `src/com/workday/custom/<pkg>/` (not inside `sskNNN`).
2. **Error handling:** one local Try/Catch per flow (error-handling.md).
   - The first mediation of the main flow gets `handle-downstream-errors="true"` + `<cc:send-error routes-to="HandleError_Main"/>`. `HandleError_Main` is a local-out to `vm://<Project>/HandleError` with abort = true (template below).
   - The first mediation of each subflow gets its own `send-error` → `HandleError_<Subflow>`. In per-record subflows use `inIsAbortOnError=false` + `inIsResetError=true`, so one bad record doesn't stop the run.
   - Each mediation and its handler go into a nested Try/Catch lane: `name="Try/Catch Swimlane" orientation="VERTICAL" topBorderColor="33023" labelAlignment="LEFT"`, the same style the SSK uses.
3. **Logging:** `Call_CreateLogEntry_<Purpose>` local-outs (template below) instead of PutIntegrationMessage. The SSK aggregates entries into the cloud log and event messages at the end.
4. **Diagram**: one lane = one flow; no arrow crosses lanes (diagram.md). Template file indices; recount in copies that already contain added lanes.
   - The **main flow** (what `Main` routes to) goes into the SSK lane **"Begin Integration Work"**, together with `Main`. Append after its `<elements href="#//@decorations.12"/>`:
     - `<elements href="#//@swimlanes.N"/>` for the Try/Catch lane (VERTICAL: first mediation + `HandleError_Main`),
     - then `<elements href="assembly.xml#…"/>` for the rest of the main flow (e.g. `Call_<Subflow>` local-outs, final log entry).
   - Every **subflow** starts with its own `local-in` and gets its own lane. Append the lanes at the end of the file: a VERTICAL parent lane `<Integration> - Subassemblies` whose `elements` attribute lists the subflow lanes. Then add ` //@swimlanes.N` (N = the parent's index; `365` in a fresh template) to the end of the `elements` attribute of the lane named **"Integration Code"**.
   - Add `visualProperties` for every new component (see diagram.md).

If your local copy has `examples/outbound-rest-ssk.md`, it shows a complete validated set of such additions (not part of the public repository).

## Templates for the most used SSK calls

```xml
<cc:local-out id="HandleError_Main" store-message="none" endpoint="vm://PROJECT/HandleError">
    <cc:set name="inLogMessage" value="'Unhandled error in the integration flow.'"/>
    <cc:set name="inLogMessageDetail" value="(context.getErrorMessage() != empty) ? context.getErrorMessage() : context.exception.message"/>
    <cc:set name="inLogLevel" value="'ERROR'"/>
    <cc:set name="inExtraLocalIn" value="context.errorComponentId"/>
    <cc:set name="inIsAbortOnError" value="true"/>
    <cc:set name="inIsResetError" value="false"/>
    <cc:set name="inIncludeDebugOutput" value="false"/>
    <cc:set name="inLogTarget" value="'primary'"/>
    <cc:set name="inIsChildThreadContext" value="false"/>
</cc:local-out>
<cc:local-out id="Call_CreateLogEntry_Posted" store-message="none" endpoint="vm://PROJECT/CreateLogEntry">
    <cc:set name="inLogMessage" value="'Record posted'"/>
    <cc:set name="inLogMessageDetail" value="'HTTP status ' + context.getProperty('http.response.status')"/>
    <cc:set name="inLogReferenceId" value="props['p.record.id']"/>
    <cc:set name="inLogLevel" value="'info'"/>
</cc:local-out>
<cc:local-out id="Call_CallRaaS_Source" store-message="none" routes-response-to="ProcessReport" endpoint="vm://PROJECT/CallRaaS">
    <cc:set name="inReportServiceAlias" value="'Source Data'"/>
    <cc:set name="inSaveResultsToIntegrationEvent" value="false"/>
    <cc:set name="inIsUseJavaUrlEncoder" value="false"/>
    <cc:set name="inReturnResults" value="'message'"/>
    <cc:set name="inDebugMode" value="props['sskIsDebugMode']"/>
    <cc:set name="inLogTarget" value="'primary'"/>
    <cc:set name="inIsChildThreadContext" value="false"/>
</cc:local-out>
```
Per-record variant (inside a loop/split subflow): same as `HandleError_Main`, but `inIsAbortOnError` = `false`, `inIsResetError` = `true`, `inLogLevel` = `'error'`, `inLogReferenceId` = the record id.

Replace `PROJECT` with the project name.

Other frequently used APIs (look up exact parameters with `SskApi.java <Name>`):

| API | Use |
|---|---|
| `CallSoap` / `CallSoapPaged` | WWS request built from an XSLT (`inBuildRequestPathToXsltFile`) |
| `CallSoapImport` + `FinalizeCallSoapImport` + `ReportResultsOfCallSoapImport` | Import_* web services |
| `GenerateOutput` | store a file on the event (retention, tags, deliverable) |
| `GetDocumentList` / `LoadFile` / `GetDISResults` | inbound documents |
| `BlockSplitter` | split into blocks, optionally in parallel threads |
| `XsltPlus` / `StreamDataMerge` | transforms with SSK logging of `xsl:message` |
| `IntegrationEventProgress` | percent-complete on the event |
| `Debug` | dump a variable or the message to the debug archive when debug mode is on |
| `AddReportPromptFromProperty` | build RaaS prompts for CallRaaS |
| `WriteNonEffectiveDatedCustomObject` | custom object REST |
