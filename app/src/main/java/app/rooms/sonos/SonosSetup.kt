package app.rooms.sonos

/**
 * Pure planning for speaker arrangement: which products are spare, which tasks are possible,
 * the exact local command for each task, and how to prove Sonos applied it.
 * Nothing here performs IO, so every rule is unit-testable.
 */
enum class SpeakerKind { SOUNDBAR, SUB, SPEAKER, PORTABLE }

fun speakerKind(model: String): SpeakerKind {
    val m = model.lowercase()
    return when {
        SonosProductKind.fromModel(model) in setOf(SonosProductKind.SUB, SonosProductKind.SUB_MINI) -> SpeakerKind.SUB
        SonosProtocol.supportsTvAudio(model) -> SpeakerKind.SOUNDBAR
        "roam" in m || "move" in m -> SpeakerKind.PORTABLE
        else -> SpeakerKind.SPEAKER
    }
}

/** Sonos bonds only matching models (Era 100 + Era 100). Compare on normalised model name. */
fun sameModel(a: SonosDevice, b: SonosDevice): Boolean =
    a.modelName.trim().equals(b.modelName.trim(), ignoreCase = true)

/** A room's current make-up, used for the "Your setup" summary and for unpairing. */
data class RoomSetup(
    val main: SonosDevice,
    val surroundUids: List<String>,
    val subUid: String?,
    val stereoPartnerUid: String?,
    val stereoChannelMap: String,
    val groupedWith: Int,
) {
    val isSpare: Boolean get() = surroundUids.isEmpty() && subUid == null && stereoPartnerUid == null
    fun summary(models: Map<String, String>): String {
        fun short(model: String?) = model?.removePrefix("Sonos ")?.trim()?.takeIf(String::isNotBlank)
        val parts = mutableListOf(short(main.modelName) ?: "Speaker")
        stereoPartnerUid?.let { parts[0] = "${parts[0]} stereo pair" }
        if (surroundUids.isNotEmpty()) parts += if (surroundUids.size == 2) "2 surrounds" else "1 surround"
        subUid?.let { parts += short(models[it]) ?: "Sub" }
        return parts.joinToString(" + ")
    }
}

sealed class SetupTask {
    abstract val title: String
    abstract val confirmLabel: String

    data class AddSub(val main: SonosDevice, val sub: SonosDevice) : SetupTask() {
        override val title get() = "Add a Sub"
        override val confirmLabel get() = "Add Sub to ${main.roomName.ifBlank { main.modelName }}"
    }
    data class AddSurrounds(val main: SonosDevice, val left: SonosDevice, val right: SonosDevice) : SetupTask() {
        override val title get() = "Add rear surrounds"
        override val confirmLabel get() = "Add surrounds to ${main.roomName.ifBlank { main.modelName }}"
    }
    data class StereoPair(val left: SonosDevice, val right: SonosDevice, val roomName: String) : SetupTask() {
        override val title get() = "Make a stereo pair"
        override val confirmLabel get() = "Pair as ${roomName.ifBlank { "stereo pair" }}"
    }
    data class RemoveSatellite(val main: SonosDevice, val satelliteUid: String, val satelliteLabel: String) : SetupTask() {
        override val title get() = "Remove $satelliteLabel"
        override val confirmLabel get() = "Remove $satelliteLabel from ${main.roomName.ifBlank { main.modelName }}"
    }
    data class SeparatePair(val main: SonosDevice, val channelMap: String) : SetupTask() {
        override val title get() = "Separate stereo pair"
        override val confirmLabel get() = "Separate ${main.roomName.ifBlank { "pair" }}"
    }
}

data class SetupCommand(val targetUid: String, val action: String, val arguments: String)

sealed class SetupOutcome {
    data class Done(val message: String) : SetupOutcome()
    data class Pending(val message: String) : SetupOutcome()
    data class Failed(val message: String) : SetupOutcome()
}

object SonosSetup {
    private val surroundChannels = setOf("LR", "RR", "LTR", "RTR")

    /** Parses the HT or stereo channel map into uid -> channels. */
    fun channelMap(value: String): Map<String, Set<String>> = value.split(';').mapNotNull { entry ->
        val uid = entry.substringBefore(':').trim()
        val channels = entry.substringAfter(':', "").split(',').map { it.trim().uppercase() }.filter(String::isNotBlank).toSet()
        if (uid.isBlank() || channels.isEmpty()) null else uid to channels
    }.toMap()

