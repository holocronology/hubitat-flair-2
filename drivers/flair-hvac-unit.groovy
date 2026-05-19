/**
 *  Flair HVAC Unit
 *
 *  Surfaces a Flair-managed HVAC unit (mini-split or similar) controlled
 *  via an IR-blasting Puck. State is pushed from the Flair Climate Connect
 *  parent app on every poll and on every successful write response. Writes
 *  go through `parent.patchHvacUnit(...)` and `parent.patchRoom(...)`;
 *  this driver makes no HTTP calls of its own.
 *
 *  Two design pillars from FLAIR_DOMAIN_NOTES §4 are encoded here:
 *
 *   - §4.4 decision tree: the user-visible `displayedMode` and
 *     `displayedAction` honour the cross-entity state (structure mode +
 *     structure-heat-cool-mode + unit power), so the tile matches what
 *     the Flair app shows. Computed parent-side and pushed in.
 *
 *   - §4.5 auto-vs-manual write split: setTargetTemperature lands on the
 *     ROOM (`set-point-c`, °C) when the structure is in auto mode, and on
 *     the HVAC unit (`temperature`, native scale) in manual mode. Fan
 *     speed and swing use different attribute names and value shapes per
 *     mode (`default-fan-speed`/all-caps vs `fan-speed`/title-case;
 *     `swing-auto`/bool vs `swing`/"On"|"Off").
 *
 *  setHvacMode in manual mode encodes the FAN AUTO interaction matrix:
 *  Dry and Auto force fan-speed to "Auto"; Fan-only forbids "Auto" and
 *  auto-falls-back to the first valid non-Auto fan speed. setHvacMode in
 *  auto mode is refused with a pointer to the Structure driver where
 *  mode is set globally.
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
        capability "Switch"                   // on/off → power

        // Identity / relationships
        attribute "hvacUnitId",          "string"
        attribute "structureId",         "string"
        attribute "roomId",              "string"
        attribute "puckId",              "string"
        attribute "puckType",            "string"   // V1 | V2 — see §5 gotcha
        attribute "manufacturer",        "string"

        // Raw API state
        attribute "power",               "string"   // On | Off
        attribute "rawMode",             "string"   // Heat | Cool | Dry | Fan | Auto | Off

        // Derived state per §4.4
        attribute "displayedMode",       "string"
        attribute "displayedAction",     "string"

        attribute "temperatureScaleNative", "string"   // F | C | K

        attribute "fanSpeed",            "string"   // Auto | High | Medium | Low
        attribute "swing",               "string"   // On | Off | Not Supported

        attribute "availableModes",      "string"   // comma-separated, raw API values (HEAT, COOL, ...)
        attribute "availableFanSpeeds",  "string"   // comma-separated for current mode
        attribute "fanOnlyFanSpeeds",    "string"   // comma-separated for FAN mode specifically
        attribute "supportsSwing",       "string"   // true | false
        attribute "constraintsShape",    "string"   // dict | list

        attribute "lastUpdate",          "string"

        command "setHvacMode",           [[name: "mode*",    type: "ENUM",   constraints: ["Off", "Heat", "Cool", "Dry", "Fan", "Auto"]]]
        command "setFanSpeed",           [[name: "speed*",   type: "ENUM",   constraints: ["Auto", "High", "Medium", "Low"]]]
        command "setSwingMode",          [[name: "mode*",    type: "ENUM",   constraints: ["On", "Off"]]]
        command "setTargetTemperature",  [[name: "temp*",    type: "NUMBER", description: "Target in hub's preferred unit"]]
    }

    preferences {
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() { }
def updated()   { }
def refresh()   { parent?.poll() }

// ---------------------------------------------------------------------------
// Switch capability — directly toggles HVAC power. Always writes to the
// hvac-unit regardless of structure mode (auto-mode units will get re-driven
// by the algorithm shortly, but the explicit power write is still valid).
// ---------------------------------------------------------------------------

def on()  { parent?.patchHvacUnit(device.currentValue("hvacUnitId"), ["power": "On"]) }
def off() { parent?.patchHvacUnit(device.currentValue("hvacUnitId"), ["power": "Off"]) }

// ---------------------------------------------------------------------------
// setHvacMode — the hairiest write. Off is straightforward (power off).
// Anything else in manual mode applies the FAN AUTO matrix; in auto mode
// the unit's mode is structure-controlled and we refuse rather than write
// a request that the next poll will revert.
// ---------------------------------------------------------------------------

def setHvacMode(String mode) {
    def hvacUnitId = device.currentValue("hvacUnitId")
    if (!hvacUnitId) { log.error "setHvacMode: missing hvacUnitId"; return }

    // Mode→Off is allowed in both modes (just powers the unit off).
    if (mode == "Off") {
        parent.patchHvacUnit(hvacUnitId, ["power": "Off"])
        return
    }

    // Need the structure system mode to choose the right write path.
    def systemMode = lookupSystemMode()
    if (systemMode == "auto") {
        log.error "${device.label}: cannot change HVAC mode in auto system mode — " +
                  "the structure controls the unit's mode in auto. Change the " +
                  "Flair Structure device's heatCoolMode (off/heat/cool/auto) instead."
        return
    }

    // Manual mode below.
    def attrs = [:]
    if (device.currentValue("power") != "On") attrs["power"] = "On"

    // FAN AUTO interaction matrix:
    //  - Dry/Auto must run with fan-speed Auto (force)
    //  - Fan must NOT run with fan-speed Auto (auto-fallback to first valid)
    if (mode in ["Dry", "Auto"]) {
        attrs["mode"] = mode
        if (device.currentValue("fanSpeed") != "Auto") attrs["fan-speed"] = "Auto"
    } else if (mode == "Fan") {
        attrs["mode"] = mode
        if (device.currentValue("fanSpeed") == "Auto") {
            def fallback = pickFirstNonAutoFanSpeed()
            if (fallback) {
                attrs["fan-speed"] = fallback
            } else {
                log.warn "${device.label}: switching to Fan mode but no non-Auto fan " +
                         "speeds advertised by the unit — Flair may reject the write."
            }
        }
    } else {
        attrs["mode"] = mode
    }

    parent.patchHvacUnit(hvacUnitId, attrs)
}

/**
 * Walk the comma-separated fanOnlyFanSpeeds attribute, skip FAN AUTO, and
 * return the first FAN <X> entry mapped to its title-case form ("High",
 * "Medium", "Low"). Returns null if none available.
 */
