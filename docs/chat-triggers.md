# Chat triggers

Implementation branch: `feature/phrase-triggers`, based on `feature/level-99-desktop-overlay`.

- Incoming player chat only, including private, public, friends, and clan chat.
- Own messages, game messages, and unknown-origin messages do not match.
- New triggers match whole words or phrases, ignoring case and repeated whitespace.
- A trigger can play clicks, haptics, or both. Haptics use the existing pattern library, intensity, and duration controls.
- The first enabled matching trigger wins. Matching messages have a one-second cooldown per trigger.
- Trigger feedback takes precedence over the direct-message alert for each configured output type.
- Existing rules retain their IDs, matching modes, click sequences, and Remote Play locks. Storage migrates from v1/v2/v3 to v4.

The vendored RuneLite bridge now includes incoming-player metadata. Preserve these local changes when syncing the bridge from its upstream source.

## Manual acceptance

1. Add `good job` with clicks and haptics. Receive that phrase from another player in public and private chat.
2. Send the phrase yourself in public, private, friends, and clan chat. No trigger should fire.
3. Receive `good jobs` or `ungood job`. Neither should match. `GOOD JOB!` should match.
4. Receive several matching messages within a second. Only the first should trigger.
5. Save a haptic-only trigger, restart, and check its settings and playback.
6. Check a saved legacy rule and a locked Remote Play rule. Their identity and restrictions should remain intact.

Automated coverage checks filtering, matching, migration, output precedence, wire compatibility, and cooldown timing. Live chat/device testing is still required.
