# Security

Sync Listen 0.3 is a preview for trusted groups, not a public anonymous file-hosting service.

- Membership is bound to a per-installation, per-server credential. Invitations only authorize joining.
- HTTP/WS on a local network is not encrypted. Use only trusted Wi-Fi/hotspots.
- Internet-facing deployments require HTTPS/WSS, reverse-proxy request limits, access controls,
  disk quotas, monitoring and backups. The application does not provide all of these controls.
- Never publish device secrets, Authorization headers, invitation tokens, private audio or signing keys.
- Report vulnerabilities privately through GitHub's Security tab when private reporting is available;
  do not include exploitable details or credentials in public issues. This project has no response-time SLA.

Android APK signatures establish publisher continuity, not a guarantee that the software is bug-free.
The Windows preview is not Authenticode-signed.
