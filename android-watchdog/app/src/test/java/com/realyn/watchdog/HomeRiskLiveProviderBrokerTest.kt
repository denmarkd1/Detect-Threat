package com.realyn.watchdog

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRiskLiveProviderBrokerTest {

    @Test
    fun `parseSmartThingsDevices maps real inventory types`() {
        val payload = JSONObject(
            """
            {
              "items": [
                {
                  "deviceId": "tv-1",
                  "label": "Living Room TV",
                  "deviceTypeName": "Samsung TV"
                },
                {
                  "deviceId": "fridge-1",
                  "name": "Kitchen Fridge",
                  "type": "refrigerator"
                }
              ]
            }
            """.trimIndent()
        )

        val devices = HomeRiskLiveProviderBroker.parseSmartThingsDevices(payload)

        assertEquals(2, devices.size)
        assertEquals("tv", devices.first { it.deviceId == "tv-1" }.deviceType)
        assertEquals("appliance", devices.first { it.deviceId == "fridge-1" }.deviceType)
        assertTrue(devices.all { it.source == "smartthings_rest" })
    }

    @Test
    fun `parseHomeAssistantStates filters irrelevant entities and deduplicates`() {
        val payload = JSONArray(
            """
            [
              {
                "entity_id": "media_player.family_room_tv",
                "attributes": { "friendly_name": "Family Room TV" }
              },
              {
                "entity_id": "lock.front_door",
                "attributes": { "friendly_name": "Front Door" }
              },
              {
                "entity_id": "sensor.motion_hall",
                "attributes": { "friendly_name": "Hall Motion" }
              },
              {
                "entity_id": "person.parent",
                "attributes": { "friendly_name": "Parent" }
              },
              {
                "entity_id": "lock.front_door",
                "attributes": { "friendly_name": "Front Door" }
              }
            ]
            """.trimIndent()
        )

        val devices = HomeRiskLiveProviderBroker.parseHomeAssistantStates(payload)

        assertEquals(3, devices.size)
        assertEquals("tv", devices.first { it.deviceId == "media_player.family_room_tv" }.deviceType)
        assertEquals("lock", devices.first { it.deviceId == "lock.front_door" }.deviceType)
        assertEquals("sensor", devices.first { it.deviceId == "sensor.motion_hall" }.deviceType)
        assertTrue(devices.all { it.source == "home_assistant_rest" })
        assertFalse(devices.any { it.deviceId == "person.parent" })
    }
}
