# Locky

An Android app lock. Pick the apps you don't want anyone else opening; Locky asks
for a PIN before they launch.

<p align="center">
  <img src="docs/screenshots/lock-screen.png" width="240" alt="Locky lock screen" />
</p>

## What it does

- **Per-app lock.** Every launchable app on the device gets a switch. Lock the ones
  you care about; leave the rest alone.
- **PIN gate.** A 4–8 digit PIN, stored only as a salted PBKDF2-HMAC-SHA256 hash.
  The PIN itself is never written to disk.
- **Grace period.** After you unlock an app you get a minute to switch away and
  come back before it asks again. Any app lock that re-prompts on every task switch
  gets uninstalled within a day.
- **Biometric unlock.** If your device has a secure lock screen, the system prompt
  can stand in for the PIN.
- **Works offline.** There is no `INTERNET` permission. Locky cannot phone home
  because it has no way to.

## How it works

Android has no public API for "which app just came to the foreground", so an app
lock has to use an `AccessibilityService`. Locky asks for the minimum needed:

| Setting | Value | Why |
| --- | --- | --- |
| `accessibilityEventTypes` | `typeWindowStateChanged` | Tells us a package came forward. Nothing else. |
| `canRetrieveWindowContent` | `false` | Locky never reads screen contents, so it does not ask. |
| `<uses-policies>` | *(none)* | Device admin is used only to resist uninstall, so it claims no device powers. |

The flow when a protected app opens:

1. `AppWatcherService` sees a `TYPE_WINDOW_STATE_CHANGED` event.
2. It checks the package against the protected set, held in memory as a `Set` so
   the check is a single lookup on the event thread.
3. If protected and outside its grace period, it starts `LockScreenActivity` on
   its own task affinity with `FLAG_SECURE`.
4. The PIN is verified against the stored hash off the main thread.
5. On success the package is granted a grace period and the screen closes.

Everything above lives in `app/src/main/java/com/locky/app/`:

```
security/    PinManager (hashing), AttemptLimiter (lock-out), BiometricUnlock
service/     AppWatcherService, LockScreenActivity, UnlockState
admin/       LockyAdminReceiver
data/        Room database, DAO, repository, installed-app loader
ui/          MainActivity, app list, setup checklist, PIN keypad, lock screen
```

## Setup

Requires **JDK 17** and the **Android SDK** (compileSdk 35, minSdk 26 — Android
8.0).

```bash
git clone https://github.com/<you>/locky.git
cd locky
./gradlew assembleDebug
```

Or open the folder in Android Studio and let it sync.

On first run Locky walks through three grants:

1. **Device admin** — stops Locky being uninstalled while the lock is on.
2. **Accessibility** — the foreground-app detection described above.
3. **PIN** — choose and confirm a 4–8 digit code.

Each step stays on the checklist once granted, so nothing silently reverts.

## Building a release APK

The `release` build type currently falls back to the debug signing config so
`assembleRelease` works immediately. Before you publish, add a real keystore:

```bash
keytool -genkey -v -keystore locky-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias locky
```

Then in `app/build.gradle.kts`, replace:

```kotlin
signingConfig = signingConfigs.getByName("debug")
```

with a keystore read from properties that are **not** committed (the repo already
ignores `keystore.properties`), and keep the file out of version control.

## Permissions

| Permission | Used for |
| --- | --- |
| `QUERY_ALL_PACKAGES` | Enumerating launchable apps is the app's whole purpose; the `<queries>` element alone cannot list them. |
| `USE_BIOMETRIC` | Optional biometric unlock. |
| `VIBRATE` | Keypad feedback. |

There is deliberately no `INTERNET`, `CAMERA`, `READ_SMS`, or storage access.

## Testing

```bash
./gradlew test          # unit tests
./gradlew lint          # Android Lint
```

The unit tests cover the two pieces with real logic worth pinning down: the PIN
lock-out policy and the grace-period timing. Both take an injected clock, so they
assert timing behaviour without waiting on it.

## Limitations

- **A determined user with developer options can uninstall or disable Locky.**
  Device admin raises the cost; it does not make it impossible. This is a
  self-discipline tool, not a security boundary against someone who owns the
  device and is being deliberate.
- **Accessibility services can be force-stopped** from Android Settings. Locky
  re-registers on next launch, but there is a window where protection is off.
- **No timer-based rules.** Locky does not yet do "lock after 10pm" or
  "lock on mobile data". The grace period is the only time-based behaviour.

## Licence

MIT — see [LICENSE](LICENSE).
