/**
 *  Flair Connect
 *
 *  Parent app for the Flair Hubitat integration. Owns OAuth 2.0 credentials,
 *  the token lifecycle, all outbound HTTP, structure discovery, and the
 *  polling scheduler. Child devices call back into this app for API access
 *  rather than making their own HTTP calls.
 *
 *  Licensed under the MIT License. See LICENSE in the repo root.
 */

import groovy.transform.Field

definition(
    name: "Flair Connect",
    namespace: "holocronology",
    author: "holocronology",
    description: "Flair smart vents and HVAC integration",
    category: "Climate",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true,
    documentationLink: "https://github.com/holocronology/hubitat-flair-2"
)

preferences {
    page(name: "mainPage")
}

@Field static final String OAUTH_URL = "https://api.flair.co/oauth2/token"
@Field static final String API_BASE  = "https://api.flair.co/api"
@Field static final String JSON_API  = "application/vnd.api+json"
// Scope list mirrors flairaio exactly. Flair expects scopes joined by '+'
// (literal, not URL-encoded space). No rooms.* scope exists; thermostats is
// view-only.
@Field static final String OAUTH_SCOPE =
    "pucks.view+pucks.edit+" +
    "structures.view+structures.edit+" +
    "thermostats.view+" +
    "users.view+users.edit+" +
    "vents.view+vents.edit"

// Refresh tokens this many ms before their stated expiry, so a poll that
// straddles the boundary doesn't burn a 401.
@Field static final long TOKEN_REFRESH_SKEW_MS = 60_000L

@Field static final String STRUCTURE_DNI_PREFIX = "flair-structure-"
@Field static final String ROOM_DNI_PREFIX      = "flair-room-"
@Field static final String PUCK_DNI_PREFIX      = "flair-puck-"
@Field static final String PUCK2_DNI_PREFIX     = "flair-puck2-"

