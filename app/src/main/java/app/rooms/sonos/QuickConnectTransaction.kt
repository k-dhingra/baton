package app.rooms.sonos

/** The same transaction is used by the phone and deterministic IO tests. */
object QuickConnectTransaction {
    fun run(
        stored: SonosDevice,
        resolve: (SonosDevice) -> SonosDevice?,
        topology: (SonosDevice) -> String?,
        selectTv: (SonosDevice) -> Boolean,
        readUri: (SonosDevice) -> String?,
    ): QuickConnectOutcome {
        val target = resolve(stored)?.takeIf { it.uid == stored.uid }
            ?: return QuickConnectOutcome.Failed("The selected device is unavailable")
        val before = topology(target)
            ?: return QuickConnectOutcome.Failed("Home theatre topology is unavailable")
        val preflight = SonosProtocol.quickConnectPreflight(target, listOf(target), before)
        if (preflight is QuickConnectPreflight.Refused) return QuickConnectOutcome.Failed(preflight.reason)
        val beforeMembers = (preflight as QuickConnectPreflight.Ready).configuredMembers
        if (!selectTv(target)) return QuickConnectOutcome.Failed("TV input could not be selected. Check Wi-Fi and try again.")
        if (readUri(target) != SonosProtocol.tvUri(target.uid))
            return QuickConnectOutcome.Failed("TV input selection could not be verified")
        val after = topology(target)
            ?: return QuickConnectOutcome.Failed("TV input selected, but the speaker setup could not be checked")
        val afterPreflight = SonosProtocol.quickConnectPreflight(target, listOf(target), after)
        if (afterPreflight !is QuickConnectPreflight.Ready || beforeMembers != afterPreflight.configuredMembers)
            return QuickConnectOutcome.Failed("TV input selected, but the configured speaker setup changed. Check it in Sonos.")
        val theatre = SonosProtocol.theatreFromZoneState(after, target.uid)
            ?: return QuickConnectOutcome.Failed("TV input selected, but the speaker setup could not be checked")
        return QuickConnectOutcome.Selected(theatre)
    }
}
