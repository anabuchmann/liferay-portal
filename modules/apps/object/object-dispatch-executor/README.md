# Object Dispatch POC — Setup Guide

## Context

POC for a Liferay-owned scheduled job that creates Object entries via the Dispatch framework. Adds a new `object-entry-trigger` Dispatch executor; a paired Clarity workspace demonstrates the end-to-end flow (Dispatch trigger creates a `ClaritySyncRequest` entry, the `onAfterAdd` Object Action calls a Spring Boot worker, the worker imports Commerce products via Headless Batch APIs and writes status back to the entry).

Last verified end-to-end 2026-05-26.

## Components

| Piece | Location | Role |
| --- | --- | --- |
| `object-dispatch-executor` OSGi module | `modules/apps/object/object-dispatch-executor/` | Registers the `object-entry-trigger` `DispatchTaskExecutor` |
| Clarity batch CX | `workspaces/clarity-solution-workspace/client-extensions/clarity-solution-batch` | Provisions `ClaritySyncRequest` object definition + `onAfterAdd` Object Action via batch 05 |
| Clarity Spring Boot CX | `workspaces/clarity-solution-workspace/client-extensions/clarity-solution-etc-spring-boot` | Registers OAuth client + Object Action endpoints in portal; runs the product-sync worker on `:58081`. Worker writes its full request payload and result to `/tmp/clarity-sync-demo.log` for demo visibility |

## Setup

The standard portal core rebuild is assumed. The portal core build does not build the new module under `modules/apps/object/` or the Clarity client extensions — those must be deployed separately.

### 1. Build and deploy the dispatch executor module

```bash
cd modules/apps/object/object-dispatch-executor
../../../../gradlew deploy
```

Result: `bundles/osgi/portal/com.liferay.object.dispatch.executor.jar`

### 2. Deploy the Clarity Client Extensions

The workspace gradle plugin places artifacts in the correct bundle directory when given the bundle home. Run both deploys with `-Pliferay.workspace.home.dir`:

```bash
cd workspaces/clarity-solution-workspace
./gradlew :client-extensions:clarity-solution-batch:deploy \
          :client-extensions:clarity-solution-etc-spring-boot:deploy \
          -Pliferay.workspace.home.dir=<path-to-your-bundles>
```

Results in `<bundles>/osgi/client-extensions/`:

- `clarity-solution-batch.lxc.zip` (~10 KB) — provisions `ClaritySyncRequest` object definition + `onAfterAdd` Object Action
- `clarity-solution-etc-spring-boot.zip` (~32 MB) — registers OAuth UA `clarity-solution-etc-spring-boot-oaua` and the function executors `clarity-solution-product-sync-object-action`, `clarity-solution-etc-spring-boot-object-action`, `clarity-solution-etc-spring-boot-workflow-action`

The runnable Spring Boot fat jar is at `client-extensions/clarity-solution-etc-spring-boot/build/libs/clarity-solution-etc-spring-boot.jar` and is started separately (Section 4).

### 3. Start Tomcat

Start the portal Tomcat and wait for `Server startup in [N] milliseconds`.

### 4. Start the Spring Boot worker

```bash
java -jar workspaces/clarity-solution-workspace/client-extensions/clarity-solution-etc-spring-boot/build/libs/clarity-solution-etc-spring-boot.jar
```

Expect `Started ClaritySpringBootApplication in <N> seconds` and `Tomcat started on port 58081`. The startup log line `Client ID null` is normal — the worker resolves the OAuth client ID at request time by calling portal (`LiferayOAuth2TokenValidator`).

Optional override of the demo log location:

```bash
java -Dclarity.demo.log.path=/path/to/your.log -jar .../clarity-solution-etc-spring-boot.jar
```

### 5. Verify provisioning

```bash
curl -s -u test@liferay.com:test \
  "http://localhost:8080/o/object-admin/v1.0/object-definitions?pageSize=200" \
  | python3 -c "import json,sys; [print(o.get('externalReferenceCode')) for o in json.load(sys.stdin).get('items',[]) if 'CLARITY' in (o.get('externalReferenceCode') or '')]"
```

Expected output: `CLARITY_SYNC_REQUEST`.

Also confirm in `catalina.out`:

```
STARTED claritysolutionbatch
STARTED claritysolutionetcspringboot
```

## Full E2E UI Demo

The UI is labelled **Job Scheduler** (Control Panel — Job Scheduler), backed by the Dispatch framework.

### Step 1 — Stage a clean demo log

```bash
> /tmp/clarity-sync-demo.log
tail -f /tmp/clarity-sync-demo.log    # leave this open in a side terminal
```

### Step 2 — Login

`http://localhost:8080` as `test@liferay.com` / `test`.

### Step 3 — Create the Dispatch trigger

Control Panel — Job Scheduler — New Dispatch Trigger.

