/**
 *  UniFi Presence Sensor
 *
 *  Child device representing one tracked client (phone) on the UniFi network.
 *  State is pushed entirely by the parent "UniFi Presence Integration" app.
 */
metadata {
    definition(name: "UniFi Presence Sensor", namespace: "unifiPresence", author: "Custom") {
        capability "PresenceSensor"
        capability "Sensor"

        attribute "lastSeen", "string"
    }
}

def installed() {
    sendEvent(name: "presence", value: "not present")
}

def setPresence(boolean present) {
    String value = present ? "present" : "not present"
    if (device.currentValue("presence") != value) {
        sendEvent(name: "presence", value: value, isStateChange: true)
    }
    if (present) {
        sendEvent(name: "lastSeen", value: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone))
    }
}
