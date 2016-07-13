/*
* Copyright 2016 SmartThings
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License. You may obtain a copy
* of the License at:
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations
* under the License.
*/

metadata {
    definition (name: "leakSmart Moisture Sensor",namespace: "smartthings", author: "SmartThings", category: "C2") {
    capability "Configuration"
    capability "Battery"
    capability "Refresh"
    capability "Temperature Measurement"
    capability "Water Sensor"

    fingerprint inClusters: "0000,0001,0003,0020,0402,0B02,FC02", outClusters: "0003,0019", manufacturer: "Waxman", model: "leakSmartv2", deviceJoinName: "LeakSmart Sensor"
}

simulator {

}

preferences {
    section {
        image(name: 'educationalcontent', multiple: true, images: [
			"http://cdn.device-gse.smartthings.com/Moisture/Moisture1.png",
			"http://cdn.device-gse.smartthings.com/Moisture/Moisture2.png",
			"http://cdn.device-gse.smartthings.com/Moisture/Moisture3.png"
        ])
    }
    section {
        input title: "Temperature Offset", description: "This feature allows you to correct any temperature variations by selecting an offset. Ex: If your sensor consistently reports a temp that's 5 degrees too warm, you'd enter '-5'. If 3 degrees too cold, enter '+3'.", displayDuringSetup: false, type: "paragraph", element: "paragraph"
        input "tempOffset", "number", title: "Degrees", description: "Adjust temperature by this many degrees", range: "*..*", displayDuringSetup: false
    }
}

tiles(scale: 2) {
    multiAttributeTile(name:"water", type: "generic", width: 6, height: 4){
        tileAttribute ("device.water", key: "PRIMARY_CONTROL") {
            attributeState "dry", label: "Dry", icon:"st.alarm.water.dry", backgroundColor:"#ffffff"
            attributeState "wet", label: "Wet", icon:"st.alarm.water.wet", backgroundColor:"#53a7c0"
        }
    }
    valueTile("temperature", "device.temperature", inactiveLabel: false, width: 2, height: 2) {
        state "temperature", label:'${currentValue}°',
        backgroundColors:[
        [value: 31, color: "#153591"],
        [value: 44, color: "#1e9cbb"],
        [value: 59, color: "#90d2a7"],
        [value: 74, color: "#44b621"],
        [value: 84, color: "#f1d801"],
        [value: 95, color: "#d04e00"],
        [value: 96, color: "#bc2323"]
    ]
    }
    valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
        state "battery", label:'${currentValue}% battery', unit:""
    }
    standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
        state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
    }

    main (["water", "temperature"])
    details(["water", "temperature", "battery", "refresh"])
}
}

def parse(String description) {
    log.debug "description: $description"

    Map map = [:]
    if (description?.startsWith('catchall:')) {
    	map = parseCatchAllMessage(description)
    }
    else if (description?.startsWith('read attr -')) {
    	map = parseReportAttributeMessage(description)
    }
    else if (description?.startsWith('temperature: ')) {
    	map = parseCustomMessage(description)
    }
    else if (description?.startsWith('zone status')) {
        log.debug "ZONE STATUS"
        map = parseIasMessage(description)
	}

    log.debug "Parse returned $map"
	def result = map ? createEvent(map) : null

	if (description?.startsWith('enroll request')) {
        List cmds = enrollResponse()
        log.debug "enroll response: ${cmds}"
        result = cmds?.collect { new physicalgraph.device.HubAction(it) }
	}
	return result
}

private Map parseCatchAllMessage(String description) {
    Map resultMap = [:]
    def cluster = zigbee.parse(description)
    if (shouldProcessMessage(cluster)) {
        switch(cluster.clusterId) {
            case 0x0001:
                log.debug "001 Cluster Data: ${cluster.data}"
                resultMap = getBatteryResult(cluster.data.last())
                break
            case 0x0402:
               // temp is last 2 data values. reverse to swap endian
                log.debug "402 Cluster Data: ${cluster.data}"
                String temp = cluster.data[-2..-1].reverse().collect { cluster.hex1(it) }.join()
                def value = getTemperature(temp)
                resultMap = getTemperatureResult(value)
                break
            case 0x0b02:
                log.debug "B02 Cluster Data: ${cluster.data}"
                String temp = cluster.data[2];
                log.debug "B02 temp data ${temp}"
                return parseAlarmCode(temp)
                break
        }
    }
    else {
    	log.debug "Did not process message ${cluster}"
    }
    return resultMap
}

