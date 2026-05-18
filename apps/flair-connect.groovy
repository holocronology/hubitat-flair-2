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
        // Future phases: requestPucks(s.id), requestPuck2s(s.id),
        // requestVents(s.id), requestHvacUnits(s.id)
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
