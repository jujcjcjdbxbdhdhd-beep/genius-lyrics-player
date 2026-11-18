package com.satulin.geniuslyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackParserTest {

    @Test
    fun testNoiseStripping() {
        assertEquals("Drive Home", MediaNotificationListener.stripNoise("Drive Home (prod. by FRESCO)"))
        assertEquals("Тесно", MediaNotificationListener.stripNoise("Тесно (slowed + reverb)"))
        assertEquals("Кащенко", MediaNotificationListener.stripNoise("Кащенко [Remastered]"))
        assertEquals("+7 (952) 812", MediaNotificationListener.stripNoise("+7 (952) 812 [Official Audio]"))
        assertEquals("NOSTYLIST", MediaNotificationListener.stripNoise("NOSTYLIST (slowed)"))
        assertEquals("В темноте", MediaNotificationListener.stripNoise("В темноте (snippet)"))
        assertEquals("Million", MediaNotificationListener.stripNoise("Million (prod. Gee Key)"))
        assertEquals("Drive Home", MediaNotificationListener.stripNoise("Drive Home (Explicit)"))
        assertEquals("В темноте", MediaNotificationListener.stripNoise("【SLOWED】 В темноте 🔥"))

        // Must NOT strip "by" inside real song titles!
        assertEquals("Stand By Me", MediaNotificationListener.stripNoise("Stand By Me"))
        assertEquals("Drive By", MediaNotificationListener.stripNoise("Drive By"))
    }

    @Test
    fun testRocketDriveHomeCases() {
        // Without dash (SoundCloud style)
        val p1 = MediaNotificationListener.parseTrack("рокет drive home", "")
        assertEquals("ROCKET", p1.artist)
        assertEquals("drive home", p1.title)
        assertTrue(p1.artistFromTitle)

        // With emojis + noise
        val pEmoji = MediaNotificationListener.parseTrack("🔥 рокет drive home (slowed + reverb) 💀", "trap_archive")
        assertEquals("ROCKET", pEmoji.artist)
        assertEquals("drive home", pEmoji.title)
        assertTrue(pEmoji.artistFromTitle)

        // Declension 'рокета'
        val p2 = MediaNotificationListener.parseTrack("рокета drive home", "")
        assertEquals("ROCKET", p2.artist)
        assertEquals("drive home", p2.title)

        // With dash: Artist - Title
        val p3 = MediaNotificationListener.parseTrack("рокет - drive home", "")
        assertEquals("ROCKET", p3.artist)
        assertEquals("drive home", p3.title)

        // Tight dash without spaces
        val pTight = MediaNotificationListener.parseTrack("ROCKET-Drive Home", "")
        assertEquals("ROCKET", pTight.artist)
        assertEquals("Drive Home", pTight.title)

        // Inverted dash: Title - Artist
        val p4 = MediaNotificationListener.parseTrack("Drive Home - ROCKET", "")
        assertEquals("ROCKET", p4.artist)
        assertEquals("Drive Home", p4.title)

        val p5 = MediaNotificationListener.parseTrack("Drive Home - рокет", "")
        assertEquals("ROCKET", p5.artist)
        assertEquals("Drive Home", p5.title)

        // Brackets: Title (Artist) or Title [Artist]
        val p6 = MediaNotificationListener.parseTrack("Drive Home (рокет)", "")
        assertEquals("ROCKET", p6.artist)
        assertEquals("Drive Home", p6.title)

        val p7 = MediaNotificationListener.parseTrack("Drive Home [рокета]", "")
        assertEquals("ROCKET", p7.artist)
        assertEquals("Drive Home", p7.title)

        // Title by Artist
        val p8 = MediaNotificationListener.parseTrack("Drive Home by рокет", "")
        assertEquals("ROCKET", p8.artist)
        assertEquals("Drive Home", p8.title)

        // Separate raw artist (SoundCloud uploader vs artist)
        val p9 = MediaNotificationListener.parseTrack("Drive Home", "рокет")
        assertEquals("ROCKET", p9.artist)
        assertEquals("Drive Home", p9.title)
        assertFalse(p9.artistFromTitle)

        // Fake SoundCloud uploader
        val pFakeUploader = MediaNotificationListener.parseTrack("Инкассатор", "sliv_trekov_52")
        assertEquals("sliv_trekov_52", pFakeUploader.artist)
        assertEquals("Инкассатор", pFakeUploader.title)
        assertFalse(pFakeUploader.artistFromTitle)

        // Null strings must be filtered
        val p10 = MediaNotificationListener.parseTrack("рокет drive home", "null")
        assertEquals("ROCKET", p10.artist)
        assertEquals("drive home", p10.title)
    }

    @Test
    fun testSoundCloudRussianAndWesternArtists() {
        val cases = listOf(
            Pair("тейп gimme the loot", Pair("Big Baby Tape", "gimme the loot")),
            Pair("тейп - gimme the loot", Pair("Big Baby Tape", "gimme the loot")),
            Pair("Gimme the Loot - тейп", Pair("Big Baby Tape", "Gimme the Loot")),
            Pair("буда бандит", Pair("OG Buda", "бандит")),
            Pair("Бандит (буда)", Pair("OG Buda", "Бандит")),
            Pair("кизару дежавю", Pair("kizaru", "дежавю")),
            Pair("макан асфальт 8", Pair("MACAN", "асфальт 8")),
            Pair("платина в темноте", Pair("Платина", "в темноте")),
            Pair("скалли сделать это", Pair("Scally Milano", "сделать это")),
            Pair("френдли таг 52 ngg - calma", Pair("FRIENDLY THUG 52 NGG", "calma")),
            Pair("фараон дико например", Pair("PHARAOH", "дико например")),
            Pair("шадоурейз astral step", Pair("shadowraze", "astral step")),
            Pair("морген cadillac", Pair("MORGENSHTERN", "cadillac")),
            Pair("скриптонит положение", Pair("Скриптонит", "положение")),
            Pair("токсис hurtz", Pair("TOXI$", "hurtz")),
            Pair("херонвотер забудь", Pair("Heronwater", "забудь")),
            Pair("салуки огней", Pair("SALUKI", "огней")),
            Pair("пепел нахуди заново", Pair("Pepel Nahudi", "заново")),
            Pair("ловв66 5 звезд", Pair("LOVV66", "5 звезд")),
            Pair("чиф киф love sosa", Pair("Chief Keef", "love sosa")),
            Pair("карти magnolia", Pair("Playboi Carti", "magnolia")),
            Pair("кен карсон freestyle 2", Pair("Ken Carson", "freestyle 2"))
        )

        for ((input, expected) in cases) {
            val res = MediaNotificationListener.parseTrack(input, "")
            assertEquals("Failed artist for: $input", expected.first, res.artist)
            assertEquals("Failed title for: $input", expected.second, res.title)
        }
    }

    @Test
    fun testTitleAndArtistMatchingAndPhonetics() {
        // Must reject completely different songs & 1-char false contains
        assertFalse(GeniusLyricsFetcher.isTitleMatch("Drive Home", "Stress Relief"))
        assertFalse(GeniusLyricsFetcher.isTitleMatch("Drive Home", "Yeah, Yeah, Yeah"))
        assertFalse(GeniusLyricsFetcher.isTitleMatch("A", "Another Song"))
        assertFalse(GeniusLyricsFetcher.isArtistMatch("ROCKET", "late night drive home"))

        // Must reject partial substring matches that are too short relative to candidate
        assertFalse(GeniusLyricsFetcher.isTitleMatch("Home", "Homesick Lullaby"))
        assertFalse(GeniusLyricsFetcher.isTitleMatch("Drive", "Driven Away Forever"))
        assertFalse(GeniusLyricsFetcher.isTitleMatch("Ice", "Ice Cream Sundae Party"))

        // Must match exact, normalized, ё/е, $/s, and phonetic equivalents
        assertTrue(GeniusLyricsFetcher.isTitleMatch("Drive Home", "Drive Home"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("drive home", "Drive Home (Bonus Track)"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("Drive Home", "Drive Home (prod. by FRESCO)"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("Синие слезы", "Синие слёзы"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("5 звезд", "5 звёзд"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("Асфальт 8", "ASPHALT 8"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("Инфинити", "Infinity"))

        assertTrue(GeniusLyricsFetcher.isArtistMatch("ROCKET", "ROCKET"))
        assertTrue(GeniusLyricsFetcher.isArtistMatch("рокет", "ROCKET"))
        assertTrue(GeniusLyricsFetcher.isArtistMatch("Ken Carson", "Ken Car\$on"))
        assertTrue(GeniusLyricsFetcher.isArtistMatch("TOXI$", "Toxis"))
        assertTrue(GeniusLyricsFetcher.isArtistMatch("Скалли", "Scally Milano"))
    }

    @Test
    fun testTransliteratedGeniusSlug() {
        assertEquals(
            "https://genius.com/Rocket-drive-home-lyrics",
            GeniusLyricsFetcher.constructDirectGeniusUrl("ROCKET", "Drive Home")
        )
        assertEquals(
            "https://genius.com/Platina-v-temnote-lyrics",
            GeniusLyricsFetcher.constructDirectGeniusUrl("Платина", "В темноте")
        )
        assertEquals(
            "https://genius.com/Og-buda-na-kasse-lyrics",
            GeniusLyricsFetcher.constructDirectGeniusUrl("OG Buda", "На кассе")
        )
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