private boolean shouldProcessMessage(cluster) {
    // 0x0B is default response indicating message got through
    // 0x07 is bind message
    boolean ignoredMessage = cluster.profileId != 0x0104 ||
    cluster.command == 0x0B ||
    cluster.command == 0x07 ||
    (cluster.data.size() > 0 && cluster.data.first() == 0x3e)
    return !ignoredMessage
}

private Map parseReportAttributeMessage(String description) {
    Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
    def nameAndValue = param.split(":")
    map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
    log.debug "Desc Map: $descMap"
    Map resultMap = [:]
    if (descMap.cluster == "0402" && descMap.attrId == "0000") {
        def value = getTemperature(descMap.value)
        resultMap = getTemperatureResult(value)
    }
    else if (descMap.cluster == "0001" && descMap.attrId == "0020") {
        resultMap = getBatteryResult(Integer.parseInt(descMap.value, 16))
    }
	return resultMap
}

private Map parseCustomMessage(String description) {
    Map resultMap = [:]
    if (description?.startsWith('temperature: ')) {
        def value = zigbee.parseHATemperatureValue(description, "temperature: ", getTemperatureScale())
        resultMap = getTemperatureResult(value)
	}
	return resultMap
}

private Map parseIasMessage(String description) {
    List parsedMsg = description.split(' ')
    String msgCode = parsedMsg[2]

    log.debug "Parse IAS MESSAGE"
    log.debug msgCode

    Map resultMap = [:]
    switch(msgCode) {
    
        case '0x0020': // Closed/No Motion/Dry
            resultMap = getMoistureResult('dry')
            break

        case '0x0021': // Open/Motion/Wet
            resultMap = getMoistureResult('wet')
            break

        case '0x0022': // Tamper Alarm
            break

        case '0x0023': // Battery Alarm
            break

        case '0x0024': // Supervision Report
            log.debug 'dry with tamper alarm'
            resultMap = getMoistureResult('dry')
            break

        case '0x0025': // Restore Report
            log.debug 'water with tamper alarm'
            resultMap = getMoistureResult('wet')
            break

        case '0x0026': // Trouble/Failure
            break

        case '0x0028': // Test Mode
            break
    }
    return resultMap
}

def getTemperature(value) {
    def celsius = Integer.parseInt(value, 16).shortValue() / 100
    if(getTemperatureScale() == "C"){
    	return celsius
    } else {
    	return celsiusToFahrenheit(celsius) as Integer
    }
}

private Map getBatteryResult(rawValue) {
    log.debug "Battery rawValue = ${rawValue}"
    def linkText = getLinkText(device)

    def result = [
        name: 'battery',
        value: '--',
        translatable: true
    ]

    def volts = rawValue / 10

    if (rawValue == 0 || rawValue == 255) {}
    else {
        if (volts > 4.8) {
            result.value = 100
            result.descriptionText = "{{ device.displayName }} battery has too much power: (> 4.8) volts."
        } else {
            def minVolts = 2.1
            def maxVolts = 4.7
            def pct = (volts - minVolts) / (maxVolts - minVolts)
            result.value = Math.min(100, (int) pct * 100)
            result.descriptionText = "{{ device.displayName }} battery was {{ value }}%"
        }
    }
    return result
}

private Map getTemperatureResult(value) {
    log.debug 'TEMP'
    if (tempOffset) {
        def offset = tempOffset as int
        def v = value as int
        value = v + offset
    }
    def descriptionText
    if ( temperatureScale == 'C' )
        descriptionText = '{{ device.displayName }} was {{ value }}°C'
    else
    	descriptionText = '{{ device.displayName }} was {{ value }}°F'

    return [
        name: 'temperature',
        value: value,
        descriptionText: descriptionText,
        translatable: true
    ]
}

