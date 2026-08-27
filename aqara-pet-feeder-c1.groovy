/**
 * Aqara Pet Feeder C1 (aqara.feeder.acn001) - Custom Hubitat Driver
 *
 * Reverse-engineered from the open-source ZHA quirk:
 * https://github.com/zigpy/zha-device-handlers/blob/dev/zhaquirks/xiaomi/aqara/feeder_acn001.py
 * and the Zigbee2MQTT converter (which additionally implements scheduling):
 * https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/lumi.ts (lumi_feeder)
 *
 * Everything is tunneled through one opaque manufacturer attribute (0xFFF1) on the
 * Aqara "Opple" cluster (0xFCC0), manufacturer code 0x115F. The value written/read
 * on that attribute is a small custom binary envelope:
 *
 *   byte 0-2 : 00 02 <seq>        header + rolling sequence number
 *   byte 3-6 : <aqaraId, int32 BE> which sub-function this message is about
 *   byte 7   : <length>            length of the value that follows
 *   byte 8.. : <value>              big-endian, size = length
 *
 * Live-tested against real hardware. Initial attempt sent the inner envelope as
 * the attribute value with no results (command reached the device, zero response,
 * no dispense) - zigbee.writeAttribute() does NOT auto-prepend the ZCL octet-string
 * length byte for data type 0x41, so sendFeederCommand() prepends it explicitly.
 *
 * On-device scheduling (setSchedule/setMode) is implemented per the Zigbee2MQTT
 * converter's format and live-tested: the device acknowledged the write with a
 * ZCL Write Attributes Response of SUCCESS, and its own readback report decoded
 * back to exactly the schedule that was sent. Note Hubitat's JSON_OBJECT command
 * parameter arrives as a raw JSON string, not a parsed object - setSchedule()
 * calls parseJson() on it explicitly.
 *
 * The device's schedule hour/minute fields are in UTC, not local time - confirmed
 * live (a schedule entry of hour=5 fired at 23:00 local / MDT, UTC-6). setSchedule()
 * takes local time and converts to UTC using Hubitat's location.timeZone (handles
 * DST automatically since it's computed at call time, not hardcoded); parseSchedule()
 * converts back to local for the exposed `schedule` attribute. Crossing midnight
 * during the conversion shifts which day-of-week bit is set, so that's handled too.
 */
import groovy.json.JsonOutput

