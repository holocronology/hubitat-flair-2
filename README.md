# hubitat-flair-2

Hubitat Elevation integration for [Flair](https://flair.co/) smart vents and smart HVAC. Companion to [home-assistant-flair-2](https://github.com/holocronology/home-assistant-flair-2).

## Status

Pre-alpha. Foundation in place; drivers landing incrementally.

## Architecture

- **Parent app** (`apps/flair-connect.groovy`) — handles OAuth 2.0, structure discovery, polling, and all HTTP traffic. Children call back to the parent for API calls.
- **Child drivers** (`drivers/`) — one per Flair device type: Structure, Room, Puck (V1), Puck V2, Vent, HVAC Unit.

## Requirements

- Hubitat Elevation hub on a current firmware
- A Flair `client_id` / `client_secret` pair — request from Flair via [this form](https://forms.gle/VohiQjWNv9CAP2ASA)

## Install (manual)

1. **Apps Code** → New App → paste `apps/flair-connect.groovy` → Save
2. **Drivers Code** → New Driver → paste each file in `drivers/` → Save
3. **Apps** → Add User App → Flair Connect → enter credentials → Done

HPM packaging is not yet provided.

## Domain reference

See [`docs/FLAIR_DOMAIN_NOTES.md`](docs/FLAIR_DOMAIN_NOTES.md) for the field-tested notes on Flair API behavior, entity relationships, and gotchas this driver set was built against. Mirrored from the HA repo.

## License

MIT — see [`LICENSE`](LICENSE).
