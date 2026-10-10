# Phase 4: PipeWire application capture

Linux Application audio now enumerates playback streams and captures the selected
application's PCM. It needs Java 11+, pw-dump, pw-cat, pw-link, a user PipeWire
server and a compatible session manager. Startup checks required helper options.
The native checkpoint used PipeWire 1.6.9; an older minimum version is not established.
This is experimental Linux functionality, not a completed Linux release.

## Identity and grouping

Only Stream/Output/Audio nodes are eligible. Sinks, microphones and capture
streams are excluded. Application metadata comes from the node and its client,
with node properties taking precedence. The durable identity uses application.id
when provided, otherwise application.process.binary. It never persists PIDs,
node IDs or object serials. Case-sensitive UTF-8 identities are hex encoded under
pipewire:app:id: or pipewire:app:binary:, surviving the shared model's lowercase
normalization. Saved Windows selections do not silently select Linux sources.

All streams sharing the selected identity are grouped, including separate
processes. For executable fallback, conflicting application names make the group
ambiguous: it is excluded from discovery and rejected if already selected.
Display names and PIDs are not used to follow a selection across restarts.
The session manager supplies these identities; applications without stable
metadata or usable audio output ports are not discoverable. Multiple instances
with the same advertised identity are deliberately one application group.

## Capture and lifecycle

A pw-cat recording node uses target=0 and node.autoconnect=false. Explicit taps
connect only selected application output ports to matching capture input channels;
the application's existing playback links are not moved or removed. Every added
link belongs to an owned pw-link --monitor process, with object.linger=false.
Closing capture kills those helpers and the recording node. No disconnect command
targets the user's original playback links.

Capture negotiates 48 kHz native-endian float32 using the union of the selected
streams' exposed channel positions. Matching channels from multiple streams mix
in PipeWire; Java averages the negotiated channels for the analyzer, sanitizing
nonfinite values and clamping each input channel. Mono, stereo and common surround
positions are recognized. Other positions fail visibly instead of dropping channels.
Groups are bounded to 32 streams and 64 ports. Channel averaging is not a weighted
surround downmix; per-app volume/mute and uncommon channel layouts need acceptance.
No second output-volume multiplier is applied.

Before PCM delivery, fresh graph snapshots must show all expected links and no
unexpected or duplicate input links. While linked, snapshots are checked roughly
every 250 ms plus query time; this is not instantaneous graph-change detection.
Unexpected sources stop capture. Changing stream IDs, serials, ports or channels
restarts the owned capture graph and clears the old analyzer signal with silence.
No whole-output or microphone fallback exists.

An application absent at startup fails visibly. During capture, disappearance
immediately clears the signal and waits for the same identity to return. Paused
playback with no PCM for 150 ms also sends explicit zero output; it remains ready
to resume, including pauses longer than four seconds. Idle/suspended streams and
exactly silent PCM are handled similarly. Muting the default system output or all
reachable playback sinks stops application haptics; unmuting resumes capture.
Mute/state changes follow the graph polling interval plus query time. This is a
mute gate, not a second volume multiplier. Newly created
streams within the same group are followed automatically. Helpers, graph output,
PCM queues and startup waits are bounded as in the output backend. Close never
waits for a listener or helper under the Music Sync lock. Daemon/session failures,
blocked port access and suspend/resume can require a visible restart.

## Verification

Run ordinary fixtures and artifact checks:

    ./gradlew test --offline

Fixtures cover client metadata, multi-stream grouping, case-safe persistence,
changed IDs/serials, ambiguity, missing/foreign selections, exact channel links,
unexpected/duplicate links, negotiated PCM, owned link commands, failed helpers,
stream restart, app exit/recovery, extended pause/resume, default mute/unmute and cancellation during a
blocked listener.

The opt-in native test plays two generated streams for one identity (stereo and
mono input) plus another application's signal into the same disposable virtual
output. It uses the production capture backend, verifies both selected signals,
excludes the other signal, and uses an output monitor to prove all three still
reach playback. It then restarts a selected stream, checks isolation again, and
verifies original playback links and capture-node cleanup.

    ./gradlew verifyPipeWireApplication --offline

The test also needs pw-cli. It does not record files, capture microphones, move
desktop playback or change the default output. It is not part of ordinary tests.
Native checkpoint on 2026-10-06: selected 440 Hz and 660 Hz amplitudes were each
approximately 0.1000000004; the other application's 997 Hz measured approximately
8.4e-17. A selected stream restart preserved capture and isolation. The virtual
output monitor received all three signals; original playback links survived
capture startup and cleanup, and owned capture nodes were removed.
The live test also muted the disposable playback sink: haptic output went to zero
and unmute resumed isolated PCM. During KDE testing on 2026-10-06, the user
confirmed that the corrected pause/resume and system-mute behavior worked in the
audio-test profile. Broader application/device acceptance below remains pending.

## Native acceptance still pending

- Capture browser and player audio concurrently; select each in turn and verify
  other apps and system notifications are excluded.
- Exercise multiple tabs/processes and applications without application.id,
  including ambiguous executable metadata and saved selections after relaunch.
- Pause/resume, add/remove streams, restart the app, switch its output, and verify
  the other apps remain audible. Test a long pause and app exit/relaunch.
- Check per-app volume/mute, physical mono/multichannel routes, Bluetooth and
  suspend/resume. Repeat mode/app changes and check for orphan helpers/links.
- Run actual Windows WASAPI application capture separately.

Keep running applications on an immutable JAR copy before rebuilding the project;
replacing their JAR can cause class-loading failures in an existing JVM.

References: [pw-link](https://docs.pipewire.org/page_man_pw-link_1.html),
[pw-cat](https://docs.pipewire.org/page_man_pw-cat_1.html),
[PipeWire properties](https://docs.pipewire.org/page_man_pipewire-props_7.html).
