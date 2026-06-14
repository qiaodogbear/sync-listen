# Android Host Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow one Android phone to host a complete Sync Listen room over the same Wi-Fi or phone hotspot without a computer.

**Architecture:** A foreground Android service runs an embedded Ktor/CIO server that preserves the existing REST/WebSocket protocol. Pure Kotlin server state and invitation codecs are tested on the JVM; Android-specific service, address discovery, notification, and UI code are thin adapters around those boundaries.

**Tech Stack:** Kotlin, Ktor Server/CIO/WebSockets/ContentNegotiation, kotlinx.serialization, Android foreground services, Compose, Hilt, OkHttp/Retrofit, JUnit.

---

## File Structure

- `android-app/app/src/main/java/com/synclisten/app/host/HostServerState.kt`: public Host lifecycle state and controller contract.
- `android-app/app/src/main/java/com/synclisten/app/host/HostAddressResolver.kt`: selects and formats a reachable LAN IPv4 address.
- `android-app/app/src/main/java/com/synclisten/app/host/HostServerService.kt`: foreground service, notification, Wi-Fi lock, and embedded server lifecycle.
- `android-app/app/src/main/java/com/synclisten/app/host/server/HostRoomStore.kt`: authoritative single-room state, membership, playlist, and playback rules.
- `android-app/app/src/main/java/com/synclisten/app/host/server/HostRoomHub.kt`: WebSocket session registration and broadcast.
- `android-app/app/src/main/java/com/synclisten/app/host/server/HostServer.kt`: Ktor routes and protocol/error mapping.
- Existing `HomeController`, ViewModels, Compose screens, invitation and BLE files: connect Host mode to existing client workflows.

### Task 1: Build Configuration And Host Address Boundary

**Files:**
- Modify: `android-app/gradle/libs.versions.toml`
- Modify: `android-app/app/build.gradle.kts`
- Modify: `android-app/app/src/main/AndroidManifest.xml`
- Create: `android-app/app/src/main/java/com/synclisten/app/host/HostServerState.kt`
- Create: `android-app/app/src/main/java/com/synclisten/app/host/HostAddressResolver.kt`
- Test: `android-app/app/src/test/java/com/synclisten/app/host/HostAddressResolverTest.kt`

- [ ] Write failing tests proving loopback/link-local/IPv6 addresses are rejected and a Wi-Fi IPv4 becomes `http://<ip>:38571`.
- [ ] Run `.\gradlew.bat testDebugUnitTest --tests "*HostAddressResolverTest"` and verify the missing boundary fails.
- [ ] Add Ktor dependencies, foreground-service manifest declarations, Host state types, and minimal resolver implementation.
- [ ] Re-run the focused test and commit `feat: add host server foundation`.

### Task 2: Authoritative Host Room Store

**Files:**
- Create: `android-app/app/src/main/java/com/synclisten/app/host/server/HostServerError.kt`
- Create: `android-app/app/src/main/java/com/synclisten/app/host/server/HostRoomStore.kt`
- Test: `android-app/app/src/test/java/com/synclisten/app/host/server/HostRoomStoreTest.kt`

- [ ] Write failing tests for create, join by code/token, snapshot, Host leave closure, member leave, and permission errors.
- [ ] Run the focused test and verify it fails because the store is absent.
- [ ] Implement a single-room mutex-protected store using existing protocol models and injected clock/ID factories.
- [ ] Add failing tests for concurrent track order allocation and Host-only play/pause/seek/next.
- [ ] Implement playlist and playback rules, re-run focused tests, and commit `feat: add embedded host room store`.

### Task 3: Ktor REST And WebSocket Protocol

**Files:**
- Create: `android-app/app/src/main/java/com/synclisten/app/host/server/HostRoomHub.kt`
- Create: `android-app/app/src/main/java/com/synclisten/app/host/server/HostServer.kt`
- Test: `android-app/app/src/test/java/com/synclisten/app/host/server/HostServerTest.kt`

- [ ] Write failing Ktor test-application tests for health/time, room creation, joining, snapshots, errors, and WebSocket authoritative snapshot.
- [ ] Run the focused test and verify routes are missing.
- [ ] Implement JSON/error handling, REST room routes, and WebSocket authentication/broadcast.
- [ ] Add failing tests for playback routes and periodic `SYNC`, then implement them.
- [ ] Re-run focused tests and commit `feat: serve embedded room protocol`.

### Task 4: Host File Upload And Download

