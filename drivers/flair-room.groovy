/**
 *  Flair Room (read-only)
 *
 *  Surfaces a Flair room as a temperature + humidity sensor in Hubitat.
 *  State is pushed from the Flair Connect parent app on every poll; this
 *  driver makes no HTTP calls of its own.
 *
 *  Setpoint and active/inactive mode are read-only here; write surfaces
 *  land in a later phase.
 *
 *  Licensed under the MIT License. See LICENSE in the repo root.
 */

metadata {
    definition(
        name:      "Flair Room",
        namespace: "holocronology",
        author:    "holocronology",
        importUrl: "https://raw.githubusercontent.com/holocronology/hubitat-flair-2/main/drivers/flair-room.groovy"
    ) {
        capability "Refresh"
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"

        attribute "roomId",      "string"
        attribute "structureId", "string"
        attribute "setPoint",    "number"   // emitted in the hub's preferred unit
        attribute "active",      "string"   // Active | Inactive (auto-mode participation)
        attribute "lastUpdate",  "string"
    }

    preferences {
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() { }
def updated()   { }

def refresh() {
    parent?.poll()
}

/**
 * Called by the parent on every poll. Emits sendEvents only when values
 * actually change, to keep the event stream quiet.
 *
 * Expected input keys (all optional): roomId, structureId, temperatureC,
 * humidity, setPointC, active.
 */
def updateState(Map data) {
    def changes = [:]

    // Temperature + setpoint: convert from the API's Celsius storage to the
    // hub's preferred scale so dashboards show the right number.
    if (data.temperatureC != null) {
        def t = convertC(data.temperatureC as BigDecimal)
        changes["temperature"] = [value: t, unit: "°${location.temperatureScale}"]
    }
    if (data.setPointC != null) {
        def sp = convertC(data.setPointC as BigDecimal)
        changes["setPoint"] = [value: sp, unit: "°${location.temperatureScale}"]
    }
    if (data.humidity != null) {
        changes["humidity"] = [value: data.humidity, unit: "%"]
    }
    if (data.active != null) {
        changes["active"] = [value: data.active ? "Active" : "Inactive"]
    }
    if (data.roomId != null)      changes["roomId"]      = [value: data.roomId]
    if (data.structureId != null) changes["structureId"] = [value: data.structureId]

    changes.each { name, evt ->
        def current = device.currentValue(name)
        if (current?.toString() != evt.value?.toString()) {
            sendEvent(evt + [name: name])
            if (debugLog) log.debug "Flair Room ${device.label}: ${name}=${evt.value}"
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
