# Built-in assembly elements: catalog

Everything Workday Studio offers for **cloud** assemblies (`assembly-core.xsd`), with the attributes the XSD requires.
- Purpose texts are short summaries.
- For the full documentation of any element (all attributes, semantics, examples), run from the workspace root:
  - `java .github/skills/workday-studio-integration/tools/StudioDocs.java element <name>` → Studio's help page plus the element's schema (attributes, defaults, child order),
  - `java .github/skills/workday-studio-integration/tools/StudioDocs.java search <words>` → for concepts (MVEL, error handling, subassemblies, …).
- **Always look an element up before using it for the first time** unless components.md/steps.md already show it.
- Top-level components also require `id`.

## In-transports (start a flow)

| Element | Purpose | Required attributes |
|---|---|---|
| `workday-in` | Entry point when the integration is launched in Workday; contains the integration-system definition (params, attributes, services). Exactly one per integration. | `id` |
| `local-in` | Named entry of a subflow, called by a local-out (`vm://<Project>/<id>`); declares `parameter`s (they arrive as props). | `id` |

## Out-transports (call something, get a response)

| Element | Purpose | Required attributes |
|---|---|---|
| `local-out` | Calls a local-in of this project (or of another project in the same cloud collection) or a Workday common component (`vm://wcc/...`); `cc:set` passes parameters. | `id` |
| `http-out` | HTTP(S) request to an external URL (REST, SOAP over HTTP, files); auth by headers or child http-basic-auth / http-custom-auth; status in `http.response.status`. | `id` |
| `workday-out-rest` | Calls Workday REST APIs and RaaS custom reports (`extra-path`, usually from `intsys.reportService.getExtrapath`). | `id`, `extra-path` |
| `workday-out-soap` | Calls Workday Web Services (SOAP) by application name and version; the message is the request envelope. | `id`, `version`, `application` |
| `sftp-out` | Writes (or lists/gets/deletes) files on an SFTP server; key or password authentication. | `id`, `username` |
| `ftp-out` | Same for plain FTP. | `id`, `username`, `password` |
| `ftps-out` | Same for FTP over TLS. | `id`, `username` |
| `email-out` | Sends the message as an e-mail through SMTP. | `id`, `from` |
| `xmpp-out` | Sends a chat message to an XMPP (Jabber) server. | `id`, `username`, `password`, `server`, `domain` |
| `custom-out` | Out-transport implemented by your Spring bean (`ref`). | `id`, `ref` |

## Mediations (run steps)

| Element | Purpose | Required attributes |
|---|---|---|
| `async-mediation` | Runs `steps` in order, then routes-to the next component; host for a local send-error. | `id` |
| `sync-mediation` | request-steps → routes-to target → response-steps when the response returns → routes-response-to. | `id` |
| `custom-mediation` | Request/response handling implemented by your Spring bean (`ref`). | `id`, `ref` |

## Other components

| Element | Purpose | Required attributes |
|---|---|---|
| `route` | Sends the message to one or more named sub-routes chosen by a routing strategy (conditions, loops, fan-out). | `id` |
| `splitter` | Splits a bulk message into pieces; every piece goes through the sub-route. | `id` |
| `aggregator` | Collects messages (typically split pieces) into batches and collates each batch into one message. | `id` |
| `map-reduce` | Splits the input and processes the pieces in parallel workers (`vm-endpoint`), then collates the results. | `id`, `vm-endpoint` |
| `web-service` | Describes a web service (partners, bindings); rarely needed in Workday cloud integrations. | `id` |

## Error handlers (child of a mediation = local; child of the assembly = global)

| Element | Purpose | Required attributes |
|---|---|---|
| `send-error` | Routes the error to a component (report, SSK HandleError, recovery); optional condition-expressions, `rethrow-error`. | `routes-to` |
| `log-error` | Writes the error and stack trace to the server log. | - |
| `custom-error-handler` | Error handling implemented by your Spring bean (`ref`). | `ref` |

## Routing strategies (first child of a route)

| Element | Purpose | Required attributes |
|---|---|---|
| `mvel-strategy` | Choose sub-routes with choose-route MVEL conditions. | - |
| `xpath-strategy` | Choose sub-routes with choose-route XPath conditions on the message. | - |
| `regex-strategy` | Choose sub-routes with choose-route regular expressions on the message text. | - |
| `loop-strategy` | Repeat the single sub-route while `condition` is true (init/increment, repeat-limit). | `condition` |
| `all-strategy` | Send the message to every sub-route. | - |
| `failover-strategy` | Try the sub-routes in order until one succeeds. | - |
| `round-robin-strategy` | Rotate messages across the sub-routes. | - |
| `doc-iterator` | Iterate over documents retrieved from Workday (e.g. event attachments), one sub-route pass per document. | - |
| `custom-strategy` | Routing implemented by your Spring bean. | `ref` |

