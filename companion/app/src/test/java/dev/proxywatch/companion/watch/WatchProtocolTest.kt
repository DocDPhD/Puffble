package dev.proxywatch.companion.watch

import dev.proxywatch.companion.ble.CycleTimer
import dev.proxywatch.companion.ble.LiveState
import dev.proxywatch.companion.ble.ProfileInfo
import dev.proxywatch.companion.ble.ProxySession
import dev.proxywatch.companion.watch.WatchProtocol.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchProtocolTest {
    // Values read from the real Proxy while idle on profile 0.
    private val idle = LiveState(operatingState = 5, profile = 0, targetDeciC = 2461, durationTicks = 24000, heaterDeciC = 300)
    private val profiles = listOf(
        ProfileInfo("Ultra Flavor", 2461, 24000, 0xFF00D0),
        ProfileInfo("Balanced Flavor", 2600, 24000, 0x53FF12),
        ProfileInfo("Daily", 2656, 24000, 0xFF005D),
        ProfileInfo("Most Vapor", 2989, 24000, null),
    )
    private val ready = ProxySession.Snapshot(ProxySession.Link.Ready, idle, profiles, 10000, null)

    @Test fun readySnapshotIsConvertedToWatchUnits() {
        assertEquals(
            WatchProtocol.WatchMessage(5, 0, "Ultra Flavor", 475, 120, 86, -1, 100, 0xFF00D0),
            WatchProtocol.fromSnapshot(ready),
        )
    }

    @Test fun countdownIsPassedThrough() {
        val active = ready.copy(live = idle.copy(operatingState = 8), remainS = 42)
        assertEquals(42, WatchProtocol.fromSnapshot(active).remainS)
    }

    @Test fun profileTableUsesDefaultColorWhenUnknown() {
        val table = WatchProtocol.profileTable(ready)
        assertEquals(WatchProtocol.ProfileEntry("Most Vapor", 570, 120, WatchProtocol.DEFAULT_RGB), table[3])
        assertEquals(4, table.size)
    }

    @Test fun nonReadyLinksAreSearchingWithReason() {
        val m = WatchProtocol.fromSnapshot(ProxySession.Snapshot(ProxySession.Link.Error("x".repeat(90))))
        assertEquals(WatchProtocol.STATE_SEARCHING, m.state)
        assertEquals(WatchProtocol.MAX_TEXT, m.statusText.length)
        assertEquals("Connecting", WatchProtocol.fromSnapshot(ProxySession.Snapshot(ProxySession.Link.Connecting)).statusText)
        assertEquals(emptyList<Any>(), WatchProtocol.profileTable(ProxySession.Snapshot()))
    }

    @Test fun permissionMessageReachesTheWatchWhole() {
        val m = WatchProtocol.fromSnapshot(ProxySession.Snapshot(ProxySession.Link.Error(ProxySession.NEEDS_PERMISSION)))
        assertEquals("Open the Puffble companion app and allow Nearby Devices Permissions", m.statusText)
    }

    @Test fun parsesCommands() {
        assertEquals(Command.StartHeat, WatchProtocol.parseCommand(1, null))
        assertEquals(Command.AbortHeat, WatchProtocol.parseCommand(2, null))
        assertEquals(Command.Refresh, WatchProtocol.parseCommand(3, null))
        assertEquals(Command.SelectProfile(3), WatchProtocol.parseCommand(null, 3))
    }

    @Test fun rejectsBadCommands() {
        assertNull(WatchProtocol.parseCommand(null, 4))
        assertNull(WatchProtocol.parseCommand(9, null))
        assertNull(WatchProtocol.parseCommand(null, null))
    }

    @Test fun cycleTimerCountsOnlyAnObservedActivePhase() {
        val t = CycleTimer()
        assertNull(t.update(7, 120, 0))           // preheat
        assertEquals(120, t.update(8, 120, 1_000)) // active begins
        assertEquals(90, t.update(8, 120, 31_000))
        assertEquals(0, t.update(8, 120, 500_000)) // clamps at zero
        assertNull(t.update(9, 120, 501_000))     // fade
    }

    @Test fun pinsFollowTheWatchSetting() {
        assertEquals(true, WatchProtocol.pinsEnabled(0))
        assertEquals(true, WatchProtocol.pinsEnabled(WatchProtocol.SettingBit.CELSIUS))
        assertEquals(false, WatchProtocol.pinsEnabled(WatchProtocol.SettingBit.NO_PINS or WatchProtocol.SettingBit.NO_ANIM))
    }

    @Test fun sessionPinText() {
        val pin = WatchProtocol.sessionPin("Daily", 510, 120)
        assertEquals("Session done", pin.title)
        assertEquals("Daily", pin.subtitle)
        assertEquals("510°F for 2:00. Cooling down. Enjoy!", pin.body)
        assertEquals("Puffco Proxy", WatchProtocol.sessionPin(" ", 500, 75).subtitle)
    }

    @Test fun cycleTimerIsUnknownWhenJoiningMidCycle() {
        val t = CycleTimer()
        assertNull(t.update(8, 120, 0))
        assertNull(t.update(8, 120, 1_000))
    }
}
