package app.rooms.sonos

import org.junit.Assert.*
import org.junit.Test

class SonosSetupTest {
    // Synthetic topology exercises bonded satellites and spare speakers.
    private val bonded = """
        <ZoneGroupState><ZoneGroups>
          <ZoneGroup Coordinator="ARC" ID="ARC:1">
            <ZoneGroupMember UUID="ARC" ZoneName="Room A" HTSatChanMapSet="ARC:LF,RF;ERA_L:LR,LTR;ERA_R:RR,RTR;SUB:SW">
              <Satellite UUID="ERA_R" ZoneName="Room A" Invisible="1" HTSatChanMapSet="ARC:LF,RF;ERA_R:RR,RTR"/>
              <Satellite UUID="ERA_L" ZoneName="Room A" Invisible="1" HTSatChanMapSet="ARC:LF,RF;ERA_L:LR,LTR"/>
              <Satellite UUID="SUB" ZoneName="Sub" Invisible="1" HTSatChanMapSet="ARC:LF,RF;SUB:SW"/>
            </ZoneGroupMember>
          </ZoneGroup>
          <ZoneGroup Coordinator="BEAM" ID="BEAM:1"><ZoneGroupMember UUID="BEAM" ZoneName="Room B"/></ZoneGroup>
        </ZoneGroups></ZoneGroupState>
    """.trimIndent()

    private val spare = """
        <ZoneGroupState><ZoneGroups>
          <ZoneGroup Coordinator="BEAM" ID="BEAM:1"><ZoneGroupMember UUID="BEAM" ZoneName="Room B"/></ZoneGroup>
          <ZoneGroup Coordinator="SUB" ID="SUB:1"><ZoneGroupMember UUID="SUB" ZoneName="Sub"/></ZoneGroup>
          <ZoneGroup Coordinator="ERA_L" ID="ERA_L:1">
            <ZoneGroupMember UUID="ERA_L" ZoneName="Room C"/><ZoneGroupMember UUID="ERA_R" ZoneName="Room D"/>
          </ZoneGroup>
        </ZoneGroups></ZoneGroupState>
    """.trimIndent()

    private val devices = mapOf(
        "ARC" to SonosDevice("ARC", "Room A", "Sonos Arc", "192.0.2.2"),
        "BEAM" to SonosDevice("BEAM", "Room B", "Sonos Beam", "192.0.2.3"),
        "SUB" to SonosDevice("SUB", "Sub", "Sonos Sub", "192.0.2.4"),
        "ERA_L" to SonosDevice("ERA_L", "Room C", "Sonos Era 100", "192.0.2.5"),
        "ERA_R" to SonosDevice("ERA_R", "Room D", "Sonos Era 100", "192.0.2.6"),
    )

    @Test fun `flattens members and satellites with group context`() {
        val members = SonosProtocol.zoneMembers(bonded)
        assertEquals(5, members.size)
        assertTrue(members.first { it.uid == "SUB" }.satellite)
        assertTrue(members.first { it.uid == "ERA_L" }.invisible)
        assertEquals(1, members.first { it.uid == "BEAM" }.groupSize)
    }

    @Test fun `summarises a synthetic bonded room`() {
        val setups = SonosSetup.roomSetups(SonosProtocol.zoneMembers(bonded), devices)
        assertEquals(listOf("ARC", "BEAM"), setups.map { it.main.uid })
        val arc = setups.first()
        assertEquals(setOf("ERA_L", "ERA_R"), arc.surroundUids.toSet())
        assertEquals("SUB", arc.subUid)
        assertEquals("Arc + 2 surrounds + Sub", arc.summary(devices.mapValues { it.value.modelName }))
        assertTrue(setups[1].isSpare)
    }

    @Test fun `fully bonded household offers separation, not additions`() {
        val setups = SonosSetup.roomSetups(SonosProtocol.zoneMembers(bonded), devices)
        assertNotNull(SonosSetup.addSubBlocker(setups))
        assertNotNull(SonosSetup.stereoBlocker(setups))
        assertEquals(3, SonosSetup.separateOptions(setups, devices.mapValues { it.value.modelName }).size)
    }

