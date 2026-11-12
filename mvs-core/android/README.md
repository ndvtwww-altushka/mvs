# Android bridge — integration is optional and not enabled by this package

`MtprotoAdapter` delegates requests to the existing `ConnectionsManager`. `MvsCoreHost` lazily owns one `MvsNetCore` per account. These classes are not automatically compiled into `TMessagesProj` by copying this folder alone.

## Contract used by the adapter

- A `ConnectionsManager.sendRequest(...)` overload accepting the explicit datacenter ID and connection type.
- `ConnectionsManager.mvsSetUpdatesObserver(account, observer)`, added by the client-side hook below.
- The observer must be invoked synchronously before the original `Updates` TLObject is freed. The adapter copies it to a byte array during that callback.

## Client integration checklist

1. Add `include ':mvs-core'` to root `settings.gradle`.
2. Add `implementation project(':mvs-core')` to `TMessagesProj/build.gradle` dependencies.
3. Add the `mvsSetUpdatesObserver` registry and call it at both relevant `Updates` processing points in the actual `ConnectionsManager` source. The exact method names/locations can differ between fork revisions; do not blindly paste without checking the current source.
4. Ensure `MtprotoAdapter.java` and `MvsCoreHost.java` are included in the app Java source set (or move them to a proper app source folder).
5. Run `./gradlew :TMessagesProj:compileDebugJavaWithJavac` and fix any signature mismatch against the exact client revision.
6. Run device smoke tests before routing any production requests through the core.

## Suggested observer code

Inside `ConnectionsManager`:

```java
private static final ConcurrentHashMap<Integer, Consumer<TLRPC.Updates>> mvsUpdatesObservers =
        new ConcurrentHashMap<>();

public static void mvsSetUpdatesObserver(int account, Consumer<TLRPC.Updates> observer) {
    if (observer == null) mvsUpdatesObservers.remove(account);
    else mvsUpdatesObservers.put(account, observer);
}

private static void mvsNotifyUpdatesObserver(int account, TLRPC.Updates updates) {
    Consumer<TLRPC.Updates> observer = mvsUpdatesObservers.get(account);
    if (observer != null) {
        try { observer.accept(updates); }
        catch (Throwable error) { FileLog.e(error); }
    }
}
```

Call `mvsNotifyUpdatesObserver(currentAccount, updates)` immediately before the existing `processUpdates(updates)` invocation in each path that receives an `Updates` object. Keep original `processUpdates` calls unchanged. Verify the callback's thread and object lifetime in the exact client source.

## Important

`adapter-stubs/` exists only to validate Java signatures without the Android client. It must not be included in the APK. The adapter does not implement MTProto encryption, auth, handshake or native DC migration; those remain inside `ConnectionsManager`.