private String pickFirstNonAutoFanSpeed() {
    def raw = device.currentValue("fanOnlyFanSpeeds") ?: ""
    def list = raw.split(",").collect { it.trim() }.findAll { it && it != "FAN AUTO" }
    if (!list) return null
    return constraintFanSpeedToTitle(list[0])
}

/** Map FAN HI / FAN MID / FAN LOW / FAN AUTO -> High / Medium / Low / Auto. */
private String constraintFanSpeedToTitle(String constraintKey) {
    switch (constraintKey) {
        case "FAN AUTO": return "Auto"
        case "FAN HI":   return "High"
        case "FAN MID":  return "Medium"
        case "FAN LOW":  return "Low"
        default: return null
    }
}

// ---------------------------------------------------------------------------
// setFanSpeed — auto-mode writes default-fan-speed (all caps),
// manual-mode writes fan-speed (title case). HA caps Dry to Auto only;
// we mirror that.
// ---------------------------------------------------------------------------

def setFanSpeed(String speed) {
    def hvacUnitId = device.currentValue("hvacUnitId")
    if (!hvacUnitId) { log.error "setFanSpeed: missing hvacUnitId"; return }
    if (!(speed in ["Auto", "High", "Medium", "Low"])) {
        log.error "setFanSpeed: invalid speed '${speed}'"; return
    }

    def systemMode = lookupSystemMode()
    if (systemMode == "auto") {
        parent.patchHvacUnit(hvacUnitId, ["default-fan-speed": speed.toUpperCase()])
        return
    }
    // Manual mode
    if (device.currentValue("rawMode") == "Dry" && speed != "Auto") {
        log.warn "${device.label}: Dry mode requires fan-speed Auto — forcing Auto."
        parent.patchHvacUnit(hvacUnitId, ["fan-speed": "Auto"])
        return
    }
    parent.patchHvacUnit(hvacUnitId, ["fan-speed": speed])
}

// ---------------------------------------------------------------------------
// setSwingMode — different attribute name AND value type between modes.
// auto: swing-auto (boolean true/false). manual: swing ("On"/"Off").
// ---------------------------------------------------------------------------

def setSwingMode(String mode) {
    def hvacUnitId = device.currentValue("hvacUnitId")
    if (!hvacUnitId) { log.error "setSwingMode: missing hvacUnitId"; return }
    if (!(mode in ["On", "Off"])) { log.error "setSwingMode: invalid mode '${mode}'"; return }

    if (device.currentValue("supportsSwing") != "true") {
        log.error "${device.label}: this unit does not support swing."
        return
    }

    def systemMode = lookupSystemMode()
    if (systemMode == "auto") {
        parent.patchHvacUnit(hvacUnitId, ["swing-auto": mode == "On"])
    } else {
        parent.patchHvacUnit(hvacUnitId, ["swing": mode])
    }
}