- Type: `object-entry-trigger`
- Name: `Nightly Product Sync`
- Cron Expression: `0 0 2 * * ?` (or any valid cron — manual run does not need a near-term schedule)
- Settings (paste into the large code-editor pane below the Name field, plain `UnicodeProperties` line format):

  ```
  objectDefinitionExternalReferenceCode=CLARITY_SYNC_REQUEST
  operation=add
  values={"eventType":"nightly-product-sync","syncStatus":"requested","source":"external-product-system"}
  ```

- Active: enabled
- Save

### Step 4 — Run the trigger

In the trigger row, click the actions menu — Run Now.

Equivalent REST call (replace `{triggerId}`):

```bash
curl -X POST -u test@liferay.com:test \
  "http://localhost:8080/o/dispatch-rest/v1.0/dispatch-triggers/{triggerId}/run"
```

Returns HTTP 204 on accept. Execution is asynchronous and takes a few seconds.

### Step 5 — Observe the chain

Within a few seconds of Run Now, the demo log fills with two stages per run:

```
<timestamp> | run=<uuid> | stage=request.payload
{full Object Action payload from portal, includes objectEntryDTOClaritySyncRequest}
----------------------------------------
<timestamp> | run=<uuid> | stage=result.values
{
  "processedItemsCount": 4,
  "failedItemsCount": 0,
  "errorMessage": "",
  "batchExternalReferenceCode": "CLARITY_PRODUCT_SYNC_<uuid>",
  "syncStatus": "COMPLETED"
}
----------------------------------------
```

The `request.payload` line proves portal POSTed to `/o/clarity-solution-etc-spring-boot/object/action/product-sync` with the new entry. The `result.values` line is the values the worker patched back onto the `ClaritySyncRequest` entry after running the Headless Batch import.

### Step 6 — Verify in the UI

1. Applications — Clarity Sync Request — Entries
   - New row with `syncStatus = COMPLETED`, `processedItemsCount = 4`, `batchExternalReferenceCode` matching the demo log
2. Control Panel — Job Scheduler — click the trigger — Dispatch Logs tab
   - Latest row: Status `Successful`, Output `Created object entry {id} for object definition ClaritySyncRequest`
3. Commerce — Catalog — Products
   - 4 new products imported by the worker via Headless Batch (names from `products.csv`)

### Step 7 — REST verification (optional)

```bash
curl -s -u test@liferay.com:test \
  "http://localhost:8080/o/c/claritysyncrequests?pageSize=5&sort=dateCreated:desc" \
  | python3 -m json.tool
```

The newest item should show `syncStatus=COMPLETED`, `processedItemsCount=4`, `batchExternalReferenceCode` populated.

## How the Worker Writes the Demo Log

`ProductSyncObjectActionRestController.post()` calls `_appendDemoLog(runId, stage, payload)` twice per request:

1. On entry, dumping the raw Object Action payload received from portal
2. After the Headless Batch import completes, dumping the final `values` patched back

The path is bound to `${clarity.demo.log.path:/tmp/clarity-sync-demo.log}`. Parent directories are created on first write; entries are appended.

## About the Settings Editor

The Job Scheduler form renders a generic AceEditor (XML syntax-highlighting mode, cosmetic only) for every executor type. There is no per-executor settings form for `object-entry-trigger`. The content is parsed as Liferay `UnicodeProperties` — simple line-based `key=value`, not XML.

This is a UX gap, not a bug. Adding a richer form requires extending `modules/apps/dispatch/dispatch-web/src/main/resources/META-INF/resources/trigger/details.jsp` or registering a custom JSP and `DispatchTriggerMetadataFactory` (see `dispatch-talend-web` for the pattern). Tracked as a follow-up for the POC if it graduates.

## REST Trigger Creation (Alternative to UI)

If the editor pane is finicky during the live demo, create the trigger entirely via REST:

```bash
curl -X POST -u test@liferay.com:test -H "Content-Type: application/json" \
  http://localhost:8080/o/dispatch-rest/v1.0/dispatch-triggers \
  -d '{
    "name": "Nightly Product Sync",
    "active": true,
    "cronExpression": "0 0 2 * * ?",
    "taskExecutorType": "object-entry-trigger",
    "dispatchTaskSettings": {
      "objectDefinitionExternalReferenceCode": "CLARITY_SYNC_REQUEST",
      "operation": "add",
      "values": "{\"eventType\":\"nightly-product-sync\",\"syncStatus\":\"requested\",\"source\":\"external-product-system\"}"
    }
  }'
```

The trigger appears in the Job Scheduler UI immediately and can be run from there or via the `POST /dispatch-triggers/{id}/run` endpoint above.

## Known Gaps

- The portal core build does not build new modules under `modules/apps/`; module-level `gradlew deploy` is required.
- The Dispatch REST API does not expose dispatch logs; that view is UI-only (Job Scheduler — Dispatch Logs tab).
- No executor-specific settings form for `object-entry-trigger` in the Job Scheduler UI; admins must enter raw `UnicodeProperties` lines in the generic AceEditor. Adding a proper JSP is a follow-up if the POC graduates.
- Demo file output (`/tmp/clarity-sync-demo.log`) is POC-only instrumentation in `ProductSyncObjectActionRestController`. Remove or gate before merging.
