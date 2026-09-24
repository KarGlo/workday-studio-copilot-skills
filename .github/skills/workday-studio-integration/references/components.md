# Top-level components: XML patterns

All snippets are valid inside `<cc:assembly>` (checked against `assembly-core.xsd`). Replace ids and targets.
Attributes marked *req* are required by the XSD. Everything else is optional; defaults are in parentheses.

## workday-in: integration entry point (exactly one)

```xml
<cc:workday-in id="StartHere" routes-to="Initialize">
    <cc:integration-system name="INT Example">
        <!-- params, attribute-map-service, report-service ... see integration-system.md -->
    </cc:integration-system>
</cc:workday-in>
```
- `routes-to` is required semantically ("Workday-In transport must route to a component").
- The `integration-system/@name` becomes the Integration System name in the tenant.

## local-in: subroutine entry (called by local-out)

```xml
<cc:local-in id="ProcessWorker" routes-to="BuildWorkerRequest">
    <cc:parameter name="inWorkerId" type="string" required="true" documentation="Worker ID to process"/>
    <cc:parameter name="inDryRun" type="boolean" required="false" default="false"/>
</cc:local-in>
```
- Optional `access="public|private"`, `icon="icons/X.png"` (16/24 px PNG in WSAR-INF), `tooltip`.
- `parameter` attributes:
  - `name`,
  - `type` (`string|boolean|integer|…`),
  - `required` (`true`, `false`, or an MVEL condition),
  - `default` (MVEL),
  - `validation` (MVEL boolean),
  - `documentation`.
- Parameters arrive as `props['inX']`.

## local-out: call a subroutine or a Workday common component

```xml
<cc:local-out id="Call_ProcessWorker" store-message="none" routes-response-to="AfterWorker"
    endpoint="vm://INT_Example/ProcessWorker">
    <cc:set name="inWorkerId" value="props['p.worker.id']"/>
    <cc:set name="inDryRun" value="false"/>
</cc:local-out>
```
- `endpoint` = `vm://<ProjectName>/<local-in id>` (same project or same cloud collection) or `vm://wcc/<Component>`.
- Other options:
  - `clone-request` (false): pass a copy of the message,
  - `propagate-abort` (false): propagate the callee's abort flag to the caller,
  - `unset-properties`: remove the `cc:set` props after the call,
  - `execute-when`.

### Workday common components (vm://wcc/...)

