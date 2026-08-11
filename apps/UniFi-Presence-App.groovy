/**
 *  UniFi Presence Integration
 *
 *  Polls a UniFi OS console (UDM / UDM-Pro / Cloud Key Gen2+) directly over its
 *  local REST API for connected-client state, and reflects presence for a
 *  configured list of devices (phones) onto child "UniFi Presence Sensor" devices.
 *
 *  Endpoints (UniFi OS, not classic self-hosted controller):
 *    POST /api/auth/login              -> session cookie (+ optional CSRF token)
 *    GET  /proxy/network/api/s/<site>/stat/sta  -> active client list
 *    POST /api/auth/logout
 */
definition(
    name: "UniFi Presence Integration",
    namespace: "unifiPresence",
    author: "babgvant",
    description: "Local presence detection via a UniFi OS console's REST API",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section("UniFi Controller") {
            input "host", "text", title: "Controller IP or hostname", required: true
            input "port", "number", title: "Port", defaultValue: 443, required: true
            input "username", "text", title: "Username", required: true
            input "password", "password", title: "Password", required: true
            input "site", "text", title: "Site name", defaultValue: "default", required: true
        }

        section("Tracked Devices") {
            paragraph "One device per line: identifier:Label\nIdentifier is a MAC address (aa:bb:cc:dd:ee:ff) for the most reliable match, or a client hostname/name if you don't know the MAC.\nExample:\naa:bb:cc:dd:ee:ff:Andrew's Phone\nKitchen-iPad:Kitchen iPad"
            input "devices", "textarea", title: "Devices", required: true
        }

        section("Polling") {
            input "pollSeconds", "number", title: "Poll interval (seconds)", defaultValue: 30, required: true
        }

        section("Logging") {
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
            input "traceLogging", "bool", title: "Enable trace (HTTP) logging", defaultValue: false
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    initialize()
}

def initialize() {
    state.cookie = null
    state.csrfToken = null
    ensureDeviceChildren()
    runIn(2, "poll")
}

/* ---------------- Child device management ---------------- */

private void ensureDeviceChildren() {
    parseDevices(settings.devices).each { Map d ->
        String dni = "unifi-presence-${sanitize(d.identifier)}"
        if (!getChildDevice(dni)) {
            addChildDevice("unifiPresence", "UniFi Presence Sensor", dni,
                [name: "UniFi Presence", label: d.label, isComponent: true])
            logInfo("Created presence device for ${d.label}")
        }
    }
}

/* ---------------- Poll cycle ---------------- */

private static final long SESSION_REFRESH_BUFFER_MS = 60000

def poll() {
    boolean sessionFresh = state.cookie &&
        (!state.sessionExpiresAt || now() < (state.sessionExpiresAt as Long) - SESSION_REFRESH_BUFFER_MS)
    if (sessionFresh) {
        fetchClients()
    } else {
        login()
    }
}

private void schedulePollNext() {
    runIn(safeInt(settings.pollSeconds, 30), "poll")
}

private Map httpBaseParams() {
    return [
        uri              : "https://${settings.host}:${settings.port ?: 443}",
        ignoreSSLIssues  : true,
        timeout          : 10
    ]
}

def login(Map data = [:]) {
    Map params = httpBaseParams() + [
        path       : "/api/auth/login",
        contentType: "application/json",
        requestContentType: "application/json",
        body       : [username: settings.username, password: settings.password]
    ]
    if (settings.traceLogging) log.debug "UniFi Presence: POST ${params.path}"
    asynchttpPost("loginResponseHandler", params, data)
}

def loginResponseHandler(resp, data) {
    if (resp.hasError()) {
        logWarn("Login failed: ${resp.getErrorMessage()}")
        state.cookie = null
        state.csrfToken = null
        state.sessionExpiresAt = null
        schedulePollNext()
        return
    }

    if (settings.traceLogging) {
        log.debug "UniFi Presence: login response status ${resp.status}, headers: ${resp.headers}"
    }

    Map extracted = extractSession(resp.headers)
    if (!extracted.cookie) {
        logWarn("Login succeeded but no session cookie was found in the response headers")
    }
    state.cookie = extracted.cookie
    state.csrfToken = extracted.csrfToken
    state.sessionExpiresAt = extracted.expiresAt
    logDebug("Login OK, csrfToken present: ${extracted.csrfToken != null}, expiresAt: ${extracted.expiresAt}")
    if (settings.traceLogging) {
        log.debug "UniFi Presence: captured cookie: ${extracted.cookie}, csrfToken: ${extracted.csrfToken}"
    }

    fetchClients(data)
}

def fetchClients(Map data = [:]) {
    Map headers = [:]
    if (state.cookie) headers["Cookie"] = state.cookie
    if (state.csrfToken) headers["X-Csrf-Token"] = state.csrfToken

    Map params = httpBaseParams() + [
        path   : "/proxy/network/api/s/${settings.site ?: 'default'}/stat/sta",
        headers: headers
    ]
    if (settings.traceLogging) log.debug "UniFi Presence: GET ${params.path}, headers: ${headers}"
    asynchttpGet("clientsResponseHandler", params, data)
}

def clientsResponseHandler(resp, data) {
    if (resp.hasError() || resp.status == 401) {
        state.cookie = null
        state.csrfToken = null
        state.sessionExpiresAt = null
        if (settings.traceLogging) {
            log.debug "UniFi Presence: failed response headers: ${resp.headers}, body: ${resp.getErrorMessage() ?: resp.getData()}"
        }

        if (resp.status == 401 && !(data?.retried)) {
            logWarn("Client fetch failed (status 401); retrying with a fresh login")
            login([retried: true])
        } else {
            logWarn("Client fetch failed (status ${resp.status}); will re-login next cycle")
            schedulePollNext()
        }
        return
    }

    List clients = []
    try {
        def json = resp.getJson()
        clients = (json?.data instanceof List) ? json.data : []
    } catch (e) {
        logWarn("Could not parse client list response: ${e}")
    }

    updatePresence(clients)
    schedulePollNext()
}

private void updatePresence(List clients) {
    parseDevices(settings.devices).each { Map d ->
        boolean present = matchClient(d, clients)
        String dni = "unifi-presence-${sanitize(d.identifier)}"
        getChildDevice(dni)?.setPresence(present)
        logDebug("${d.label}: ${present ? 'present' : 'not present'}")
    }
}

/* ---------------- Pure helper logic (unit-testable) ---------------- */

static Map extractSession(Map headers) {
    String cookie = null
    String csrf = null
    Long expiresAt = null

    (headers ?: [:]).each { String k, v ->
        String key = k?.toLowerCase()
        String val = v?.toString()
        // Hubitat's asynchttp headers map returns each value as the raw header
        // line ("HeaderName: value"), so strip the redundant name prefix back off.
        if (val && k && val.toLowerCase().startsWith("${key}:")) {
            val = val.substring(k.length() + 1).trim()
        }
        if (key == "set-cookie") {
            String pair = val?.split(";")?.getAt(0)
            if (pair) cookie = cookie ? "${cookie}; ${pair}" : pair
        } else if (key == "x-token-expire-time") {
            try { expiresAt = Long.parseLong(val) } catch (ignored) { }
        } else if (key?.contains("csrf")) {
            csrf = val
        }
    }

    return [cookie: cookie, csrfToken: csrf, expiresAt: expiresAt]
}

static List<Map> parseDevices(String raw) {
    List<Map> result = []
    if (!raw) return result

    raw.split("\n").each { String line ->
        String cleaned = line?.trim()
        if (!cleaned || cleaned.startsWith("#")) return

        int idx = cleaned.lastIndexOf(":")
        if (cleaned.count(":") > 1 && looksLikeMac(cleaned)) {
            // MAC addresses contain colons themselves (5 of them); split on the
            // 6th colon-delimited group boundary instead of the last colon.
            List<String> parts = cleaned.split(":") as List<String>
            if (parts.size() >= 7) {
                String mac = parts[0..5].join(":")
                String label = parts[6..-1].join(":")
                result << [identifier: mac.toLowerCase(), label: label ?: mac]
                return
            }
        }

        if (idx <= 0) return
        String identifier = cleaned.substring(0, idx).trim()
        String label = cleaned.substring(idx + 1).trim()
        result << [identifier: identifier, label: label ?: identifier]
    }
    return result
}

static boolean looksLikeMac(String s) {
    return s ==~ /(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}.*/
}

static boolean matchClient(Map device, List clients) {
    String identifier = (device.identifier ?: "").toLowerCase()
    boolean isMac = looksLikeMac(identifier)

    return clients.any { client ->
        if (!(client instanceof Map)) return false
        if (isMac) {
            return (client.mac ?: "").toString().toLowerCase() == identifier
        }
        String name = (client.name ?: client.hostname ?: "").toString().toLowerCase()
        return name == identifier
    }
}

/* ---------------- Misc helpers ---------------- */

private String sanitize(String s) {
    return (s ?: "").toLowerCase().replaceAll(/[^a-z0-9]/, "-")
}

private Integer safeInt(def val, Integer fallback) {
    try {
        return (val == null) ? fallback : Integer.parseInt(val.toString())
    } catch (ignored) {
        return fallback
    }
}

private void logInfo(String msg) { log.info "UniFi Presence: ${msg}" }
private void logWarn(String msg) { log.warn "UniFi Presence: ${msg}" }
private void logDebug(String msg) { if (settings.debugLogging) log.debug "UniFi Presence: ${msg}" }
