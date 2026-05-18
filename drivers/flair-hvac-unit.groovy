/**
 *  Flair HVAC Unit (read-only)
 *
 *  Surfaces a Flair-managed HVAC unit (mini-split or similar) controlled
 *  via an IR-blasting Puck. State is pushed from the Flair Connect parent
 *  app on every poll; this driver makes no HTTP calls of its own.
 *
 *  Write surfaces (set mode, fan, swing, target temperature, power) land
 *  in a later phase. The exposed `displayedMode` and `displayedAction`
 *  attributes implement the cross-entity decision tree from
 *  FLAIR_DOMAIN_NOTES §4.4 so the Hubitat tile matches what the Flair app
 *  shows even when the unit's own `mode` attribute is stale relative to
 *  the structure-level off state.
 *
 *  Per FLAIR_DOMAIN_NOTES §5 and §6, the HVAC unit (not the puck) is the
 *  authoritative controllable HVAC entity; the puck merely transmits IR.
 *
 *  Licensed under the MIT License. See LICENSE in the repo root.
 */

metadata {
    definition(
        name:      "Flair HVAC Unit",
        namespace: "holocronology",
        author:    "holocronology",
        importUrl: "https://raw.githubusercontent.com/holocronology/hubitat-flair-2/main/drivers/flair-hvac-unit.groovy"
    ) {
        capability "Refresh"
        capability "Sensor"
        capability "TemperatureMeasurement"   // exposes 'temperature' — the unit's target setpoint

        // Identity / relationships
        attribute "hvacUnitId",          "string"
        attribute "structureId",         "string"
        attribute "roomId",              "string"
        attribute "puckId",              "string"   // resolved owner (V1 or V2)
        attribute "puckType",            "string"   // V1 | V2 — see §5 gotcha
        attribute "manufacturer",        "string"   // make-name

        // Raw API state
        attribute "power",               "string"   // On | Off
        attribute "rawMode",             "string"   // Heat | Cool | Dry | Fan | Auto | Off

        // Derived state per §4.4 (what the Flair app would actually show)
        attribute "displayedMode",       "string"   // Heat | Cool | Dry | Fan | Auto | Off
        attribute "displayedAction",     "string"   // Heating | Cooling | Drying | Fan | Idle | Off

        // Setpoint / scale
        // 'temperature' (from TemperatureMeasurement) carries the target,
        // converted to the hub's preferred unit. The unit's native scale
        // (often F on US mini-splits) is exposed separately for diagnostics.
        attribute "temperatureScaleNative", "string"   // F | C | K

        // Fan / swing
        attribute "fanSpeed",            "string"   // Auto | High | Medium | Low
        attribute "swing",               "string"   // On | Off | Not Supported

        // Capability matrices
        attribute "availableModes",      "string"   // comma-separated, raw API values
        attribute "availableFanSpeeds",  "string"   // comma-separated, given current mode
        attribute "supportsSwing",       "string"   // true | false
        attribute "constraintsShape",    "string"   // dict | list (diagnostic — list = button-only)

        attribute "lastUpdate",          "string"
    }

    preferences {
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() { }
def updated()   { }
def refresh()   { parent?.poll() }

/**
 * Parent calls this on every poll. Expected keys (all optional; nulls are
 * silently skipped to keep the "retain previous on transient failure"
 * invariant intact):
 *
 *   hvacUnitId, structureId, roomId, puckId, puckType, manufacturer,
 *   power, rawMode, displayedMode, displayedAction,
 *   targetTemperatureHub (already in hub's unit), temperatureScaleNative,
 *   fanSpeed, swing, availableModes (List), availableFanSpeeds (List),
 *   supportsSwing, constraintsShape
 */
def updateState(Map data) {
    def changes = [:]

    if (data.targetTemperatureHub != null) {
        changes["temperature"] = [value: data.targetTemperatureHub, unit: "°${location.temperatureScale}"]
    }
    if (data.availableModes instanceof List)     changes["availableModes"]     = [value: data.availableModes.join(", ")]
    if (data.availableFanSpeeds instanceof List) changes["availableFanSpeeds"] = [value: data.availableFanSpeeds.join(", ")]

    [
        hvacUnitId:           data.hvacUnitId,
        structureId:          data.structureId,
        roomId:               data.roomId,
        puckId:               data.puckId,
        puckType:             data.puckType,
        manufacturer:         data.manufacturer,
        power:                data.power,
        rawMode:              data.rawMode,
        displayedMode:        data.displayedMode,
        displayedAction:      data.displayedAction,
        temperatureScaleNative: data.temperatureScaleNative,
        fanSpeed:             data.fanSpeed,
        swing:                data.swing,
        supportsSwing:        data.supportsSwing,
        constraintsShape:     data.constraintsShape,
    ].each { name, value ->
        if (value == null) return
        changes[name] = [value: value.toString()]
    }

    changes.each { name, evt ->
        def current = device.currentValue(name)
        if (current?.toString() != evt.value?.toString()) {
            sendEvent(evt + [name: name])
            if (debugLog) log.debug "Flair HVAC ${device.label}: ${name}=${evt.value}"
        }
    }
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}
