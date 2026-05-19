# hubitat-flair-2

Hubitat Elevation integration for [Flair](https://flair.co/) — Structures, Rooms, Pucks (V1 and V2), and HVAC Units. Companion to [home-assistant-flair-2](https://github.com/holocronology/home-assistant-flair-2).

## Status

Beta. All read and write surfaces implemented for Structures, Rooms, Pucks (V1 and V2 as distinct device classes), and HVAC Units. Verified end-to-end against a real Flair account (1 structure, 7 rooms, 3 V1 pucks, 4 V2 pucks, 7 Mitsubishi mini-splits). HPM packaging not yet provided — install is manual paste-and-save.

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

## Requirements

- Hubitat Elevation hub on a current firmware
- A Flair `client_id` / `client_secret` pair — request from Flair via [this form](https://forms.gle/VohiQjWNv9CAP2ASA)

## Install (manual)

1. **Apps Code** → **+ New App** → paste [`apps/flair-connect.groovy`](apps/flair-connect.groovy) → **Save**
2. **Drivers Code** → **+ New Driver** → paste each of these (one driver per save):
   - [`drivers/flair-structure.groovy`](drivers/flair-structure.groovy)
   - [`drivers/flair-room.groovy`](drivers/flair-room.groovy)
   - [`drivers/flair-puck.groovy`](drivers/flair-puck.groovy)
   - [`drivers/flair-puck-v2.groovy`](drivers/flair-puck-v2.groovy)
   - [`drivers/flair-hvac-unit.groovy`](drivers/flair-hvac-unit.groovy)
3. **Apps** → **+ Add User App** → **Flair Climate Connect** → enter Client ID and Client Secret → **Done**

Within ~2 seconds you should see "OAuth token acquired", a structure discovery line, and one device per Flair entity (structure + N rooms + N pucks + N HVAC units) created in **Devices**.

## Architecture

- **Parent app** (`apps/flair-connect.groovy` → installed as **Flair Climate Connect**) — owns OAuth 2.0, structure discovery, polling, all HTTP traffic, and token lifecycle. Children call back to the parent for API access; they do not make HTTP requests of their own.
- **Polling** — configurable interval (default 30 s, range 15–600 s) and per-request timeout (default 20 s). On transient failures, children retain their previously pushed state rather than blanking to unavailable.
- **Write path** — driver commands call `parent.patchStructure(...)`, `parent.patchHvacUnit(...)`, or `parent.patchRoom(...)`. The PATCH response is parsed and pushed back to the child, so device tiles update within ~1 second of the click rather than waiting for the next poll.

## What you get

### Flair Structure (one per structure)

| Attribute | Meaning |
|---|---|
| `systemMode` | `auto` or `manual` — global system mode |
| `heatCoolMode` | `off`/`heat`/`cool`/`auto` — auto-mode target (the API value `float` is rendered as `off`) |
| `setPoint` | Auto-mode setpoint, in your hub's preferred unit |
| `setPointController` | `Thermostat` or `Flair App` — who's allowed to set the structure setpoint |
| `awayMode` | `Smart Away` or `Off Only` — away-mode policy |
| `homeAway` | `Home` or `Away` — actual presence state (from the `home` boolean) |
| `homeAwaySetBy` | `Manual`/`Thermostat`/`Flair App Geolocation` — who's allowed to change presence |

**Commands:** `setSystemMode`, `setHeatCoolMode`, `setSetPoint`, `setAwayMode`, `setHome`, `setAway`, `setHomeAwaySetBy`.

`setSetPoint` refuses with a clear log error when `setPointController` is `Thermostat`, because the Flair API silently no-ops setpoint writes in that mode.

### Flair Room (one per room)

Implements `TemperatureMeasurement`, `RelativeHumidityMeasurement`, `Refresh`, `Sensor`. Plus `setPoint` (room target, in hub's preferred unit), `active` (Active/Inactive — auto-mode participation), and the obvious IDs.

### Flair Puck (V1)

Implements `TemperatureMeasurement`, `RelativeHumidityMeasurement`, `IlluminanceMeasurement`, `SignalStrength`, `PressureMeasurement`, `Refresh`, `Sensor`. Plus `voltage`, `inactive`.

### Flair Puck V2

Same sensor capabilities as V1 except **no `IlluminanceMeasurement` and no `PressureMeasurement`** — the V2 hardware lacks those physical sensors. Adds V2-specific metadata: `displayColor` (Black/White), `temperatureScale`, `setpointBoundLow`/`setpointBoundHigh`, `temperatureOffset` (calibration), `locked`, `connectedGateway`.

### Flair HVAC Unit (one per Flair-managed mini-split / HVAC unit)

Implements `TemperatureMeasurement` (target setpoint), `Switch` (power on/off), `Refresh`, `Sensor`. Plus:

| Attribute | Meaning |
|---|---|
| `power`, `rawMode` | Raw API values |
| `displayedMode`, `displayedAction` | Computed via the FLAIR_DOMAIN_NOTES §4.4 decision tree — what the Flair app actually shows. Hides stale "Heat" cached state when the unit is powered off or the structure is in auto-off |
| `fanSpeed`, `swing` | Current fan / swing settings |
| `availableModes`, `availableFanSpeeds` | Comma-separated, from the unit's `constraints` matrix |
| `manufacturer`, `temperatureScaleNative` | Make name and the unit's native scale (F/C/K) |
| `puckId`, `puckType` | The paired Puck (V1 or V2 — resolved via §5 fallback) |

**Commands:** `on`, `off`, `setHvacMode`, `setFanSpeed`, `setSwingMode`, `setTargetTemperature`.

Writes encode the auto/manual split (§4.5) and the FAN AUTO interaction matrix automatically. In auto mode, the structure controls the HVAC unit's mode globally; `setHvacMode` on a unit refuses with a pointer to the Structure driver.

## Automation recipe ideas

A few patterns these devices unlock that are awkward without first-class Structure / HVAC Unit / Room entities:

### Presence-driven away mode

When everyone leaves, flip the structure to Away; when someone arrives, flip Home.

- **Trigger:** All presence sensors → "not present"
- **Action:** `Flair: FuzzHouse` → `setAway`
- Reverse rule with `setHome` on first arrival

### Bedtime cool-down on a specific room

At a fixed time each night, push the master bedroom setpoint down 2°F.

- **Trigger:** Time of day = 22:00
- **Action:** Set `Flair Room: Master Bedroom`'s `setPoint` to 66.

### Mini-split mode follows structure

If you flip the structure heat-cool mode (off / heat / cool / auto) on the Structure tile, every HVAC unit's `displayedMode` follows automatically — no per-unit rule needed. This is the §4.4 decision tree at work.

### Switch the heating source by season

Use a virtual switch + Rule Machine to call `Flair Structure → setHeatCoolMode heat` in winter and `cool` in summer, and `off` when neither's needed.

## Polling, resilience, and diagnostics

- 30 s default poll cadence (15–600 s configurable from the parent app).
- Per-request timeout 20 s default (5–120 s configurable).
- Transient failures retain the previously pushed state. You'll see log lines like:
  `warn Flair: pucks fetch failed for structure 69615 (status=408); retaining 3 cached puck child(ren)` — this is the resilience invariant doing its job, not a bug.
- Auth failures are fatal-until-reauth: the parent clears the token and surfaces an error directing you to re-enter credentials.
- Token is cached with proactive refresh ~60 s before expiry. Flair's current TTL is 10 days.

## Domain reference

[`docs/FLAIR_DOMAIN_NOTES.md`](docs/FLAIR_DOMAIN_NOTES.md) — field-tested notes on Flair API behavior, entity relationships, attribute key locations, and gotchas this driver set was built against. Mirrored from the HA repo with Hubitat-specific annotations:

- §6: V1 vs V2 hardware (V2 has no light/pressure sensor; uses `sub-ghz-rssi` for RSSI)
- §6.3: V2 current-reading HVAC fields are diagnostic, not authoritative
- §13 step 6: vents intentionally not covered; deferred to ljbotero's repo

## License

MIT — see [`LICENSE`](LICENSE).
