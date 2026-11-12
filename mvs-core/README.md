# mvs-core

Слой ядра mvs на чистой JVM: кадры, планировщик, ретраи, журнал сообщений.
Собирается и тестируется без Android, без Gradle и без сети.

## Структура

```
src/app/mvs/core/util   Clock, Backoff, Budget
src/app/mvs/core/wire   FrameCodec, Decoder, Reassembler
src/app/mvs/core/net    CallScheduler, RetryInterceptor, CircuitBreaker,
                        DcSupervisor, MvsNetCore, MtprotoPort, RpcException
src/app/mvs/core/store  SegmentLog, MessageStore, CipherPort, AesGcmCipher
test/app/mvs/core       RegressionTest (25 проверок)
```

## Прогнать у себя

Нужен JDK 17:

```sh
cd mvs-core
sh run-tests.sh
```

Скрипт компилирует `src` и `test` и запускает `app.mvs.core.RegressionTest`.
В CI то же самое делает `.github/workflows/mvs-core.yml`.

## Границы

Модуль ничего не знает про Telegram: движок подключается через `MtprotoPort`,
ключ — через `CipherPort`. Мост `MtprotoAdapter` живёт в Android-части
(`org.telegram.tgnet`) и в JVM-модуль не входит; в CI он проверяется только
compile-only контрактом против стабов с сигнатурами из этого дерева.

Авторитетная проверка — `./gradlew :TMessagesProj:compileDebugJavaWithJavac`
и прогон на устройстве. Ни того, ни другого пока не было.

К сборке приложения модуль **не подключён**: `mvs-core/build.gradle` удалён,
`:mvs-core` в `settings.gradle` отсутствует, `ConnectionsManager.java` не изменён.
Мост к движку лежит в `mvs-core/android/` и собирается только против стабов.
Включение — отдельный коммит: патч `android/ConnectionsManager.mvs.patch`,
`:mvs-core` в `settings.gradle`, зависимость в `TMessagesProj/build.gradle`.
