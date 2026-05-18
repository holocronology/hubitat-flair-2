/**
 *  Flair Puck V2 (read-only)
 *
 *  Surfaces a Flair V2 Puck as a multi-sensor in Hubitat. State is pushed
 *  from the Flair Connect parent app on every poll; this driver makes no
 *  HTTP calls of its own.
 *
 *  V2 pucks differ from V1 in two important ways (see FLAIR_DOMAIN_NOTES.md
 *  §5 and §6):
 *
 *  1. The parent fetches V2s from a separate endpoint
 *     (/structures/{id}/puck2s) than V1s, and then per-active-V2 fetches
 *     /puck2s/{id}/current-reading for the live sensor values. Metadata
 *     (display color, scale, bounds, calibration, locked) comes from the
 *     first response; live values arrive in a second push.
 *
 *  2. Several V2 attributes are optional — they're present only on devices
 *     that support that feature. The updateState() method drops null
 *     values rather than emitting blank events.
 *
 *  3. V2 hardware lacks the light and pressure sensors that V1 carries —
 *     IlluminanceMeasurement and PressureMeasurement are intentionally not
 *     declared. RSSI uses sub-ghz-rssi from current-reading rather than
 *     current-rssi on the list response.
 *
 *  4. Although the V2's current-reading carries HVAC IR-signaling state
 *     (mode-status, fan-speed-status, ir-device-set-point, etc.), that is
 *     diagnostic — it reflects what the puck is currently transmitting,
 *     not the authoritative HVAC state. Each puck is paired to one or more
 *     hvac-units entities in Flair, and *those* are the controllable HVAC
 *     objects (surfaced via the Flair HVAC Unit driver in a later phase).
 *     The V2 driver here is sensor-only.
 *
 *  Licensed under the MIT License. See LICENSE in the repo root.
 */

metadata {
    definition(
        name:      "Flair Puck V2",
        namespace: "holocronology",
        author:    "holocronology",
        importUrl: "https://raw.githubusercontent.com/holocronology/hubitat-flair-2/main/drivers/flair-puck-v2.groovy"
    ) {
        // V2 hardware lacks light and pressure sensors that V1 carries.
        // IlluminanceMeasurement and PressureMeasurement are intentionally
        // not declared.
        capability "Refresh"
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "SignalStrength"

        attribute "puckId",                   "string"
        attribute "structureId",              "string"
        attribute "voltage",                  "number"
        attribute "inactive",                 "string"

        // V2-only metadata (optional per device — guard with null checks)
        attribute "displayColor",             "string"   // Black | White
        attribute "temperatureScale",         "string"   // F | C | K
        attribute "setpointBoundLow",         "number"   // °C
        attribute "setpointBoundHigh",        "number"   // °C
        attribute "temperatureOffset",        "number"   // °C calibration
        attribute "locked",                   "string"   // true | false
        attribute "connectedGateway",         "string"   // paired bridge name

        attribute "lastUpdate",               "string"
    }

    preferences {
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() { }
def updated()   { }

def refresh()   { parent?.poll() }

/**
 * Called by the parent. Two distinct shapes land here over time:
 *
 *   - metadata pass:  keys include puckId, structureId, displayColor,
 *                     temperatureScale, setpointBoundLow/High,
 *                     temperatureOffset, locked, inactive
 *   - readings pass:  keys include temperatureC, humidity, light,
 *                     voltage, rssi, pressure
 *
 * Either may be called with any subset; only non-null values are pushed.
 * This keeps the "retain previous reading on transient failure" invariant
 * (FLAIR_DOMAIN_NOTES §9) intact — if a current-reading fetch fails the
 * parent simply doesn't call this with the readings keys, and the
 * previous sendEvent values persist in Hubitat.
 */
def updateState(Map data) {
    def changes = [:]

    // Readings pass
    if (data.temperatureC != null) {
        def t = convertC(data.temperatureC as BigDecimal)
        changes["temperature"] = [value: t, unit: "°${location.temperatureScale}"]
    }
    if (data.humidity != null)   changes["humidity"]    = [value: data.humidity, unit: "%"]
    if (data.light != null)      changes["illuminance"] = [value: data.light, unit: "lux"]
    if (data.rssi != null)       changes["rssi"]        = [value: data.rssi, unit: "dBm"]
    if (data.voltage != null)    changes["voltage"]     = [value: data.voltage, unit: "V"]
    if (data.pressure != null)   changes["pressure"]    = [value: data.pressure, unit: "kPa"]

    // Metadata pass
    if (data.inactive != null)            changes["inactive"]           = [value: data.inactive.toString()]
    if (data.puckId != null)              changes["puckId"]             = [value: data.puckId]
    if (data.structureId != null)         changes["structureId"]        = [value: data.structureId]
    if (data.displayColor != null)        changes["displayColor"]       = [value: data.displayColor]
    if (data.temperatureScale != null)    changes["temperatureScale"]   = [value: data.temperatureScale]
    if (data.setpointBoundLow != null)    changes["setpointBoundLow"]   = [value: data.setpointBoundLow]
    if (data.setpointBoundHigh != null)   changes["setpointBoundHigh"]  = [value: data.setpointBoundHigh]
    if (data.temperatureOffset != null)   changes["temperatureOffset"]  = [value: data.temperatureOffset]
    if (data.locked != null)              changes["locked"]             = [value: data.locked.toString()]
    if (data.connectedGateway != null)    changes["connectedGateway"]   = [value: data.connectedGateway]

    changes.each { name, evt ->
        def current = device.currentValue(name)
        if (current?.toString() != evt.value?.toString()) {
            sendEvent(evt + [name: name])
            if (debugLog) log.debug "Flair Puck V2 ${device.label}: ${name}=${evt.value}"
        }
    }
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

private BigDecimal convertC(BigDecimal value) {
    if (value == null) return null
    if (location.temperatureScale == "F") {
        return (value * 9 / 5 + 32).setScale(1, java.math.RoundingMode.HALF_UP)
    }
    return value.setScale(2, java.math.RoundingMode.HALF_UP)
}