    /** One entry per visible room with its bonded make-up, read from ZoneGroupState. */
    fun roomSetups(members: List<SonosZoneMember>, devices: Map<String, SonosDevice>): List<RoomSetup> =
        members.filter { !it.invisible && !it.satellite }.mapNotNull { member ->
            val main = devices[member.uid] ?: return@mapNotNull null
            val ht = channelMap(member.htSatChanMapSet)
            val surrounds = ht.filter { (uid, ch) -> uid != member.uid && ch.any { it in surroundChannels } }.keys.toList()
            val sub = ht.entries.firstOrNull { (uid, ch) -> uid != member.uid && "SW" in ch }?.key
            val stereo = channelMap(member.channelMapSet)
            val partner = stereo.keys.firstOrNull { it != member.uid }
            RoomSetup(main, surrounds, sub, partner, if (partner != null) member.channelMapSet else "", member.groupSize)
        }

    fun spare(setups: List<RoomSetup>, kind: SpeakerKind): List<SonosDevice> =
        setups.filter { it.isSpare && speakerKind(it.main.modelName) == kind }.map { it.main }

    fun subTargets(setups: List<RoomSetup>): List<SonosDevice> =
        setups.filter { it.subUid == null && speakerKind(it.main.modelName) in setOf(SpeakerKind.SOUNDBAR, SpeakerKind.SPEAKER) }.map { it.main }

    fun surroundTargets(setups: List<RoomSetup>): List<SonosDevice> =
        setups.filter { it.surroundUids.isEmpty() && speakerKind(it.main.modelName) == SpeakerKind.SOUNDBAR }.map { it.main }

    /** Matching spare pairs: returns every model that has at least two spare speakers. */
    fun pairableGroups(setups: List<RoomSetup>): Map<String, List<SonosDevice>> =
        spare(setups, SpeakerKind.SPEAKER).groupBy { it.modelName.trim().lowercase() }.filterValues { it.size >= 2 }

    /** Plain-language reason a task is unavailable, or null when it can be started. */
    fun addSubBlocker(setups: List<RoomSetup>): String? = when {
        spare(setups, SpeakerKind.SUB).isEmpty() -> "No spare Sub found. Set one up first, or separate it from another room."
        subTargets(setups).isEmpty() -> "Every compatible room already has a Sub."
        else -> null
    }

    fun surroundBlocker(setups: List<RoomSetup>): String? = when {
        setups.none { speakerKind(it.main.modelName) == SpeakerKind.SOUNDBAR } -> "Surrounds need a soundbar or Amp."
        surroundTargets(setups).isEmpty() -> "Every soundbar already has surrounds."
        pairableGroups(setups).isEmpty() -> "Needs two matching spare speakers, such as two Era 100s."
        else -> null
    }

    fun stereoBlocker(setups: List<RoomSetup>): String? =
        if (pairableGroups(setups).isEmpty()) "Needs two matching spare speakers in separate rooms." else null

    fun separateOptions(setups: List<RoomSetup>, models: Map<String, String>): List<SetupTask> = setups.flatMap { room ->
        buildList {
            room.subUid?.let { add(SetupTask.RemoveSatellite(room.main, it, models[it]?.takeIf(String::isNotBlank) ?: "Sub")) }
            room.surroundUids.forEachIndexed { i, uid -> add(SetupTask.RemoveSatellite(room.main, uid, if (i == 0) "left rear speaker" else "right rear speaker")) }
            if (room.stereoPartnerUid != null) add(SetupTask.SeparatePair(room.main, room.stereoChannelMap))
        }
    }

    fun command(task: SetupTask): SetupCommand = when (task) {
        is SetupTask.AddSub -> SetupCommand(task.main.uid, "AddHTSatellite",
            "<HTSatChanMapSet>${xml("${task.main.uid}:LF,RF;${task.sub.uid}:SW")}</HTSatChanMapSet>")
        is SetupTask.AddSurrounds -> SetupCommand(task.main.uid, "AddHTSatellite",
            "<HTSatChanMapSet>${xml("${task.main.uid}:LF,RF;${task.left.uid}:LR;${task.right.uid}:RR")}</HTSatChanMapSet>")
        is SetupTask.StereoPair -> SetupCommand(task.left.uid, "CreateStereoPair",
            "<ChannelMapSet>${xml("${task.left.uid}:LF,LF;${task.right.uid}:RF,RF")}</ChannelMapSet>")
        is SetupTask.RemoveSatellite -> SetupCommand(task.main.uid, "RemoveHTSatellite",
            "<SatRoomUUID>${xml(task.satelliteUid)}</SatRoomUUID>")
        is SetupTask.SeparatePair -> SetupCommand(task.main.uid, "SeparateStereoPair",
            "<ChannelMapSet>${xml(task.channelMap)}</ChannelMapSet>")
    }

    /** Every uid that must be a standalone, ungrouped room before the command is sent. */
    fun mustBeStandalone(task: SetupTask): List<String> = when (task) {
        is SetupTask.AddSub -> listOf(task.sub.uid)
        is SetupTask.AddSurrounds -> listOf(task.left.uid, task.right.uid)
        is SetupTask.StereoPair -> listOf(task.left.uid, task.right.uid)
        else -> emptyList()
    }