private Map getMoistureResult(value) {
    log.debug "water"
    def descriptionText
    if ( value == "wet" )
    	descriptionText = '{{ device.displayName }} is wet'
    else
    	descriptionText = '{{ device.displayName }} is dry'
    return [
        name: 'water',
        value: value,
        descriptionText: descriptionText,
        translatable: true
    ]
}

private Map parseAlarmCode(value) {
    log.debug "parse alarm code ${value}"
    Map resultMap = [:]
    switch(value) {
        case "1": // Closed/No Motion/Dry
            log.debug "dry"
            resultMap = getMoistureResult('dry')
            break

        case "17": // Open/Motion/Wet
            log.debug "wet"
            resultMap = getMoistureResult('wet')
            break
	}
    return resultMap
}

def refresh() {
    log.debug "Refreshing"
    def refreshCmds = [
    //zigbee.readAttribute(0x0402, 0x0000), "delay 200",
    //zigbee.readAttribute(0x0001, 0x0020), "delay 200",
    zigbee.readAttribute(0x0b02, 0x0000), "delay 200",
    //zigbee.readAttribute(0xFC02, 0x0001), "delay 200"
    	"st rattr 0x${device.deviceNetworkId} 1 0x402 0", "delay 200",
    	"st rattr 0x${device.deviceNetworkId} 1 1 0x20", "delay 200"
	]

	return refreshCmds + enrollResponse()
}

def configure() {
    sendEvent(name: "checkInterval", value: 7200, displayed: true)

    String zigbeeEui = swapEndianHex(device.hub.zigbeeEui)
    log.debug "Configuring Reporting, IAS CIE, and Bindings."
    def configCmds = [
    //	"zcl global write 0x500 0x10 0xf0 {${zigbeeEui}}", "delay 200",
    //	"send 0x${device.deviceNetworkId} 1 1", "delay 500",

        zigbee.configureReporting(0x0001, 0x0020, 0x20, 30, 21600, 0x01), "delay 500",
        zigbee.configureReporting(0x0402, 0x0000, 0x29, 30, 3600, 0x0064), "delay 500",
        zigbee.configureReporting(0x0b02, 0x0000, 0x10, 0, 3600, null), "delay 500"
    //	"zdo bind 0x${device.deviceNetworkId} ${endpointId} 1 1 {${device.zigbeeId}} {}", "delay 500",
    //	"zcl global send-me-a-report 1 0x20 0x20 30 21600 {01}",	//checkin time 6 hrs
    //	"send 0x${device.deviceNetworkId} 1 1", "delay 500",

    //	"zdo bind 0x${device.deviceNetworkId} ${endpointId} 1 0x402 {${device.zigbeeId}} {}", "delay 500",
    //	"zcl global send-me-a-report 0x402 0 0x29 30 3600 {6400}",
    //	"send 0x${device.deviceNetworkId} 1 1", "delay 500",

    ]
    def tmp = zigbee.configureReporting(0x0500, 0x0000, 0x10, 0, 3600, null)
    log.debug "Command for 500 report: ${tmp}"

    return configCmds + refresh() // send refresh cmds as part of config
}

def enrollResponse() {
	log.debug "Sending enroll response"
/* String zigbeeEui = swapEndianHex(device.hub.zigbeeEui)
[
//Resending the CIE in case the enroll request is sent before CIE is written
"zcl global write 0x500 0x10 0xf0 {${zigbeeEui}}", "delay 200",
"send 0x${device.deviceNetworkId} 1 ${endpointId}", "delay 500",
//Enroll Response
"raw 0x500 {01 23 00 00 00}",
"send 0x${device.deviceNetworkId} 1 1", "delay 200"
]*/
}

private getEndpointId() {
	new BigInteger(device.endpointId, 16).toString()
}

private hex(value) {
	new BigInteger(Math.round(value).toString()).toString(16)
}

private String swapEndianHex(String hex) {
	reverseArray(hex.decodeHex()).encodeHex()
}

private byte[] reverseArray(byte[] array) {
    int i = 0;
    int j = array.length - 1;
    byte tmp;
    while (j > i) {
        tmp = array[j];
        array[j] = array[i];
        array[i] = tmp;
        j--;
        i++;
    }
    return array
}