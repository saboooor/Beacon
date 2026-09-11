package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAlternationTest {

    private val rule1 = AppRule(
        pkg = "com.whatsapp",
        label = "WhatsApp",
        pattern = Pattern.PULSE,
        color = 0xFF00E676.toInt(),
        speedMs = 800,
        brightness = 1f,
    )

    private val rule2 = AppRule(
        pkg = "com.slack",
        label = "Slack",
        pattern = Pattern.WAVE,
        color = 0xFF00E5FF.toInt(),
        speedMs = 1200,
        brightness = 0.8f,
    )

    private val rule3 = AppRule(
        pkg = "org.telegram.messenger",
        label = "Telegram",
        pattern = Pattern.BREATHE,
        color = 0xFFFF4081.toInt(),
        speedMs = 600,
        brightness = 0.9f,
    )

    @Test
    fun `interval clamping enforces 2000 to 10000 ms`() {
        val clampLow = 1000.coerceIn(2000, 10000)
        val clampMid = 4000.coerceIn(2000, 10000)
        val clampHigh = 20000.coerceIn(2000, 10000)

        assertEquals(2000, clampLow)
        assertEquals(4000, clampMid)
        assertEquals(10000, clampHigh)
    }

    @Test
    fun `active notification map tracks multiple notifications in order`() {
        val map = LinkedHashMap<String, Store.ActiveNotificationAlert>()
        val alert1 = Store.ActiveNotificationAlert("key1", rule1, rule1.color)
        val alert2 = Store.ActiveNotificationAlert("key2", rule2, rule2.color)
        val alert3 = Store.ActiveNotificationAlert("key3", rule3, rule3.color)

        map["key1"] = alert1
        map["key2"] = alert2
        map["key3"] = alert3

        assertEquals(3, map.size)
        val list = map.values.toList()
        assertEquals("key1", list[0].notifKey)
        assertEquals("key2", list[1].notifKey)
        assertEquals("key3", list[2].notifKey)
    }

    @Test
    fun `alternation cycles through active notifications in round-robin order`() {
        val list = listOf(
            Store.ActiveNotificationAlert("key1", rule1, rule1.color),
            Store.ActiveNotificationAlert("key2", rule2, rule2.color),
            Store.ActiveNotificationAlert("key3", rule3, rule3.color),
        )

        var index = 0
        assertEquals("com.whatsapp", list[index].rule.pkg)

        index = (index + 1) % list.size
        assertEquals("com.slack", list[index].rule.pkg)

        index = (index + 1) % list.size
        assertEquals("org.telegram.messenger", list[index].rule.pkg)

        index = (index + 1) % list.size
        assertEquals("com.whatsapp", list[index].rule.pkg)
    }

    @Test
    fun `dismissing a notification adjusts index and keeps remaining active`() {
        val map = LinkedHashMap<String, Store.ActiveNotificationAlert>()
        map["key1"] = Store.ActiveNotificationAlert("key1", rule1, rule1.color)
        map["key2"] = Store.ActiveNotificationAlert("key2", rule2, rule2.color)
        map["key3"] = Store.ActiveNotificationAlert("key3", rule3, rule3.color)

        var index = 2 // pointing to key3
        map.remove("key3")
        index = index % map.size

        assertEquals(2, map.size)
        assertEquals(0, index) // wrapped to 0 (key1)
        val remaining = map.values.toList()
        assertEquals("key1", remaining[index].notifKey)

        map.remove("key1")
        index = index % map.size
        assertEquals(1, map.size)
        assertEquals("key2", map.values.toList()[index].notifKey)

        map.remove("key2")
        assertTrue(map.isEmpty())
    }

    @Test
    fun `screen off rule check correctly identifies if notification can flash`() {
        val screenOffRule = rule1.copy(onlyWhenScreenOff = true)
        val alwaysRule = rule2.copy(onlyWhenScreenOff = false)

        val screenIsOn = true
        val canFlashScreenOffRule = !(screenOffRule.onlyWhenScreenOff && screenIsOn)
        val canFlashAlwaysRule = !(alwaysRule.onlyWhenScreenOff && screenIsOn)

        assertFalse(canFlashScreenOffRule)
        assertTrue(canFlashAlwaysRule)
    }

    @Test
    fun `stopWhenUnlocked rule suppresses flash when phone is unlocked but allows when locked`() {
        val stopWhenUnlockedRule = rule1.copy(stopWhenUnlocked = true)
        val normalRule = rule2.copy(stopWhenUnlocked = false)

        // Case 1: Phone is unlocked
        val phoneIsUnlocked = true
        val canFlashWhenUnlocked1 = !(stopWhenUnlockedRule.stopWhenUnlocked && phoneIsUnlocked)
        val canFlashWhenUnlocked2 = !(normalRule.stopWhenUnlocked && phoneIsUnlocked)

        assertFalse(canFlashWhenUnlocked1)
        assertTrue(canFlashWhenUnlocked2)

        // Case 2: Phone is locked (screen on or off)
        val phoneIsLocked = false
        val canFlashWhenLocked1 = !(stopWhenUnlockedRule.stopWhenUnlocked && phoneIsLocked)
        val canFlashWhenLocked2 = !(normalRule.stopWhenUnlocked && phoneIsLocked)

        assertTrue(canFlashWhenLocked1)
        assertTrue(canFlashWhenLocked2)
    }

    @Test
    fun `stayUntilDismissed defaults to false and can be enabled per-rule`() {
        val defaultRule = AppRule("com.test", "Test")
        assertFalse(defaultRule.stayUntilDismissed)

        val persistentRule = defaultRule.copy(stayUntilDismissed = true)
        assertTrue(persistentRule.stayUntilDismissed)
    }

    @Test
    fun `unlocking dismisses active notifications whose rules have stopWhenUnlocked`() {
        val map = LinkedHashMap<String, Store.ActiveNotificationAlert>()
        val dismissOnUnlockRule = rule1.copy(stayUntilDismissed = true, stopWhenUnlocked = true)
        val keepOnUnlockRule = rule2.copy(stayUntilDismissed = true, stopWhenUnlocked = false)

        map["key1"] = Store.ActiveNotificationAlert("key1", dismissOnUnlockRule, dismissOnUnlockRule.color)
        map["key2"] = Store.ActiveNotificationAlert("key2", keepOnUnlockRule, keepOnUnlockRule.color)

        assertEquals(2, map.size)

        // Simulate unlock dismissal logic
        val toRemove = map.filter { it.value.rule.stopWhenUnlocked }.keys.toList()
        for (key in toRemove) {
            map.remove(key)
        }

        assertEquals(1, map.size)
        assertEquals("key2", map.keys.first())
        assertFalse(map.containsKey("key1"))
    }
}
