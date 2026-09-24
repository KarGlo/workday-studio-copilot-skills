# Steps (inside `<cc:steps>`, `<cc:request-steps>`, `<cc:response-steps>`)

- Steps run in document order.
- Every step accepts `id` (always set it), `description` (shown in the consolidated log) and `execute-when` (MVEL boolean; step is skipped when false).
- I/O attributes where applicable:
  - input: `input="message|variable|rootpart|soapbody|attachment"` + `input-variable`,
  - output: `output="message|variable|…"` + `output-variable` + `output-mimetype`.

## eval: run MVEL

```xml
<cc:eval id="ReadConfig">
    <cc:expression>props['p.url'] = intsys.getAttribute('Endpoint URL')</cc:expression>
    <cc:expression>props['p.count'] = 0</cc:expression>
</cc:eval>
```
- One statement per `<cc:expression>` is clearest. Multi-line scripts are allowed inside one expression.
- Each expression is independent. Share values through `props`, not local variables.

## validate-exp: assert conditions (raises an error when false)

```xml
<cc:validate-exp id="CheckConfig">
    <cc:expression failure-message="Attribute 'Endpoint URL' is empty.">props['p.url'] != empty</cc:expression>
</cc:validate-exp>
```
Optional `error-number` on `expression`. The raised error goes to the error handler (`context.errorMessage` = failure-message).

## write: create message/variable content from a template

```xml
<cc:write id="BuildRequest" output-mimetype="text/xml">
    <cc:message>
        <cc:text>&lt;env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/">
    &lt;env:Body>
        &lt;wd:Get_Workers_Request xmlns:wd="urn:com.workday/bsvc" wd:version="v44.0">
            &lt;wd:Request_References>
                &lt;wd:Worker_Reference>&lt;wd:ID wd:type="Employee_ID">@{props['p.worker.id']}&lt;/wd:ID>&lt;/wd:Worker_Reference>
            &lt;/wd:Request_References>
        &lt;/wd:Get_Workers_Request>
    &lt;/env:Body>
&lt;/env:Envelope></cc:text>
    </cc:message>
</cc:write>
```
- `cc:text` is an MVEL template: markup must be escaped (`&lt;`), and dynamic values go in `@{...}`.
- Template directives:
  - `@if{cond}...@end{}`,
  - `@foreach{props['list']}...@{item}...@end{}`,
  - `@foreach{props['list'] as x}...@{x}...@end{}`.
- Alternative to `cc:text`: `<cc:static-file input-file="request-template.xml"/>` (file in WSAR-INF).
- `write` with `output="variable" output-variable="x"` keeps the current message intact.
- Requests for `workday-out-soap` and `vm://wcc/PagedGet`: write the full `env:Envelope` as above (Workday samples do this).

## xslt-plus: XSLT 3.0 (Saxon); preferred over `xslt`

```xml
<cc:xslt-plus id="Transform" output-mimetype="text/xml" url="xslt/Transform.xsl"/>
```
- `url` is relative to `ws/WSAR-INF/`, or `mctx:vars/<var>` for an XSLT held in a variable.
- For text output (CSV/JSON) set `<xsl:output method="text"/>` and `output-mimetype="text/csv"` or `"application/json"`.
- `xsl:message` output is collected in `props['xsltstep.messages']` (`messages-property`).
- Inside XSLT files, match namespaced RaaS fields with `*[local-name()='Field']`, not `*:Field`. Studio's Eclipse XSL validator only parses XPath 1.0 and reports `*:Field` as "Xpath is invalid", although Saxon runs it. XSLT 2/3 functions (`replace`, `xsl:function`) are accepted.
- Requires assembly version ≥ 2017.5. Plain `<cc:xslt url="..."/>` exists for XSLT 1.0/2.0.

## copy: move content between message and variables

```xml
<cc:copy id="SaveOriginal" output="variable" output-variable="p.original"/>
<cc:copy id="RestoreOriginal" input="variable" input-variable="p.original"/>
```
`input-xpath`/`output-xpath` (+ `namespaces="wd urn:com.workday/bsvc"`) copy fragments.

