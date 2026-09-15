# Changelog

## 0.3.0 - 2026-09-16 (Preview)

### Security and Compatibility

- Bind room membership to an installation credential scoped to the server origin. Authenticate REST, downloads and WebSocket handshakes; prevent actor spoofing.
- Enforce Host/Admin/Member permissions on the server. Protect the selected track and validate exact playlist permutations.
- Narrow Android APK sharing to a dedicated cache directory, disable identity backup, and omit HTTP body logs.
- Introduce independent Android release signing. Upgrade all clients and recreate legacy unauthenticated rooms; this protocol change is not backward compatible.

### Reliability

- Make Android host persistence transactional and roll back the in-memory mirror on failed commits. Restore rooms through real membership rejoin.
- Preserve paused state on seek, stop early SYNC before scheduled execution, and handle Ready/Ended transitions without ignoring pause.
- Restart completed tracks from the beginning; bound client seeks to decoded duration.
- Improve cancellation, WebSocket cleanup/retry, local-only network handling, upload byte limits and cache verification.
- Prevent starting a batch before all selected files have been inspected.
- Rebuild desktop audio around reusable per-session decoder/output lifecycles and verified streaming downloads.

### Experience and Engineering

- Retain and complete the existing Android/desktop UI work, invitation confirmation, role updates, server-edit guard and leave confirmation.
- Correct local-file/buffering diagnostics and use a bounded scrollable diagnostics panel.
- Remove nonfunctional cloud shortcut buttons; document the trusted-LAN deployment boundary.
- Expand the combined automated suites to 134 passing tests, three-platform CI configuration, review inventory, architecture diagram and engineering workbook.
- Stop tracking generated build output, add signing/packaging scripts, MIT license and third-party source notices.

See docs/test-report.md for actual verification and docs/known-issues.md for unverified hardware/background behavior. Test count is not code coverage or a claim of zero defects.
