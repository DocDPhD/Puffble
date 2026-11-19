package dev.proxywatch.companion.watch

import dev.proxywatch.companion.ble.CooldownDetector
import dev.proxywatch.companion.ble.CycleTimer
import dev.proxywatch.companion.ble.SessionEndDetector
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

    @Test fun naturalFinishStraightToIdleCountsAsDone() {
        // Logged on the real Proxy: remain 3, 2, then state 5.
        val d = SessionEndDetector()
        assertEquals(false, d.update(7, null, false))
        assertEquals(false, d.update(8, 3, false))
        assertEquals(false, d.update(8, 2, false))
        assertEquals(true, d.update(5, null, false))
        assertEquals(false, d.update(5, null, false))  // only once
    }

    @Test fun stoppingMidSessionIsNotDone() {
        val d = SessionEndDetector()
        d.update(8, 60, false)
        assertEquals(false, d.update(5, null, true))
        val e = SessionEndDetector()
        e.update(8, 60, false)
        assertEquals(false, e.update(5, null, false))  // stopped from the Puffco app: countdown far from zero
    }

    @Test fun fadeStateAndUnknownCountdown() {
        val d = SessionEndDetector()
        d.update(8, 50, false)
        assertEquals(true, d.update(9, null, false))  // firmware that does report fade
        val e = SessionEndDetector()
        e.update(8, null, false)                      // joined mid-session
        assertEquals(true, e.update(5, null, false))
        val f = SessionEndDetector()
        f.update(8, null, false)
        assertEquals(false, f.update(5, null, true))  // ...unless Puffble just stopped it
    }

    @Test fun refusedStartThenShutdownStartsCooldown() {
        // Logged on the real Proxy when it overheated: start acked, still idle, then the link dropped.
        val c = CooldownDetector(120_000)
        c.onStartSent(0)
        assertEquals(false, c.onPoll(5, 300, false))
        assertEquals(true, c.onPoll(5, 4_500, false))   // no preheat within 4 s
        assertEquals(120, c.remainingS(4_500))
        assertEquals(false, c.onLinkLost(5_000))        // already cooling: no second trigger
        assertEquals(true, c.expire(124_600))
        assertNull(c.remainingS(124_600))
    }

    @Test fun linkLossRightAfterStartStartsCooldown() {
        val c = CooldownDetector(120_000)
        c.onStartSent(0)
        assertEquals(true, c.onLinkLost(2_000))
    }

    @Test fun normalStartAndStopDoNotCoolDown() {
        val c = CooldownDetector(120_000)
        c.onStartSent(0)
        assertEquals(false, c.onPoll(7, 400, false))    // preheat began
        assertEquals(false, c.onPoll(8, 10_000, false))
        assertEquals(false, c.onPoll(5, 20_000, true))  // stopped from Puffble
        assertEquals(false, c.onLinkLost(60_000))       // idle link loss is just a disconnect
        assertNull(c.remainingS(60_000))
    }

    @Test fun heatingStraightToSleepCoolsDownAndSkipClears() {
        val c = CooldownDetector(120_000)
        c.onPoll(8, 0, false)
        assertEquals(true, c.onPoll(4, 1_000, false))
        assertEquals(true, c.clear())
        assertNull(c.remainingS(2_000))
    }

    @Test fun cooldownMessageOutranksSearching() {
        val snap = ProxySession.Snapshot(
            link = ProxySession.Link.Error("disconnected (status 22)"),
            cooldownRemainS = 97, cooldownReason = ProxySession.TOO_HOT, lastHeaterDeciC = 1028,
        )
        val m = WatchProtocol.fromSnapshot(snap)
        assertEquals(WatchProtocol.STATE_COOLDOWN, m.state)
        assertEquals(97, m.remainS)
        assertEquals(ProxySession.COOLDOWN_S, m.durationS)
        assertEquals(217, m.heaterF)
        assertEquals("Your Proxy shut off to cool down", m.statusText)
        assertEquals(WatchProtocol.Command.SkipCooldown, WatchProtocol.parseCommand(4, null))
    }

    @Test fun boostAddsFifteenSecondsOnce() {
        val t = CycleTimer()
        t.update(7, 120, 0)
        assertEquals(120, t.update(8, 120, 1_000))
        t.boost(15)
        assertEquals(125, t.update(8, 120, 11_000))   // 110 left + 15
        assertEquals(125, t.update(8, 135, 11_000))   // Proxy raised its own length: not counted twice
        t.boost(15)
        assertEquals(140, t.update(8, 135, 11_000))
    }

    @Test fun boostOutsideActiveIsIgnored() {
        val t = CycleTimer()
        t.update(7, 120, 0)
        t.boost(15)
        assertEquals(120, t.update(8, 120, 1_000))
    }

    @Test fun parsesBoostAndCustomStart() {
        assertEquals(Command.Boost, WatchProtocol.parseCommand(5, null))
        assertEquals(Command.StartCustom(500, 45), WatchProtocol.parseCommand(6, null, 500, 45))
        assertNull(WatchProtocol.parseCommand(6, null, 700, 45))   // too hot
        assertNull(WatchProtocol.parseCommand(6, null, 500, 5))    // too short
        assertNull(WatchProtocol.parseCommand(6, null, null, 45))
    }

    @Test fun customSessionShowsAsCustom() {
        val m = WatchProtocol.fromSnapshot(ready.copy(live = idle.copy(operatingState = 7), custom = true))
        assertEquals("Custom", m.profileName)
        assertEquals(WatchProtocol.CUSTOM_RGB, m.ringRgb)
    }

    @Test fun fahrenheitRoundTrips() {
        assertEquals(2600L, dev.proxywatch.companion.lorax.Units.fahrenheitToDeciC(500))
        assertEquals(500, dev.proxywatch.companion.lorax.Units.deciCToFahrenheit(2600))
        assertEquals(9000L, dev.proxywatch.companion.lorax.Units.secondsToTicks(45))
    }

    @Test fun cycleTimerIsUnknownWhenJoiningMidCycle() {
        val t = CycleTimer()
        assertNull(t.update(8, 120, 0))
        assertNull(t.update(8, 120, 1_000))
    }
}
