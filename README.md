# H2Open Driver

Android app for H2Open delivery drivers: sign in, see today's trips, work
through the stops and confirm each delivery (quantities, empties collected,
payment, signature). It talks to the `h2o_driver` API of the
[h2open-modules](https://github.com/OrhanYigitDurmaz/h2open-modules) Odoo
modules (see `SPEC.md` there). Calling customers over VoIP (Asterisk) and push
notifications come next.

Kotlin, Jetpack Compose, Material 3. Min Android 8.0 (API 26).

## Layout

* `api/`: plain Kotlin client for `/h2o/api/v1/...` (OkHttp,
  kotlinx.serialization). No Android dependency: `cd api && ../gradlew test`
  builds and tests it on any JDK.
* `app/`: the Android app.
  * `data/SessionStore.kt`: server URL, app token (encrypted with an Android
    Keystore key) and the driver's profile.
  * `ui/login`, `ui/trips`, `ui/trip`, `ui/stop`: one screen each.
  * Strings in English (`values/`) and Turkish (`values-tr/`).

## Build

Needs JDK 17+ and the Android SDK (Android Studio has both).

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :api:test :app:testDebugUnitTest :app:lintDebug
```

CI builds the debug APK on every pull request; download it from the run's
**h2open-driver-debug-apk** artifact.

## Try it against a local Odoo

Run Odoo with the h2o modules and demo data, then sign in with server
`http://10.0.2.2:8069` from the emulator (or your PC's LAN address from a
phone; plain HTTP is only allowed to `10.0.2.2` and `localhost`, see
`app/src/main/res/xml/network_security_config.xml`). The demo driver is
`driver` / `driver`.

The API client also has an end-to-end test that drives a whole trip on a real
server. It runs only when `H2O_IT_URL` is set:

```sh
cd api   # its own settings file builds it without the Android SDK
H2O_IT_URL=http://localhost:8069 H2O_IT_DATE=2026-10-19 ../gradlew test
```

`H2O_IT_DATE` must be a day with a planned trip for the demo driver
(`env['h2o.trip']._h2o_plan_day(day, route)` in an Odoo shell).
