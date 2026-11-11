# SimpleCallBlocker

Малко Android приложение (Kotlin, Jetpack Compose, Material 3), което блокира нежелани входящи повиквания чрез официалния `CallScreeningService` + `RoleManager`. Без ROOT, без интернет, без foreground service.

- minSdk 29 (Android 10), targetSdk 36
- Пакет: `com.datecs.simplecallblocker`

## Структура

| Файл | Роля |
|---|---|
| `BlockerScreeningService.kt` | `CallScreeningService` – решава дали повикването се блокира |
| `PhoneNumbers.kt` | Нормализация на номера (E.164), префикси, проверка в контактите |
| `Settings.kt` | Локално съхранение (SharedPreferences) + журнал (`files/blocked_log.tsv`, последните 200) |
| `MainActivity.kt` | Единственият екран (Compose) |
| `AdminReceiver.kt` | Опционален `DeviceAdminReceiver` за Device Owner (неактивен по подразбиране) |

## Логика на блокиране

1. Само **входящи** повиквания; изходящите никога не се пипат.
2. Главният превключвател е OFF → пропуска.
3. **Скрит номер** (няма номер или presentation ≠ ALLOWED) → блокира се само при включен *Block Unknown*.
4. Номерът се нормализира до E.164 по държавата на SIM → мрежата → езиковата настройка.
   `0888 123 456`, `+359888123456` и `00359888123456` са един и същ номер.
5. **Черен списък** – точно съвпадение на нормализираните номера → блокира.
6. **Префикс** – `+3592`, `003592` или национално `02` (превежда се до `+3592`). Префикс без `+`/`00` се сравнява и с цифрите точно както ги е изпратила мрежата.
7. **Block Unknown** – номер, който не е в контактите (`PhoneLookup`, изисква `READ_CONTACTS`) → блокира.
8. Блокиране = `setDisallowCall(true)` + `setRejectCall(true)` + `setSkipNotification(true)`; записва се в журнала.

**Fail-open:** при exception, липсващо разрешение за контакти или празни настройки повикването се **пропуска**. Грешка никога не води до блокиране на всички повиквания.

**Време за отговор:** всички проверки са локални (SharedPreferences в паметта + една заявка към контактите), отнемат милисекунди. `respondToCall` се вика преди записа в журнала. Telecom дава ~5 s.

**Батерия / фонова работа:** услугата е *bound service* – системата я свързва само за времето на всяко входящо повикване. Няма foreground service, няма `BOOT_COMPLETED`, няма периодична работа. Работи след рестарт, без приложението да е отваряно (проверено).

## Компилиране

Android Studio: *Open* → тази папка → *Build ▸ Build APK(s)*. Или от команден ред (JDK 17+, напр. JBR на Android Studio):

```
gradlew assembleDebug      → app/build/outputs/apk/debug/app-debug.apk
gradlew assembleRelease    → app/build/outputs/apk/release/app-release.apk   (R8, ~2 MB)
```

> Release е подписан с debug ключа, за да се инсталира директно. За реална дистрибуция задайте собствен `signingConfig` в `app/build.gradle.kts`.

## Инсталация

```
adb install -r app/build/outputs/apk/release/app-release.apk
```
или копирайте APK-то на телефона и го отворете (разрешете „Инсталиране от неизвестни източници“).

## Предоставяне на Call Screening ролята

**От телефона:** отворете приложението → *Grant Call Screening role* → изберете **SimpleCallBlocker** → *Set as default*.
(Ръчно: *Настройки ▸ Приложения ▸ Приложения по подразбиране ▸ Caller ID & spam / Call screening*; на Xiaomi/MIUI/HyperOS пътят може да е различен.)

**През ADB** (за тестови/служебни устройства):
```
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING com.datecs.simplecallblocker
adb shell dumpsys role | grep -A2 CALL_SCREENING      # проверка
```

Статусът се вижда най-горе в приложението (`GRANTED` / `NOT GRANTED`). Без ролята **нищо не се филтрира**.

За *Block Unknown* приложението иска разрешение **Контакти**. Без него се блокират само скритите номера.

## Тестов сценарий

### На емулатор (без SIM – така е проверено)
Емулатор с Google APIs image (използван: API 33). Държавата на SIM на емулатора е `US`.

```
adb install -r app-release.apk
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING com.datecs.simplecallblocker
```
В приложението въведете: черен списък `001-650-555-1234`; префикс `650777`; префикс `+44`.