    @Test fun `spare speakers unlock sub surround and stereo tasks`() {
        val setups = SonosSetup.roomSetups(SonosProtocol.zoneMembers(spare), devices)
        assertNull(SonosSetup.addSubBlocker(setups))
        assertNull(SonosSetup.surroundBlocker(setups))
        assertNull(SonosSetup.stereoBlocker(setups))
        assertEquals(listOf("SUB"), SonosSetup.spare(setups, SpeakerKind.SUB).map { it.uid })
        assertEquals(listOf("BEAM"), SonosSetup.surroundTargets(setups).map { it.uid })
    }

    @Test fun `commands target the main player with exact channel maps`() {
        val sub = SonosSetup.command(SetupTask.AddSub(devices.getValue("BEAM"), devices.getValue("SUB")))
        assertEquals("BEAM", sub.targetUid); assertEquals("AddHTSatellite", sub.action)
        assertEquals("<HTSatChanMapSet>BEAM:LF,RF;SUB:SW</HTSatChanMapSet>", sub.arguments)
        val rears = SonosSetup.command(SetupTask.AddSurrounds(devices.getValue("BEAM"), devices.getValue("ERA_L"), devices.getValue("ERA_R")))
        assertEquals("<HTSatChanMapSet>BEAM:LF,RF;ERA_L:LR;ERA_R:RR</HTSatChanMapSet>", rears.arguments)
        val pair = SonosSetup.command(SetupTask.StereoPair(devices.getValue("ERA_L"), devices.getValue("ERA_R"), "Room C"))
        assertEquals("CreateStereoPair", pair.action)
        assertEquals("<ChannelMapSet>ERA_L:LF,LF;ERA_R:RF,RF</ChannelMapSet>", pair.arguments)
        val remove = SonosSetup.command(SetupTask.RemoveSatellite(devices.getValue("ARC"), "SUB", "Sub"))
        assertEquals("ARC", remove.targetUid); assertEquals("<SatRoomUUID>SUB</SatRoomUUID>", remove.arguments)
    }

    @Test fun `preflight refuses stale or already bonded selections`() {
        val bondedMembers = SonosProtocol.zoneMembers(bonded)
        assertEquals("This room already has a Sub.", SonosSetup.preflight(SetupTask.AddSub(devices.getValue("ARC"), devices.getValue("BEAM")), bondedMembers))
        assertNotNull(SonosSetup.preflight(SetupTask.AddSub(devices.getValue("BEAM"), devices.getValue("SUB")), bondedMembers))
        val spareMembers = SonosProtocol.zoneMembers(spare)
        assertNull(SonosSetup.preflight(SetupTask.AddSub(devices.getValue("BEAM"), devices.getValue("SUB")), spareMembers))
        val mismatched = SetupTask.StereoPair(devices.getValue("ERA_L"), devices.getValue("BEAM"), "Room C")
        assertNotNull(SonosSetup.preflight(mismatched, spareMembers))
    }

    @Test fun `verification reads the requested arrangement back`() {
        val bondedMembers = SonosProtocol.zoneMembers(bonded)
        assertTrue(SonosSetup.verified(SetupTask.AddSub(devices.getValue("ARC"), devices.getValue("SUB")), bondedMembers))
        assertTrue(SonosSetup.verified(SetupTask.AddSurrounds(devices.getValue("ARC"), devices.getValue("ERA_L"), devices.getValue("ERA_R")), bondedMembers))
        assertFalse(SonosSetup.verified(SetupTask.RemoveSatellite(devices.getValue("ARC"), "SUB", "Sub"), bondedMembers))
        assertTrue(SonosSetup.verified(SetupTask.RemoveSatellite(devices.getValue("ARC"), "SUB", "Sub"),
            SonosProtocol.zoneMembers(spare).map { if (it.uid == "SUB") it else it } + SonosZoneMember("ARC", "Room A", "ARC", 1, false, false, "", "")))
    }

    @Test fun `preview warns about groups being broken before bonding`() {
        val lines = SonosSetup.preview(SetupTask.StereoPair(devices.getValue("ERA_L"), devices.getValue("ERA_R"), "Room C"), SonosProtocol.zoneMembers(spare))
        assertTrue(lines.any { "leave that group" in it })
        assertTrue(lines.last().contains("30 seconds"))
    }

