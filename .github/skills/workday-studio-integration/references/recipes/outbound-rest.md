# Recipe: Outbound REST (Workday data → external REST API)

Flow tables and snippets below describe a validated pilot. A complete copy (assembly.xml, assembly-diagram.xml, xslt/BuildPayload.xsl) may exist in `examples/outbound-rest-plain/` of your local copy. When present, copy it and adapt names, fields and endpoint.

## Plain mode flow (the example)

Two flow lanes, the **main flow** (rows 1–8 + E) and the **ProcessRecord subflow** (S1–S4 + SE), connected only by the `Call_ProcessRecord` local-out. Each flow has a VERTICAL Try/Catch lane around its first mediation:
- `Initialize` + `ReportFailure`: an error aborts the run,
- `PrepareRecord` + `RecordFailed`: a record error becomes a `Status=ERROR` result and the split continues.

| # | id | type | does | next |
|---|---|---|---|---|
| 1 | StartHere | workday-in | launch param `Test Mode (Do Not Send)`; attributes `Endpoint URL`, `API Token`; report alias `Source Data` | Initialize |
| 2 | Initialize | async-mediation | read attributes/params into `props`, `validate-exp` config, whole-run `send-error` (`handle-downstream-errors="true"`) | GetSourceData |
| 3 | GetSourceData | workday-out-rest | RaaS: `extra-path="@{intsys.reportService.getExtrapath('Source Data')}"` | SplitRecords (response) |
| 4 | SplitRecords | splitter | `xml-stream-splitter xpath="/*/*"`: one `Report_Entry` per message | Call_ProcessRecord |
| 5 | Call_ProcessRecord | local-out → `vm://<Project>/ProcessRecord` | runs the per-record subflow (lane 2) | CollectResults (response) |
| 6 | CollectResults | aggregator | `size-batch-strategy batch-size="-1"` + `xml-message-content-collater` `<Results>` | Summarize |
| S1 | ProcessRecord | local-in (lane 2) | subflow entry | PrepareRecord |
| S2 | PrepareRecord | async-mediation | capture record id, `xslt-plus` → JSON text, set headers (`removeAllHeaders`, Content-Type, Authorization); `handle-downstream-errors` + `send-error` → RecordFailed | PostRecord |
| S3 | PostRecord | http-out | POST; `error-as-response="true"`, `retries`, skipped by `execute-when` in test mode | RecordResult (response) |
| S4 | RecordResult | async-mediation | status → `props`, `write` `<Result><Id/><Status/></Result>`; the subflow ends here and returns this message to `Call_ProcessRecord` | (returns) |
| SE | RecordFailed | async-mediation (Try/Catch) | writes `<Result>` with `Status` = `ERROR`; it is returned to the caller like a normal result | (returns) |
| 7 | Summarize | async-mediation | XPath counts (`Integer.parseInt`), `store` results document into a variable | ReportSummary |
| 8 | ReportSummary | local-out → `vm://wcc/PutIntegrationMessage` | INFO/ERROR summary + attached results document | end |
| E | ReportFailure | local-out → `vm://wcc/PutIntegrationMessage` | CRITICAL with `context.errorComponentId` / `context.errorMessage` | end |

Tenant setup to tell the user about:
- create the custom report (RaaS enabled) with fields `Employee_ID`, `First_Name`, `Last_Name`, `Email`,
- link it to the alias "Source Data",
- fill the attributes,
- give the ISU access to the report.

## Adapting

