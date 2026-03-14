# Android MVP

Минимальный Android-companion для текущего `tg-ws-proxy`.

Что уже есть:
- один модуль `app`;
- `ForegroundService`, который держит локальный SOCKS5-прокси;
- Kotlin-порт core-логики: SOCKS5 handshake, Telegram DC detection, MTProto init patching, WebSocket tunnel, direct TCP fallback;
- простой экран управления и настройки;
- файловый лог в app-private storage.

Что это решает:
- приложение может поднять локальный прокси на `127.0.0.1:1080`;
- пользователь вручную настраивает Telegram Android на этот SOCKS5-прокси;
- дальше логика повторяет desktop-версию проекта.

Что нужно проверить на устройстве:
- что Telegram Android принимает SOCKS5 на `127.0.0.1` другого приложения;
- что `wss://kws*.web.telegram.org/apiws` стабильно работает с конкретным набором DC IP.

Открытие проекта:
1. Откройте папку `android-app/` в Android Studio.
2. Используйте Android SDK и JDK 17+.
3. Дождитесь Gradle Sync.
4. Соберите и установите `app` на устройство.

Текущее ограничение:
- в этом окружении не было Android SDK/Gradle wrapper, поэтому модуль добавлен как исходники для открытия в Android Studio, без локальной сборки из терминала.
