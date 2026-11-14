# TRACE data contract

The supplied HTML and two APK archives were used as reverse-engineering references. Their Java source was not available. The rebuilt Route app writes the paths the dashboard already reads; the sample in `tests/fixtures/sample-data.json` is synthetic and does not access the previous Firebase project.

| RTDB path | Shape | Writer | Reader |
| --- | --- | --- | --- |
| `roles/{uid}` | `admin`, `monitor`, or `route` | Admin | Owner, admin |
| `assignments/{uid}` | Existing route fields; optional stable `id`, `designation`, `supervisorUid`, `dutyPoint` | Admin | Owner, admin, scoped reporting official |
| `config/routes/{id}` | Route definition | Admin | Signed-in users |
| `config/stations/{id}` | Polling site and coordinates | Admin | Control room; viewers only for IDs in their scope |
| `siScopes/{uid}` | Viewer mandal, officer, rank, mobile, allowed route IDs and polling booth IDs | Admin | Owner, admin |
| `config/settings` | Thresholds and election name | Admin | Signed-in users |
| `devices/{uid}` | Assigned unit metadata, app version | Owner after approved assignment; admin | Control room, owner |
| `live/{uid}` | Last route snapshot: `lat`, `lng`, original GPS timestamp `t`, client upload time `up`, server timestamp `receivedAt` for new clients, `gps`, `spd`, `hdg`, `acc`, `bat`, `sig`, `duty`, `sessionId`, optional `assignmentId` | Owner; admin | Control room, owner, scoped reporting official |
| `track/{uid}/{YYYYMMDD}/mobile/{GPS timestamp}` | `[lat, lng, accuracy metres, speed km/h]`; point-duty clients append `{sessionId, assignmentId, pointId, startedAt}` at index 4 | Owner | Control room, owner, scoped reporting official |
| `reportingRoster/{supervisorUid}/{officerUid}` | Derived UID index for direct and one-level indirect reports | Cloud Function | That supervisor |
| `dutySessions/{uid}/{sessionId}` | Immutable assignment/point snapshot and UTC epoch-millisecond window; optional `endedAt` | Cloud Function | Owner, admin, scoped reporting official |
| `attendance/{uid}/{sessionId}` | Server-derived current/confirmed state, first arrival, fix time, accuracy, distance, and transition records keyed by fix time and state | Cloud Function | Owner, admin, control-room monitor, scoped reporting official |
| `events/{id}` | `type`, `uid`, original timestamp `t`, optional location and report fields | Owner creates; admin updates | Control room |
| `msg/{uid}` | `id`, `text`, `t`, optional `voice` | Admin | Owner, control room |
| `photos/{id}` | `uid`, `t`, base64 JPEG `img`, optional `note` | Owner creates | Control room |
| `voice/{id}` | base64 `a`, `mime`, `t`, `by`, optional `uid` | Owner creates or admin | Signed-in users |
| `incidents/{id}` | Incident lifecycle and dispatch data | Admin | Control room |
| `lock/{key}` | Existing route lock state | Admin | Signed-in users |

`track` retains the dashboard's nested date/batch format. The Route app uses a fixed `mobile` batch key and original GPS timestamps, making retries idempotent. SOS events use fixed generated IDs. The offline SQLite queue preserves event and point order; the latest `live` snapshot is coalesced while offline. A successful Firebase HTTP write is treated as server receipt; operator acknowledgement is stored separately on the event.

The current media format mirrors the supplied dashboard. Large deployments should move blobs to Cloud Storage and keep authorized metadata in RTDB before production use.

An optional `dutyPoint` is embedded in the administrator-owned assignment. It has stable `id`, `name`, `lat`, `lng`, `radiusM`, `eventId`, `eventName`, `sectorId`, optional `reportingGroupId`, `instructions`, `windowStart` and `windowEnd`. Windows and fix times are Unix milliseconds; operator forms interpret local inputs in `Asia/Kolkata`. Ordinary route and polling duties do not need a duty point. Updating point geometry issues a new assignment `id`; updating only `supervisorUid` changes the reporting chain without changing that identity. The roster index speeds up listing, while rules recheck the current assignment and event/sector scope on each detail read. A stale roster entry cannot grant access. Administrators grant a viewer's `siScopes/{uid}/events/{eventId}` and `sectors/{sectorId}` explicitly.

Presence is **device-location evidence**, not proof of the officer's personal identity or immunity to location spoofing. The only verified state writer is the Database-triggered function in `functions/`. It accepts point fixes for a captured duty session and the matching assignment/point, checks coordinates, accuracy, event window, fix order and plausible movement, and confirms entry or exit after two qualifying fixes within 45 seconds. Entry requires distance plus accuracy to be at least 15 m inside the configured radius; exit requires distance minus accuracy to be at least 15 m outside. Boundary noise and poor fixes produce `uncertain` without erasing first arrival. A later valid entry records a separate return. The monitor also shows `Location uncertain` once the last fix is older than 90 seconds, even if the last confirmed transition was arrival. Delayed valid fixes remain in `track` with original times and cannot move an already newer attendance state backward. No client can write `attendance` or `dutySessions` through security rules.

The native Route service requests a usable fix about every 10 seconds during active duty; the separately opted-in Monitor viewer location remains at its own roughly 30-second cadence and stays under `viewerLive/{uid}`. A heartbeat without a new fix retains the last known coordinates and original `t`; `receivedAt` describes server receipt, not GPS freshness. Historic records keep their original fix timestamp and are sent in bounded batches after the latest `live` state. The stopped-duty status is queued durably and a network-constrained Android job retries queued writes without restarting location capture. The app uses a visible Resume Duty flow after interruption; it does not auto-start GPS after reboot or user stop.