## Splitter strategies (child of a splitter, after the sub-route)

| Element | Purpose | Required attributes |
|---|---|---|
| `xml-stream-splitter` | Streaming XPath split of large XML (RaaS, WWS responses); the XPath must be streamable. | `xpath` |
| `xpath-splitter` | XPath split on an in-memory XML document. | `xpath` |
| `json-splitter` | Split a JSON document with a JSON path. | `json-path` |
| `standard-splitter` | Split text into records/lines; optional header/content/footer parser children. | - |
| `unzip-splitter` | One message per entry of a ZIP/TAR archive. | - |
| `mtable-splitter` | One message per row of an mtable held in a property. | - |
| `custom-splitter` | Splitting implemented by your Spring bean. | `ref` |

## Standard-splitter parsers

| Element | Purpose | Required attributes |
|---|---|---|
| `header-fixed-lines` | Treat the first N lines as a header. | - |
| `header-ends-with` | Header ends at a line matching a regex. | - |
| `content-fixed-lines` | Each record has N lines. | - |
| `content-starts-with` | A record starts at a line matching a regex. | `regexp` |
| `content-starts-ends` | A record spans from a start regex to an end regex. | `start-regexp`, `end-regexp` |
| `footer-fixed-lines` | Treat the last N lines as a footer. | - |
| `footer-starts-with` | Footer starts at a line matching a regex. | - |

## Aggregator: batch strategies (first child)

| Element | Purpose | Required attributes |
|---|---|---|
| `size-batch-strategy` | Batch every N messages; `batch-size="-1"` = all pieces of the current split. | - |
| `time-batch-strategy` | Batch by time period. | - |
| `custom-batch-strategy` | Batching implemented by your Spring bean. | `ref` |

## Aggregator: collaters (second child)

| Element | Purpose | Required attributes |
|---|---|---|
| `message-content-collater` | Concatenate message contents (optional header/footer/separator text). | - |
| `xml-message-content-collater` | Wrap XML pieces between required header and footer text. | - |
| `json-collater` | Combine JSON pieces. | - |
| `zip-file-collater` | Pack the pieces into one ZIP file. | - |
| `mtable-collater` | Collect rows into a new mtable. | - |
| `custom-collater` | Collation implemented by your Spring bean. | `ref` |

## Steps: scripting and content

| Element | Purpose | Required attributes |
|---|---|---|
| `eval` | Run MVEL expressions (set props/vars, headers, compute values). | - |
| `javascript` | Run a JavaScript function on the message. | - |
| `custom` | Run a method of your Spring bean as a step. | `ref` |
| `write` | Create message/variable content from an MVEL template or a static file. | - |
| `copy` | Copy content between message, variables and attachments (optionally XPath fragments). | - |
| `store` | Save content as a Workday document (for the event, delivery or later retrieval). | - |
| `retrieve` | Load a stored document into the message or a variable. | `entry` |

## Steps: transformation

| Element | Purpose | Required attributes |
|---|---|---|
| `xslt-plus` | XSLT 3.0 transform (preferred). | `url` |
| `xslt` | XSLT 1.0/2.0 transform. | `url` |
| `etv` | Validate/format XML elements as directed by etv: attributes placed on them (usually by an XSLT). | `message-property` |
| `xtt` | Turn XML into text (delimited or fixed-width) as directed by xtt: attributes in the XML. | `message-property` |
| `json-transformer` | Reshape JSON with json-mapping rules. | - |
| `textschema` | Convert text <-> XML with a text schema file. | `url`, `style` |
| `fop` | Render XSL-FO/XML into PDF or RTF. | - |
| `wrap-soap` | Wrap the message in a SOAP envelope. | - |

## Steps: format conversion

| Element | Purpose | Required attributes |
|---|---|---|
| `json-to-xml` | JSON → XML. | - |
| `xml-to-json` | XML → JSON. | - |
| `csv-to-xml` | CSV → XML rows. | - |
| `xml-to-csv` | Row XML → CSV. | - |
| `text-excel` | Excel <-> CSV/XML. | `style` |
| `java-to-xml` | Marshal Java objects (JAXB) to XML. | `property` |
| `xml-to-java` | Unmarshal XML to Java objects (JAXB). | `property`, `packages` |
| `x12-to-xml` | ANSI X12 EDI → XML. | - |
| `xml-to-x12` | XML → ANSI X12 EDI. | - |
| `character-conversion` | Clean text: upper-case, remove accents/EOLs/control characters. | - |
| `base64-encode` | Base64 encode. | - |
| `base64-decode` | Base64 decode. | - |
| `zip` | Create a ZIP. | - |
| `unzip` | Extract from a ZIP. | - |
| `compress` | GZIP compress. | - |
| `decompress` | GZIP decompress. | - |
| `pgp-encrypt` | PGP encrypt (and optionally sign). | `certificate` |
| `pgp-decrypt` | PGP decrypt (and optionally verify). | `private-key` |

