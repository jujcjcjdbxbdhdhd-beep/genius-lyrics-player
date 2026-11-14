package com.satulin.geniuslyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackParserTest {

    @Test
    fun testRocketDriveHomeCases() {
        // Without dash (SoundCloud style)
        val p1 = MediaNotificationListener.parseTrack("рокет drive home", "")
        assertEquals("ROCKET", p1.artist)
        assertEquals("drive home", p1.title)

        // Declension 'рокета'
        val p2 = MediaNotificationListener.parseTrack("рокета drive home", "")
        assertEquals("ROCKET", p2.artist)
        assertEquals("drive home", p2.title)

        // With dash
        val p3 = MediaNotificationListener.parseTrack("рокет - drive home", "")
        assertEquals("ROCKET", p3.artist)
        assertEquals("drive home", p3.title)

        // Title and separate raw artist
        val p4 = MediaNotificationListener.parseTrack("Drive Home", "рокет")
        assertEquals("ROCKET", p4.artist)
        assertEquals("Drive Home", p4.title)

        // Null strings must be filtered
        val p5 = MediaNotificationListener.parseTrack("рокет drive home", "null")
        assertEquals("ROCKET", p5.artist)
        assertEquals("drive home", p5.title)
    }

    @Test
    fun testSoundCloudRussianArtists() {
        val cases = listOf(
            Pair("тейп gimme the loot", Pair("Big Baby Tape", "gimme the loot")),
            Pair("тейп - gimme the loot", Pair("Big Baby Tape", "gimme the loot")),
            Pair("буда бандит", Pair("OG Buda", "бандит")),
            Pair("кизару дежавю", Pair("kizaru", "дежавю")),
            Pair("макан асфальт 8", Pair("MACAN", "асфальт 8")),
            Pair("платина в темноте", Pair("Платина", "в темноте")),
            Pair("скалли сделать это", Pair("Scally Milano", "сделать это")),
            Pair("френдли таг 52 ngg - calma", Pair("FRIENDLY THUG 52 NGG", "calma")),
            Pair("фараон дико например", Pair("PHARAOH", "дико например")),
            Pair("шадоурейз astral step", Pair("shadowraze", "astral step")),
            Pair("морген cadillac", Pair("MORGENSHTERN", "cadillac")),
            Pair("скриптонит положение", Pair("Скриптонит", "положение"))
        )

        for ((input, expected) in cases) {
            val res = MediaNotificationListener.parseTrack(input, "")
            assertEquals("Failed artist for: $input", expected.first, res.artist)
            assertEquals("Failed title for: $input", expected.second, res.title)
        }
    }

    @Test
    fun testStripJunkTags() {
        val p1 = MediaNotificationListener.parseTrack("рокет drive home [slowed + reverb]", "")
        assertEquals("ROCKET", p1.artist)
        assertEquals("drive home", p1.title)

        val p2 = MediaNotificationListener.parseTrack("буда бандит (prod. by stereoRYZE)", "")
        assertEquals("OG Buda", p2.artist)
        assertEquals("бандит", p2.title)

        val p3 = MediaNotificationListener.parseTrack("kizaru - дежавю (Official Video).mp3", "")
        assertEquals("kizaru", p3.artist)
        assertEquals("дежавю", p3.title)
    }

    @Test
    fun testNullSafety() {
        val p1 = MediaNotificationListener.parseTrack("null", "null")
        assertEquals("", p1.artist)
        assertEquals("", p1.title)

        val p2 = MediaNotificationListener.parseTrack("", "")
        assertEquals("", p2.artist)
        assertEquals("", p2.title)

        assertEquals("", MediaNotificationListener.sanitizeText("null"))
        assertEquals("", MediaNotificationListener.sanitizeText("undefined"))
        assertEquals("", MediaNotificationListener.sanitizeText(null))
    }
}
