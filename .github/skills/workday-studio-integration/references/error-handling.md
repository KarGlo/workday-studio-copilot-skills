# Error handling

## How errors travel (Workday runtime semantics)

- On an error, normal processing stops and the runtime unwinds back through the components already processed, looking for a handler.
  - **Local handlers** are children of a mediation. They catch errors of that mediation only, unless the mediation has `handle-downstream-errors="true"`; then they also catch everything downstream of it.
  - **Global handlers** are direct children of `cc:assembly`. They are the fail-safe.
- A handler that fires marks the error handled. With `rethrow-error="true"` it reports and passes the error on to the next handler up.
- After handling, processing resumes from the handler along the response path. `continue-after-error="recover"` on the mediation instead continues with the next step after the failing one. Use it only when the handler fixed the cause.
- If nothing handles the error, the event ends with errors.
- Error details inside the handler's target:
  - `context.errorMessage` / `context.getErrorMessage()`,
  - `context.errorComponentId`,
  - `context.errorCode`,
  - `context.exception`.
- Raise errors yourself with:
  - `validate-exp` / `validate-xpath`,
  - `context.setError(...)` in eval.

## Handlers

```xml
<cc:send-error id="OnError" routes-to="ReportFailure"/>
<cc:send-error id="OnHttpError" routes-to="ReportHttpFailure" rethrow-error="true">
    <cc:condition-expression>context.errorMessage contains 'HTTP'</cc:condition-expression>
</cc:send-error>
<cc:log-error id="LogIt" level="error"/>
<cc:custom-error-handler id="JavaHandler" ref="MyErrorBean"/>
```
- `send-error` routes to a normal component (usually a PutIntegrationMessage local-out or SSK `HandleError`).
- `condition-expression`s (MVEL) must all be true for the handler to fire.
- A mediation holds one handler, placed after `steps`.

## Rule: one local Try/Catch per flow

- Every flow (the main flow and every `local-in` subflow) that contains a mediation guards itself. Its first mediation gets `handle-downstream-errors="true"` + a `send-error` to a handler that lives with it in a VERTICAL Try/Catch lane (see diagram.md).
- The main flow's handler reports and aborts the run. A per-item subflow's handler reports and lets the loop continue with the next item.
- Errors a subflow doesn't handle travel up into the caller's Try/Catch. That is also the right choice when a failure should stop everything, e.g. the source report cannot be fetched.

## Pattern 1: whole-run guard (plain mode, default)

The first mediation after `workday-in` catches everything downstream:
```xml
<cc:async-mediation id="Initialize" routes-to="GetSourceData" handle-downstream-errors="true">
    <cc:steps><!-- read configuration, validate --></cc:steps>
    <cc:send-error id="OnError" routes-to="ReportFailure"/>
</cc:async-mediation>
<cc:local-out id="ReportFailure" store-message="none" endpoint="vm://wcc/PutIntegrationMessage">
    <cc:set name="is.message.severity" value="'CRITICAL'"/>
    <cc:set name="is.message.summary" value="'Failed in ' + context.errorComponentId + ': ' + context.errorMessage"/>
</cc:local-out>
```

## Pattern 2: per-record resilience (continue with the next record)

- Inside a splitter sub-flow, give the per-record mediation its own handler that records the failure and lets the split continue.
  ```xml
  <cc:async-mediation id="PrepareRecord" routes-to="PostRecord" handle-downstream-errors="true">
      <cc:steps><!-- build the record request --></cc:steps>
      <cc:send-error id="OnRecordError" routes-to="RecordFailed"/>
  </cc:async-mediation>
  ```
- `RecordFailed` is a mediation that writes a failure result (e.g. a `<Result>` with `Status` = `ERROR`). The subflow then returns that message to its caller, whose aggregator counts it (see `recipes/outbound-rest.md`).
- For HTTP targets, prefer `error-as-response="true"` on `http-out` and branch on `context.getProperty('http.response.status')`. HTTP 4xx/5xx then don't raise at all.

## Pattern 3: StarterKit

Use `local-out` to `vm://<Project>/HandleError` from `send-error` (see ssk.md). It logs to the SSK cloud log, sets severity and optionally aborts.

| Where | `inIsAbortOnError` | `inIsResetError` | `inLogLevel` |
|---|---|---|---|
| main flow (whole run) | `true` | `false` | `'ERROR'` |
| per-item subflow (log and continue with the next record) | `false` | `true` | `'error'` |

- `inIsAbortOnError="true"` sets the abort flag. Any later call into the same SSK component then fails with "Loop-detected in local-in", so never use it inside a loop.
- `inIsResetError="true"` clears the residual error so the next item starts clean. This is the SSK's own pattern in `Call_HandleError_CatchReadErrors_128`.

## Severity → event status

- Messages with severity `CRITICAL` or `ERROR` put the integration event into an error state; `WARNING` does not fail the run.
- Report one summary message at the end of a successful run, and one CRITICAL message from the global/whole-run handler on failure.
