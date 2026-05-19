/**
 *  Flair Structure
 *
 *  Surfaces Flair structure-level state in Hubitat, and exposes commands
 *  for the writable structure attributes (system mode, heat/cool mode,
 *  setpoint, away policy, home/away presence, and who can change presence).
 *
 *  State is pushed from the Flair Climate Connect parent app on every poll
 *  and again on every write response. This driver makes no HTTP calls of
 *  its own — all writes go through `parent.patchStructure(...)`.
 *
 *  Notable guards (per FLAIR_DOMAIN_NOTES §3.3): setSetPoint refuses with
 *  a clear log error when the structure's set-point controller is
 *  "Thermostat", because the Flair API silently no-ops setpoint writes in
 *  that mode — better to surface the constraint than fake a success.
 *
 *  Licensed under the MIT License. See LICENSE in the repo root.
 */

metadata {
    definition(
        name:      "Flair Structure",
        namespace: "holocronology",
        author:    "holocronology",
        importUrl: "https://raw.githubusercontent.com/holocronology/hubitat-flair-2/main/drivers/flair-structure.groovy"
    ) {
        capability "Refresh"
        capability "Sensor"

        attribute "structureId",         "string"
        attribute "systemMode",          "string"   // auto | manual
        attribute "heatCoolMode",        "string"   // off | heat | cool | auto (off == API 'float')
        attribute "setPoint",            "number"   // emitted in hub's preferred unit
        attribute "setPointController",  "string"   // Thermostat | Flair App
        attribute "awayMode",            "string"   // Smart Away | Off Only
        attribute "homeAway",            "string"   // Home | Away (derived from 'home' bool)
        attribute "homeAwaySetBy",       "string"   // Manual | Thermostat | Flair App Geolocation
        attribute "lastUpdate",          "string"

        command "setSystemMode",       [[name: "mode*",        type: "ENUM",   constraints: ["auto", "manual"]]]
        command "setHeatCoolMode",     [[name: "mode*",        type: "ENUM",   constraints: ["off", "heat", "cool", "auto"]]]
        command "setSetPoint",         [[name: "temperature*", type: "NUMBER", description: "Target in hub's preferred unit"]]
        command "setAwayMode",         [[name: "policy*",      type: "ENUM",   constraints: ["Smart Away", "Off Only"]]]
        command "setHome"
        command "setAway"
        command "setHomeAwaySetBy",    [[name: "setter*",      type: "ENUM",   constraints: ["Manual", "Thermostat", "Flair App Geolocation"]]]
    }

    preferences {
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() { }
def updated()   { }

def refresh()   { parent?.poll() }

// ---------------------------------------------------------------------------
// Commands — translate friendly UI values to Flair's raw API enums and
// delegate the HTTP to the parent app.
// ---------------------------------------------------------------------------

def setSystemMode(String mode) {
    if (!(mode in ["auto", "manual"])) { log.error "setSystemMode: invalid '${mode}'"; return }
    parent?.patchStructure(device.currentValue("structureId"), [mode: mode])
}

def setHeatCoolMode(String mode) {
    // The API enum uses 'float' for off; the rest pass through.
    def api = (mode == "off") ? "float" : mode
    if (!(api in ["float", "heat", "cool", "auto"])) { log.error "setHeatCoolMode: invalid '${mode}'"; return }
    parent?.patchStructure(device.currentValue("structureId"), ["structure-heat-cool-mode": api])
}

def setSetPoint(BigDecimal temperature) {
    // §3.3 guard: when controller is "Thermostat", the API silently no-ops
    // structure setpoint writes. Refuse rather than fake success.
    if (device.currentValue("setPointController") == "Thermostat") {
        log.error "${device.label}: cannot set structure setpoint — controller is Thermostat. " +
                  "In the Flair app, switch set-point control to 'Flair App' first."
        return
    }
    if (temperature == null) { log.error "setSetPoint: temperature required"; return }
    def celsius = (location.temperatureScale == "F")
        ? ((temperature - 32) * 5 / 9)
        : temperature
    celsius = celsius.setScale(2, java.math.RoundingMode.HALF_UP)
    parent?.patchStructure(device.currentValue("structureId"), ["set-point-temperature-c": celsius])
}

def setAwayMode(String policy) {
    if (!(policy in ["Smart Away", "Off Only"])) { log.error "setAwayMode: invalid '${policy}'"; return }
    parent?.patchStructure(device.currentValue("structureId"), ["structure-away-mode": policy])
}

def setHome() { parent?.patchStructure(device.currentValue("structureId"), [home: true])  }
def setAway() { parent?.patchStructure(device.currentValue("structureId"), [home: false]) }

def setHomeAwaySetBy(String setter) {
    def api = [
        "Manual":                "Manual",
        "Thermostat":            "Third Party Home Away",
        "Flair App Geolocation": "Flair Autohome Autoaway",
    ][setter]
    if (!api) { log.error "setHomeAwaySetBy: invalid '${setter}'"; return }
    parent?.patchStructure(device.currentValue("structureId"), ["home-away-mode": api])
}

// ---------------------------------------------------------------------------
// State push from parent
// ---------------------------------------------------------------------------

/**
 * Parent calls this on every poll AND on every successful write response.
 * Only emits sendEvent when a value actually changes — keeps the event
 * stream quiet and preserves "retain previous on transient failure" (a
 * null in `data` is silently skipped, leaving the previous Hubitat value
 * intact).
 *
 * Expected input keys (all optional): structureId, systemMode,
 * heatCoolMode, setPointC, setPointController, awayMode, homeAway,
 * homeAwaySetBy.
 */
def updateState(Map data) {
    def changes = [:]
    // Convert API Celsius setpoint to the hub's preferred unit for display.
    if (data.setPointC != null) {
        def sp = convertC(data.setPointC as BigDecimal)
        changes["setPoint"] = [value: sp, unit: "°${location.temperatureScale}"]
    }
    // The API value 'float' is the structure-off state; surface it as "off"
    // since the command takes "off". Keeps the device's heatCoolMode
    // round-trippable with setHeatCoolMode.
    if (data.heatCoolMode != null) {
        def friendly = (data.heatCoolMode == "float") ? "off" : data.heatCoolMode
        changes["heatCoolMode"] = [value: friendly]
    }
    [
        structureId:         data.structureId,
        systemMode:          data.systemMode,
        setPointController:  data.setPointController,
        awayMode:            data.awayMode,
        homeAway:            data.homeAway,
        homeAwaySetBy:       data.homeAwaySetBy,
    ].each { name, value ->
        if (value == null) return
        changes[name] = [value: value]
    }

    changes.each { name, evt ->
        def current = device.currentValue(name)
        if (current?.toString() != evt.value?.toString()) {
            sendEvent(evt + [name: name])
            if (debugLog) log.debug "Flair Structure ${device.label}: ${name}=${evt.value}"
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
