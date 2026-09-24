# Self-check before handing over

**First run `java .github/skills/workday-studio-integration/tools/WdCheck.java <ProjectFolder>` and fix until `RESULT: OK`.** It automates the Structure (XSD), MVEL, reference, diagram and swimlane checks below, and part of the logic checks (`vm://` targets, required parameters, param/attribute/alias names, XSLT files). This list documents what it checks and adds what it cannot see. Go through § Logic yourself.

Studio runs three validators on assembly.xml:
- **Syntax:** XSD `assembly-core.xsd` + `spring-beans-2.5.xsd`.
- **Semantic:** Workday rules.
- **Bean classpath:** custom Java classes exist.

Check the items below for every element you created or changed. The quoted texts are the messages Studio would show in the Problems view.

## Structure (XSD)

- [ ] Well-formed XML. Every `<` in MVEL/text is `&lt;`, every `&` is `&amp;`, and attribute quotes are balanced.
- [ ] Only cloud-allowed elements (`assembly-xml.md` § Allowed elements).
- [ ] Every top-level component has `id`. Ids are unique, NCName (no spaces, no leading digit) and not equal to the project name.
- [ ] Every `routes-to` / `routes-response-to` value is an existing top-level `id` (XSD IDREF, "There is no ID/IDREF binding for IDREF …"). Watch for typos and renamed ids.
- [ ] Required attributes present:
  - `workday-out-rest/@extra-path`,
  - `workday-out-soap/@application` + `@version`,
  - `sftp-out/@username`,
  - `custom-mediation/@ref`,
  - `send-error/@routes-to`,
  - `xslt-plus/@url`,
  - `cloud-log/@message`,
  - `retrieve/@entry`,
  - `text-excel/@style`,
  - `loop-strategy/@condition`,
  - `xml-stream-splitter/@xpath`,
  - `local-in/cc:parameter/@name`,
  - `cc:set/@name`,
  - `report-alias/@name`,
  - `attribute/@name`,
  - `param/@name`.
- [ ] Child order:
  - `splitter`: sub-route → strategy,
  - `route`: strategy → sub-routes,
  - `aggregator`: batch strategy → collater,
  - mediation: steps → handler,
  - `sync-mediation`: request-steps → response-steps → handler,
  - `integration-system`: param → attribute-map-service → … → report-service,
  - `param`: type → default → launch-option,
  - `attribute`: type → value → display-option,
  - `set-headers`: remove-headers → add-headers,
  - `xml-message-content-collater`: header-text → footer-text.
- [ ] `choose-route/@route` values match `sub-route/@name` values of the same route.
- [ ] Spring `<bean>`s are outside `<cc:assembly>` and every `ref` has a bean with that id.

## Semantic rules (Studio messages)

- [ ] "Workday-In transport '…' must route to a component": `workday-in` has `routes-to`.
- [ ] "The transport/mediation '…' routes-to an undefined component".
- [ ] "Attribute '…' should not have preceding or trailing spaces": no leading/trailing spaces in `id`, `routes-to`, names, endpoints.
- [ ] "String containing MVEL expression '…' is invalid. Closing and opening tags '{...}' do not match": every `@{` has its `}`, and braces inside are balanced.
- [ ] "MVEL expression '…' is invalid. The '…' property is not an MVEL template capable property": no `@{}` in expression-only attributes (`execute-when`, `cc:set/@value`, `cc:expression`).
- [ ] "The file '…' referred to in the XSLT step cannot be found": `url="xslt/X.xsl"` exists under `ws/WSAR-INF/`.
- [ ] "'xslt-plus' step is not available for assembly version below 2017.5": the assembly `version` is recent enough.
- [ ] "Header name '…' contains one or more whitespace(s)": no spaces in `add-header/@name`.
- [ ] "Duplicate key" / "Allowed characters for key: -, _, A-Z, a-z, 0-9": `cloud-log/log-column/@key` values are unique and match `[A-Za-z0-9_-]+`; they are not `Timestamp`, `Log-Level`, `Message`, `Details` or `Reference ID` (reserved).
- [ ] "The variable name '…' cannot have leading or trailing whitespace": `input-variable`/`output-variable` are trimmed.
- [ ] "The cron expression … is not valid" (only if you add schedules).
- [ ] "The route '…' is not a named sub-route of the router component '…'": see choose-route check above.
- [ ] "Listener service(s) and launch parameter(s) are mutually exclusive": don't combine a `listener-service` with `param`s.
- [ ] "Missing integration system parameter type for '…'": every `param`/`attribute` has a `type`.
- [ ] To use PATCH on http-out, set `props['wd.http.client'] = 'apache'` first.

## Logic (not validated by Studio, but required)

- [ ] Every flow path ends in something meaningful (a PutIntegrationMessage/SSK log, or a return to a caller). No dead ends that silently drop data.
- [ ] Every flow (main + each subflow) that contains a mediation has its own Try/Catch:
  - its first mediation has `handle-downstream-errors="true"` + `send-error`,
  - main flow handler: CRITICAL report or SSK HandleError with abort,
  - per-record handler: report and continue (SSK: `inIsAbortOnError=false`, `inIsResetError=true`),
  - diagram: mediation + handler in a nested VERTICAL lane.
- [ ] `local-out` endpoints use the real project name (`vm://<ProjectName>/<local-in id>`) and the target local-in exists. Every `required="true"` parameter of that local-in is set with `cc:set`.
- [ ] Every `lp.getSimpleData('X')` / `intsys.getAttribute('X')` name matches a declared `param`/`attribute` exactly (case and spaces).
- [ ] Every `intsys.reportService.getExtrapath('A')` alias exists in a `report-service`.
- [ ] RaaS/XML XPaths match report namespaces with `local-name()`. In `.xsl` files never use `*:Name`: Studio's XSL validator flags it as "Xpath is invalid".
- [ ] Numbers read via XPath or params are converted before arithmetic and comparisons.
- [ ] No secrets or tenant-specific URLs hard-coded.
- [ ] One lane = one flow: every non-main lane starts with a `local-in`; lanes are connected only by `local-out` → `vm://` calls; no `routes-to`/`routes-response-to`/`sub-route`/`send-error` points into another lane.
- [ ] Diagram: `diagram.md` § Check.

## Commands

- Full check: `java .github/skills/workday-studio-integration/tools/WdCheck.java <ProjectFolder>` (add `--verbose` for every item).
- Only when Java is unavailable, check well-formedness at least:
  - macOS/Linux: `xmllint --noout ws/WSAR-INF/assembly.xml ws/WSAR-INF/assembly-diagram.xml`
  - Windows: `[xml](Get-Content ws\WSAR-INF\assembly.xml) | Out-Null`