| Endpoint | Key `cc:set` names | Use |
|---|---|---|
| `vm://wcc/PutIntegrationMessage` | `is.message.severity` (`'INFO'`/`'WARNING'`/`'ERROR'`/`'CRITICAL'`/`'DEBUG'`), `is.message.summary`, `is.message.detail`, `is.document.variable.name` (var produced by a `store` step), `is.document.file.name`, `is.document.deliverable` (`'true'` to hand the file to a delivery service) | message + optional document on the integration event. CRITICAL/ERROR drive the event status |
| `vm://wcc/GetEventDocuments` | `ie.event.wid` (defaults to current event) | fetch documents attached to an event (inbound files) |
| `vm://wcc/PutIntegrationEvent` | see Studio palette | update the event (e.g. percent complete) |
| `vm://wcc/PagedGet` | `is.paged.get.application` (`'Human_Resources'`), `is.paged.get.version` (`'v44.0'`), `is.paged.get.request.current.page.xpath` (`'/env:Envelope/env:Body/wd:Get_Workers_Request/wd:Response_Filter/wd:Page'`), `is.paged.get.response.current.page.xpath` / `…total.pages.xpath` / `…total.results.xpath` (`'/env:Envelope/env:Body/*/wd:Response_Results/wd:Page'` etc.), then either `is.paged.get.process.endpoint` (vm:// local-in called per page) or `is.paged.get.aggregate.xpath` + `.header` + `.footer` (aggregate all pages into the returned message) | paging over Get_* SOAP operations. The message must be the full request envelope. Results: `props['is.paged.get.total.results']` |
| `vm://wcc/GetIntegrationSystems`, `vm://wcc/GetEventConfigurations`, `vm://wcc/GetIntegrationEvent` | | configuration lookups |

```xml
<cc:local-out id="ReportSummary" store-message="none" endpoint="vm://wcc/PutIntegrationMessage">
    <cc:set name="is.message.severity" value="'INFO'"/>
    <cc:set name="is.message.summary" value="'Processed ' + props['p.count'] + ' records'"/>
    <cc:set name="is.document.variable.name" value="'p.output.document'"/>
</cc:local-out>
```

## workday-out-rest: Workday REST / RaaS

```xml
<cc:workday-out-rest id="GetSourceData" store-message="none" routes-response-to="SplitRecords"
    extra-path="@{intsys.reportService.getExtrapath('Source Data')}"/>
```
- *req* `extra-path`: the path after the tenant REST base. For RaaS use `intsys.reportService.getExtrapath('<report alias>')`. The alias is declared in the workday-in `report-service` (see integration-system.md).
- Append RaaS prompts as a query string: `extra-path="@{intsys.reportService.getExtrapath('Alias')}?Effective_Date=@{props['p.date']}&amp;format=simplexml"`.
- `method` (GET), `response-timeout` (600000). The response is XML (`wd:Report_Data/wd:Report_Entry`) unless `format=json`.
- Custom objects: `intsys.customObjectService.getExtrapath('<alias>')` with a `custom-object-service`.

## workday-out-soap: Workday Web Services (SOAP)

```xml
<cc:workday-out-soap id="CallGetWorkers" store-message="none" routes-response-to="HandleWorkers"
    application="Human_Resources" version="v44.0"/>
```
- *req* `application` (WWS service name: `Human_Resources`, `Staffing`, `Integrations`, `Financial_Management`, …) and *req* `version` (e.g. `v44.0`, or `@{props['globalApiVersion']}`).
- The message must be the SOAP request envelope. Build it with a `write` step (template) or XSLT. `wd:` namespace = `urn:com.workday/bsvc`.
- SOAP faults raise errors (handle them with `send-error`; `context.errorMessage` holds the fault text). `failure-message` replaces the exception text. `replace-with-soap-fault="true"` replaces the failed message with a SOAP fault carrying `faultcode`/`faultstring`.
- For paged `Get_*` operations use `vm://wcc/PagedGet` (components table above) instead of looping yourself.

## http-out: external REST/HTTP

```xml
<cc:http-out id="PostRecord" store-message="none" routes-response-to="RecordResult"
    endpoint="@{props['p.endpoint.url']}" http-method="POST"
    error-as-response="true" retries="3" retryDelay="5000"/>
```
- Before it, set headers in an `eval`: `message.removeAllHeaders()` (drops headers left by earlier Workday calls), then `message.setHeader('Content-Type','application/json')` and `message.setHeader('Authorization','Bearer ' + props['p.token'])`.
- Basic auth alternative: child `<cc:http-basic-auth username="@{props['p.user']}" password="@{props['p.pwd']}"/>`.
- `http-method`:
  - GET/POST/PUT/DELETE (POST if omitted),
  - PATCH needs `props['wd.http.client'] = 'apache'` beforehand.
- Response status: `context.getProperty('http.response.status')`. With `error-as-response="true"`, 4xx/5xx responses are passed on as the message so you can inspect them, instead of raising an error.
- Other options:
  - `retries` (2), `retryDelay` ms (3000),
  - `connect-timeout` (300000), `response-timeout`,
  - `accept`, `accept-gzip`, `gzip-content`.

## sftp-out: deliver a file

```xml
<cc:sftp-out id="DeliverFile" store-message="none"
    endpoint="sftp://@{intsys.getAttribute('SFTP Host')}/@{intsys.getAttribute('SFTP Directory')}"
    username="@{intsys.getAttribute('SFTP User')}" password="@{intsys.getAttribute('SFTP Password')}"
    output-file-pattern="@{props['p.file.name']}"/>
```
- *req* `username`. `endpoint` = `sftp://host[:port]/existing/dir`. The message root part is the file content.
- `output-file-pattern` accepts literal text plus the tokens `${INFILE}`, `${UUID}` and `${EXT}`.
- `method`: `put` (default), `list`, `get` or `delete`.
- `ftp-out` and `ftps-out` are analogous. For key auth use a child `private-key-properties`.

## async-mediation: run steps

```xml
<cc:async-mediation id="Initialize" routes-to="GetSourceData" handle-downstream-errors="true">
    <cc:steps>
        <cc:eval id="ReadConfig">
            <cc:expression>props['p.endpoint.url'] = intsys.getAttribute('Endpoint URL')</cc:expression>
        </cc:eval>
    </cc:steps>
    <cc:send-error id="OnError" routes-to="ReportFailure"/>
</cc:async-mediation>
```
- Children in order: `steps`, then one error handler (`send-error`, `log-error` or `custom-error-handler`).
- `handle-downstream-errors="true"`: the handler also catches errors of everything after this mediation.
- `continue-after-error` = `rewind` (default) or `recover`: see error-handling.md.
- `execute-steps-when` (MVEL): skip all steps when false.

## sync-mediation: steps before and after a call

```xml
<cc:sync-mediation id="WrapCall" routes-to="CallGetWorkers" routes-response-to="AfterCall">
    <cc:request-steps>
        <cc:eval id="Before"><cc:expression>props['p.started'] = util.currentTime()</cc:expression></cc:eval>
    </cc:request-steps>
    <cc:response-steps>
        <cc:eval id="After"><cc:expression>props['p.done'] = true</cc:expression></cc:eval>
    </cc:response-steps>
</cc:sync-mediation>
```

## custom-mediation: Java bean

```xml
<cc:custom-mediation id="GenerateSignature" routes-to="PostRecord" ref="SignatureBean"/>
```
Requires `<bean id="SignatureBean" class="com.acme.SignatureBean"/>` after `</cc:assembly>`.

## route: conditional / loop / fan-out

```xml
<cc:route id="RouteByType">
    <cc:mvel-strategy>
        <cc:choose-route expression="props['p.type'] == 'csv'" route="Csv"/>
        <cc:choose-route expression="true" route="Xml"/>
    </cc:mvel-strategy>
    <cc:sub-route name="Csv" routes-to="BuildCsv"/>
    <cc:sub-route name="Xml" routes-to="BuildXml"/>
</cc:route>
```
- Strategy first, then `sub-route`s. `choose-route` entries are evaluated in order; the first true wins. End with an `expression="true"` fallback so every message matches a sub-route.
- Loop:
  ```xml
  <cc:loop-strategy condition="props['i'] &lt; props['n']" init="props['i'] = 0" increment="props['i'] = props['i'] + 1" repeat-limit="1000"/>
  ```
  Use it with a single `sub-route`.
- Other strategies:
  - `xpath-strategy` (choose-route expression is XPath),
  - `all-strategy` (every sub-route in turn),
  - `failover-strategy`,
  - `doc-iterator` (documents retrieved from Workday).

## splitter: one message per record

```xml
<cc:splitter id="SplitRecords" no-split-message-error="false">
    <cc:sub-route name="EachRecord" routes-to="PrepareRecord"/>
    <cc:xml-stream-splitter xpath="/*/*"/>
</cc:splitter>
```
- `sub-route` first, then the strategy:
  - `xml-stream-splitter xpath` (streaming; use for RaaS/large XML; the xpath must be streamable: simple child steps),
  - `xpath-splitter xpath`,
  - `json-splitter json-path`,
  - `standard-splitter` (text lines),
  - `unzip-splitter`,
  - `custom-splitter ref`.
- `no-split-message-error` (true): set it to `false` when an empty input is acceptable.
- In the sub-flow, `util.isLastMessageInBatch()` tells you whether this is the last piece.

## aggregator: collect split pieces

```xml
<cc:aggregator id="CollectResults" routes-to="Summarize">
    <cc:size-batch-strategy batch-size="-1"/>
    <cc:xml-message-content-collater output-mimetype="text/xml">
        <cc:header-text>&lt;Results></cc:header-text>
        <cc:footer-text>&lt;/Results></cc:footer-text>
    </cc:xml-message-content-collater>
</cc:aggregator>
```
- Batch strategy first:
  - `size-batch-strategy batch-size="-1"`: all pieces, closing on the last split message,
  - `batch-size="N"`: every N pieces,
  - `time-batch-strategy`.
- Then the collater:
  - `message-content-collater` (plain concatenation; optional `header-text`/`footer-text`/`separator`),
  - `xml-message-content-collater` (header and footer required),
  - `json-collater`,
  - `zip-file-collater`,
  - `mtable-collater`.
- Collater output can go to a variable: `output="variable" output-variable="all"`.

## Global error handler (top-level)

```xml
<cc:send-error id="GlobalErrorHandler" routes-to="ReportFailure"/>
```
Catches anything no local handler caught. It is not an ID-addressable component in the diagram; see diagram.md before adding one.