metadata {
    definition(name: "Aqara Pet Feeder C1", namespace: "repulsor", author: "TheCase") {
        capability "Actuator"
        capability "Refresh"
        capability "Configuration"
        capability "Switch"

        command "feed"
        command "setServingSize", [[name: "Portions*", type: "NUMBER", description: "Portions to dispense on next feed (1-10)"]]
        command "setChildLock", [[name: "Lock*", type: "ENUM", constraints: ["on", "off"]]]
        command "setLed", [[name: "LED*", type: "ENUM", constraints: ["on", "off"]]]
        command "setMode", [[name: "Mode*", type: "ENUM", constraints: ["manual", "schedule"]]]
        command "setSchedule", [[name: "Schedule*", type: "JSON_OBJECT", description: 'List of entries, e.g. [{"days":"everyday","hour":12,"minute":0,"size":1},{"days":"everyday","hour":20,"minute":0,"size":1}]. hour/minute are LOCAL time. days: everyday/workdays/weekend/mon/tue/wed/thu/fri/sat/sun, a comma list like "mon,wed,fri", or a raw bitmask number']]
        command "resyncSchedule"

        attribute "lastFeedingSource", "string"
        attribute "lastFeedingSize", "number"
        attribute "lastFeedingTime", "string"
        attribute "portionsDispensedToday", "number"
        attribute "weightDispensedToday", "number"
        attribute "errorDetected", "string"
        attribute "childLock", "string"
        attribute "led", "string"
        attribute "servingSize", "number"
        attribute "portionWeight", "number"
        attribute "feedingMode", "string"
        attribute "schedule", "string"

        fingerprint profileId: "0104", inClusters: "0000,0003,0004,0005,0006,FCC0", outClusters: "0003,0019", manufacturer: "Aqara", model: "aqara.feeder.acn001", deviceJoinName: "Aqara Pet Feeder C1"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

@groovy.transform.Field static final int CLUSTER_OPPLE = 0xFCC0
@groovy.transform.Field static final int ATTR_FEEDER = 0xFFF1
@groovy.transform.Field static final String MFG_CODE = "0x115F"

// Aqara-side "tags" carried inside the feeder_attr envelope
@groovy.transform.Field static final long AQ_FEEDING = 0x04150055
@groovy.transform.Field static final long AQ_FEEDING_REPORT = 0x041502BC
@groovy.transform.Field static final long AQ_PORTIONS_DISPENSED = 0x0D680055
@groovy.transform.Field static final long AQ_WEIGHT_DISPENSED = 0x0D690055
@groovy.transform.Field static final long AQ_ERROR_DETECTED = 0x0D0B0055
@groovy.transform.Field static final long AQ_SCHEDULING_STRING = 0x080008C8
@groovy.transform.Field static final long AQ_DISABLE_LED = 0x04170055
@groovy.transform.Field static final long AQ_CHILD_LOCK = 0x04160055
@groovy.transform.Field static final long AQ_FEEDING_MODE = 0x04180055
@groovy.transform.Field static final long AQ_SERVING_SIZE = 0x0E5C0055
@groovy.transform.Field static final long AQ_PORTION_WEIGHT = 0x0E5F0055

// Weekday bitmask: mon=1, tue=2, wed=4, thu=8, fri=16, sat=32, sun=64 (OR'd together)
@groovy.transform.Field static final Map<String, Integer> FEEDER_DAYS = [
    "everyday": 127, "workdays": 31, "weekend": 96,
    "mon": 1, "tue": 2, "wed": 4, "thu": 8, "fri": 16, "sat": 32, "sun": 64,
    "mon-wed-fri-sun": 85, "tue-thu-sat": 42
]

def installed() {
    configure()
}

def updated() {
    configure()
}

def configure() {
    if (logEnable) log.debug "configure()"
    def cmds = []
    // Bind + ask the device to proactively report the feeder_attr blob
    cmds += zigbee.configureReporting(CLUSTER_OPPLE, ATTR_FEEDER, 0x41, 0, 3600, null, [mfgCode: MFG_CODE])
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

def refresh() {
    if (logEnable) log.debug "refresh() - attempting read of feeder_attr (device may not respond to reads)"
    def cmds = zigbee.readAttribute(CLUSTER_OPPLE, ATTR_FEEDER, [mfgCode: MFG_CODE])
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

def feed() {
    if (logEnable) log.debug "feed()"
    sendFeederCommand(AQ_FEEDING, [0x01], 1)
}

/**
 * Switch capability, used as a momentary trigger for HomeKit: on() feeds and
 * auto-reverts to "off" shortly after, so a tap in Apple Home's tile triggers
 * a feed and resets itself, ready for the next tap. (PushableButton was
 * tried first but HomeKit maps it to a Stateless Programmable Switch
 * service, which isn't directly tappable in the Home app - confirmed live,
 * it shows up but tapping does nothing. Removed since nothing here uses
 * Hubitat-side button automations either.)
 */
def on() {
    if (logEnable) log.debug "on() - momentary switch, triggers feed()"
    sendEvent(name: "switch", value: "on")
    feed()
    runInMillis(1000, "autoOff")
}

def off() {
    sendEvent(name: "switch", value: "off")
}

def autoOff() {
    sendEvent(name: "switch", value: "off")
}

def setServingSize(BigDecimal portions) {
    int p = portions.intValue()
    if (logEnable) log.debug "setServingSize(${p})"
    // zhaquirks writes serving_size/portion_weight as a 4-byte value even though
    // the field is conceptually a single byte - matching that here.
    sendFeederCommand(AQ_SERVING_SIZE, int32Bytes(p), 4)
}

def setChildLock(String onOff) {
    if (logEnable) log.debug "setChildLock(${onOff})"
    sendFeederCommand(AQ_CHILD_LOCK, [onOff == "on" ? 0x01 : 0x00], 1)
}

def setLed(String onOff) {
    if (logEnable) log.debug "setLed(${onOff})"
    // device attribute is "disable_led_indicator" - invert so the user-facing
    // command reads naturally ("on" = LED lit).
    sendFeederCommand(AQ_DISABLE_LED, [onOff == "on" ? 0x00 : 0x01], 1)
}

def setMode(String mode) {
    if (logEnable) log.debug "setMode(${mode})"
    // On-device schedule only actually fires while mode is "schedule".
    sendFeederCommand(AQ_FEEDING_MODE, [mode == "schedule" ? 0x01 : 0x00], 1)
}

/**
 * Pushes a feeding schedule to the device, per the Zigbee2MQTT lumi_feeder converter.
 * Each entry: [days: <name/comma-list/bitmask>, hour: 0-23, minute: 0-59, size: 1-10].
 * hour/minute are LOCAL time - converted to UTC internally, since that's what the
 * device actually expects (confirmed live). Call setMode("schedule") separately to
 * have the device actually run it.
 */
def setSchedule(entries) {
    if (logEnable) log.debug "setSchedule(${entries})"
    // Hubitat's JSON_OBJECT command parameter arrives as a raw JSON string, not
    // a parsed object - parse it explicitly (confirmed necessary by live testing:
    // without this, entries.collect iterates individual characters of the string
    // and throws MissingPropertyException on item.days).
    List parsedEntries = (entries instanceof String) ? parseJson(entries) : entries
    state.lastScheduleEntries = parsedEntries
    applySchedule(parsedEntries)
}

/**
 * Re-sends the last schedule set via setSchedule(), recomputing the local->UTC
 * conversion with the current timezone offset. The device only stores a fixed
 * UTC time, so a DST transition after setSchedule() silently shifts the local
 * fire time by an hour until this is called again - wire this up to a twice-
 * yearly Rule Machine automation around the usual DST changeover dates.
 */
def resyncSchedule() {
    if (logEnable) log.debug "resyncSchedule()"
    if (!state.lastScheduleEntries) {
        log.warn "resyncSchedule: no schedule has been set yet"
        return
    }
    applySchedule(state.lastScheduleEntries)
}

private void applySchedule(List parsedEntries) {
    List<String> hexEntries = parsedEntries.collect { item ->
        int days = resolveDays(item.days)
        int hour = (item.hour as Integer) ?: 0
        int minute = (item.minute as Integer) ?: 0
        int size = (item.size as Integer) ?: 1
        Map utc = localToUtc(days, hour, minute)
        if (logEnable) log.debug "applySchedule: local ${days}/${hour}:${minute} -> UTC ${utc}"
        [utc.days, utc.hour, utc.minute, size, 0].collect { String.format("%02x", it & 0xFF) }.join()
    }
    String scheduleStr = hexEntries.join(",")
    List<Integer> valueBytes = scheduleStr.getBytes("US-ASCII").collect { (int) (it & 0xFF) } + [0]
    sendFeederCommand(AQ_SCHEDULING_STRING, valueBytes, valueBytes.size())
}

private int resolveDays(daysValue) {
    if (daysValue instanceof Number) return daysValue.intValue()
    String s = daysValue.toString().toLowerCase()
    if (FEEDER_DAYS.containsKey(s)) return FEEDER_DAYS[s]
    int mask = 0
    s.split(",").each { day ->
        String d = day.trim()
        if (FEEDER_DAYS.containsKey(d)) mask |= FEEDER_DAYS[d]
    }
    return mask ?: 0x7f
}

/** mon=bit0..sun=bit6 cyclic rotation, shift can be negative */
private int shiftDayBitmask(int days, int shiftDays) {
    if (shiftDays == 0) return days
    int s = ((shiftDays % 7) + 7) % 7
    int result = 0
    (0..6).each { i ->
        if ((days & (1 << i)) != 0) result |= (1 << ((i + s) % 7))
    }
    return result
}

private int currentUtcOffsetMinutes() {
    return (location.timeZone.getOffset(now()) / 60000) as int
}

/** local hour/minute -> UTC, shifting the day bitmask if the conversion crosses midnight */
private Map localToUtc(int days, int hour, int minute) {
    int offsetMinutes = currentUtcOffsetMinutes()
    int total = hour * 60 + minute - offsetMinutes
    int dayShift = 0
    while (total < 0) { total += 1440; dayShift -= 1 }
    while (total >= 1440) { total -= 1440; dayShift += 1 }
    return [days: shiftDayBitmask(days, dayShift), hour: (total / 60) as int, minute: total % 60]
}

/** UTC hour/minute (as stored on device) -> local, shifting the day bitmask if needed */
private Map utcToLocal(int days, int hour, int minute) {
    int offsetMinutes = currentUtcOffsetMinutes()
    int total = hour * 60 + minute + offsetMinutes
    int dayShift = 0
    while (total < 0) { total += 1440; dayShift -= 1 }
    while (total >= 1440) { total -= 1440; dayShift += 1 }
    return [days: shiftDayBitmask(days, dayShift), hour: (total / 60) as int, minute: total % 60]
}

private void sendFeederCommand(long aqaraId, List<Integer> valueBytes, int length) {
    String envelope = buildFeederAttr(aqaraId, valueBytes, length)
    // Octet String (data type 0x41) requires a leading length byte on the wire.
    // zigbee.writeAttribute() does not appear to add this automatically -
    // prepending it explicitly here (confirmed necessary by live testing: without
    // it, the write reaches the device but gets silently dropped, no response,
    // no dispense).
    String outerLength = String.format("%02X", (envelope.length() / 2) as int)
    String payload = outerLength + envelope
    def cmds = zigbee.writeAttribute(CLUSTER_OPPLE, ATTR_FEEDER, 0x41, payload, [mfgCode: MFG_CODE])
    if (logEnable) log.debug "sendFeederCommand aqaraId=${Long.toHexString(aqaraId)} envelope=${envelope} payload=${payload} cmds=${cmds}"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

/**
 * Builds the inner feeder_attr envelope (header + aqaraId + length + value) as a hex string.
 * The outer ZCL octet-string length byte is added by the caller (sendFeederCommand).
 */
private String buildFeederAttr(long aqaraId, List<Integer> valueBytes, int length) {
    int seq = (state.sendSeq ?: 0)
    state.sendSeq = (seq + 1) % 256

    List<Integer> bytes = [0x00, 0x02, seq]
    bytes << (int) ((aqaraId >> 24) & 0xFF)
    bytes << (int) ((aqaraId >> 16) & 0xFF)
    bytes << (int) ((aqaraId >> 8) & 0xFF)
    bytes << (int) (aqaraId & 0xFF)
    bytes << length
    bytes.addAll(valueBytes)

    return bytes.collect { String.format("%02X", it & 0xFF) }.join()
}

private List<Integer> int32Bytes(int v) {
    return [(v >> 24) & 0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF]
}

def parse(String description) {
    if (logEnable) log.debug "parse: ${description}"
    def descMap = zigbee.parseDescriptionAsMap(description)
    if (logEnable) log.debug "descMap: ${descMap}"

    if (descMap?.clusterInt == CLUSTER_OPPLE && (descMap?.attrInt == ATTR_FEEDER || descMap?.attrId == "FFF1")) {
        parseFeederAttribute(descMap.value)
    }
}

private void parseFeederAttribute(String hexValue) {
    if (!hexValue || hexValue.length() < 16) {
        if (logEnable) log.debug "parseFeederAttribute: value too short: ${hexValue}"
        return
    }
    // byte offsets, 2 hex chars per byte
    long aqaraId = Long.parseLong(hexValue.substring(6, 14), 16)
    int length = Integer.parseInt(hexValue.substring(14, 16), 16)
    String valueHex = hexValue.substring(16, Math.min(16 + length * 2, hexValue.length()))

    if (logEnable) log.debug "parseFeederAttribute: aqaraId=${Long.toHexString(aqaraId)} length=${length} valueHex=${valueHex}"

    switch (aqaraId) {
        case AQ_FEEDING_REPORT:
            String s = new String(hubitat.helper.HexUtils.hexStringToByteArray(valueHex))
            if (s.length() >= 4) {
                // 0=schedule, 1=manual (physical button), 2=remote (zigbee command)
                int sourceCode = Integer.parseInt(s.substring(0, 2))
                String source = ["schedule", "manual", "remote"][sourceCode] ?: "unknown"
                sendEvent(name: "lastFeedingSource", value: source)
                sendEvent(name: "lastFeedingSize", value: Integer.parseInt(s.substring(3, 4)))
                sendEvent(name: "lastFeedingTime", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
            }
            break
        case AQ_PORTIONS_DISPENSED:
            sendEvent(name: "portionsDispensedToday", value: Integer.parseInt(valueHex, 16))
            break
        case AQ_WEIGHT_DISPENSED:
            sendEvent(name: "weightDispensedToday", value: Long.parseLong(valueHex, 16))
            break
        case AQ_ERROR_DETECTED:
            sendEvent(name: "errorDetected", value: (valueHex == "01") ? "true" : "false")
            break
        case AQ_CHILD_LOCK:
            sendEvent(name: "childLock", value: (valueHex == "01") ? "on" : "off")
            break
        case AQ_DISABLE_LED:
            sendEvent(name: "led", value: (valueHex == "01") ? "off" : "on")
            break
        case AQ_FEEDING_MODE:
            sendEvent(name: "feedingMode", value: (valueHex == "01") ? "schedule" : "manual")
            break
        case AQ_SERVING_SIZE:
            sendEvent(name: "servingSize", value: Integer.parseInt(valueHex, 16))
            break
        case AQ_PORTION_WEIGHT:
            sendEvent(name: "portionWeight", value: Integer.parseInt(valueHex, 16))
            break
        case AQ_SCHEDULING_STRING:
            parseSchedule(valueHex)
            break
        default:
            if (logEnable) log.debug "unhandled aqaraId ${Long.toHexString(aqaraId)}: ${valueHex}"
    }
}

private void parseSchedule(String valueHex) {
    String scheduleAscii = new String(hubitat.helper.HexUtils.hexStringToByteArray(valueHex), "US-ASCII")
    List entries = []
    scheduleAscii.split(",").each { entryStr ->
        String hexPart = entryStr.replaceAll(/[^0-9a-fA-F]/, "")
        if (hexPart.length() >= 8) {
            int utcDays = Integer.parseInt(hexPart.substring(0, 2), 16)
            int utcHour = Integer.parseInt(hexPart.substring(2, 4), 16)
            int utcMinute = Integer.parseInt(hexPart.substring(4, 6), 16)
            int size = Integer.parseInt(hexPart.substring(6, 8), 16)
            // Device stores UTC - convert back to local for display, matching what
            // was originally passed to setSchedule().
            Map local = utcToLocal(utcDays, utcHour, utcMinute)
            String dayName = FEEDER_DAYS.find { it.value == local.days }?.key
            entries << [days: dayName ?: local.days, hour: local.hour, minute: local.minute, size: size]
        }
    }
    if (logEnable) log.debug "parseSchedule: ${entries}"
    sendEvent(name: "schedule", value: JsonOutput.toJson(entries))
}