// ---------------------------------------------------------------------------
// setTargetTemperature — auto-mode writes to the ROOM, manual-mode to the
// HVAC unit. The room write uses Celsius (always); the manual write uses
// the unit's native scale.
// ---------------------------------------------------------------------------

def setTargetTemperature(BigDecimal temperature) {
    def hvacUnitId = device.currentValue("hvacUnitId")
    def roomId     = device.currentValue("roomId")
    if (!hvacUnitId) { log.error "setTargetTemperature: missing hvacUnitId"; return }
    if (temperature == null) { log.error "setTargetTemperature: temperature required"; return }

    // Mode guard (matches HA climate.py): Off, Fan, and Dry modes don't
    // accept a temperature write — Flair responds 422 UNPROCESSABLE ENTITY.
    // Refuse cleanly here so the user gets an actionable error instead of
    // a generic HTTP failure.
    def rawMode = device.currentValue("rawMode")
    if (rawMode in ["Off", "Fan", "Dry"]) {
        log.error "${device.label}: setTargetTemperature not supported in ${rawMode} mode. " +
                  "Switch to Heat, Cool, or Auto first."
        return
    }

    def systemMode = lookupSystemMode()
    if (systemMode == "auto") {
        if (!roomId) { log.error "setTargetTemperature (auto): unit has no associated room"; return }
        def celsius = hubToCelsius(temperature)
        parent.patchRoom(roomId, [
            "set-point-c": celsius,
            "active":      true,
        ])
        return
    }
    // Manual mode
    if (device.currentValue("power") != "On") {
        log.error "${device.label}: cannot set temperature while powered off. " +
                  "Use on() first or pick a non-Off mode."
        return
    }
    def nativeTemp = hubToNative(temperature)
    parent.patchHvacUnit(hvacUnitId, ["temperature": nativeTemp])
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Read the structure's system mode from the corresponding Flair Structure
 * child device. Falls back to "manual" if not resolvable — assumes the
 * less-magical write path on uncertainty.
 */
private String lookupSystemMode() {
    def structureId = device.currentValue("structureId")
    if (!structureId) return "manual"
    def structureDni = "flair-structure-${structureId}".toString()
    def structureDev = parent?.getChildDevice(structureDni)
    return structureDev?.currentValue("systemMode") ?: "manual"
}

private BigDecimal hubToCelsius(BigDecimal value) {
    if (value == null) return null
    if (location.temperatureScale == "F") return ((value - 32) * 5 / 9).setScale(2, java.math.RoundingMode.HALF_UP)
    return value.setScale(2, java.math.RoundingMode.HALF_UP)
}

/** Hub-unit -> unit's native scale. Used for manual-mode temperature writes. */
private BigDecimal hubToNative(BigDecimal value) {
    def nativeScale = device.currentValue("temperatureScaleNative") ?: "F"
    def hub         = location.temperatureScale
    if (nativeScale == hub) return value.setScale(1, java.math.RoundingMode.HALF_UP)
    // Go via Celsius.
    def celsius = (hub == "F") ? ((value - 32) * 5 / 9) : value
    if (nativeScale == "F") return (celsius * 9 / 5 + 32).setScale(1, java.math.RoundingMode.HALF_UP)
    if (nativeScale == "K") return (celsius + 273.15).setScale(2, java.math.RoundingMode.HALF_UP)
    return celsius.setScale(2, java.math.RoundingMode.HALF_UP)
}

// ---------------------------------------------------------------------------
// State push from parent
// ---------------------------------------------------------------------------

def updateState(Map data) {
    def changes = [:]

    if (data.targetTemperatureHub != null) {
        changes["temperature"] = [value: data.targetTemperatureHub, unit: "°${location.temperatureScale}"]
    }
    if (data.availableModes instanceof List)     changes["availableModes"]     = [value: data.availableModes.join(", ")]
    if (data.availableFanSpeeds instanceof List) changes["availableFanSpeeds"] = [value: data.availableFanSpeeds.join(", ")]
    if (data.fanOnlyFanSpeeds instanceof List)   changes["fanOnlyFanSpeeds"]   = [value: data.fanOnlyFanSpeeds.join(", ")]
    if (data.power != null)                      changes["switch"]             = [value: data.power == "On" ? "on" : "off"]

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
