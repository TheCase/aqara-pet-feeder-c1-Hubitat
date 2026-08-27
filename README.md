# Aqara Pet Feeder C1 — Hubitat Driver

Custom Hubitat driver for the Aqara Smart Pet Feeder C1 (`aqara.feeder.acn001` /
ZNCWWSQ01LM), talking to it directly over Zigbee — no Aqara hub, no Home
Assistant, no Zigbee2MQTT required. Just pair the feeder to your Hubitat hub
like any other Zigbee device and assign it this driver.

## Status

**Verified working against real hardware**, including on-device scheduling:

- `feed()`, `setServingSize()`, and status reporting (`lastFeedingSource`,
  `portionsDispensedToday`, `weightDispensedToday`, etc.) — triggering
  `feed()` produces an audible dispense, and the device's own follow-up
  report correctly shows `lastFeedingSource: remote` with an incremented
  `portionsDispensedToday`.
- `setSchedule()` — the device responded with a ZCL Write Attributes Response
  status `SUCCESS`, and its own readback report decoded back to exactly the
  schedule that was sent (round-tripped correctly).
- Schedule times are UTC on the device, confirmed live (an entry of hour=5
  fired at 23:00 local / MDT, UTC-6) — the driver converts local↔UTC for you
  automatically. See [Timezones and DST](#timezones-and-dst).

Reverse-engineered from two sources: the open-source ZHA quirk
([`feeder_acn001.py`](https://github.com/zigpy/zha-device-handlers/blob/dev/zhaquirks/xiaomi/aqara/feeder_acn001.py)),
which covers feed/status but explicitly does not implement scheduling, and
the actively-maintained Zigbee2MQTT converter
([`lumi.ts`](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/lumi.ts),
`lumi_feeder`), which does implement scheduling and was the source for
`setSchedule()`/`setMode()`.

Two real divergences found during live testing:

1. `zigbee.writeAttribute()` does **not** auto-prepend the ZCL octet-string
   length byte for data type `0x41` — the initial version assumed it did,
   which sent a well-formed inner envelope that the device silently dropped
   (command delivered, zero response, no dispense). `sendFeederCommand()`
   now prepends that length byte explicitly.
2. Hubitat's `JSON_OBJECT` command parameter type passes the raw JSON
   **string**, not a parsed object — `setSchedule()` threw
   `MissingPropertyException` iterating individual characters of the string
   until `parseJson()` was called explicitly first.

## Why a custom driver

The C1 is Zigbee-only (no Wi-Fi/Bluetooth), and Hubitat has no built-in
support for it. Aqara devices generally don't follow the standard Zigbee HA
profile — this one tunnels every function through a single manufacturer
attribute rather than exposing normal ZCL attributes, which is why generic
Hubitat drivers can pair with it but can't do anything useful.

## Installation

1. Pair the feeder to your Hubitat hub as a normal Zigbee device (it'll show
   up with no working driver — that's expected).
2. **Drivers Code → New Driver**, paste in `aqara-pet-feeder-c1.groovy`,
   **Save**.
3. Go to the feeder's device page → **Type** → select **Aqara Pet Feeder
   C1** → **Save Device**.
4. Click **Configure** once to bind attribute reporting.

## Commands

| Command | Description |
|---|---|
| `feed()` | Trigger an immediate dispense at the current serving size |
| `setServingSize(portions)` | Set portions dispensed per feed (1–10) |
| `setChildLock(on/off)` | Enable/disable the physical child lock |
| `setLed(on/off)` | Enable/disable the status LED |
| `refresh()` | Attempt to re-sync state (device may not respond to reads — status is normally push/report-driven) |
| `configure()` | Re-bind attribute reporting |
| `setMode(manual/schedule)` | Switch between manual-only and on-device schedule. The schedule only actually fires while mode is `"schedule"` |
| `setSchedule(entries)` | Push a feeding schedule. JSON list, e.g. `[{"days":"everyday","hour":12,"minute":0,"size":1},{"days":"everyday","hour":20,"minute":0,"size":1}]`. `hour`/`minute` are **local time**. `days` accepts a named value (`everyday`, `workdays`, `weekend`, `mon`..`sun`), a comma list (`"mon,wed,fri"`), or a raw bitmask number |
| `resyncSchedule()` | Re-sends the last schedule passed to `setSchedule()`, recomputing the local→UTC conversion with the current offset. Call after a DST transition — see [Timezones and DST](#timezones-and-dst) |

## Attributes

`lastFeedingSource`, `lastFeedingSize`, `lastFeedingTime` (local timestamp,
set whenever a feeding report arrives), `portionsDispensedToday`,
`weightDispensedToday`, `errorDetected`, `childLock`, `led`, `servingSize`,
`portionWeight`, `feedingMode`, `schedule` (JSON string of the device's
current schedule in local time, updated whenever it reports one back).

## On-device scheduling

`setSchedule()` pushes a schedule to the feeder's own memory — it'll keep
running even if Hubitat is offline. Call `setMode("schedule")` to activate
it (`setMode("manual")` disables it without clearing the stored entries).

Each entry is `{days, hour, minute, size}`:

- `days`: `everyday`, `workdays` (mon–fri), `weekend` (sat–sun), a single day
  (`mon`..`sun`), a comma list (`"mon,wed,fri"`), or a raw weekday bitmask
  (mon=1, tue=2, wed=4, thu=8, fri=16, sat=32, sun=64, OR'd together)
- `hour` / `minute`: 0–23 / 0–59
- `size`: portions for that feeding

If you'd rather drive feeding entirely from Hubitat instead (e.g. to
integrate with other automations/conditions), `feed()` + Rule Machine /
Simple Automation Rules works just as well — `setSchedule()` is there for
when you want it to survive a Hubitat outage.

## Timezones and DST

The device stores schedule times in UTC, not local time (confirmed live —
see [Status](#status)). `setSchedule()` converts the `hour`/`minute` you
give it from local time to UTC using Hubitat's `location.timeZone`, computed
fresh at the moment the command runs — so it's correct for whatever offset
is active right then, including DST. `parseSchedule()` converts the
device's UTC readback back to local for the `schedule` attribute, so what
you read back matches what you originally sent. If the local→UTC conversion
crosses midnight (e.g. an 11pm entry becoming 5am UTC the next day), the
day-of-week bitmask shifts accordingly so the entry still fires on the
right local day.

**The catch**: the device only stores a fixed UTC time — it has no concept
of DST itself. So a schedule set correctly today will silently fire an hour
off in local time after the next DST transition, until re-sent. Call
`resyncSchedule()` (re-sends the last schedule passed to `setSchedule()`,
recomputed with the current offset) to fix this — the simplest approach is
one Rule Machine automation with a time trigger a day or two after each of
the year's two DST changeover dates, calling `resyncSchedule()`.

## Protocol notes

Everything routes through one opaque attribute:

- Cluster `0xFCC0` (Aqara "Opple" manufacturer cluster), endpoint 1
- Manufacturer code `0x115F`
- Attribute `0xFFF1` (octet string) — a hand-rolled binary envelope, not a
  normal typed ZCL attribute

Envelope format (all values written into/read from attribute `0xFFF1`):

```
byte 0-2  00 02 <seq>         header + rolling sequence number
byte 3-6  <aqaraId, int32BE>  which sub-function this message concerns
byte 7    <length>            length of the value that follows
byte 8+   <value>             big-endian, size = length
```

Aqara sub-function IDs:

| Function | ID | Notes |
|---|---|---|
| Feed now | `0x04150055` | Bool, length 1 |
| Feeding report (readback) | `0x041502BC` | UTF-8 string: source char + size hex digit |
| Portions dispensed today | `0x0D680055` | uint16 BE |
| Weight dispensed today | `0x0D690055` | uint32 BE |
| Error detected | `0x0D0B0055` | Bool |
| LED indicator disabled | `0x04170055` | Bool (inverted in this driver's `led` attribute/command) |
| Child lock | `0x04160055` | Bool |
| Feeding mode | `0x04180055` | 0 = Manual, 1 = Schedule |
| Serving size | `0x0E5C0055` | Written as length 4 (matches upstream quirk, even though conceptually 1 byte) |
| Portion weight (g) | `0x0E5F0055` | Written as length 4 |
| Schedule string | `0x080008C8` | See below — ASCII text, not fixed binary |

The schedule string is a special case: its value isn't fixed-width binary
like the others, it's **ASCII text**. Each entry is 5 raw bytes
`[days, hour, minute, size, 0x00]`, hex-encoded to a 10-character string,
multiple entries comma-joined, and the whole joined string (as ASCII bytes,
not decoded hex) gets a trailing `0x00` and becomes the envelope's value —
e.g. two entries at 12:00 and 20:00 (both `everyday`, size 1) becomes the
literal ASCII text `"7f0c000100,7f14000100"` + a null byte. Decoding a
readback report reverses this: hex-decode the envelope value to ASCII, split
on `,`, hex-decode each 10-char entry back to its 5 raw bytes.

## Known risks

- **Sequence numbering**: the upstream Python quirk increments its sequence
  counter twice per call (an apparent quirk/bug in the original code, landing
  on odd numbers only: 1, 3, 5…). This driver uses a simple single-increment
  counter instead — functionally should be equivalent (devices shouldn't
  care about specific gaps), but noting the divergence.
- **No battery/power reporting**: the device doesn't expose a Power
  Configuration cluster in its signature, so no battery attribute is
  implemented.
- **Reads may not work**: `refresh()` attempts a manufacturer-specific read
  of attribute `0xFFF1`, but the upstream quirk only ever *parses* reports —
  it never issues reads — so the device may not respond meaningfully to one.
  State is primarily push-driven via `configureReporting`.

## Debugging

Enable debug logging in the driver's preferences, open Hubitat's live
**Logs** view, and watch:

- `sendFeederCommand` lines when you issue a command — confirms what was
  sent.
- `parse:` / `descMap:` lines for anything the device reports back.
- `parseFeederAttribute` lines decoding incoming reports.

If commands go out but nothing happens on the device, and no reports come
back, the framing is the first thing to check (see Known risks above).
