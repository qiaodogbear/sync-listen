# Project Working Notes

- Read TASKS.md and the top of EXECUTION_LOG.md before resuming. Record concrete verification and next steps there; do not duplicate progress checklists.
- Preserve unrelated local changes. Never commit audio, databases, local.properties, device credentials, signing material, or .audit-tmp.
- Android is a separate Gradle build. Root Gradle owns shared and desktop; Android does not depend on shared.
- Use PowerShell 7 on Windows. Build from an ASCII path (for this machine, S: maps to this repository) when Gradle tests fail to load classes in a Unicode path.
- Use JDK 21. Desktop packaging requires a full JDK with jpackage; this machine uses Eclipse Adoptium jdk-21.0.11.10-hotspot. Android Studio JBR is also available for Android.
- Android validation: in android-app, run .\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon.
- Desktop validation: at root, run .\gradlew.bat :shared:test :desktop:test :desktop:createDistributable --no-daemon.
- Backend validation: in backend, run npm ci, npm run lint, npm run typecheck, npm test, npm run build, npm audit --audit-level=high. Node >=22.13.0 is required.
- Release signing: scripts/build-release.ps1; private keys are outside the repo at $HOME/.synclisten/signing. Do not initialize or replace an existing signing key. Never log passwords.
- On this 14 GB host, avoid running two emulators and memory-intensive Gradle/R8 work simultaneously. Use hidden Start-Process windows for background helpers.
- Phone hosting is trusted-LAN only. Keep device authentication and service-side role checks on every protected route, including downloads and WebSocket handshakes.
- Test Node/Ktor protocol changes on both implementations. A passed build is not a device, audio, BLE, NFC, or long-running background test.
- See docs/debugging.md for exact tool paths, AVD, adb, deployment and recovery commands.
