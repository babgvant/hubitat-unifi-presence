# Hubitat UniFi Presence Integration

Direct local presence detection against a UniFi OS console (UDM, UDM-Pro, Cloud Key
Gen2+) — no cloud, no separate proxy service. Polls the console's REST API for
connected clients and reflects presence for a configured list of devices onto child
presence-sensor devices.

## Contents

- `apps/UniFi-Presence-App.groovy` — polling app; logs into the controller, fetches
  the active-client list, matches configured devices by MAC or name, and updates
  child devices
- `drivers/UniFi-Presence-Sensor.groovy` — one per tracked device, standard
  `PresenceSensor` capability

## Install

1. In Hubitat, open **Drivers Code**, **New Driver**, paste in
   `drivers/UniFi-Presence-Sensor.groovy`, save.
2. Open **Apps Code**, **New App**, paste in `apps/UniFi-Presence-App.groovy`, save.
3. From **Apps**, **Add User App**, choose **UniFi Presence Integration**.
4. Enter the controller's IP, port (443 for a UniFi OS console), username, password,
   and site name (usually `default`).
5. List the devices to track, one per line: `identifier:Label`. Use a MAC address
   (`aa:bb:cc:dd:ee:ff:Andrew's Phone`) for the most reliable match, or a client
   hostname/name if you don't know the MAC.

## Status

Verified offline (Groovy compile check through class generation, plus unit tests for
device-list parsing, MAC/name matching, and session-header extraction). **Not yet
verified against a real UniFi OS console.** The riskiest unverified piece is the
login/session handshake:

- Login is `POST /api/auth/login` with a JSON body; UniFi OS returns a session cookie
  and, on some firmware versions, a CSRF token that must be echoed back as
  `X-Csrf-Token` on later requests. This integration captures both automatically from
  the login response headers, whichever are present.
- The client list is fetched from `/proxy/network/api/s/<site>/stat/sta` — the
  `/proxy/network` prefix is specific to UniFi OS consoles and differs from a classic
  self-hosted controller's `/api/s/<site>/stat/sta`. If you're on a classic controller
  instead, this integration needs that path changed.
- Unlike re-logging in on every poll cycle, this integration keeps the session across
  polls and only re-authenticates when a request comes back unauthorized — cheaper,
  but means a session-expiry edge case could theoretically go unnoticed for one poll
  cycle longer than a naive per-cycle relogin would.

Before relying on it, enable trace logging on the app and confirm against the
controller's own request logs (or a browser dev-tools session against the same login
flow) that the cookie/CSRF handling actually works on your firmware version.