| Команда | Очакване |
|---|---|
| `adb emu gsm call 6505551234` | **блокиран** [blacklist] – записан като `00…`, идва национално |
| `adb emu gsm call 6507771111` | **блокиран** [prefix] |
| `adb emu gsm call +447700900123` | **блокиран** [prefix] |
| `adb emu gsm call 6505550000` | **звъни** |
| Block Unknown ON, `6509990000` (не е контакт) | **блокиран** [unknown] |
| Block Unknown ON, номер от контактите | **звъни** |
| Block Unknown ON, отнето разрешение за контакти, `6509990000` | **звъни** (fail-open) |
| Главен превключвател OFF, `6505551234` | **звъни** |
| `adb reboot`, без да се отваря приложението, `6505551234` | **блокиран** |

Проверка: `adb shell dumpsys telephony.registry | grep mCallState` (1 = звъни, 0 = отхвърлено), `adb logcat -s SimpleCallBlocker` и секцията *Blocked calls* в приложението. Отказаното повикване се вижда в `dumpsys telecom` като `DisconnectCause REJECTED / INCOMING_REJECTED`.

### На реален телефон (България)
1. Черен списък: `0888 123 456` (номер на втори телефон). Обадете се от него → не звъни, появява се в журнала.
2. Махнете го, добавете префикс `+359888` (или `0888`) → пак блокиран.
3. Обадете се от друг номер, който не е в списъците → звъни.
4. Включете *Block Unknown*, обадете се от номер, който не е в контактите → блокиран; от контакт → звъни.
5. Обадете се със скрит номер (`#31#` пред номера) при Block Unknown ON → блокиран; OFF → звъни.

## Ограничения

- **Ролята е задължителна.** Само едно приложение може да държи `ROLE_CALL_SCREENING`. Ако потребителят избере друго, блокирането спира (статусът в приложението го показва).
- **Блокираните повиквания не винаги се виждат** в системния списък с обаждания – зависи от dialer-а на производителя (`setSkipCallLog` не се ползва – според документацията се взема предвид само ако приложението е и default dialer). Журналът в приложението е източникът на истина.
- **Контакти:** на Android 10+ системата *може* да не извиква screening услугата за номера от контактите, ако приложението не е и default dialer (документирано поведение на AOSP; някои производители го спазват). На тестовия API 33 емулатор услугата **беше** извикана и контакт в черния списък беше блокиран. Ако на конкретно устройство контактите не се филтрират – това е системно ограничение.
- **Скрити номера:** `handle` е празен или presentation е `RESTRICTED`/`UNKNOWN`/`PAYPHONE`. Блокират се само с *Block Unknown*. Не може да се разграничи „скрит“ от „неизвестен поради мрежата“.
- **Международни номера:** нормализацията ползва `PhoneNumberUtils.formatNumberToE164` и държавата на SIM. Без SIM/мрежа се ползва езиковата настройка на устройството – национален номер (`0888…`) може да не се разпознае правилно. Записвайте номерата с `+359…` за пълна сигурност.
- **VoIP повиквания** (WhatsApp, Viber, …) не минават през Telecom и **не се филтрират**.
- **Dual SIM:** държавата се взима от SIM по подразбиране.
- **Android 10 (API 29):** `Call.Details.getCallDirection()` е наличен от API 29 – поддържано. Android 11+ добавя верификация STIR/SHAKEN (`callerNumberVerificationStatus`) – не се ползва (зависи от оператора и рядко е налична в Европа).
- **Xiaomi / MIUI / HyperOS:** може да изисква ръчно „Autostart“ и премахване на ограниченията за батерия, за да се свърже услугата надеждно.

## Device Owner (опционално, в момента НЕ се използва)

Приложението работи напълно без Device Owner. Компонентът `AdminReceiver` е включен само за евентуално бъдещо използване.

**Device Owner НЕ дава Call Screening ролята** – тя пак се дава от диалога или с `cmd role add-role-holder`.

Какво се управлява чрез `DevicePolicyManager` (само ако е Device Owner, бутоните се появяват в приложението):

| Функция | API |
|---|---|
| Забрана за деинсталиране | `setUninstallBlocked` |
| Автоматично разрешение за контакти (за Block Unknown) | `setPermissionGrantState(READ_CONTACTS)` |
| Отказ от Device Owner | `clearDeviceOwnerApp` |

Други възможни (не са реализирани): `addUserRestriction(DISALLOW_APPS_CONTROL)` срещу изчистване на данните, `setLockTaskPackages`, `setGlobalSetting` и т.н.

**ADB инструкция** – само на подготвено устройство **без нито един акаунт** (обикновено веднага след factory reset, преди добавяне на Google акаунт):
```
adb install -r app-release.apk
adb shell dpm set-device-owner com.datecs.simplecallblocker/.AdminReceiver
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING com.datecs.simplecallblocker
```
Премахване: бутон *Remove Device Owner* в приложението (или factory reset).
