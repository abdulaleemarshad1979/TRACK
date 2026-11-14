# Duty-point rollout and field verification

This branch implements the duty-point flow against Firebase Realtime Database. The published Route 2.2.3 APK remains the 30-second release. Do not use the new attendance UI as an operational record until the Functions, rules and Route build have been deployed and a physical duty trip has passed.

Route 2.3.0 and Monitor 1.2.0 are signed update candidates on this branch. Their manifests and APKs match, but installed apps check the manifests on GitHub `main`, so branch builds do not trigger an update prompt. The Firebase project currently has billing disabled; the new attendance Functions require the Blaze plan before they can be deployed. Publish the server changes and both APKs only after the field checks below and an explicit release decision.

## Administrator preparation

1. Confirm the event, roster, sector, reporting chain, duty window, instructions and exact coordinates with the approving officer. The mentioned 24th and 26th are planning dates, not defaults in code or data.
2. Create officer route accounts and Monitor viewer accounts using the existing administrator flow. Assign each official an authenticated UID. For an SI's constable, set `supervisorUid` to that SI's viewer UID. For a sector CI, assign the SI's `supervisorUid` to the CI's viewer UID.
3. Open **Duty points** in the TRACK control room. Choose an account, enter the approved point ID, name, coordinates, radius, event, sector and IST window, and set its supervisor UID. Use **Grant this official access** for each SI/CI viewer who needs the event and sector. The same form can update reporting without moving a point. A point change requires Stop Duty first and issues a new assignment ID.
4. Inspect the point and scope on staging. Deploy the Functions and rules together in the database's `asia-southeast1` region. Publish or sign a new Route APK only after device verification and explicit release authorization. Deploying `main` also deploys Hosting, so do not merge this branch to `main` as an incidental test.

All point data is operator-provided. Tests use synthetic UIDs and coordinates. No production roster, event dates or CM-program point is embedded in the source.

## Presence states

`Not arrived` means no confirmed point entry in the active assignment/session. `At duty point` follows two accurate device fixes well inside that officer's assigned radius. `Left duty point` follows two fixes well outside after arrival. `Location uncertain` covers boundary ambiguity, poor accuracy, missing GPS, off duty, or a fix older than 90 seconds. First arrival remains visible after leaving or signal loss. Every confirmed entry, exit and return has its original fix time and accuracy. Review the map's **Recent GPS trail** for the last 15 minutes of reported points; the existing control-room replay remains available for longer review. Marker animation never creates a presence event.

The presence label is based on a phone's reported coordinates. It cannot establish which person held the phone, and software location spoofing remains possible. An operator should compare the device trail, fix accuracy, timestamps and contact history before relying on an attendance record.

## Field acceptance record to complete before release

Use a current Android device and a field phone with its manufacturer's battery management. Record model, Android version, app version, permissions, power settings, network and test start/end. For healthy runs, export the original fix timestamps and server `receivedAt` values and calculate count, median, p95 and largest gap for capture and receipt separately; also record battery and data use. The 10-second interval is a target, not a guaranteed delivery deadline.

| Scenario | Expected observation | Status in this branch |
| --- | --- | --- |
| Sign in, remain away from the assigned point | My Duty opens and active-duty GPS sharing starts after precise permission and GPS are available; arrival stays unconfirmed | Code/rules inspection; physical trip pending |
| Sign in without an assignment | My Duty opens with a clear missing-assignment message; GPS sharing waits for an authorized assignment | Code inspection; device test pending |
| Sign in with precise location denied or GPS disabled | Sign-in completes, shows the recovery step, and does not claim to share a fix | Code inspection; device test pending |
| Navigate to assigned point; visit unrelated point first | Maps opens correct coordinates; unrelated visit creates no arrival | Unit/emulator synthetic checks; physical trip pending |
| Enter, leave and return | One arrival, one departure and one return with retained first arrival | Database and Functions emulator pass; physical trip pending |
| Noisy boundary, weak GPS, old replay, reassignment | No false/newer-state transition; old supervisor loses read access | Unit and rules emulator pass; physical trip pending |
| Screen locked, app switched, Recents swipe | Foreground service continues if OS allows; notification remains | Code inspection; device test pending |
| Mobile data off, Wi-Fi on | Fresh fixes continue to reach the control room over Wi-Fi | Code inspection; device test pending |
| Mobile data and Wi-Fi both off during active duty, then reconnect | GPS fixes are saved locally; no remote update while offline; latest live position and original-time trail upload after reconnection | SQLite outbox and service inspected; device test pending |
| No data, reconnect, Stop/Start, sign out | Bounded batches and fresh live state; an old queued active snapshot cannot replace a stop or new duty | Queue policy unit test and code inspection; device test pending |
| Permission denial/revocation, OS kill, Active apps Stop, force-stop, reboot/power-off | Capture stops or remains interrupted; visible Resume after unlock, no automatic restart | Code inspection; device test pending |
| Monitor/Route builds and complete duty trip | Debug APK and staging telemetry reviewed end to end | Both debug APKs built and Route unit tests/lint passed; devices and staging trip pending |

For the no-network test, sign in online with precise location enabled, turn off mobile data and Wi-Fi while leaving Location on, move outdoors for several minutes, and record the Route screen's queued count. Restore either network, then compare the control room's new live fix with the trail's original fix times and server `receivedAt` values. Repeat with mobile data off but Wi-Fi connected; updates should still arrive. Remain signed in until queued records finish uploading. If the officer signs out while offline, the app removes their authentication token; queued records remain on that phone but cannot upload until the same account signs in again. No app can transmit to Firebase while the phone has no network. The phone cannot capture or transmit during full power-off. A missing signal shows last known location and age; it does not identify the cause. The Route foreground service does not request background-location permission or start from boot. On Android 13+, denying notification permission can hide the notification while the system's Active apps control remains available. A network-constrained job only retries queued database writes after a stop; it never starts GPS.
