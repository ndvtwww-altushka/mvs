# Android bridge — what is actually applied

Status: the core carries one real request of the client. Branch `ink-studio`.

## What is applied

1. Build wiring: `settings.gradle` includes `:mvs-core`, and `TMessagesProj/build.gradle`
   depends on `project(':mvs-core')`.
2. The bridge lives in the app source set: `org.telegram.tgnet.MtprotoAdapter` and
   `org.telegram.messenger.MvsCoreHost`.
3. `ConnectionsManager` owns the update observer registry (`mvsSetUpdatesObserver`), called
   from all three `Updates` paths.
4. **Real traffic.** `MessagesController.appConfigFetcher.getRemote` sends
   `help.getAppConfig` through `MvsCoreHost.sendWithFailover(...)`. That request runs at
   startup and again every four minutes; when it fails the client falls back to its local
   SQLite copy, so a core problem degrades app config instead of breaking the client.
5. A native fallback stays: if the core refuses before any attempt (shut down, kill-switch,
   invalid arguments), the same method calls `ConnectionsManager.sendRequest` directly.

## Why retries are off inside the core

A reply `TLObject` is freed when the native callback returns, and the same request object
must not be submitted twice. So:

- `MvsCoreHost` builds the core with `maxRetries = 0`;
- retrying is done by `MvsNetCore.sendWithFailover`, which asks a factory for a brand new
  request object on every attempt;
- `MtprotoAdapter(account, true)` serializes each reply into a `byte[]` inside the callback,
  and `MvsCoreHost` rebuilds it with `SerializedData` + `deserializeResponse`. Nothing keeps
  a reference to a freed TLObject.

## DC failover — what it does, and what it does not

`MvsNetCore.sendWithFailover(method, factory, options)`:

- builds an ordered plan, home data centre first (`failoverPlan(preferred)` is public so the
  decision can be inspected instead of guessed);
- tries at most `Config.maxFailoverAttempts` centres (default 3), one attempt each;
- moves on only for transport-shaped failures: timeout, `IOException`, negative or zero RPC
  codes, 5xx;
- keeps 4xx, flood waits and `303 MIGRATE_*` final, because another data centre cannot fix a
  rejected request;
- records per-DC health in `DcSupervisor`;
- passes a concrete DC to `ConnectionsManager.sendRequest(... datacenterId ...)`, so the
  request really is sent to that centre.

Boundaries, stated plainly:

- the physical socket, the auth key and `MIGRATE_*` handling stay in the native engine: the
  core chooses the centre, not the transport;
- `Config.nativeDcRouting` is still `false`, and `send()` still preserves the client's default
  DC. Only `sendWithFailover` picks centres, and only the app-config request uses it today;
- failover happens only after the home centre fails, so the normal path adds no new DC
  traffic and no new auth keys.

## Update observer — the cost nobody sees

`MvsCoreHost.get()` registers the observer, so every `Updates` is serialized to bytes on the
network thread even though nothing consumes them yet. The host attaches a no-op listener so
the queue stays drained. That is the price of this integration; drop it once a real consumer
exists, or the serialization is pure overhead.

## What the build proves

- the whole client compiles with the core linked;
- `assembleStaging` unpacks `classes*.dex` and fails when `app/mvs/core/net/MvsNetCore`,
  `org/telegram/tgnet/MtprotoAdapter` or `org/telegram/messenger/MvsCoreHost` is missing;
- the `verify-apk` workflow repeats that check on an existing artifact and reports every
  class through annotations.

## What it does not prove

- no device smoke test runs in CI;
- one call site: every other request still bypasses the core;
- R8 trims unreachable core code (`FrameCodec`, `MessageStore`, `AesGcmCipher` are absent from
  the APK) and merges `CircuitBreaker` into `DcSupervisor`. The dex gate therefore requires
  entry points, not class names.

## `adapter-stubs/`

Compile-only signature stubs for `org.telegram.tgnet` classes. They let the core gate compile
the bridge on a plain JVM without the client, and they never reach the APK. They can drift
from the real classes, so the APK build stays the second gate: a wrong signature fails there.

## App name in login notifications ("NekoX" instead of "MVS")

The line Telegram sends — `Device: NekoX, 12.10.6, Samsung ...` — is composed on the server
from the `api_id` used to sign in. It is not a string in this repository, so nothing here can
rename it.

`api_id` comes from `BuildConfig.APP_ID` (see `NekoXConfig.currentAppId()`), which
`build.gradle` fills from `TELEGRAM_APP_ID` (local.properties or env) and otherwise from a
default that belongs to the upstream project. To make the notification say `MVS`:

1. register an application at my.telegram.org with the name `MVS` — this needs your own phone
   number, nobody else can do it for you;
2. put the pair into `local.properties` (never committed):
   `TELEGRAM_APP_ID=<id>` and `TELEGRAM_APP_HASH=<hash>`;
3. rebuild. `BuildVars.APP_ID` / `APP_HASH` then point at your id.

The client also has an in-app custom api id/hash override (`NekoXConfig`, stored in
`nekox_config`). It swaps the credentials, but the displayed name still belongs to whichever
id was registered on the server.
