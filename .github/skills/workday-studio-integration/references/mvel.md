# MVEL in Workday Studio (runtime: MVEL 1.3, Workday fork)

## Where MVEL appears

- **Expression:** the whole attribute or element is code:
  - `<cc:expression>`,
  - `execute-when`, `execute-steps-when`,
  - `cc:set/@value`,
  - `choose-route/@expression` (in `mvel-strategy`),
  - `parameter/@default` and `parameter/@validation`,
  - `collate-when`, `condition`, `cloud-log/@reference-id`.
  - String literals need quotes: `value="'CRITICAL'"`.
- **Template:** literal text with `@{expr}` islands:
  - `endpoint`, `extra-path`, `url`, `title`, `summary`, `failure-message`,
  - `cloud-log/@message`,
  - `<cc:text>` in `write`/`log`.
  - Template-only directives: `@if{cond}…@end{}`, `@foreach{list}…@{item}…@end{}`.
- Never wrap an expression attribute in `@{}`, and never leave a template's dynamic part outside `@{}`.

## Language essentials

- Java-like, dynamically typed. The last value is the result. Separate statements with `;` or newlines.
- `==` compares values (like `equals`).
- `empty` tests null or empty: `props['x'] != empty`.
- `contains`: `['a','b'] contains x` or `list.contains(x)`.
- Lists `['a','b']`, maps `['k':'v']`, arrays `{'a','b'}`.
- Property navigation: `context.exception.message`, and `props.myKey` equals `props['myKey']`. Use the bracket form when keys contain dots.
- Strings: `+` concatenates; `#` concatenates as strings (`'a' # 1`). Java String methods work (`trim()`, `toUpperCase()`, `substring()`, `replaceAll()`, `split()`).
- Java classes: fully qualified or `import x.y.Z;` at the start of the expression. Examples: `java.time.LocalDate.now().toString()`, `java.net.URLEncoder.encode(s, 'UTF-8')`, `Integer.parseInt(s)`, `org.apache.commons.lang.StringUtils.leftPad(s, 10, '0')`.
- **Gotcha:** a semicolon inside a string literal breaks parsing. Build it with `(char) 59`, e.g. `'a' # (char) 59 # 'b'`.
- **Gotcha:** numbers read from XPath/params are strings. Convert with `Integer.parseInt(...)` before arithmetic or `>` comparisons.
- XML escaping inside attributes/elements: `<` → `&lt;`, `&` → `&amp;` (so `&&` → `&amp;&amp;`), `"` inside double-quoted attributes → `&quot;` (or use single quotes).

## Objects available

| Object | Use | Examples |
|---|---|---|
| `props` | properties map (strings, numbers, lists, Java objects); survives across components and local-out calls | `props['p.count'] = 0`, `props['p.count'] + 1` |
| `vars` | named document variables (message-like) | `vars['p.doc'].xpath('/*/*:ID')`, `vars['p.doc'] = null` (free memory) |
| `parts` | parts of the current message; `parts[0]` = root part | `parts[0].xpath("/*/*[local-name()='ID']")`, `parts[0].xstream('/r/a')` (streaming, single pass, for large docs), `parts[0].isXml()`, `parts[0].mimeType` |
| `message` | the current message | `message.rootPartAsText`, `message.setRootPartAsText('...')`, `message.setHeader('K','V')`, `message.removeAllHeaders()`, `message.setMimeType('text/csv')` |
| `context` | MediationContext: errors, raw properties, abort | `context.errorMessage` / `context.getErrorMessage()`, `context.errorComponentId`, `context.errorCode`, `context.exception`, `context.getProperty('http.response.status')`, `context.containsProperty('x')`, `context.removeProperty('x')`, `context.setAbort(true)`, `context.baseURL` |
| `lp` | launch parameters of the current event | `lp.getSimpleData('Name')`, `lp.getDate('Name')`, `lp.getReferenceData('Name','WID')`, `lp.getReferenceDataList('Name','Employee_ID')`, `lp.exists('Name')`, `lp.integrationEventWID`, `lp.integrationSystemRefWID`, `lp.sentOn` |
| `intsys` | integration system config | `intsys.getAttribute('Name')`, `intsys.getAttributeAsBoolean('Flag')`, `intsys.getAttributeReferenceData('Name','WID')`, `intsys.integrationMapLookup('Map','in')`, `intsys.integrationMapReverseLookup('Map','out')`, `intsys.reportService.getExtrapath('Alias')`, `intsys.customObjectService.getExtrapath('Alias')` |
| `util` | helpers | `util.currentTime()`, `util.isLastMessageInBatch()`, `util.listToCommaDelimString(list)`, `util.cleanString(s)`, `util.currentFilename`, `util.readFileToVar('var','file.xml','text/xml')` (file in WSAR-INF) |

XPath in MVEL (`xpath`/`xstream`) is namespace-aware:
- use `*:Name` wildcards or `local-name()`,
- `wd:` is predeclared for `urn:com.workday/bsvc`,
- `env:` for the SOAP envelope.

## Common idioms

```text
props['p.test.mode'] = ['1','true'].contains(lp.getSimpleData('Test Mode'))
props['p.today'] = java.time.LocalDate.now().toString()
props['p.file.name'] = 'Extract_' + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern('yyyyMMdd_HHmmss')) + '.csv'
props['p.status'] = context.getProperty('http.response.status')
props['p.ok'] = props['p.status'] != empty && props['p.status'].toString().startsWith('2')
props['p.list'] = new java.util.ArrayList()
props['p.list'].add(parts[0].xpath('/*/*:ID'))
```
(In XML, write `&&` as `&amp;&amp;`.)

## Naming convention for props

- Prefix your own props: `p.` (plain) or `local`/`global` + name (SSK style).
- Never reuse Workday-reserved prefixes: `is.` (common components), `wd.`, `cc.`, `http.`, `ssk*`.
