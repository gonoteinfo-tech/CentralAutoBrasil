# Screen Mirroring Implementation Plan

> **For agentic workers:** Use the task interfaces below. Implement shared components and platform components in isolated files, then integrate and review the complete change.

**Goal:** Phone screen and permitted playback audio streamed over local Wi-Fi to a separate Android receiver, with selectable 1080p/720p.

**Architecture:** Native Android capture and decoding over a pinned TLS connection. A small Java module owns bounded framing; sender and receiver share it. Receiver runs directly on the head unit, outside Android Auto.

**Tech Stack:** Java17, Android SDK36, MediaProjection, MediaCodec AVC, AudioPlaybackCapture, AudioTrack, AndroidKeyStore TLS. Sender minSdk28 (audio29+); receiver minSdk23.

**Spec:** ../specs/2026-10-18-screen-mirroring-design.md

## Global Constraints

- Preserve radios, favorites and browser behavior; no remote touch or protected-content bypass.
- TLS certificate fingerprint confirmed before capture; six-digit code within TLS; private IPv4 only.
- Capture permission per session, foreground notification and complete resource cleanup.
- Maximum protocol payload2MiB; no unbounded queues or silent fallback from1080p.
- No claim of physical Roadstar compatibility or end-to-end performance without device evidence.

## Review Focus

- Truncated/oversized frames must close the connection without large allocation.
- Revoked capture, denied audio, screen lock and stopped receiver must release resources.
- Reconnection must not reuse a MediaProjection token or stale codec/socket.
- Encoder/decoder lacking1080p must report that limitation with720p available.
- Portrait capture must preserve its aspect ratio; receiver rendering must avoid stretching.

### Task1: Shared protocol and checks

- [x] Create `mirror-core` Java module and `MirrorWire` with strict frame validation and handshake.
- [x] Run real JVM checks for invalid magic/code, lengths, types, truncation, format and round-trip.
- [x] Expose constants, Frame and VideoFormat plus read/write methods consumed by both apps.

### Task2: Receiver APK

- [x] Create `receiver` Android app, native Activity/SurfaceView, TLS listener and AndroidKeyStore certificate.
- [x] Show private IP, six-digit code and certificate fingerprint; accept a single authenticated sender.
- [x] Decode AVC onto a proportion-preserving surface and play bounded PCM audio queues.
- [x] Close sockets/decoders/audio/threads when Activity stops or connection fails.
- [x] Compile and lint against minSdk23; cover malformed peer behavior through shared JVM checks.

### Task3: Phone sender

- [x] Add `MirrorActivity` for endpoint, code, fingerprint confirmation, resolution and optional internal audio.
- [x] Add `MirrorService` for consented capture, AVC encoder and PCM playback audio capture.
- [x] Use pinned TLS and bounded frames; visible foreground notification with stop action and error status.
- [x] Pause radio/browser when starting; release capture and network resources on every stop/error path.
- [x] Compile/lint and check unsupported encoder, denied/revoked permissions and stale session paths statically.

### Task4: Integration and delivery

- [x] Link Espelhamento from existing home; update manifest, module dependencies and version0.2.0.
- [x] Build/test/lint sender+receiver in GitHub Actions and verify both APK signatures.
- [x] Independent review of protocol/capture/receiver; correct concrete findings and rerun affected checks.
- [x] Document installation, Wi-Fi pairing,1080p limitations, protected capture and unexecuted device tests.
- [x] Upload code and deliver both APKs with honest build/device verification evidence.

## Delivery evidence

- Final code commit: f04795053d9dd9812bf3e1028dc158e71ebb70e8.
- GitHub Actions 39131858649: build and Android emulator jobs succeeded; receiver2/sender1 instrumentation tests passed.
- Both0.2.0 debug APK signatures verified; artifact12137718543 includes bothAPK files and checksums.
- Revisions fixed FORMAT/audio ordering, pairing throttling, AndroidKeyStore TLS permissions, UI-thread TLS cleanup and rapid bind restart.
- Synthetic frame timestamps follow actual render time; receiver delay/queue limits unchanged.
- Physical Android17/Roadstar compatibility, sustained frame rate and perceptual AV sync remain unexecuted and documented.