def mainPage() {
    dynamicPage(name: "mainPage", title: "Flair Connect", install: true, uninstall: true) {
        section("Flair credentials") {
            input "clientId",     "string",   title: "Client ID",     required: true
            input "clientSecret", "password", title: "Client Secret", required: true
            paragraph "Request credentials from Flair: https://forms.gle/VohiQjWNv9CAP2ASA"
        }
        section("Polling") {
            input "scanInterval", "number", title: "Scan interval (seconds)",
                defaultValue: 30, required: true, range: "15..600"
            input "httpTimeout",  "number", title: "HTTP timeout (seconds)",
                defaultValue: 20, required: true, range: "5..120"
        }
        section("Logging") {
            input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
        }
        if (state.lastPollAt) {
            section("Status") {
                paragraph "Last poll: ${new Date(state.lastPollAt as long)}"
                paragraph "Token valid until: ${state.tokenExpiresAt ? new Date(state.tokenExpiresAt as long) : 'n/a'}"
                paragraph "Structures discovered: ${state.structureIds?.size() ?: 0}"
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    logInfo "installed"
    initialize()
}

def updated() {
    logInfo "updated"
    unschedule()
    state.accessToken     = null
    state.tokenExpiresAt  = 0
    initialize()
}

def uninstalled() {
    logInfo "uninstalled — removing child devices"
    unschedule()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

private initialize() {
    if (!clientId || !clientSecret) {
        logWarn "credentials missing; not scheduling poll"
        return
    }
    // The async chain: requestToken → handleTokenResponse →
    // requestInitialDiscovery → handleDiscoveryResponse → schedulePoll.
    requestToken([chainTo: "requestInitialDiscovery"])
}

/** One-shot discovery used during initialize() — it also arms the poll schedule. */
def requestInitialDiscovery() {
    requestDiscovery([initial: true])
}

private schedulePoll() {
    def interval = (scanInterval ?: 30) as int
    if (interval < 60) {
        schedule("0/${interval} * * * * ?", "poll")
    } else {
        def minutes = (interval / 60) as int
        schedule("0 0/${minutes} * * * ?", "poll")
    }
    logInfo "polling every ${interval}s"
}

// ---------------------------------------------------------------------------
// OAuth — async. Sync httpPost on Hubitat hands the closure a raw
// ByteArrayInputStream when request/response content types differ, so the
// only reliable pattern is asynchttp*.
// ---------------------------------------------------------------------------

/**
 * Kick off an async token request. The optional chainTo data carries the
 * name of the next method to call on success — keeps the lifecycle linear
 * without nesting closures.
 */
private requestToken(Map chainData = [:]) {
    // Hand-built body string: a Map would let Hubitat URL-encode the '+'
    // separators in the scope value to '%2B', which Flair rejects.
    def body = "client_id=${clientId}" +
               "&client_secret=${clientSecret}" +
               "&scope=${OAUTH_SCOPE}" +
               "&grant_type=client_credentials"
    def params = [
        uri:         OAUTH_URL,
        body:        body,
        contentType: "application/x-www-form-urlencoded",
        timeout:     (httpTimeout ?: 20) as int,
    ]
    logDebug "requesting OAuth token"
    asynchttpPost("handleTokenResponse", params, chainData)
}

def handleTokenResponse(resp, data) {
    if (resp?.hasError()) {
        def status = resp.getStatus()
        def msg    = resp.getErrorMessage() ?: ""
        if (status in [400, 401, 403]) {
            logError "OAuth failed (status=${status}) — re-enter credentials in Flair Connect. ${msg}"
            state.accessToken    = null
            state.tokenExpiresAt = 0
        } else {
            logWarn "OAuth transient error (status=${status}): ${msg}"
        }
        return
    }
    def json = null
    try { json = resp.getJson() } catch (e) { logError "OAuth response not JSON: ${e.message}"; return }

    if (!json?.access_token) {
        logError "OAuth response missing access_token; keys=${json instanceof Map ? json.keySet() : json?.class?.simpleName}"
        state.accessToken = null
        return
    }
    def expSec = (json.expires_in ?: 3600) as long
    state.accessToken    = json.access_token
    state.tokenExpiresAt = now() + (expSec * 1000) - TOKEN_REFRESH_SKEW_MS
    logInfo "OAuth token acquired (expires in ${expSec}s)"

    if (data?.chainTo) {
        "${data.chainTo}"()
    }
}

// ---------------------------------------------------------------------------
// Discovery — async
// ---------------------------------------------------------------------------

def requestDiscovery(Map data = [:]) {
    if (!state.accessToken) {
        logWarn "discovery skipped — no token"
        return
    }
    def params = [
        uri:     "${API_BASE}/structures",
        headers: [Authorization: "Bearer ${state.accessToken}", Accept: JSON_API],
        timeout: (httpTimeout ?: 20) as int,
    ]
    logDebug "GET /structures (discovery)"
    asynchttpGet("handleDiscoveryResponse", params, data)
}

def handleDiscoveryResponse(resp, data) {
    if (resp?.hasError()) {
        def status = resp.getStatus()
        if (status in [401, 403]) {
            logError "discovery unauthorized (status=${status}) — clearing token"
            state.accessToken = null
        } else {
            def retained = state.structureIds?.size() ?: 0
            logWarn "discovery failed (status=${status}); retaining ${retained} cached structure(s)"
        }
        return
    }
    def json = null
    try { json = resp.getJson() } catch (e) { logWarn "discovery response not JSON: ${e.message}"; return }

    def items = (json?.data ?: []) as List
    state.structureIds   = items.collect { it.id }
    state.structureNames = items.collectEntries { [(it.id): it.attributes?.name] }

    syncStructureChildren(items)
    fanOutRelated(items)

    if (data?.initial) {
        logInfo "discovered ${items.size()} structure(s): ${state.structureNames?.values()}"
        schedulePoll()
    } else {
        logDebug "poll refresh: ${items.size()} structure(s)"
    }
}

// ---------------------------------------------------------------------------
// Child device wiring
// ---------------------------------------------------------------------------

/**
 * Idempotent child lookup-or-create. Returns the child device (existing or
 * just created), or null if creation truly failed.
 *
 * Tolerates DuplicateDNIException as a benign race: two concurrent fan-outs
 * (e.g. a scheduled poll overlapping a manual Done) can both pass the
 * getChildDevice() null-check before either addChildDevice() returns. The
 * loser of that race re-fetches the just-created device and continues.
 */
private getOrCreateChild(String dni, String typeName, String label) {
    def child = getChildDevice(dni)
    if (child) return child
    try {
        child = addChildDevice(
            "holocronology", typeName, dni,
            [name: typeName, label: label, isComponent: false]
        )
        logInfo "created child device: ${label} (${dni})"
        return child
    } catch (e) {
        // The Hubitat sandbox doesn't expose specific exception classes from
        // com.hubitat.app.exception in a way we can name in a catch clause,
        // so we sniff the class's simpleName / message instead. This keeps
        // the two known benign cases (race-induced duplicate, missing driver
        // type) actionable without coupling us to a FQN that may shift
        // between Hubitat versions.
        def cls = e.class.simpleName
        def msg = e.message?.toLowerCase() ?: ""
        if (cls.contains("Duplicate") || msg.contains("already exists")) {
            // Concurrent fan-out winner already created it. Look it up.
            logDebug "DNI ${dni} created by a concurrent fan-out (${cls}); using existing child"
            return getChildDevice(dni)
        }
        if (cls.contains("UnknownDeviceType") || msg.contains("device type")) {
            logError "${typeName} driver not installed — paste the corresponding driver into Drivers Code, then re-run discovery"
            return null
        }
        logError "failed to create child ${dni}: ${cls}: ${e.message}"
        return null
    }
}

private syncStructureChildren(List items) {
    // toString() the DNIs explicitly — GString interpolation produces a
    // GString whose hashCode differs from the plain String that Hubitat
    // returns from getChildDevices(), so without coercion the just-created
    // child is wrongly flagged as an orphan on the very next pass.
    def seenDnis = [] as Set
    items.each { item ->
        def dni = "${STRUCTURE_DNI_PREFIX}${item.id}".toString()
        seenDnis << dni
        def label = "Flair: ${item.attributes?.name ?: item.id}"
        def child = getOrCreateChild(dni, "Flair Structure", label)
        if (child) pushStructureState(child, item)
    }
    // Reap orphans: structures the user removed from Flair stay as ghosts
    // otherwise.
    getChildDevices()
        .findAll { it.deviceNetworkId.startsWith(STRUCTURE_DNI_PREFIX) && !(it.deviceNetworkId in seenDnis) }
        .each {
            logInfo "removing orphan structure child: ${it.label} (${it.deviceNetworkId})"
            deleteChildDevice(it.deviceNetworkId)
        }
}

private pushStructureState(child, item) {
    def attrs = (item.attributes ?: [:]) as Map
    child.updateState([
        structureId:         item.id,
        systemMode:          attrs["mode"],
        heatCoolMode:        attrs["structure-heat-cool-mode"],
        setPoint:            attrs["set-point-temperature-c"],
        setPointController:  setPointControllerLabel(attrs["set-point-mode"]),
        awayMode:            attrs["structure-away-mode"],
        // 'home' is the actual presence state (boolean); 'home-away-mode' is
        // a misleading name — it's the setter source (Manual / Thermostat /
        // Geolocation), see select.py HomeAwayModeSetBy in the HA repo.
        homeAway:            attrs.containsKey("home") ? (attrs["home"] ? "Home" : "Away") : null,
        homeAwaySetBy:       homeAwaySetByLabel(attrs["home-away-mode"]),
    ])
}

/** Map Flair's raw set-point-mode enum to the friendly labels from HA's const.py. */
private String setPointControllerLabel(String raw) {
    switch (raw) {
        case "Home Evenness For Active Rooms Follow Third Party": return "Thermostat"
        case "Home Evenness For Active Rooms Flair Setpoint":     return "Flair App"
        default: return raw
    }
}

/** Map Flair's raw home-away-mode enum to friendly source labels. */
private String homeAwaySetByLabel(String raw) {
    switch (raw) {
        case "Manual":                  return "Manual"
        case "Third Party Home Away":   return "Thermostat"
        case "Flair Autohome Autoaway": return "Flair App Geolocation"
        default: return raw
    }
}

// ---------------------------------------------------------------------------
// Related-resource fan-out — for each discovered structure, kick off async
// fetches for its rooms/pucks/vents/hvac-units. Each handler updates only
// its own DNI namespace, so they're independent and don't need a join.
// ---------------------------------------------------------------------------

private fanOutRelated(List structures) {
    structures.each { s ->
        requestRooms(s.id)
        requestPucks(s.id)
        requestPuck2s(s.id)
        // Future phases: requestVents(s.id), requestHvacUnits(s.id)
    }
}

// ---------------------------------------------------------------------------
// Rooms
// ---------------------------------------------------------------------------

def requestRooms(String structureId) {
    if (!state.accessToken) return
    def params = [
        uri:     "${API_BASE}/structures/${structureId}/rooms",
        headers: [Authorization: "Bearer ${state.accessToken}", Accept: JSON_API],
        timeout: (httpTimeout ?: 20) as int,
    ]
    logDebug "GET /structures/${structureId}/rooms"
    asynchttpGet("handleRoomsResponse", params, [structureId: structureId.toString()])
}

def handleRoomsResponse(resp, data) {
    if (resp?.hasError()) {
        def status = resp.getStatus()
        def retained = (getChildDevices().count { it.deviceNetworkId.startsWith(ROOM_DNI_PREFIX) }) as int
        if (status in [401, 403]) {
            logError "rooms fetch unauthorized (status=${status}) — clearing token"
            state.accessToken = null
        } else {
            logWarn "rooms fetch failed for structure ${data?.structureId} (status=${status}); retaining ${retained} cached room child(ren)"
        }
        return
    }
    def json = null
    try { json = resp.getJson() } catch (e) { logWarn "rooms response not JSON: ${e.message}"; return }

    def items = (json?.data ?: []) as List
    syncRoomChildren(data?.structureId as String, items)
}

private syncRoomChildren(String structureId, List items) {
    def seenDnis = [] as Set
    items.each { item ->
        def dni = "${ROOM_DNI_PREFIX}${item.id}".toString()
        seenDnis << dni
        def label = "Flair Room: ${item.attributes?.name ?: item.id}"
        def child = getOrCreateChild(dni, "Flair Room", label)
        if (child) pushRoomState(child, structureId, item)
    }
    // Reap only the rooms tied to *this* structure: we don't have visibility
    // into rooms of other structures yet (each call is per-structure), so we
    // restrict the orphan set to children whose structureId attribute
    // matches this structureId.
    getChildDevices()
        .findAll { c ->
            c.deviceNetworkId.startsWith(ROOM_DNI_PREFIX) &&
            !(c.deviceNetworkId in seenDnis) &&
            c.currentValue("structureId")?.toString() == structureId
        }
        .each {
            logInfo "removing orphan room child: ${it.label} (${it.deviceNetworkId})"
            deleteChildDevice(it.deviceNetworkId)
        }
}

private pushRoomState(child, String structureId, item) {
    def attrs = (item.attributes ?: [:]) as Map
    child.updateState([
        roomId:        item.id,
        structureId:   structureId,
        temperatureC:  attrs["current-temperature-c"],
        humidity:      attrs["current-humidity"],
        setPointC:     attrs["set-point-c"],
        active:        attrs["active"],
    ])
}

// ---------------------------------------------------------------------------
// Pucks (V1)
// ---------------------------------------------------------------------------

def requestPucks(String structureId) {
    if (!state.accessToken) return
    def params = [
        uri:     "${API_BASE}/structures/${structureId}/pucks",
        headers: [Authorization: "Bearer ${state.accessToken}", Accept: JSON_API],
        timeout: (httpTimeout ?: 20) as int,
    ]
    logDebug "GET /structures/${structureId}/pucks"
    asynchttpGet("handlePucksResponse", params, [structureId: structureId.toString()])
}

def handlePucksResponse(resp, data) {
    if (resp?.hasError()) {
        def status = resp.getStatus()
        def retained = (getChildDevices().count { it.deviceNetworkId.startsWith(PUCK_DNI_PREFIX) }) as int
        if (status in [401, 403]) {
            logError "pucks fetch unauthorized (status=${status}) — clearing token"
            state.accessToken = null
        } else {
            logWarn "pucks fetch failed for structure ${data?.structureId} (status=${status}); retaining ${retained} cached puck child(ren)"
        }
        return
    }
    def json = null
    try { json = resp.getJson() } catch (e) { logWarn "pucks response not JSON: ${e.message}"; return }
    def items = (json?.data ?: []) as List
    syncPuckChildren(data?.structureId as String, items)

    // V1 pucks have a current-reading sub-resource too (light, room-pressure
    // live there, not on the list response). Skip inactive ones for the same
    // reason as V2.
    items.each { item ->
        if (item.attributes?.inactive != true) {
            requestPuckCurrentReading(item.id as String, data?.structureId as String)
        }
    }
}

private syncPuckChildren(String structureId, List items) {
    def seenDnis = [] as Set
    items.each { item ->
        def dni = "${PUCK_DNI_PREFIX}${item.id}".toString()
        seenDnis << dni
        def label = "Flair Puck: ${item.attributes?.name ?: item.id}"
        def child = getOrCreateChild(dni, "Flair Puck", label)
        if (child) pushPuckState(child, structureId, item)
    }
    getChildDevices()
        .findAll { c ->
            c.deviceNetworkId.startsWith(PUCK_DNI_PREFIX) &&
            !(c.deviceNetworkId in seenDnis) &&
            c.currentValue("structureId")?.toString() == structureId
        }
        .each {
            logInfo "removing orphan puck child: ${it.label} (${it.deviceNetworkId})"
            deleteChildDevice(it.deviceNetworkId)
        }
}

private pushPuckState(child, String structureId, item) {
    def a = (item.attributes ?: [:]) as Map
    // Temperature, humidity, voltage, RSSI come from the list response.
    // Light and pressure live on the current-reading sub-resource and arrive
    // via a separate handler (pushPuckReading) — see HA sensor.py which uses
    // attributes['current-rssi'] / attributes['voltage'] / attributes
    // ['current-temperature-c'] / attributes['current-humidity'] for these,
    // and current_reading['light'] / current_reading['room-pressure'] for
    // the rest.
    child.updateState([
        puckId:        item.id,
        structureId:   structureId,
        temperatureC:  a["current-temperature-c"],
        humidity:      a["current-humidity"],
        voltage:       a["voltage"],
        rssi:          a["current-rssi"],
        inactive:      a["inactive"],
    ])
}

def requestPuckCurrentReading(String puckId, String structureId) {
    if (!state.accessToken) return
    def params = [
        uri:     "${API_BASE}/pucks/${puckId}/current-reading",
        headers: [Authorization: "Bearer ${state.accessToken}", Accept: JSON_API],
        timeout: (httpTimeout ?: 20) as int,
    ]
    logDebug "GET /pucks/${puckId}/current-reading"
    asynchttpGet("handlePuckCurrentReadingResponse", params, [puckId: puckId, structureId: structureId])
}

def handlePuckCurrentReadingResponse(resp, data) {
    if (resp?.hasError()) {
        // Retain previous reading on transient failure (§9).
        def status = resp.getStatus()
        if (status in [401, 403]) {
            logError "current-reading unauthorized for puck ${data?.puckId} (status=${status}) — clearing token"
            state.accessToken = null
        } else {
            logWarn "current-reading failed for puck ${data?.puckId} (status=${status}); retaining previous reading"
        }
        return
    }
    def json = null
    try { json = resp.getJson() } catch (e) { logWarn "current-reading not JSON for puck ${data?.puckId}: ${e.message}"; return }

    def reading = (json?.data?.attributes ?: [:]) as Map
    def dni = "${PUCK_DNI_PREFIX}${data.puckId}".toString()
    def child = getChildDevice(dni)
    if (!child) {
        logDebug "current-reading landed for puck ${data?.puckId} but no child exists yet — ignoring"
        return
    }
    child.updateState([
        light:    scaleLight(reading["light"]),
        pressure: reading["room-pressure"],
    ])
}

/**
 * Scale Flair's raw light reading to lux. Matches the formula in HA's
 * sensor.py: (raw / 100) * 200 — i.e. raw × 2. Returns null on null input
 * so the driver's "skip null" rule keeps previous lux intact.
 */
private scaleLight(raw) {
    if (raw == null) return null
    return (raw as BigDecimal) * 2
}

// ---------------------------------------------------------------------------
// Pucks V2 — see FLAIR_DOMAIN_NOTES §5. Lives under a separate relationship
// and type key (puck2s), and each active V2's live sensor values must be
// fetched from a second endpoint (/puck2s/{id}/current-reading).
//
// Retain-on-failure invariant (§9): if either fetch fails, do not blank
// existing state. The driver's updateState only emits sendEvent for non-null
// values, so simply not calling it with readings keeps the previous values.
// ---------------------------------------------------------------------------

def requestPuck2s(String structureId) {
    if (!state.accessToken) return
    def params = [
        uri:     "${API_BASE}/structures/${structureId}/puck2s",
        headers: [Authorization: "Bearer ${state.accessToken}", Accept: JSON_API],
        timeout: (httpTimeout ?: 20) as int,
    ]
    logDebug "GET /structures/${structureId}/puck2s"
    asynchttpGet("handlePuck2sResponse", params, [structureId: structureId.toString()])
}

def handlePuck2sResponse(resp, data) {
    if (resp?.hasError()) {
        def status = resp.getStatus()
        def retained = (getChildDevices().count { it.deviceNetworkId.startsWith(PUCK2_DNI_PREFIX) }) as int
        if (status in [401, 403]) {
            logError "puck2s fetch unauthorized (status=${status}) — clearing token"
            state.accessToken = null
        } else {
            logWarn "puck2s fetch failed for structure ${data?.structureId} (status=${status}); retaining ${retained} cached puck2 child(ren)"
        }
        return
    }
    def json = null
    try { json = resp.getJson() } catch (e) { logWarn "puck2s response not JSON: ${e.message}"; return }
    def items = (json?.data ?: []) as List
    syncPuck2Children(data?.structureId as String, items)

    // For each active V2, kick off the current-reading sub-fetch. Inactive
    // pucks would only return a stale or empty reading; skip them.
    items.each { item ->
        if (item.attributes?.inactive != true) {
            requestPuck2CurrentReading(item.id as String, data?.structureId as String)
        }
    }
}

private syncPuck2Children(String structureId, List items) {
    def seenDnis = [] as Set
    items.each { item ->
        def dni = "${PUCK2_DNI_PREFIX}${item.id}".toString()
        seenDnis << dni
        def label = "Flair Puck V2: ${item.attributes?.name ?: item.id}"
        def child = getOrCreateChild(dni, "Flair Puck V2", label)
        if (child) pushPuck2Meta(child, structureId, item)
    }
    getChildDevices()
        .findAll { c ->
            c.deviceNetworkId.startsWith(PUCK2_DNI_PREFIX) &&
            !(c.deviceNetworkId in seenDnis) &&
            c.currentValue("structureId")?.toString() == structureId
        }
        .each {
            logInfo "removing orphan puck2 child: ${it.label} (${it.deviceNetworkId})"
            deleteChildDevice(it.deviceNetworkId)
        }
}

/**
 * Pushes the V2 metadata + the sensor values that live on the puck2s list
 * response (everything except light and pressure, which arrive separately
 * via the current-reading sub-fetch). The HA sensor.py for V2 reads
 * temperature, humidity, voltage, and current-rssi directly from
 * puck2_data.attributes — see lines 1518, 1596, 1755, 1845.
 */
private pushPuck2Meta(child, String structureId, item) {
    def a = (item.attributes ?: [:]) as Map
    child.updateState([
        puckId:                item.id,
        structureId:           structureId,
        // Sensor values from the list response:
        temperatureC:          a["current-temperature-c"],
        humidity:              a["current-humidity"],
        voltage:               a["voltage"],
        rssi:                  a["current-rssi"],
        inactive:              a["inactive"],
        // V2-specific metadata:
        displayColor:          a["puck-display-color"],
        temperatureScale:      a["temperature-scale"],
        setpointBoundLow:      a["setpoint-bound-low"],
        setpointBoundHigh:     a["setpoint-bound-high"],
        temperatureOffset:     a["temperature-offset-override-c"],
        locked:                a["locked"],
    ])
}

def requestPuck2CurrentReading(String puck2Id, String structureId) {
    if (!state.accessToken) return
    def params = [
        uri:     "${API_BASE}/puck2s/${puck2Id}/current-reading",
        headers: [Authorization: "Bearer ${state.accessToken}", Accept: JSON_API],
        timeout: (httpTimeout ?: 20) as int,
    ]
    logDebug "GET /puck2s/${puck2Id}/current-reading"
    asynchttpGet("handlePuck2CurrentReadingResponse", params, [puck2Id: puck2Id, structureId: structureId])
}

def handlePuck2CurrentReadingResponse(resp, data) {
    if (resp?.hasError()) {
        // Per FLAIR_DOMAIN_NOTES §9: retain previous reading on transient
        // failure. Do not blank values on the child — simply log and exit.
        def status = resp.getStatus()
        if (status in [401, 403]) {
            logError "current-reading unauthorized for puck2 ${data?.puck2Id} (status=${status}) — clearing token"
            state.accessToken = null
        } else {
            logWarn "current-reading failed for puck2 ${data?.puck2Id} (status=${status}); retaining previous reading"
        }
        return
    }
    def json = null
    try { json = resp.getJson() } catch (e) { logWarn "current-reading not JSON for puck2 ${data?.puck2Id}: ${e.message}"; return }

    def reading = (json?.data?.attributes ?: [:]) as Map
    def dni = "${PUCK2_DNI_PREFIX}${data.puck2Id}".toString()
    def child = getChildDevice(dni)
    if (!child) {
        logDebug "current-reading landed for puck2 ${data?.puck2Id} but no child exists yet — ignoring"
        return
    }
    // V2 hardware lacks light and pressure sensors. RSSI uses sub-ghz-rssi
    // (the radio used to reach the bridge), not current-rssi which may be
    // null in the V2 list response. HVAC IR-signaling fields in
    // current-reading (mode-status, fan-speed-status, ir-device-set-point,
    // etc.) are intentionally NOT surfaced here — those reflect what the
    // puck is currently transmitting, not the authoritative HVAC unit
    // state. The actual HVAC control entity is the paired hvac-units
    // resource, surfaced by the Flair HVAC Unit driver in a later phase.
    child.updateState([
        rssi:             reading["sub-ghz-rssi"],
        connectedGateway: reading["connected-gateway-name"],
    ])
}

// ---------------------------------------------------------------------------
// Polling — async. For Phase 1 this just re-fetches structures to keep the
// connection warm. Phase 2 will fan out to rooms/pucks/vents/hvac-units.
// ---------------------------------------------------------------------------

def poll() {
    state.lastPollAt = now()
    // Refresh token proactively if it's about to expire; the discovery call
    // below will get the new one via state.
    if (!state.accessToken || now() >= (state.tokenExpiresAt ?: 0)) {
        logDebug "token expired/missing — refreshing before poll"
        requestToken([chainTo: "requestDiscovery"])
        return
    }
    requestDiscovery()
}

// ---------------------------------------------------------------------------
// Logging — never log credentials or tokens
// ---------------------------------------------------------------------------

private logDebug(msg) { if (debugLog) log.debug "Flair: ${msg}" }
private logInfo(msg)  { log.info  "Flair: ${msg}" }
private logWarn(msg)  { log.warn  "Flair: ${msg}" }
private logError(msg) { log.error "Flair: ${msg}" }
