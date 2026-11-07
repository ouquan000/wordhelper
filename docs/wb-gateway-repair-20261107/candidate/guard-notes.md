# WB startup / guard candidate notes — 2026-11-07

These are review candidates only. This worker did not deploy them, execute a real service starter/guard/sanitizer, stop any existing process, change real configuration/accounts/authentication, touch Git, or call a real model. The router implementation belongs to the other worker and was not edited here.

## Deliverables and installation contract

| Candidate | Formal installation destination, for the parent to deploy |
| --- | --- |
| `start-silent.cmd` | `D:\deepseek_v4.1flash1017\workbuddy2api-panel\run\start-silent.cmd` |
| `wb-service-check.ps1` | `D:\deepseek_v4.1flash1017\workbuddy2api-panel\run\wb-service-check.ps1` |
| `wb-gateway-guard.ps1` | `D:\deepseek_v4.1flash1017\thebigfish\tools\wb-gateway-guard.ps1` |

Install the shared helper before the startup/guard candidates. The startup script uses its own formal run directory; the global guard defaults to the formal helper path above. No candidate-directory path is embedded in production code. The existing VBS starters and router starter remain dependencies. Neither scheduled tasks nor the original BOM watcher script is changed.

The PowerShell files contain ASCII source and run under Windows PowerShell 5.1. A `-Library` switch and a dot-source invocation both load definitions only: no automatic repair, logging, HTTP call, service start, or watcher start occurs at library load.

## State and exit contract

| Code | State | Meaning and action |
| --- | --- | --- |
| 0 | healthy | All relevant owner and HTTP checks passed; existing listener is left running. |
| 10 | missing | No listener was found. NoRestart/hold leaves it missing and returns nonzero. |
| 20 | foreign | Explicit CONFLICT; executable or router script identity is wrong/unreadable. No stop, kill, or duplicate launch. |
| 30 | unhealthy | Correct process identity, but HTTP/status/JSON/service/count check failed. WARN, nonzero; no restart or duplicate. |
| 40 | error | Inspection failed, owner changed during HTTP, dependency/config repair failed, or starter failed. Fail closed. |

The global guard combines nonzero conditions using the largest numeric code; each individual conflict/unhealthy condition is still logged explicitly. A watcher inspection failure also returns nonzero. Logs contain fixed reason strings and PIDs, never HTTP bodies or configuration/account content.

Main port 8790 must belong to the exact canonical full path `run\wb2api.exe`; aux port 8789 must belong to `run-bot\wb2api.exe`. Same basename in a different directory is rejected. Every owner of the numeric listening port must match. HTTP succeeds only for status 200, JSON media type, object JSON, exact case-sensitive `service=workbuddy2api`, and a finite positive integer `healthy`; strings, booleans, zero, fractions, wrong JSON and 404 are rejected.

Router port 8788 requires an executable named `node.exe`, its command line beginning with that executable and the exact formal `run\wb-router.js` entry script, plus a structural `/_router/status` identity check. Paths merely mentioned in `-e` or following a different script fail. The parser was cross-checked against Windows `CommandLineToArgvW` for quoted paths with spaces, trailing backslashes, escaped quotes and empty arguments.

Router identity uses the established `state`, `qq`, `aux.target/healthy/paused`, `hold`, `weight`, and `counters` structure. Main/aux target values must match the formal ports. Missing legacy main-health fields, and even an absent old `main` object, are accepted when the remaining strong schema and process identity pass. Optional new `mainOk/auxOk`, reason fields and nested `main.healthy/reason` are type-checked when supplied. An unhealthy upstream does not make the router process identity unhealthy: the guard separately probes each pool, so the overall report is still nonzero for that pool. Legacy acceptance never substitutes an arbitrary 200 body for a valid status document.

## Startup and recovery behavior

The main CMD retains the existing sanitizer invocation, accepts its documented 0 (already clean) and 1 (repaired) codes, rejects other sanitizer errors, keeps `wb2api.exe -config config.json`, and keeps append redirection to `panel.log`. It uses exact captured errorlevel equality tests, not descending `if errorlevel N` thresholds or PowerShell string-evaluation assembly.

Main identity is checked before router work. Correct healthy main is skipped; foreign/unhealthy main is reported nonzero without launching a duplicate. Router identity is checked before invoking the original router VBS. A missing router is started using that VBS and then checked again, with up to 20 seconds for identity and HTTP readiness. A foreign/unhealthy router is reported nonzero instead of being swallowed by the old router CMD's netstat shortcut. A missing main is checked again immediately before running the existing binary.

