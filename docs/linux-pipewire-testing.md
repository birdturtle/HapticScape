# Phase 3: PipeWire output capture

Linux output-mode Music Sync now uses `pw-dump` and `pw-cat` helper processes.
Application-only capture is implemented in [Phase 4](linux-application-capture-testing.md). This is an experimental source
backend, not a declaration that the Linux release or all desktop integrations are
supported.

## Runtime behavior

Install Java 11+, PipeWire tools (`pw-dump`, `pw-cat`), a running user PipeWire
server, and a compatible session manager such as WirePlumber. Helpers are looked
up on PATH and invoked with argument arrays, never through a shell. Startup checks
the required `pw-cat` options. The live signal check passed with PipeWire 1.6.9;
an older minimum supported version has not yet been established.

The catalog lists `Audio/Sink` nodes and a **Default system output** entry.
Default capture resolves `default.audio.sink` from the default metadata, never
generic capture auto-selection. Persisted output identities encode case-sensitive
`node.name` values under `pipewire:sink:`. Live `object.serial` values are used
only to open the current node. Missing, foreign or ambiguous selections fail
visibly rather than selecting another output.

Capture requests sink-monitor PCM at 48 kHz, interleaved native-endian float32,
stereo FL/FR. PipeWire negotiates/resamples/remixes into this format; Java averages
the two channels for the shared analyzer and sanitizes nonfinite samples. PCM
stays in bounded memory buffers and is never written to disk. Gain is passed to
the analyzer as 1.0, avoiding a second application of gain already in the monitor.
Actual hardware output-volume and mute behavior still require acceptance.
Sink mute is explicitly passed as zero gain, so monitor audio cannot keep haptics
active while that output is muted.

The stream forbids session-manager fallback, external target moves and automatic
reconnection. Its actual graph links are verified against the resolved sink
before PCM is delivered and rechecked during capture. An unexpected input source
stops capture. A target change restarts the owned stream deliberately and flushes
the old signal with silence. Default changes and node serial changes are observed
through fresh snapshots roughly every 500 ms, plus graph-query time; this is not
instantaneous switching.

A selected output that disappears reports an error: reconnect it and restart
Music Sync. If the default output disappears with no replacement, or the daemon
stops, capture also reports an actionable error. Suspend/resume may require
restarting Music Sync. A stream that has no linked PCM for four seconds stops
instead of waiting indefinitely. A helper query is bounded to 2.5 seconds.
Discovery output is capped at 4 MiB, stderr is drained with bounded retained text,
and close kills/reaps owned processes asynchronously without waiting under the
Music Sync lock. No application playback links are moved.

## Verification

```bash
./gradlew test verifyLinuxTray verifyLinuxKeyringBindings --offline
```

Fixtures cover platform routing, stable identities despite changed IDs/serials,
default metadata formats, microphones excluded from outputs, missing/ambiguous
targets, wrong routing, default changes, selected-output disappearance, helper
errors, fragmented float PCM/channel conversion, malformed frames, bounded
discovery and cleanup without waiting for a callback.

An explicit live test creates two low-priority disposable virtual sinks, plays
different generated signals into each and captures one using the production
backend. It does not capture microphones, record files or change desktop defaults:

```bash
./gradlew verifyPipeWireOutput --offline
```

This task also needs `pw-cli` and access to the live PipeWire session. It is not
part of the ordinary test task. Native checkpoint on 2026-10-06: selected 440 Hz
signal amplitude was approximately 0.1000000003; the 997 Hz signal on the other
output measured approximately 1.7e-17. Both disposable outputs were removed.
This verifies selected-output PCM and exclusion of a separate sink, not
application isolation within one sink or all physical-device behavior.

## KDE acceptance still pending

- Enable Music Sync on the real default output while playing a known signal.
- Change the default output during capture and verify the source changes.
- Select a physical output; unplug/reconnect it and check the visible failure
  and explicit restart behavior.
- Exercise mono/multichannel outputs, output volume/mute, suspend/resume and
  repeated switching. Playback must remain audible and helpers must not remain
  after disabling Music Sync.
- Verify actual WASAPI behavior on Windows separately; Linux tests do not invoke
  the Windows APIs.

References: [pw-cat](https://docs.pipewire.org/page_man_pw-cat_1.html),
[pw-dump](https://docs.pipewire.org/page_man_pw-dump_1.html),
[PipeWire stream properties](https://docs.pipewire.org/group__pw__keys.html),
[WirePlumber linking policy](https://pipewire.pages.freedesktop.org/wireplumber/policies/linking.html).
