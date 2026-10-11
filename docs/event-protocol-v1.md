# HapticScape Gameplay Event Protocol v1

This document defines the source-neutral gameplay event messages used at the
HapticScape integration boundary. The protocol describes observations only. It
does not expose gameplay actions, device control, Remote Play, or HapticScape
settings.

## Envelope

Every message is a single JSON object:

```json
{
  "protocol": "hapticscape-event",
  "version": 1,
  "source": "runelite",
  "type": "xp",
  "payload": {}
}
```

- `protocol` is always `hapticscape-event`.
- `version` is `1` for this document.
- `source` identifies the integration that observed the event.
- `type` selects one event schema below.
- `payload` contains only fields specific to that event.

Unknown protocols, unsupported versions, unknown event types, missing required
fields, and incorrectly typed fields are rejected. Version 1 messages are
limited to 65,536 characters before parsing.

## Event types

### `xp`

```json
{
  "skillId": "woodcutting",
  "previousXp": 1000,
  "currentXp": 1420,
  "gainedXp": 420,
  "previousLevel": 9,
  "currentLevel": 10
}
```

### `chat`

```json
{
  "kind": "direct_message",
  "rawMessage": "<col=ffffff>Hello</col>",
  "normalizedMessage": "Hello",
  "receivedPlayerMessage": true
}
```

`kind` is one of `direct_message`, `trade_request`, or `other`.

`receivedPlayerMessage` identifies incoming player chat. LumBridge excludes outgoing and self-authored public, friends, and clan messages, game messages, and messages with unknown sender identity. The field is optional for older bridges: incoming direct messages default to true, other kinds to false. Sender names are not included.

### `player_death`

The payload is currently empty.

```json
{}
```

### `vitals_changed`

```json
{
  "kind": "hitpoints",
  "currentValue": 17,
  "maximumValue": 99
}
```

`kind` is one of `hitpoints`, `prayer`, or `special_attack`.

### `inventory_changed`

```json
{
  "filledSlots": 28,
  "capacity": 28
}
```

### `toxic_status_changed`

```json
{
  "status": "venomed"
}
```

`status` is one of `clear`, `poisoned`, or `venomed`.

### `loot_received`

```json
{
  "stackCount": 2,
  "totalValue": 1500000
}
```

The source integration resolves game-specific value facts. HapticScape decides
whether the value crosses the user's configured alert threshold.

### `notification`

```json
{
  "sourceFocused": true,
  "sendWhenFocused": false
}
```

The source reports focus facts; HapticScape owns notification policy.

## Compatibility rules

- Existing field meanings do not change within protocol version 1.
- New optional payload fields may be ignored by older v1 consumers.
- Breaking field or semantic changes require a protocol version increment.
- Java class names, RuneLite classes, and Java serialization are not part of
  the wire contract.
- Transport is intentionally out of scope. Phase 3B will carry these messages
  over a loopback-only local transport.
