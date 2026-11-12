# Android bridge — applied in this repository

Status: the bridge is wired into the app build (commit `21652f6`, branch `ink-studio`).
Nothing calls it yet, so no production traffic goes through the core.

## What is applied

1. `settings.gradle` includes `:mvs-core`.
2. `TMessagesProj/build.gradle` depends on `project(':mvs-core')`.
3. `ConnectionsManager` owns the observer registry:
   - `mvsSetUpdatesObserver(int account, Consumer<TLRPC.Updates> observer)`;
   - `mvsNotifyUpdatesObserver(...)`, called right before `processUpdates` in all
     three `Updates` paths (typed request, plugin replacement, native message).
   The observer runs synchronously while the TLObject is still alive; the adapter
   copies it to a byte array inside that callback.
4. `MtprotoAdapter` and `MvsCoreHost` live in the app source set
   (`TMessagesProj/src/main/java/org/telegram/tgnet/` and `.../messenger/`).
5. `adapter-stubs/` stays out of the APK.

## What the build proves

- `TMessagesProj:assembleStaging` compiles the core against the real
  `ConnectionsManager`: the 9-argument `sendRequest`, `cancelRequest(int, boolean)`,
  `NativeByteBuffer` and the request flags all match this revision.
- The staging job unpacks `classes*.dex` and fails the build when
  `app/mvs/core/net/MvsNetCore`, `org/telegram/tgnet/MtprotoAdapter` or
  `org/telegram/messenger/MvsCoreHost` is absent.
- The standalone `verify-apk` workflow repeats that check on an existing artifact
  and reports every class through annotations.

## What it does not prove

- No call site exists: `MvsCoreHost.get(account)` is never invoked, so the
  scheduler, the retry chain, the breaker and the update queue never run.
- R8 trims unreachable code. With `minifyEnabled` on staging and release, the
  current APK keeps the bridge and the reachable core paths. `FrameCodec`,
  `MessageStore` and `AesGcmCipher` are absent because nothing reaches them.
  `CircuitBreaker` is merged into `DcSupervisor`: the name is gone, the logic is
  not. That is why the dex check requires entry points, not every class.
- No device smoke test has been run.
- `nativeDcRouting` stays `false`: physical DC selection remains in
  `ConnectionsManager`, and the core does not claim otherwise.

## Before routing production traffic

- Choose call sites deliberately. Replacing `sendRequest` wholesale changes the
  behaviour of every feature at once.
- Re-check the observer thread and the object lifetime on this exact client
  revision, including the plugin path.
- Watch updates processing and battery on a real device with the core enabled.

## Important

`adapter-stubs/` exists only to validate Java signatures without the Android
client and must not be included in the APK. The bridge does not implement MTProto
encryption, auth, handshake or native DC migration; those remain inside
`ConnectionsManager`.