## store: save a document (for the event, delivery, later retrieval)

```xml
<cc:store id="StoreOutput" output="variable" output-variable="p.output.document"
    createDocumentReference="false" schema="http://www.w3.org/2005/Atom"
    title="@{props['p.file.name']}" expiresIn="P30D" summary="Extract file"/>
```
- Stores the current message (or `input="variable"`).
- Attach the document to the event by passing the `output-variable` name to PutIntegrationMessage via `is.document.variable.name`. Also set `is.document.deliverable` = `'true'` if a delivery service should pick it up.
- `createDocumentReference="true"` makes the store step itself create the reference. Use one mechanism, not both.
- Limits: 1 GB per document, 3 GB per run (compressed).

## retrieve: load a stored document

```xml
<cc:retrieve id="LoadDoc" output="variable" output-variable="p.doc" collection="@{props['p.collection']}" entry="@{props['p.entry']}"/>
```
*req* `entry`. Typical after `vm://wcc/GetEventDocuments`, which lists documents with collection and entry ids.

## set-headers: HTTP/message headers declaratively

```xml
<cc:set-headers id="Headers" clear-all="true">
    <cc:add-headers>
        <cc:add-header name="Content-Type" value="application/json"/>
    </cc:add-headers>
</cc:set-headers>
```
- Child order: `remove-headers` then `add-headers`.
- For computed values an `eval` with `message.setHeader(name, value)` is simpler.

## cloud-log: structured, user-visible log file on the event

```xml
<cc:cloud-log id="LogRecord" level="info" message="Posted @{props['p.record.id']}"
    message-details="HTTP @{props['p.http.status']}" reference-id="props['p.record.id']"/>
```
- `level`: `debug|info|warn|warning|error|fatal|critical` or an MVEL expression. `message` and `message-details` are templates; `reference-id` is an MVEL expression.
- Entries accumulate in variable `cloud-log-content`. Store it at the end and attach it with PutIntegrationMessage (`is.document.variable.name` = `'p.log.document'`):
  ```xml
  <cc:store id="StoreLog" input="variable" input-variable="cloud-log-content" output="variable" output-variable="p.log.document" schema="http://www.w3.org/2005/Atom" title="Log.html"/>
  ```
- Optional `<cc:log-column key="K" label="L">@{...}</cc:log-column>` children and `output-file-type="HTML|CSV|XLSX"`.

## log: server log line (debugging only; don't log big payloads)

```xml
<cc:log id="Trace" level="info">
    <cc:log-message><cc:text>Status @{props['p.http.status']}</cc:text></cc:log-message>
</cc:log>
```
Add `<cc:message-content/>` inside `log-message` to include the message body.

## Converters

| Step | Key attributes | Notes |
|---|---|---|
| `xml-to-json` | `output-mimetype="application/json"`, `ignore-attributes` (true), `schema-url` (XSD for typing) | quick conversion; for an exact JSON shape use XSLT with `method="text"` |
| `json-to-xml` | `root-element-name` (root), `nested-array-name` (entry), `nested-object-name` (data) | JSON responses → XML for XPath/XSLT |
| `csv-to-xml` | `useFirstLineAsHeader`, `separator`, `rootName` (root), `rowName` (row), `colNames`, `format="simple|rfc4180"` | |
| `xml-to-csv` | `writeHeaderLine`, `separator`, `format` | expects simple row XML |
| `text-excel` | *req* `style` | Excel ↔ XML |
| `base64-encode` / `base64-decode`, `zip`/`unzip`, `compress`/`decompress` | input/output | |
| `pgp-encrypt` | *req* `certificate` | |
| `pgp-decrypt` | *req* `private-key` | |

## custom: Java bean step

```xml
<cc:custom id="Sign" ref="SignatureBean" method-name="sign"/>
```
Needs a matching `<bean>` after `</cc:assembly>`.

## validate-xpath / validate (schema)

```xml
<cc:validate-xpath id="HasEntries" xpath="count(/*/*) &gt; 0"/>
```
Raises an error when the XPath is false. `validate mode="schema" schema="x.xsd"` validates against an XSD.
