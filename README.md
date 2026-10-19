# H2Open Driver

Android app for H2Open delivery drivers: sign in, see today's trips, work
through the stops and confirm each delivery (quantities, empties collected,
payment, signature). Drivers are notified when an order is placed and can take
it themselves from the Orders tab: it goes on their trip of the day. It talks to the `h2o_driver` API of the
[h2open-modules](https://github.com/OrhanYigitDurmaz/h2open-modules) Odoo
modules (see `SPEC.md` there). Drivers call customers over the office PBX
(VoIP through Asterisk), and push notifications bring in calls, orders and trip
changes.

The app needs a connection: there is no offline mode. While the phone is
offline a bar at the top says so and everything that changes data is disabled.

Kotlin, Jetpack Compose, Material 3. Min Android 8.0 (API 26).

## Layout

* `api/`: plain Kotlin client for `/h2o/api/v1/...` (OkHttp,
  kotlinx.serialization). No Android dependency: `cd api && ../gradlew test`
  builds and tests it on any JDK.
* `app/`: the Android app.
  * `data/SessionStore.kt`: server URL, the access and refresh tokens
    (encrypted with an Android Keystore key) and the driver's profile.
    `api/H2oApi.kt` refreshes the access token before it expires and once on
    a 401, one refresh at a time; a refused refresh goes back to the login
    screen. Drivers who use the app at least every 90 days stay signed in.
  * `ui/login`, `ui/trips`, `ui/trip`, `ui/stop`, `ui/orders`: one screen
    each. Trips and Orders are the two tabs; the Orders tab lists the open
    orders (`GET driver/orders`) and takes one (`POST driver/orders/<id>/claim`).
    A stop the driver took can be given back from the stop screen. The trip
    screen reorders the pending stops and opens `ui/summary`, the end-of-day
    summary (also shown when a trip is closed). Navigation opens Google Maps
    at the customer's pinned location (no Maps API, so no billing); stops
    without a pin are marked "No location" and navigate by address.
  * `data/Connectivity.kt`: online or not (`ui/common/Offline.kt` shows the bar).
  * `data/CrashReporter.kt`: a crash is saved on the phone and sent to Odoo
    (`POST crashes`, Water Delivery > Configuration > App Crashes) once the
    app is online and signed in.
  * `voip/`: calls over the office PBX. `VoipManager` asks Odoo for SIP
    credentials (`POST sip/credentials`), registers only while a call or a
    push needs it, and pairs each SIP call with a WebRTC audio session
    (`WebRtcMedia`). `CallService` is the foreground service and call
    notification, `CallActivity` the full-screen call UI. Each call is also
    handed to Android's call system as a self-managed call (`TelecomCalls`,
    `H2oConnectionService`): a GSM call holds it, car kits answer and hang
    up, and Android routes the audio.
  * `push/`: Firebase Cloud Messaging; an `incoming_call` data message
    wakes the app to register so Asterisk can ring it. `order_open` shows a
    "New order" notification that opens the Orders tab; `order_claimed`
    removes it once a driver has taken the order. `trip_changed` refreshes the
    trip on screen.
* `sip/`: plain Kotlin SIP-over-WebSocket user agent (RFC 3261 / 7118):
  registration with digest auth, outgoing and incoming calls, cancel, hang
  up. Media is not its job: it carries the SDP the app hands it.
  `sip/asterisk-test/run.sh` starts an Asterisk in Docker, then
  `cd sip && H2O_SIP_WS=ws://localhost:8088/ws ../gradlew test` runs the
  call tests against it (CI does the same).
  * Strings in English (`values/`) and Turkish (`values-tr/`).

## Finding errors

Error messages in the app say what went wrong and then the exact failure:
the call (`POST driver/stops/12/deliver`), the HTTP status and Odoo's
message, or the exception. Every failure is also written to logcat with its
stack trace and Odoo's raw answer, under tags starting with `H2Open`:

    adb logcat | grep H2Open

In Android Studio's Logcat, filter on `tag:H2Open`.

## Build

Needs JDK 17+ and the Android SDK (Android Studio has both).

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :api:test :app:testDebugUnitTest :app:lintDebug
```

CI builds the debug APK on every pull request; download it from the run's
**h2open-driver-debug-apk** artifact.

### Push for incoming calls

Put the Firebase project's `google-services.json` (Android app
`tr.h2open.driver`) in `app/`; it is not committed. The build reads it into
`BuildConfig`. Without it the app builds and outgoing calls work, but nothing
wakes it for incoming calls. On the Odoo side, set the FCM project id and
service account on the H2Open settings page (h2o_push), and the SIP domain
and WSS URL (h2o_telephony).

## Try it against a local Odoo

Run Odoo with the h2o modules and demo data, then sign in to the debug APK
with server `http://10.0.2.2:8069` from the emulator, or your PC's LAN
address (`http://192.168.x.x:8069`) from a phone on the same network. Debug
builds allow plain HTTP to any host; release builds only allow HTTPS (see the
two `network_security_config.xml` files). The demo driver is `driver` /
`driver`.

The API client also has an end-to-end test that drives a whole trip on a real
server. It runs only when `H2O_IT_URL` is set:

```sh
cd api   # its own settings file builds it without the Android SDK
H2O_IT_URL=http://localhost:8069 H2O_IT_DATE=2026-10-19 ../gradlew test
```

`H2O_IT_DATE` must be a day with a planned trip for the demo driver
(`env['h2o.trip']._h2o_plan_day(day, route)` in an Odoo shell).
