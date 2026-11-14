# CLAUDE handoff: TRACK duty presence

## Goal of next session

Finish and verify the assigned duty point, GPS, attendance, and scoped Monitor work on `codex/duty-presence-gps`. The implementation is in place, but physical device and approved staging checks remain. Keep the website name **TRACK**. Do not change the published Android app label or package identity without a separate decision.

## State of play

- Git baseline: `origin/main` at `942bbe27548faddb90a40e95a8c7283cefe9e03e` when work began. Branch: `codex/duty-presence-gps`.
- Route now has one-step sign-in that opens My Duty and starts duty location when precise permission and GPS are available, maps navigation, a 10-second GPS target, bounded offline batches, and visible Resume Duty. Signed Route 2.3.0 and Monitor 1.2.0 update candidates are on this branch; the published `main` manifests still advertise Route 2.2.3 and Monitor 1.1.1.
- Firebase Functions derive attendance from authenticated track records and immutable duty-session context. Rules enforce own-record and reporting-chain reads and block client attendance writes. The TRACK control room and scoped viewer show roster, point, presence, last fix, and trail.
- Verification completed: `npm test` (109 passed), `npm run test:rules` (4 passed), `npm test --prefix functions` (4 passed), Database+Functions emulator synthetic entry/departure/return and reassignment trip passed, `npm run build`, Route unit/lint/debug assemble, and Monitor debug assemble. `git diff --check` passed. Android SDK for local builds is at `/tmp/track-android-sdk`; this path is temporary.
- No real phone or approved staging account was available. The scenarios, measurements, and release gate are in `docs/duty-presence.md`. Debug APKs are under `android-route/build/outputs/apk/debug/` and `android-monitor/build/outputs/apk/debug/`, which Git ignores.
- The active Firebase project reports billing disabled, so the new Functions cannot be deployed until the owner enables the Blaze plan. The current app updater checks GitHub `main`, downloads a SHA-256 verified APK, then asks the user to approve Android's installer. It cannot silently install or send GPS to Firebase without any network.
- `docs/PROJECT-MEMORY.md` was already untracked before this work. Leave it untouched and out of commits.

## Open decisions and required work

1. Test a complete trip from the Route app through an approved staging database to both Monitor views on a current Android device and a field phone. Cover lock screen, app switching, Recents swipe, weak GPS, offline recovery, permission changes, stop/start, sign-out, OS kill, Active apps Stop, force-stop, and reboot. Record sample count, median, p95, and largest gap for capture and server receipt, plus battery/data use. Mark exact outcomes in `docs/duty-presence.md`.
2. Review staging rules and Functions together before deployment. Production rules, Functions, data, release signing, publication, and `main` pushes require separate authorization. The Functions emulator uses `us-central1` because of its database emulator limitation; deployed Functions target the database's `asia-southeast1` region.
3. Confirm event dates, roster, reporting UIDs, sectors, and point coordinates with the operator. The 24th and 26th are planning context only; no real event allocation is hardcoded.
4. Investigate any failures found on hardware or staging and rerun the affected tests. Do not describe attendance as proof that a particular person held the phone.

## Skills to use

- `android-clean-architecture` if device findings require Route changes.
- `backend-patterns` and `ai-security` if rules or Functions need changes.
- `handoff` when handing unfinished field verification to another session.

## Artifacts

- Original user brief: `Pasted text.txt` in the originating Codex chat (not committed; request it again if unavailable).
- Rollout and acceptance record: `docs/duty-presence.md`.
- Data shape and permissions: `docs/data-contract.md`, `firebase/database.rules.json`, `functions/`.
- Route implementation: `android-route/src/main/java/org/trace/route/`.
- Website sources: `dashboard/source.html`, `dashboard/si-monitor.template.html`; generated pages: `dashboard/TRACK.html`, `dashboard/TRACE.html`, `dashboard/si-monitor.html`.
- Regression tests: `tests/dashboard.spec.mjs`, `tests/rules.test.mjs`, `functions/geofence.test.mjs`, `functions/integration.mjs`.
