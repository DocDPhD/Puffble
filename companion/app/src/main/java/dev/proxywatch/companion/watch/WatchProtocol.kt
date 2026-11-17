package dev.proxywatch.companion.watch

import dev.proxywatch.companion.ble.ProfileInfo
import dev.proxywatch.companion.ble.ProxySession
import dev.proxywatch.companion.lorax.Units
import java.util.UUID

/**
 * AppMessage contract with the Puffble watchapp in /watch. Pure Kotlin so it is unit tested.
 *
 * Key numbers must match watch/package.json "messageKeys". The SDK numbers array keys
 * first (from 10000), then single keys in listed order.
 */
object WatchProtocol {
    val APP_UUID: UUID = UUID.fromString("1b43e005-bf25-4e85-909e-80da9a074ece")

    object Key {
        // Profile table, one key per slot 0..3: base + index.
        const val P_NAME = 10000u
        const val P_TARGET_F = 10004u
        const val P_DURATION_S = 10008u
        const val P_RGB = 10012u

        const val STATE = 10016u
        const val PROFILE_IDX = 10017u
        const val PROFILE_NAME = 10018u
        const val TARGET_F = 10019u
        const val DURATION_S = 10020u
        const val HEATER_F = 10021u
        const val REMAIN_S = 10022u
        const val BATT_PCT = 10023u
        const val RING_RGB = 10024u
        const val SET_PROFILE = 10025u
        const val CMD = 10026u
        const val STATUS_TEXT = 10027u
        const val SETTINGS = 10028u
    }

    /** SETTINGS bits from the watch (stored as "off" flags; 0 = defaults). */
    object SettingBit {
        const val CELSIUS = 1
        const val NO_VIBES = 2
        const val NO_ANIM = 4
        const val NO_PINS = 8
        const val NO_TINT = 16
        const val NO_SESSION_ALERTS = 32
        const val LIGHT_ON = 64
    }

    fun pinsEnabled(settings: Int): Boolean = settings and SettingBit.NO_PINS == 0

    /** Timeline pin text for a finished session. */
    data class PinText(val title: String, val subtitle: String, val body: String)

    fun sessionPin(profileName: String, targetF: Int, durationS: Int): PinText = PinText(
        title = "Session done",
        subtitle = profileName.ifBlank { "Puffco Proxy" },
        body = "$targetF°F for ${durationS / 60}:${"%02d".format(durationS % 60)}. Cooling down. Enjoy!",
    )

    /** STATE value sent while the phone has no working link to the Proxy. */
    const val STATE_SEARCHING = 255

    /** STATE value for the suggested cool-down after the Proxy shut itself off (see CooldownDetector). */
    const val STATE_COOLDOWN = 254

    // Watch-side buffer is 96 bytes (DETAIL_LEN in main.c); leave room for UTF-8.
    const val MAX_TEXT = 80
    const val MAX_NAME = 20

    /** Ring color used when a profile's color is unknown. */
    const val DEFAULT_RGB = 0x303030

    data class ProfileEntry(val name: String, val targetF: Int, val durationS: Int, val rgb: Int)

    /** Phone to watch. [remainS] -1 = unknown. [statusText] explains a searching state. */
    data class WatchMessage(
        val state: Int,
        val profileIdx: Int = 0,
        val profileName: String = "",
        val targetF: Int = 0,
        val durationS: Int = 0,
        val heaterF: Int = 0,
        val remainS: Int = -1,
        val battPct: Int = 0,
        val ringRgb: Int = DEFAULT_RGB,
        val statusText: String = "",
    )

    fun entry(p: ProfileInfo) = ProfileEntry(
        name = p.name.take(MAX_NAME),
        targetF = Units.deciCToFahrenheit(p.targetDeciC),
        durationS = Units.ticksToSeconds(p.durationTicks),
        rgb = p.rgb ?: DEFAULT_RGB,
    )

    fun profileTable(s: ProxySession.Snapshot): List<ProfileEntry> =
        if (s.link == ProxySession.Link.Ready) s.profiles.map(::entry) else emptyList()

    fun fromSnapshot(s: ProxySession.Snapshot): WatchMessage {
        val live = s.live
        // Cool-down outranks everything else, and works while the Proxy is off and unreachable.
        val cooling = s.cooldownRemainS
        if (cooling != null) {
            return WatchMessage(
                state = STATE_COOLDOWN,
                profileIdx = live?.profile ?: 0,
                profileName = s.currentProfile?.name.orEmpty().take(MAX_NAME),
                durationS = ProxySession.COOLDOWN_S,
                heaterF = s.lastHeaterDeciC?.let { Units.deciCToFahrenheit(it) } ?: 0,
                remainS = cooling,
                battPct = s.batterySoc?.let { Units.socToPercent(it).coerceIn(0, 100) } ?: 0,
                statusText = s.cooldownReason.take(MAX_TEXT),
            )
        }
        if (s.link != ProxySession.Link.Ready || live == null) {
            val text = when (val link = s.link) {
                is ProxySession.Link.Error -> link.message
                is ProxySession.Link.Connecting, is ProxySession.Link.Ready -> "Connecting"
                is ProxySession.Link.Idle -> "Phone not connected"
            }
            return WatchMessage(STATE_SEARCHING, statusText = text.take(MAX_TEXT))
        }
        val profile = s.currentProfile
        return WatchMessage(
            state = live.operatingState,
            profileIdx = live.profile,
            profileName = profile?.name.orEmpty().take(MAX_NAME),
            targetF = Units.deciCToFahrenheit(live.targetDeciC),
            durationS = Units.ticksToSeconds(live.durationTicks),
            heaterF = Units.deciCToFahrenheit(live.heaterDeciC),
            remainS = s.remainS ?: -1,
            battPct = s.batterySoc?.let { Units.socToPercent(it).coerceIn(0, 100) } ?: 0,
            ringRgb = profile?.rgb ?: DEFAULT_RGB,
        )
    }

    /** Watch to phone. */
    sealed interface Command {
        data object Refresh : Command
        data object StartHeat : Command
        data object AbortHeat : Command
        data class SelectProfile(val index: Int) : Command
        data object SkipCooldown : Command
    }

    /** CMD: 1 start, 2 abort, 3 refresh, 4 skip cool-down. SET_PROFILE stands alone. */
    fun parseCommand(cmd: Int?, setProfile: Int?): Command? = when {
        setProfile != null -> setProfile.takeIf { it in 0..3 }?.let { Command.SelectProfile(it) }
        cmd == 1 -> Command.StartHeat
        cmd == 2 -> Command.AbortHeat
        cmd == 3 -> Command.Refresh
        cmd == 4 -> Command.SkipCooldown
        else -> null
    }
}