- **Payload shape:** edit `xslt/BuildPayload.xsl`. Keep `method="text"` and the `f:json-string` escaping function. For XML targets, output XML and set `output-mimetype="text/xml"`.
- **One request for all records:** drop the splitter and aggregator. `GetSourceData` → `PrepareBatch` (XSLT over the whole report produces a JSON array) → `PostBatch` → `ReportSummary`.
- **PUT/DELETE/PATCH:** `http-method`. PATCH also needs `props['wd.http.client'] = 'apache'` in an eval before the call.
- **Record key in the URL:** `endpoint="@{props['p.endpoint.url']}/@{props['p.record.id']}"`.
- **Basic auth:** remove the Authorization header and add a `<cc:http-basic-auth username="@{props['p.user']}" password="@{props['p.pwd']}"/>` child to the http-out.
- **OAuth2 client credentials:** add before the split:
  ```xml
  <cc:async-mediation id="BuildTokenRequest" routes-to="GetToken">
      <cc:steps>
          <cc:write id="Form" output-mimetype="application/x-www-form-urlencoded">
              <cc:message><cc:text>grant_type=client_credentials&amp;client_id=@{intsys.getAttribute('Client ID')}&amp;client_secret=@{intsys.getAttribute('Client Secret')}</cc:text></cc:message>
          </cc:write>
          <cc:eval id="Headers">
              <cc:expression>message.removeAllHeaders()</cc:expression>
          </cc:eval>
      </cc:steps>
  </cc:async-mediation>
  <cc:http-out id="GetToken" store-message="none" routes-response-to="ReadToken" endpoint="@{intsys.getAttribute('Token URL')}" http-method="POST"/>
  <cc:async-mediation id="ReadToken" routes-to="GetSourceData">
      <cc:steps>
          <cc:json-to-xml id="TokenToXml"/>
          <cc:eval id="Keep">
              <cc:expression>props['p.api.token'] = parts[0].xpath('/root/access_token')</cc:expression>
          </cc:eval>
      </cc:steps>
  </cc:async-mediation>
  ```
  Then Initialize routes to `BuildTokenRequest`. The splitter still works because `GetSourceData` produces a fresh message.
- **Stop on first failure instead of reporting per record:** set `error-as-response="false"` (the default). The http-out then raises, and Initialize's handler reports CRITICAL.
- **Very large reports:** keep `xml-stream-splitter`, and avoid `vars` copies of the full report.

## StarterKit (SSK) mode variant

Use when the user wants SSK logging and error conventions. When your local copy has `examples/outbound-rest-ssk.md`, it contains validated, copy-ready additions. Differences from plain mode:

1. There is no own workday-in. The SSK `StartHere` → `Foundation` → `InitializeFrameworkThenRunMain` calls `vm://<Project>/Main`. Your flow starts at the local-in `Main`: set `Main/@routes-to` to your first mediation.
2. Declare your launch params, attributes and report alias in the SSK's existing `workday-in` integration-system:
   - append your `cloud:param`s after the SSK params,
   - add your own `attribute-map-service` after the SSK ones,
   - add a `report-service`.
3. RaaS through SSK:
   ```xml
   <cc:local-out id="Call_CallRaaS_SourceData" store-message="none" routes-response-to="SplitRecords" endpoint="vm://PROJECT/CallRaaS">
       <cc:set name="inReportServiceAlias" value="'Source Data'"/>
       <cc:set name="inSaveResultsToIntegrationEvent" value="false"/>
       <cc:set name="inIsUseJavaUrlEncoder" value="false"/>
       <cc:set name="inReturnResults" value="'message'"/>
       <cc:set name="inDebugMode" value="props['sskIsDebugMode']"/>
       <cc:set name="inLogTarget" value="'primary'"/>
       <cc:set name="inIsChildThreadContext" value="false"/>
   </cc:local-out>
   ```
   Or keep the plain `workday-out-rest`; both are fine.
4. Per-record logging: `local-out` to `vm://PROJECT/CreateLogEntry` (`inLogMessage`, `inLogLevel` = `'info'`/`'warn'`/`'error'`, `inLogReferenceId`) instead of the `write` + aggregator + summary part.
5. Errors: `send-error` → `local-out` to `vm://PROJECT/HandleError` with `inLogMessage`, `inLogMessageDetail` = `context.getErrorMessage()`, `inLogLevel` = `'ERROR'`, `inExtraLocalIn` = `context.errorComponentId`, `inIsAbortOnError`, `inLogTarget` = `'primary'`, `inIsChildThreadContext` = `false`. See `HandleError_CustomLogic` in the StarterKit for the full parameter set.
6. The SSK writes the cloud log file and event messages at the end. Don't add your own final PutIntegrationMessage unless you need an extra document.
