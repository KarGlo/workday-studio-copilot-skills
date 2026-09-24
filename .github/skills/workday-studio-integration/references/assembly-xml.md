# assembly.xml: structure, execution model, rules

## Skeleton (keep exactly this header; Studio generates the same)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans
     xmlns="http://www.springframework.org/schema/beans"
     xmlns:beans="http://www.springframework.org/schema/beans"
     xmlns:atom="http://www.w3.org/2005/Atom"
     xmlns:cc="http://www.capeclear.com/assembly/10"
     xmlns:cloud="urn:com.workday/esb/cloud/10.0"
     xmlns:env="http://schemas.xmlsoap.org/soap/envelope/"
     xmlns:pi="urn:com.workday/picof"
     xmlns:wd="urn:com.workday/bsvc"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
 
	<cc:assembly id="WorkdayAssembly" version="KEEP-STUDIO-VALUE">
        <!-- top-level components, 8-space indent, one after another -->
	</cc:assembly>

    <!-- optional Spring beans for custom Java: <bean id="MyBean" class="com.acme.MyBean"/> -->
</beans>
```

- `cc:assembly/@id` is `WorkdayAssembly` in every project. The diagram refers to it.
- Namespaces you may need in XPath/MVEL: `wd` = `urn:com.workday/bsvc` (SOAP API), `env` = SOAP envelope. RaaS output uses `urn:com.workday.report/<Report_Name>`, which changes per report: match with `*[local-name()='Field']`. In MVEL `xpath()` calls, `*:Field` also works, but in `.xsl` files it triggers Studio's XPath 1.0 validator.
- Do not put XML comments between top-level components in files you extend (see diagram rules). In new files, prefer swimlane names over comments to document the flow.

## Execution model (what routes where)

- A **message** (root part + attachments + headers) travels through components together with a **MediationContext**:
  - `props`: string-keyed properties, the usual working storage,
  - `vars`: named message-like variables holding documents,
  - `context`: error info and helper methods.
- **In-transports** start a flow:
  - `workday-in` runs when the integration is launched in Workday. There is exactly one per integration.
  - `local-in` is a subroutine entry, called by `local-out`.
- `routes-to="X"`: after this component finishes the request path, send the message to component `X` (mediations, in-transports, `send-error`, `sub-route`).
- **Out-transports** (`local-out`, `http-out`, `workday-out-rest`, `workday-out-soap`, `sftp-out`, …) call something and get a response. `routes-response-to="Y"` continues with the response in `Y`. Without it, the flow returns to the caller.
- `sync-mediation`: `request-steps` → `routes-to` target → (response comes back) → `response-steps` → `routes-response-to`.
- `local-out` → `local-in` (vm://Project/Id) runs the subroutine chain. When that chain ends, control returns to the `local-out`, which continues at its `routes-response-to`. Props set in the subroutine stay visible to the caller. Use `unset-properties="true"` to drop the `cc:set` params afterwards.
- `splitter` sends each piece through its `sub-route`. An `aggregator` downstream collects the pieces (`size-batch-strategy batch-size="-1"` = all pieces of the split) and continues once with the collated message.
- `execute-when` (MVEL boolean) on out-transports and steps skips them when false. The message continues unchanged.
- Errors unwind to the nearest error handler. See `error-handling.md`.

## Top-level vs nested

- **Top-level components** are direct children of `cc:assembly`. They need a unique `id` (XSD ID) and are the boxes on the canvas:
  - `workday-in`, `local-in`,
  - `local-out`, `http-out`, `workday-out-rest`, `workday-out-soap`, `sftp-out`, `ftp-out`, `ftps-out`, `email-out`, `custom-out`, `xmpp-out`,
  - `async-mediation`, `sync-mediation`, `custom-mediation`,
  - `route`, `splitter`, `aggregator`, `map-reduce`, `web-service`,
  - a global `send-error` / `log-error` / `custom-error-handler`.
- **Nested elements** have no diagram box of their own:
  - steps (inside `cc:steps`, `cc:request-steps`, `cc:response-steps`),
  - error handlers inside mediations,
  - `sub-route`, `cc:set`, `cc:parameter`, strategies and collaters.

## Allowed elements (cloud subset, `assembly-core.xsd`)

- **In-transports:** `workday-in`, `local-in`.
- **Out-transports:** `local-out`, `http-out`, `workday-out-rest`, `workday-out-soap`, `sftp-out`, `ftp-out`, `ftps-out`, `email-out`, `custom-out`, `xmpp-out`.
- **Mediations:** `async-mediation`, `sync-mediation`, `custom-mediation`.
- **Other components:** `route`, `splitter`, `aggregator`, `map-reduce`, `web-service`.
- **Error handlers:** `send-error`, `log-error`, `custom-error-handler`.
- **Steps:**
  - scripting: `eval`, `javascript`, `custom`,
  - writing and storage: `write`, `copy`, `store`, `retrieve`,
  - XSLT: `xslt`, `xslt-plus`, `etv`, `xtt`,
  - validation: `validate`, `validate-exp`, `validate-xpath`,
  - logging: `log`, `cloud-log`,
  - headers and endpoints: `set-headers`, `set-dynamic-endpoint`, `wrap-soap`,
  - format converters: `json-to-xml`, `xml-to-json`, `json-transformer`, `csv-to-xml`, `xml-to-csv`, `text-excel`, `textschema`, `java-to-xml`, `xml-to-java`,
  - encoding and crypto: `base64-encode`, `base64-decode`, `character-conversion`, `pgp-encrypt`, `pgp-decrypt`,
  - archives: `zip`, `unzip`, `compress`, `decompress`,
  - mtables: `mtable-builder`, `mtable-writer`,
  - other: `fop`, `xmldiff`, `x12-to-xml`, `xml-to-x12`, `enqueue-message`, `stats`, `aggregate-stats`.
- **Not allowed** (non-cloud, fail validation): `http-in`, `as2-in`, `event-in`, `custom-in`, `custom-in-scheduled`, `database-in`, `email-in`, `file-in`, `ftp-in`, `ftps-in`, `sftp-in`, `jms-in`, `static-file-in`, `file-out`, `jms-out`, `as2-out`, routing strategies `cluster-strategy`, `throttling-strategy`, `custom-throttling-strategy`, steps `event`, `run-as`, `logout-session`, `assign-customer-id`, handler `event-error`.

## Ids

- Component `id`: XSD ID (NCName). Use letters, digits, `_`, `-`, start with a letter, no spaces. It must be unique across the whole file and must not equal the project name (reserved).
- Naming convention: verb-first PascalCase that says what the box does: `GetWorkers`, `BuildRequest`, `PostToTarget`, `ReportSummary`, `HandleError_Init`. Prefix `Call_` on local-outs that call subroutines (`Call_CreateLogEntry_Start`).
- Step `id`: free text (spaces allowed), unique within its mediation. Always set one: it shows in logs and in the debugger.
- `sub-route/@name`: unique within its route/splitter. `choose-route/@route` must match one.

## Attribute value kinds (from the XSD)

| Kind | Attributes (common) | How to write |
|---|---|---|
| IDREF | `routes-to`, `routes-response-to` | exact id of a top-level component |
| MVEL expression | `execute-when`, `execute-steps-when`, `collate-when`, `force-batch-when`, `split-until`, `cc:set/@value`, `cc:expression` text, `choose-route/@expression` (in `mvel-strategy`), `loop-strategy/@condition/@init/@increment`, `parameter/@default/@validation` | code. String literals need quotes: `value="'INFO'"` |
| MVEL template | `endpoint`, `extra-path`, `application`, `version` (workday-out-soap), `url`, `title`, `summary`, `failure-message`, `message`/`message-details` (cloud-log), `expiresIn`, `entryID`, `username`, `password`, `host`, `subject`, `output-file-pattern`, `cc:text` content | literal text; dynamic parts as `@{expr}`: `endpoint="@{props['p.url']}/items"` |
| MVEL boolean/integer template | `error-as-response`, `streaming`, `retries`, `retryDelay`, `connect-timeout`, `response-timeout`, `timeout`, `port` | literal (`true`, `3`) or `@{...}` |
| Plain | `id`, `output-variable`, `input-variable`, `output-mimetype`, `ref`, `icon`, booleans like `handle-downstream-errors`, `clone-request`, `unset-properties` | literal |

Attribute names follow the XSD, not the help text: `retryDelay`, `logRetries` (camelCase) on http-out, `createDocumentReference`, `expiresIn` on store.

## Input/output convention of steps

Most steps take `input="message|variable|attachment|rootpart|soapbody"` (+ `input-variable`) and `output="message|variable|…"` (+ `output-variable`, `output-mimetype`). The default is the message. Use variables to keep intermediate documents without destroying the current message.

## Spring beans (custom Java)

- `custom-mediation ref="BeanId"`, `<cc:custom ref="BeanId"/>` (step), `custom-splitter ref`, `custom-out ref` point to `<bean id="BeanId" class="..."/>` placed after `</cc:assembly>`.
- The Java class lives in the project's `src/` and must be on the build path (Studio's Bean Classpath Validator checks this).
- Use `scope="prototype"` when the bean keeps state.