The global guard covers main 8790, aux 8789 and router 8788. It repairs the two pool configuration files using the preserved BOM/backup strategy; clean bytes are validated before writes, and invalid JSON leaves both its input bytes and the last valid backup unchanged. It calls each original VBS starter only after two missing-listener observations and hold checks, then re-verifies ownership and HTTP readiness. It never kills a process. Startup and guard do not retry recovery over a listener that is present but unhealthy.

`run\.hold-restart` protects both main and router; `run-bot\.hold-restart` protects aux. Healthy listeners remain valid under hold. `-NoRestart` prevents all service launches and also prevents the BOM watcher fallback launch. As in the old guard, NoRestart still performs configuration sanitation and writes health logs, so it is not a strictly read-only mode. Tests do not run NoRestart against real directories.

The BOM watcher fallback remains. It checks the actual PowerShell executable and exact `-File` script identity instead of trusting an arbitrary reused PID. It also finds an already running exact watcher when its pid file is missing, to avoid unnecessary duplication. An inspection error never authorizes a watcher start. A permitted missing-watcher launch reports that the launcher was started; long-term watcher survival is checked on the next guard invocation.

The configured recovery wait is held in the uniquely named `RecoveryWaitSeconds`. A regression test imports a helper with its own `WaitSeconds=0` parameter and verifies that an explicit recovery wait of 17 reaches the post-start checker unchanged.

HTTP defaults are a 2,500 ms total request/read deadline, 32,768 body bytes, 16 KiB response headers, no redirects, no proxy, and no keepalive reuse. Unknown-length/chunked responses are capped while reading; a slow drip cannot reset the total deadline. Listener identity is sampled before and after HTTP; any PID/path/command-line/creation-time change fails closed. Process inventory itself uses Windows CIM/TCP APIs and is not claimed to have an HTTP-style deadline. The missing/start observations are not an OS-atomic port reservation: an external owner can still appear in the small gap after the last check. The guard checks again after recovery and no external owner is ever stopped.

## Executed verification

From `D:\deepseek harness\work_anki_enligish`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "D:\deepseek harness\work_anki_enligish\docs\wb-gateway-repair-20261107\tests\guard-tests.ps1"
```

Final result, Windows PowerShell **5.1.26100.9711**, exit **0**:

```text
GUARD_TESTS passed=True assertions=201 cmdCases=12 httpCases=13 cleanup=True frozenUnchanged=True
```

Evidence: `..\tests\guard-test-results.json`; executable tests: `..\tests\guard-tests.ps1`; localhost fixture: `..\tests\guard-http-fixture.js`.

Coverage includes exact main/aux paths, exact Node script command line, mixed/foreign/unreadable owners, health count/type/identity errors, legacy and new router status, all service decision states, NoRestart/hold, failures before and after recovery, configuration BOM repair and good-backup preservation, watcher identity/NoRestart, helper-import wait clobber regression, and 12 native CMD scenarios using compiled LOCAL executable shims in a temporary directory with spaces. CMD tests never execute the real sanitizer, real helper CLI, real wb2api binary or real VBS.

The 13 real HTTP fixture cases cover correct JSON, wrong service, zero healthy, malformed JSON, JSON array, HTML arbitrary-200, redirect, 404, known-length oversize, chunked oversize, slow headers, slow drip and abrupt truncation. The fixture binds an OS-assigned loopback port and explicitly rejects 8788/8789/8790. Only the owned localhost fixture and bounded native CMD/shim test children were started. The fixture was stopped and waited for in `finally`; the CMD/shim children exited within their deadline, and all per-run temporary directories/compiled shims/config fixtures were removed. The final evidence records `CleanupComplete=true` and `FixtureProcessExited=true`.

## Frozen acceptance and limits

Both start and final verification retained these SHA-256 values:

- `acceptance-spec.md`: `c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91`
- `acceptance-spec.lock`: `50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b`

This is implementation verification, not either of the parent's two independent acceptance rounds and not a score. No live main/aux restart was tested, no real starter/guard was executed, and no live models/authenticated catalogue/UI verification was performed by this worker. The native missing-router VBS launch branch is exercised through the guard's isolated callback tests and static CMD contract; no real WScript/router process was started for that branch. Deployment, controlled router replacement, live GETs and independent acceptance remain with the parent.