    /** Re-validates against fresh topology so a stale screen cannot trigger a wrong bond. */
    fun preflight(task: SetupTask, members: List<SonosZoneMember>): String? {
        val byUid = members.associateBy { it.uid }
        fun visible(uid: String) = byUid[uid]?.let { !it.invisible && !it.satellite } == true
        fun free(uid: String) = byUid[uid]?.let { it.htSatChanMapSet.isBlank() && it.channelMapSet.isBlank() } == true
        val mainUid = when (task) {
            is SetupTask.AddSub -> task.main.uid
            is SetupTask.AddSurrounds -> task.main.uid
            is SetupTask.StereoPair -> task.left.uid
            is SetupTask.RemoveSatellite -> task.main.uid
            is SetupTask.SeparatePair -> task.main.uid
        }
        if (!visible(mainUid)) return "That room is no longer available. Refresh and try again."
        mustBeStandalone(task).forEach { uid ->
            if (!visible(uid) || !free(uid)) return "A selected speaker is already part of another setup. Refresh and try again."
        }
        val mainMap = channelMap(byUid[mainUid]?.htSatChanMapSet.orEmpty())
        return when (task) {
            is SetupTask.AddSub -> if (mainMap.values.any { "SW" in it }) "This room already has a Sub." else null
            is SetupTask.AddSurrounds -> if (mainMap.values.any { ch -> ch.any { it in surroundChannels } }) "This room already has surrounds." else null
            is SetupTask.StereoPair -> if (!sameModel(task.left, task.right)) "Only matching models can be paired." else null
            is SetupTask.RemoveSatellite -> if (task.satelliteUid !in mainMap) "That speaker is no longer part of this room." else null
            is SetupTask.SeparatePair -> if (byUid[mainUid]?.channelMapSet.isNullOrBlank()) "This room is not a stereo pair any more." else null
        }
    }

    /** True once ZoneGroupState shows the requested arrangement. */
    fun verified(task: SetupTask, members: List<SonosZoneMember>): Boolean {
        val byUid = members.associateBy { it.uid }
        return when (task) {
            is SetupTask.AddSub -> channelMap(byUid[task.main.uid]?.htSatChanMapSet.orEmpty())[task.sub.uid]?.contains("SW") == true
            is SetupTask.AddSurrounds -> channelMap(byUid[task.main.uid]?.htSatChanMapSet.orEmpty()).let { map ->
                map[task.left.uid]?.any { it == "LR" } == true && map[task.right.uid]?.any { it == "RR" } == true
            }
            is SetupTask.StereoPair -> channelMap(byUid[task.left.uid]?.channelMapSet.orEmpty()).containsKey(task.right.uid)
            is SetupTask.RemoveSatellite -> byUid[task.main.uid] != null &&
                task.satelliteUid !in channelMap(byUid[task.main.uid]?.htSatChanMapSet.orEmpty()) &&
                byUid[task.satelliteUid]?.let { !it.invisible && !it.satellite } == true
            is SetupTask.SeparatePair -> byUid[task.main.uid]?.channelMapSet.isNullOrBlank()
        }
    }

    /** Human preview shown before confirming. Consequences first, no protocol jargon. */
    fun preview(task: SetupTask, members: List<SonosZoneMember>): List<String> {
        val byUid = members.associateBy { it.uid }
        val lines = mutableListOf<String>()
        mustBeStandalone(task).forEach { uid ->
            val m = byUid[uid]
            if (m != null && m.groupSize > 1) lines += "${m.zoneName.ifBlank { "A selected speaker" }} is playing with other rooms. It will leave that group first."
        }
        when (task) {
            is SetupTask.AddSub -> {
                lines += "${task.sub.roomName.ifBlank { "The Sub" }} stops appearing as its own room."
                lines += "Its bass is tuned for ${task.main.roomName}. You can adjust Sub level later in Sound."
            }
            is SetupTask.AddSurrounds -> {
                lines += "${task.left.roomName} becomes the left rear and ${task.right.roomName} the right rear."
                lines += "Both stop appearing as separate rooms."
                lines += "Place them slightly behind where you sit, at ear height."
            }
            is SetupTask.StereoPair -> {
                lines += "${task.left.roomName} plays the left channel and ${task.right.roomName} the right."
                lines += "They appear as one room called ${task.roomName}."
            }
            is SetupTask.RemoveSatellite -> {
                lines += "The ${task.satelliteLabel} returns as its own room."
                lines += "${task.main.roomName} keeps playing without it."
            }
            is SetupTask.SeparatePair -> lines += "Both speakers return as separate rooms."
        }
        lines += "Takes up to 30 seconds. Sound in these rooms may drop out briefly."
        return lines
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
