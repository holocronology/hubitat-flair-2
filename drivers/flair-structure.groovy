/**
 *  Flair Structure (read-only)
 *
 *  Surfaces Flair structure-level state in Hubitat. State is pushed from the
 *  Flair Connect parent app on every poll; this driver does not make any
 *  HTTP calls of its own.
 *
 *  Commands (system mode select, away toggle, setpoint) land in a later
 *  phase.
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

        // Raw Flair structure state. Setpoint is always Celsius because that
        // is the API's storage unit; conversion lives in display layers.
        attribute "structureId",         "string"
        attribute "systemMode",          "string"   // auto | manual
        attribute "heatCoolMode",        "string"   // float | heat | cool | auto
        attribute "setPoint",            "number"   // °C
        attribute "setPointController",  "string"   // Thermostat | Flair App
        attribute "awayMode",            "string"   // Smart Away | Off Only
        attribute "homeAway",            "string"   // Home | Away (derived from 'home' bool)
        attribute "homeAwaySetBy",       "string"   // Manual | Thermostat | Flair App Geolocation
        attribute "lastUpdate",          "string"
    }

    preferences {
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() { }
def updated()   { }

def refresh() {
    // Children always poll through the parent so HTTP and token handling
    // stay centralised.
    parent?.poll()
}

/**
 * Parent calls this on every poll to push fresh structure state.
 * Only emits an event when the value actually changes, to avoid Hubitat
 * event-stream noise.
 */
def updateState(Map data) {
    [
        structureId:         data.structureId,
        systemMode:          data.systemMode,
        heatCoolMode:        data.heatCoolMode,
        setPoint:            data.setPoint,
        setPointController:  data.setPointController,
        awayMode:            data.awayMode,
        homeAway:            data.homeAway,
        homeAwaySetBy:       data.homeAwaySetBy,
    ].each { name, value ->
        if (value == null) return
        def current = device.currentValue(name)
        if (current?.toString() != value.toString()) {
            sendEvent(name: name, value: value)
            if (debugLog) log.debug "Flair Structure ${device.label}: ${name}=${value}"
        }
    }
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}