## Steps: validation, logging, headers, other

| Element | Purpose | Required attributes |
|---|---|---|
| `validate-exp` | Fail with a message when an MVEL condition is false. | - |
| `validate-xpath` | Fail when an XPath condition is false. | `xpath` |
| `validate` | Validate XML against an XSD/DTD or for well-formedness. | - |
| `log` | Write a line (and optionally the message) to the server log. | - |
| `cloud-log` | Add a structured entry to the user-visible cloud log (HTML/CSV/XLSX). | `message` |
| `set-headers` | Add/remove message (HTTP) headers. | - |
| `set-dynamic-endpoint` | Set the WS-Addressing endpoint used by the next out-transport. | `endpoint` |
| `mtable-builder` | Build an mtable (in-memory table) from CSV/JSON/custom input. | - |
| `mtable-writer` | Write an mtable as CSV/JSON/custom output. | - |
| `xmldiff` | Compare two XML documents. | - |
| `enqueue-message` | Put the message on a Workday message queue. | `queueName` |
| `stats` | Record runtime statistics (rarely needed). | `category`, `integration` |
| `aggregate-stats` | Aggregate runtime statistics (rarely needed). | `integration`, `category` |

## Mtable readers/writers

| Element | Purpose | Required attributes |
|---|---|---|
| `csv-mtable-reader` | Read CSV into an mtable. | - |
| `json-mtable-reader` | Read JSON into an mtable. | - |
| `extensibility-mtable-reader` | Read with a custom parser. | - |
| `csv-mtable-writer` | Write an mtable as CSV. | - |
| `json-mtable-writer` | Write an mtable as JSON. | - |
| `extensibility-mtable-writer` | Write with a custom writer. | - |

## Transport children and security

| Element | Purpose | Required attributes |
|---|---|---|
| `http-basic-auth` | HTTP Basic authentication on http-out. | - |
| `http-custom-auth` | Custom HTTP authentication bean on http-out. | `authenticator-bean` |
| `https-properties` | TLS settings of http-out. |  |
| `proxy-properties` | Proxy settings for (S)FTP(S). |  |
| `firewall-properties` | Firewall settings for FTPS. |  |
| `client-key-properties` | Client certificate for FTPS. |  |
| `server-key-properties` | Server key for FTPS. |  |
| `private-key-properties` | Private key for SFTP. |  |
| `rest-binding` | REST binding. | - |
| `wsdl-soap-binding` | SOAP binding from a WSDL. | - |
| `wsdl-http-binding` | HTTP binding from a WSDL. | - |
| `ws-security` | WS-Security policy file. | `file` |
| `ws-ut` | WS-Security username token. | - |
| `ws-rm` | WS-ReliableMessaging policy. | - |
| `custom-policy` | Custom policy bean. | `ref` |

## Workday common components (local-out endpoint `vm://wcc/<Name>`)

| Name | Purpose |
|---|---|
| `PutIntegrationMessage` | Message (+ optional document) on the integration event; drives the event status. |
| `PutIntegrationEvent` | Update the integration event (status, percent complete). |
| `GetEventDocuments` | List the documents attached to an event (inbound files). |
| `GetEventConfigurations` | Service configurations attached to an event. |
| `GetIntegrationEvent` | Read an integration event by reference. |
| `GetIntegrationSystems` | Read and parse an integration system configuration. |
| `PagedGet` | Page through a Get_* WWS operation (aggregate, or process page by page). |
| `PagedGetLocalPaging` | Paging done locally: fetch all ids once, then details per id. |
| `PdfPrintStep` | Render RaaS data to PDF with a BIRT design. |
| `SalesforceConnector` | Authenticated calls to salesforce.com. |
| `Ftp` | Legacy FTP subassembly; prefer ftp-out / sftp-out. |
| `PrismAnalytics` | Load data into Prism Analytics. |

Parameters of each: `StudioDocs.java element <name>` (e.g. `element putintegrationmessage`).

Not allowed in cloud assemblies (non-cloud schema only): `http-in`, `as2-in`, `event-in`, `custom-in`, `custom-in-scheduled`, `database-in`, `email-in`, `file-in`, `ftp-in`, `ftps-in`, `sftp-in`, `jms-in`, `static-file-in`, `file-out`, `jms-out`, `as2-out`, routing strategies `cluster-strategy`, `throttling-strategy`, `custom-throttling-strategy`, steps `event`, `run-as`, `logout-session`, `assign-customer-id`, handler `event-error`.
