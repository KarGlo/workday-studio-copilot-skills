# Integration system (`cc:workday-in/cc:integration-system`)

Defines what the Workday tenant sees: the Integration System name, launch parameters, integration attributes and maps, and services (report, delivery, retrieval, …). All elements are in `xmlns:cloud="urn:com.workday/esb/cloud/10.0"`.

## Child order (XSD sequence; wrong order fails validation)

1. `cloud:param`*: launch parameters
2. `cloud:attribute-map-service`*: attributes and integration maps
3. `cloud:sequence-generator-service`*
4. `cloud:transaction-log-service`*
5. `cloud:delivery-service`*
6. `cloud:retrieval-service`*
7. `cloud:report-service`*
8. `cloud:listener-service`*
9. `cloud:custom-object-service`*
10. `cloud:service-reference`*
11. `cloud:data-initialization-service`*

## Launch parameters (`cloud:param`)

```xml
<cloud:param name="Effective Date">
    <cloud:type>
        <cloud:simple-type>date</cloud:simple-type>
    </cloud:type>
    <cloud:launch-option>required</cloud:launch-option>
</cloud:param>
<cloud:param name="Test Mode">
    <cloud:type>
        <cloud:simple-type>boolean</cloud:simple-type>
    </cloud:type>
    <cloud:default>
        <cloud:boolean>false</cloud:boolean>
    </cloud:default>
</cloud:param>
<cloud:param name="Output Format">
    <cloud:type>
        <cloud:enumeration-type name="OutputFormat">
            <cloud:enumeration>CSV</cloud:enumeration>
            <cloud:enumeration>XML</cloud:enumeration>
        </cloud:enumeration-type>
    </cloud:type>
</cloud:param>
<cloud:param name="Organizations">
    <cloud:type>
        <cloud:class-report-field description="Organization" type="WID" singular="false">WID-OF-THE-CLASS-REPORT-FIELD</cloud:class-report-field>
    </cloud:type>
</cloud:param>
```
- Children: `type` (required), then optional `default`, then `launch-option`*.
- `simple-type`: `boolean|date|datetime|number|text`.
- `launch-option`: `required`, `display-only`, `do-not-show`, `display-as-password`, `as-of-effective-date`, `as-of-entry-datetime`, `begin-effective-date`, `begin-entry-datetime`.
- `default` holds one of `cloud:boolean`, `cloud:text`, `cloud:numeric`, `cloud:date`, `cloud:dateTime`, `cloud:enumeration`, `cloud:reference`.
- **Instance prompts** (`class-report-field`) need the WID of a Workday class report field. You cannot invent it. Ask the user, copy it from an existing integration, or leave a clearly marked TODO and tell the user to pick the field in Studio's Integration System editor.

Read in MVEL:
- `lp.getSimpleData('Effective Date')`: a string. Booleans come back as `'true'`/`'1'`; test with `['1','true'].contains(lp.getSimpleData('Test Mode'))`.
- `lp.getDate('Name')`.
- `lp.exists('Name')`.
- `lp.getReferenceData('Organizations', 'WID')`: first value. `lp.getReferenceDataList('Organizations', 'WID')` returns a list. The second argument is the ID type (`'WID'`, `'Employee_ID'`, `'Organization_Reference_ID'`, …).
- `lp.integrationEventWID`, `lp.integrationSystemRefWID`, `lp.sentOn`.

## Integration attributes and maps (`cloud:attribute-map-service`)

```xml
<cloud:attribute-map-service name="INT Example - Connection">
    <cloud:attribute name="Endpoint URL">
        <cloud:type>
            <cloud:simple-type>text</cloud:simple-type>
        </cloud:type>
        <cloud:display-option>required-for-launch</cloud:display-option>
    </cloud:attribute>
    <cloud:attribute name="Client Secret">
        <cloud:type>
            <cloud:simple-type>text</cloud:simple-type>
        </cloud:type>
        <cloud:display-option>display-as-password</cloud:display-option>
    </cloud:attribute>
    <cloud:map name="Country Code Map">
        <cloud:internal-type>
            <cloud:simple-type>text</cloud:simple-type>
        </cloud:internal-type>
        <cloud:external-type>
            <cloud:simple-type>text</cloud:simple-type>
        </cloud:external-type>
    </cloud:map>
</cloud:attribute-map-service>
```
- Attribute children: `type`, optional `value` (a default), `display-option`* (`required-for-launch`, `display-as-password`).
- Put all `attribute`s before all `map`s.
- Service names must be unique in the tenant. Prefix them with the integration name.

Read in MVEL:
- `intsys.getAttribute('Endpoint URL')` (null when not configured),
- `intsys.getAttributeAsBoolean('Flag')`,
- `intsys.getAttributeReferenceData('Name', 'WID')`,
- maps: `intsys.integrationMapLookup('Country Code Map', internalValue)` and `intsys.integrationMapReverseLookup('Country Code Map', externalValue)`.

Use attributes for environment configuration (URLs, credentials, folder names). Use launch parameters for per-run choices.

## Report service (RaaS)

```xml
<cloud:report-service name="INT Example - Reports">
    <cloud:report-alias name="Source Data" description="Workers to send"/>
</cloud:report-service>
```
- `report-alias` may contain `<cloud:report-reference type="WID" description="...">WID</cloud:report-reference>` to pin the custom report.
- Without it, the user links the alias to the report in the tenant (Configure Integration Services).
- Call it with `workday-out-rest extra-path="@{intsys.reportService.getExtrapath('Source Data')}"`.
- The report must be web-service enabled and shared with the integration system user.

## Other services (shown in required order)

```xml
<cloud:sequence-generator-service name="INT Example - File Sequence">
    <cloud:sequencer>fileSequence</cloud:sequencer>              <!-- read: lp.getSequencedValue(...) -->
</cloud:sequence-generator-service>
<cloud:delivery-service name="INT Example - Delivery"/>       <!-- deliver event documents (SFTP/email configured in tenant) -->
<cloud:retrieval-service name="INT Example - Retrieval"/>     <!-- pick up inbound files into the event -->
<cloud:custom-object-service name="INT Example - Custom Objects">
    <cloud:custom-object-alias name="MyObject"/>
</cloud:custom-object-service>
```
- Inbound files fetched by a retrieval service are attached to the event. Read them with `vm://wcc/GetEventDocuments` + a `retrieve` step, or `route` + `doc-iterator`.
- For a delivery service, mark stored documents deliverable: `is.document.deliverable` = `'true'` on PutIntegrationMessage.
