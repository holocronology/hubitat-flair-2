# hubitat-flair-2

Hubitat Elevation integration for [Flair](https://flair.co/) — Structures, Rooms, Pucks (V1 and V2), and HVAC Units. Companion to [home-assistant-flair-2](https://github.com/holocronology/home-assistant-flair-2).

## Scope and complementary integrations

This integration covers the **temperature-control side** of Flair: structures, rooms, the sensor pucks (V1 and V2 as distinct device classes), and HVAC units controlled through the pucks' IR.

**Vents are intentionally out of scope.** [ljbotero/hubitat-flair-vents](https://github.com/ljbotero/hubitat-flair-vents) already covers vent control thoroughly. The two integrations are designed to coexist — install both if you have all Flair hardware:

| Hardware | This integration | ljbotero/hubitat-flair-vents |
|---|---|---|
| Structures | ✅ | — |
| Rooms (as first-class entities) | ✅ | (room state is fused into the puck device there) |
| Pucks (V1) | ✅ (separate `Flair Puck` driver) | ✅ (single `Flair pucks` driver) |
| Pucks (V2) | ✅ (separate `Flair Puck V2` driver) | ✅ (same `Flair pucks` driver) |
| HVAC Units | ✅ | — |
| Vents | — | ✅ |

> [!WARNING]
> If you install both integrations against the same Flair account, **install only one set of Puck drivers**. Running both this integration's `Flair Puck` / `Flair Puck V2` drivers AND ljbotero's `Flair pucks` driver will create duplicate puck devices in Hubitat for the same hardware. Pick one: ours (separate V1/V2 surfaces, room as its own entity) or ljbotero's (single driver, room state fused in). Vent users add ljbotero's app on top either way.

## Status

Pre-alpha. Phases 1–5 (Structures, Rooms, Pucks V1+V2, HVAC Units, read-only) verified against real hardware. Phase 6+ adds write surfaces.

## Architecture

- **Parent app** (`apps/flair-connect.groovy` → installed as **Flair Climate Connect**) — handles OAuth 2.0, structure discovery, polling, and all HTTP traffic. Children call back to the parent for API calls; they do not make HTTP requests of their own.
- **Child drivers** (`drivers/`):
  - `flair-structure.groovy` → Flair Structure (system mode, structure setpoint, home/away)
  - `flair-room.groovy` → Flair Room (temperature, humidity, room setpoint)
  - `flair-puck.groovy` → Flair Puck (V1 — temperature, humidity, light, RSSI, voltage, pressure)
  - `flair-puck-v2.groovy` → Flair Puck V2 (same minus light/pressure; V2 hardware lacks those sensors)
  - `flair-hvac-unit.groovy` → Flair HVAC Unit (mode, fan, swing, setpoint; §4.4 decision tree applied for displayed state)

## Requirements

- Hubitat Elevation hub on a current firmware
- A Flair `client_id` / `client_secret` pair — request from Flair via [this form](https://forms.gle/VohiQjWNv9CAP2ASA)

## Install (manual)

1. **Apps Code** → New App → paste `apps/flair-connect.groovy` → Save
2. **Drivers Code** → New Driver → paste each file in `drivers/` → Save (one per file)
3. **Apps** → Add User App → **Flair Climate Connect** → enter credentials → Done

HPM packaging is not yet provided.

## Domain reference

See [`docs/FLAIR_DOMAIN_NOTES.md`](docs/FLAIR_DOMAIN_NOTES.md) for the field-tested notes on Flair API behavior, entity relationships, and gotchas this driver set was built against. Mirrored from the HA repo with Hubitat-specific annotations added (see §6 for the V1 vs V2 hardware distinction and §13 for the Hubitat-specific build order).

## License

MIT — see [`LICENSE`](LICENSE).
