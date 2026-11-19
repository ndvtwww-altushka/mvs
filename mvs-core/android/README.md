# Android-часть (не подключена)

Мост к движку, который не входит ни в сборку приложения, ни в JVM-модуль:

- `org/telegram/tgnet/MtprotoAdapter.java` — реализация `MtprotoPort` поверх
  `ConnectionsManager`;
- `org/telegram/messenger/MvsCoreHost.java` — ядро на аккаунт;
- `ConnectionsManager.mvs.patch` — observer-хук для апдейтов.

Границы:

- Android-модуль в репозитории **не изменён**: `settings.gradle`,
  `TMessagesProj/build.gradle` и `ConnectionsManager.java` в исходном виде.
- Адаптер собирается только против стабов `../adapter-stubs` (compile-only):
  это проверка типов, а не интеграции.
- Чтобы включить: применить патч, добавить `:mvs-core` в `settings.gradle` и
  зависимость в `TMessagesProj/build.gradle`, собрать
  `./gradlew :TMessagesProj:compileDebugJavaWithJavac`.