**Files:**
- Modify: `android-app/app/src/main/java/com/synclisten/app/host/server/HostRoomStore.kt`
- Modify: `android-app/app/src/main/java/com/synclisten/app/host/server/HostServer.kt`
- Test: `android-app/app/src/test/java/com/synclisten/app/host/server/HostFileRoutesTest.kt`

- [ ] Write failing tests for MP3/FLAC upload, SHA-256 mismatch, physical deduplication, playlist update, and download bytes.
- [ ] Run focused tests and verify file routes fail.
- [ ] Implement bounded streaming upload to a temporary file, hash verification, atomic hash storage, and READY Track insertion.
- [ ] Implement READY Track download and cleanup of temporary files.
- [ ] Re-run focused tests and commit `feat: add embedded host file transfer`.

### Task 5: Foreground Service And Host Controller

**Files:**
- Create: `android-app/app/src/main/java/com/synclisten/app/host/HostServerService.kt`
- Create: `android-app/app/src/main/java/com/synclisten/app/host/AndroidHostServerController.kt`
- Modify: `android-app/app/src/main/java/com/synclisten/app/data/DataModule.kt`
- Test: `android-app/app/src/test/java/com/synclisten/app/host/HostServerControllerTest.kt`

- [ ] Write failing controller tests for start success, no-address failure, duplicate start, and stop.
- [ ] Run focused tests and verify controller behavior is absent.
- [ ] Implement controller state transitions and injectable runtime launcher.
- [ ] Implement foreground service notification, Wi-Fi lock, CIO startup/shutdown, and Hilt bindings.
- [ ] Run focused and full unit tests, then commit `feat: run embedded host foreground service`.

### Task 6: Create/Join UI And Invitation Routing

**Files:**
- Modify: `android-app/app/src/main/java/com/synclisten/app/ui/HomeController.kt`
- Modify: `android-app/app/src/main/java/com/synclisten/app/ui/HomeViewModel.kt`
- Modify: `android-app/app/src/main/java/com/synclisten/app/ui/RoomViewModel.kt`
- Modify: `android-app/app/src/main/java/com/synclisten/app/ui/InviteViewModel.kt`
- Modify: `android-app/app/src/main/java/com/synclisten/app/ui/SyncListenApp.kt`
- Test: `android-app/app/src/test/java/com/synclisten/app/ui/HomeControllerTest.kt`

- [ ] Write failing tests proving mobile-host creation starts Host mode, switches client to loopback, exposes advertised URL, and rolls back on failure.
- [ ] Run focused tests and verify Host creation is absent.
- [ ] Implement separate mobile-host/external-server create actions, manual Host-address join, Host status display, invitation advertised URL, and stop-on-Host-leave.
- [ ] Re-run focused tests and commit `feat: connect mobile host user flow`.

### Task 7: BLE Reachable Host Invitation

**Files:**
- Modify: `android-app/app/src/main/java/com/synclisten/app/nearby/BleRoomDiscovery.kt`
- Modify: `android-app/app/src/main/java/com/synclisten/app/ui/HomeViewModel.kt`
- Modify: `android-app/app/src/main/java/com/synclisten/app/ui/InviteViewModel.kt`
- Modify: `android-app/app/src/main/java/com/synclisten/app/ui/SyncListenApp.kt`
- Test: `android-app/app/src/test/java/com/synclisten/app/nearby/BleRoomDiscoveryTest.kt`

- [ ] Replace the existing codec test with failing tests for the 13-byte versioned IPv4/port/room-code payload and legacy 6-byte decode.
- [ ] Run focused tests and verify the new payload behavior fails.
- [ ] Implement `BleInvite`, versioned codec, advertised URL scanning, and UI join routing.
- [ ] Re-run focused tests and commit `feat: advertise reachable host over ble`.

### Task 8: Documentation, Regression, And Two-Device Acceptance

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/api.md`
- Modify: `docs/debugging.md`
- Modify: `docs/known-issues.md`
- Modify: `docs/test-report.md`
- Modify: `TASKS.md`
- Modify: `EXECUTION_LOG.md`

- [ ] Run backend `npm run lint`, `npm run typecheck`, `npm test`, and `npm run build`.
- [ ] Run Android `clean testDebugUnitTest lintDebug assembleDebug`.
- [ ] Stop the computer backend and install the APK on `SyncListen_A` and `SyncListen_B`.
- [ ] Verify A hosts; B joins through A; upload/download/hash, playback controls, sync, reconnect, and Host stop all work.
- [ ] Record measured evidence and limitations, mark T301-T306 complete, and commit `test: complete android host server acceptance`.
