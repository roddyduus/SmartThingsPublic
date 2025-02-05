/**
 *	Copyright 2022 SmartThings
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 */
import physicalgraph.zigbee.zcl.DataType

metadata {
	definition (name: "Eva Meter Reader", namespace: "EVA", author: "Roddy Duus", mnmn: "SmartThingsCommunity", ocfDeviceType: "x.com.st.d.energymeter", vid: "92543bd9-8a3c-3c8a-b43a-036a6a4bea9d") {
		capability "Energy Meter"
		capability "Power Meter"
		capability "Refresh"
		capability "Health Check"
		capability "Sensor"
		capability "Configuration"
		capability "Voltage Measurement"
		capability "afterguide46998.currentMeasurement"
		capability "Temperature Measurement"
		
		fingerprint profileId: "0104", manufacturer: "Eva", model: "Meter Reader", deviceJoinName: "Eva Meter Reader" // Single Phase, Eva Meter Reader 01 0104 0053 01 07 0000 0003 0402 0702 0B01 0B04 FEED 02 0003 0019	}
}

}

def getATTRIBUTE_READING_INFO_SET() { 0x0000 }
def getATTRIBUTE_HISTORICAL_CONSUMPTION() { 0x0400 }
def getATTRIBUTE_ACTIVE_POWER() { 0x050B }
def getATTRIBUTE_VOLTAGE() { 0x0505 }
def getATTRIBUTE_CURRENT() { 0x0508 }
def getTEMPERATURE_MEASUREMENT_MEASURED_VALUE_ATTRIBUTE() { 0x0000 }

def convertHexToInt24Bit(value) {
	int result = zigbee.convertHexToInt(value)
	if (result & 0x800000) {
		result |= 0xFF000000
	}	
	return result
}

def parse(String description) {
	log.debug "description is $description"
	if (description?.startsWith('temperature:')) { //parse temperature
		List result = []
		def map = [:]
		map.name = description.split(": ")[0]
		map.value = description.split(": ")[1]
		map.unit = getTemperatureScale()
		log.debug "${device.displayName}: Reported temperature is ${map.value}°$map.unit"
		return createEvent(map)
	} else {
		List result = []
		def descMap = zigbee.parseDescriptionAsMap(description)
		log.debug "Desc Map: $descMap"
				
		List attrData = [[clusterInt: descMap.clusterInt ,attrInt: descMap.attrInt, value: descMap.value, isValidForDataType: descMap.isValidForDataType]]
		descMap.additionalAttrs.each {
			attrData << [clusterInt: descMap.clusterInt, attrInt: it.attrInt, value: it.value, isValidForDataType: it.isValidForDataType]
		}
		attrData.each {
			def map = [:]
			if (it.isValidForDataType && (it.value != null)) {
				if (it.clusterInt == zigbee.SIMPLE_METERING_CLUSTER && it.attrInt == ATTRIBUTE_HISTORICAL_CONSUMPTION) {
					log.debug "meter"
					map.name = "power"
					map.value = convertHexToInt24Bit(it.value)/powerDivisor
					map.unit = "W"
				} else if (it.clusterInt == zigbee.SIMPLE_METERING_CLUSTER && it.attrInt == ATTRIBUTE_READING_INFO_SET) {
					log.debug "energy"
					map.name = "energy"
					map.value = zigbee.convertHexToInt(it.value)/energyDivisor
					map.unit = "kWh"						
				} else if (it.clusterInt == zigbee.ELECTRICAL_MEASUREMENT_CLUSTER && it.attrInt == ATTRIBUTE_VOLTAGE) {
					log.debug "voltage"
					map.name = "voltage"
					map.value = zigbee.convertHexToInt(it.value)/voltageDivisor
					map.unit = "V"
				} else if (it.clusterInt == zigbee.ELECTRICAL_MEASUREMENT_CLUSTER && it.attrInt == ATTRIBUTE_CURRENT) {
					log.debug "current"
					map.name = "current"
					map.value = zigbee.convertHexToInt(it.value)/currentDivisor
					map.unit = "A"
				} else if (it.clusterInt == zigbee.TEMPERATURE_MEASUREMENT_CLUSTER && it.attrInt == TEMPERATURE_MEASUREMENT_MEASURED_VALUE_ATTRIBUTE) {
					log.debug "temperature"
					map.name = "temperature"
					map.unit = getTemperatureScale()
					map.value = zigbee.parseHATemperatureValue("temperature: " + (zigbee.convertHexToInt(it.value)), "temperature: ", tempScale)
					log.debug "${device.displayName}: Reported temperature is ${map.value}°$map.unit"
				}
			}
				
			if (map) {
				result << createEvent(map)
			}
			log.debug "Parse returned $map"
		}
		return result
	}
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	return refresh()
}

def refresh() {
	log.debug "refresh "
	zigbee.simpleMeteringPowerRefresh() +
	zigbee.readAttribute(zigbee.SIMPLE_METERING_CLUSTER, ATTRIBUTE_READING_INFO_SET) + 
	zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTRIBUTE_VOLTAGE) +
	zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTRIBUTE_CURRENT) +
	zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, TEMPERATURE_MEASUREMENT_MEASURED_VALUE_ATTRIBUTE)
}

def configure() {
	def configCmds = []
	// this device will send instantaneous demand and current summation delivered every 1 minute
	sendEvent(name: "checkInterval", value: 2 * 60 + 10 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])

	log.debug "Configuring Reporting"
	configCmds = zigbee.configureReporting(zigbee.SIMPLE_METERING_CLUSTER, ATTRIBUTE_HISTORICAL_CONSUMPTION, DataType.INT24, 5, 600, 1) +
		zigbee.configureReporting(zigbee.SIMPLE_METERING_CLUSTER, ATTRIBUTE_READING_INFO_SET, DataType.UINT48, 5, 600, 1) +
		zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTRIBUTE_VOLTAGE, DataType.UINT16, 5, 600, 3) + /* 3 unit : 0.3V */
		zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTRIBUTE_CURRENT, DataType.UINT16, 5, 600, 1) + /* 1 unit : 0.01A */
		zigbee.configureReporting(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, TEMPERATURE_MEASUREMENT_MEASURED_VALUE_ATTRIBUTE, DataType.INT16, 20, 300, 10 /* 1 uint : 0.1C */)
	return configCmds + refresh()
}

private getActivePowerDivisor() { 1 }
private getPowerDivisor() { 1 }
private getEnergyDivisor() { 1000 }
private getFrequencyDivisor() { 10 }
private getVoltageDivisor() { 10 }
private getCurrentDivisor() { 1000 }
private getPowerFactorDivisor() { 1 }
