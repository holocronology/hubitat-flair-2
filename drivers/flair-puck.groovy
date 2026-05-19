/**
 *  Flair Puck (V1, read-only)
 *
 *  Surfaces a Flair V1 Puck as a multi-sensor in Hubitat. State is pushed
 *  from the Flair Connect parent app on every poll; this driver makes no
 *  HTTP calls of its own.
 *
 *  V1 pucks return their live sensor values directly on the
 *  /structures/{id}/pucks call, so no follow-up sub-fetch is required.
 *  (V2 pucks are different — see flair-puck-v2.groovy.)
 *
 *  Licensed under the MIT License. See LICENSE in the repo root.
 */

metadata {
    definition(
        name:      "Flair Puck",
        namespace: "holocronology",
        author:    "holocronology",
        importUrl: "https://raw.githubusercontent.com/holocronology/hubitat-flair-2/main/drivers/flair-puck.groovy"
    ) {
        capability "Refresh"
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "IlluminanceMeasurement"
        capability "SignalStrength"          // exposes rssi
        capability "PressureMeasurement"     // exposes pressure

        attribute "puckId",      "string"
        attribute "structureId", "string"
        attribute "voltage",     "number"   // raw volts
        attribute "inactive",    "string"   // true | false — Flair offline flag
        attribute "lastUpdate",  "string"
    }

    preferences {
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() { }
def updated()   { }

def refresh()   { parent?.poll() }

/**
 * Parent calls this on every poll. Expected keys (all optional):
 *   puckId, structureId, temperatureC, humidity, light, voltage, rssi,
 *   pressure, inactive.
 *
 * Emits sendEvent only when a value actually changes.
 */
def updateState(Map data) {
    def changes = [:]

    if (data.temperatureC != null) {
        def t = convertC(data.temperatureC as BigDecimal)
        changes["temperature"] = [value: t, unit: "°${location.temperatureScale}"]
    }
    if (data.humidity != null)   changes["humidity"]    = [value: data.humidity, unit: "%"]
    if (data.light != null)      changes["illuminance"] = [value: data.light, unit: "lux"]
    if (data.rssi != null)       changes["rssi"]        = [value: data.rssi, unit: "dBm"]
    if (data.voltage != null)    changes["voltage"]     = [value: data.voltage, unit: "V"]
    if (data.pressure != null)   changes["pressure"]    = [value: data.pressure, unit: "kPa"]
    if (data.inactive != null)   changes["inactive"]    = [value: data.inactive.toString()]
    if (data.puckId != null)     changes["puckId"]      = [value: data.puckId]
    if (data.structureId != null) changes["structureId"] = [value: data.structureId]

    changes.each { name, evt ->
        def current = device.currentValue(name)
        if (current?.toString() != evt.value?.toString()) {
            sendEvent(evt + [name: name])
            if (debugLog) log.debug "Flair Puck ${device.label}: ${name}=${evt.value}"
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