    @Test fun `removal and separation previews never claim changes outside the task`() {
        val lines = SonosSetup.preview(SetupTask.RemoveSatellite(devices.getValue("ARC"), "SUB", "Sonos Sub"), SonosProtocol.zoneMembers(bonded))
        assertTrue(lines.any { "returns as its own room" in it })
        assertFalse(lines.any { "leave that group" in it })
    }

    @Test fun `speaker kinds classify common models`() {
        assertEquals(SpeakerKind.SOUNDBAR, speakerKind("Sonos Arc Ultra"))
        assertEquals(SpeakerKind.SUB, speakerKind("Sonos Sub Mini"))
        assertEquals(SpeakerKind.SPEAKER, speakerKind("Sonos Era 100"))
        assertEquals(SpeakerKind.PORTABLE, speakerKind("Sonos Roam 2"))
    }
}

class SonosFavouriteTest {
    @Test fun `parses a synthetic favourite shortcut with resMD and no res`() {
        val items = SonosProtocol.parseDidlItems("""
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
            <item id="FV:2/1" parentID="FV:2"><dc:title>Example Radio</dc:title><upnp:class>object.itemobject.item.sonos-favorite</upnp:class>
            <res></res><r:type>shortcut</r:type><r:description>Sample station</r:description><r:resMD>&lt;DIDL-Lite&gt;&lt;/DIDL-Lite&gt;</r:resMD></item></DIDL-Lite>
        """.trimIndent())
        assertEquals("Example Radio", items.single().title)
        assertEquals("Sample station", items.single().description)
        assertEquals("<DIDL-Lite></DIDL-Lite>", items.single().metadata)
        assertTrue(SonosProtocol.favouritePlayback(items.single()) is FavouritePlayback.Unsupported)
    }

    @Test fun `stream favourites play directly and containers go through the queue`() {
        val radio = SonosDidlItem("FV:2/3", "Sample Radio", "x-sonosapi-stream:sample?sid=254", metadata = "<DIDL-Lite/>")
        assertTrue(SonosProtocol.favouritePlayback(radio) is FavouritePlayback.Direct)
        val album = SonosDidlItem("FV:2/4", "Album", "x-rincon-cpcontainer:1004206calbum", metadata = "<upnp:class>object.container.album</upnp:class>")
        assertTrue(SonosProtocol.favouritePlayback(album) is FavouritePlayback.Queue)
    }

    @Test fun `favourite links reject arbitrary locations and controls`() {
        val sample = SonosDidlItem("FV:2/5", "Sample", null)
        assertTrue(SonosProtocol.favouritePlayback(sample.copy(uri = "https://example.invalid/media")) is FavouritePlayback.Unsupported)
        assertTrue(SonosProtocol.favouritePlayback(sample.copy(uri = "http://192.0.2.2/private")) is FavouritePlayback.Unsupported)
        assertTrue(SonosProtocol.favouritePlayback(sample.copy(uri = "file:///jffs/settings/savedqueues/../../other")) is FavouritePlayback.Unsupported)
        assertTrue(SonosProtocol.favouritePlayback(sample.copy(uri = "x-sonosapi-stream:sample\nUnexpected")) is FavouritePlayback.Unsupported)
        assertTrue(SonosProtocol.favouritePlayback(sample.copy(uri = "https://example.invalid/album", metadata = "object.container.album")) is FavouritePlayback.Unsupported)
        assertTrue(SonosProtocol.favouritePlayback(sample.copy(uri = "file:///jffs/settings/savedqueues/SQ:1")) is FavouritePlayback.Queue)
    }

    @Test fun `queue subtitle uses artist and album, never raw uri`() {
        val item = SonosDidlItem("Q:0/1", "Song", "x-file-cifs://example.invalid/song.flac", artist = "Artist", album = "Album")
        assertEquals("Artist · Album", SonosProtocol.queueSubtitle(item))
        assertEquals("", SonosProtocol.queueSubtitle(item.copy(artist = "", album = "")))
    }

    @Test fun `groupContaining finds coordinator and visible members only`() {
        val xml = """<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="A"><ZoneGroupMember UUID="A"/><ZoneGroupMember UUID="B"/><ZoneGroupMember UUID="S" Invisible="1"/></ZoneGroup></ZoneGroups></ZoneGroupState>"""
        assertEquals("A" to listOf("A", "B"), SonosProtocol.groupContaining(xml, "B"))
    }
}
