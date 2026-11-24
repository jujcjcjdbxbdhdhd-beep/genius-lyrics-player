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

    @Test
    fun testGeniusBilingualAndCollabMatching() {
        // Genius titles append English translations in parentheses
        assertTrue(GeniusLyricsFetcher.isTitleMatch("Пули", "Пули (Bullets)"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("В темноте", "В темноте (In the Dark)"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("Инкассатор", "Инкассатор (Collector)"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("Бандит", "Бандит (Bandit)"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("Сделать Это", "Сделать Это (To Do This)"))
        assertTrue(GeniusLyricsFetcher.isTitleMatch("Море", "Море (Remix) (Sea)"))

        // Prefix match with parentheticals stripped
        assertTrue(GeniusLyricsFetcher.isTitleWordPrefixMatch("Заново", "Заново завоевать (Again Reconquer)"))
        assertFalse(GeniusLyricsFetcher.isTitleWordPrefixMatch("И", "Иногда"))

        // Strict artist & title rejection of unrelated hits (Yandex bug regression check)
        assertFalse(GeniusLyricsFetcher.isArtistMatch("ROCKET", "Rocket From The Crypt"))
        assertFalse(GeniusLyricsFetcher.isArtistMatch("Face", "Poker Face"))
        assertFalse(GeniusLyricsFetcher.isTitleMatch("Пули", "Bring Us Bullets"))

        // Bilingual & featured collab artist matching
        assertTrue(GeniusLyricsFetcher.isArtistMatch("Платина", "Платина (Platina) (Ft. BUSHIDO ZHO)"))
        assertTrue(GeniusLyricsFetcher.isArtistMatch("163ONMYNECK", "Scally Milano (Ft. 163ONMYNECK)"))
        assertTrue(GeniusLyricsFetcher.isArtistMatch("BUSHIDO ZHO", "Платина (Platina) (Ft. BUSHIDO ZHO)"))
    }

    @Test
    fun testGeniusSearchQueryBuilderAndNewAliases() {
        assertEquals(
            "ROCKET пули",
            MediaNotificationListener.buildGeniusSearchQuery("ROCKET", "пули", "рокет пули", artistFromTitle = true)
        )
        assertEquals(
            "ROCKET Пули",
            MediaNotificationListener.buildGeniusSearchQuery("ROCKET", "Пули", "Пули", artistFromTitle = false)
        )
        assertEquals(
            "Инкассатор",
            MediaNotificationListener.buildGeniusSearchQuery("sliv_trekov_52", "Инкассатор", "Инкассатор", artistFromTitle = false)
        )
        assertEquals(
            "ROCKET drive home",
            MediaNotificationListener.buildGeniusSearchQuery("ROCKET", "drive home", "🔥 рокет drive home (slowed + reverb) 💀", artistFromTitle = true)
        )

        // New artist aliases & Russian/year noise stripping
        assertEquals("LILDRUGHILL", MediaNotificationListener.parseTrack("лилдрагхилл - test", "").artist)
        assertEquals("madk1d", MediaNotificationListener.parseTrack("мадкид трек [слив] [2024]", "").artist)
        assertEquals("трек", MediaNotificationListener.stripNoise("трек [слив] (2024)"))
    }

    @Test
    fun testEstimateLineTimestamps() {
        val rawLines = listOf(
            LyricLine(text = "[Интро]"),
            LyricLine(text = "Эти дни летят как пули, слушай"),
            LyricLine(text = "Время дурит, эту дурь не юзай"),
            LyricLine(text = "Это пропаганда плохой жизни")
        )
        val durationMs = 180_000L
        val timed = GeniusLyricsFetcher.estimateLineTimestamps(rawLines, durationMs)
        assertEquals(rawLines.size, timed.size)
        for (i in timed.indices) {
            assertTrue("Timestamp must be >= 0", timed[i].timeMs >= 0L)
            assertTrue("Timestamp must be <= duration", timed[i].timeMs <= durationMs)
            if (i > 0) {
                assertTrue("Timestamps must be monotonic", timed[i].timeMs >= timed[i - 1].timeMs)
            }
        }
        assertTrue("Intro offset respected", timed.first().timeMs >= 2000L)
        assertTrue("Outro offset respected", timed.last().timeMs < durationMs - 5000L)
    }

    @Test
    fun testAdaptSyncedLyricsScalesForSlowedTrack() {
        val lines = listOf(
            LyricLine(10_000L, "Первая строка"),
            LyricLine(60_000L, "Вторая строка"),
            LyricLine(140_000L, "Третья строка")
        )
        val res = LyricsResult(
            title = "Song", artist = "Artist", lines = lines, plainLyrics = "",
            isSynced = true, lrcDurationMs = 150_000L
        )
        // Slowed + reverb version is 25% longer -> timestamps must stretch
        val slowed = GeniusLyricsFetcher.adaptLyricsToTrackDuration(res, 187_500L, "Song (slowed + reverb)")
        assertEquals(12_500L, slowed[0].timeMs)
        assertEquals(175_000L, slowed[2].timeMs)

        // Same-length track -> timestamps untouched
        val normal = GeniusLyricsFetcher.adaptLyricsToTrackDuration(res, 151_000L, "Song")
        assertEquals(lines.map { it.timeMs }, normal.map { it.timeMs })
    }

    @Test
    fun testParseLrcLinesHandlesOffsetAndMultiTags() {
        val lrc = "[offset:+500]\n[00:10.00][00:30.50]Припев\n[00:20.5]Куплет"
        val parsed = GeniusLyricsFetcher.parseLrcLines(lrc)
        assertEquals(listOf(9_500L, 20_000L, 30_000L), parsed.map { it.timeMs })
        assertEquals("Куплет", parsed[1].text)
    }

    @Test
    fun testComputeSeekTargetAndResolveTimestamp() {
        // 1. computeSeekTargetMs: normal case, positive offset, lower clamp, upper clamp, unknown duration
        assertEquals(45_000L, GeniusLyricsFetcher.computeSeekTargetMs(45_000L, 0L, 180_000L))
        assertEquals(43_000L, GeniusLyricsFetcher.computeSeekTargetMs(45_000L, 2_000L, 180_000L))
        assertEquals(0L, GeniusLyricsFetcher.computeSeekTargetMs(1_500L, 5_000L, 180_000L))
        assertEquals(179_500L, GeniusLyricsFetcher.computeSeekTargetMs(179_000L, -5_000L, 180_000L))
        assertEquals(61_000L, GeniusLyricsFetcher.computeSeekTargetMs(62_000L, 1_000L, 0L))

        // 2. resolveLineTimestampMs: synced lines work even when durationMs == 0L
        val synced = listOf(
            LyricLine(timeMs = 12_000L, text = "Первая строка"),
            LyricLine(timeMs = 24_500L, text = "Вторая строка")
        )
        assertEquals(12_000L, GeniusLyricsFetcher.resolveLineTimestampMs(synced, 0, 0L))
        assertEquals(24_500L, GeniusLyricsFetcher.resolveLineTimestampMs(synced, 1, 0L))

        // 3. Unsynced lines with duration > 10s return monotonic weighted timestamps
        val unsynced = listOf(
            LyricLine(text = "Короткая"),
            LyricLine(text = "Более длинная строка с несколькими словами"),
            LyricLine(text = "Финальная строка куплета")
        )
        val t0 = GeniusLyricsFetcher.resolveLineTimestampMs(unsynced, 0, 180_000L)
        val t1 = GeniusLyricsFetcher.resolveLineTimestampMs(unsynced, 1, 180_000L)
        val t2 = GeniusLyricsFetcher.resolveLineTimestampMs(unsynced, 2, 180_000L)
        assertTrue(t0 in 0L until t1)
        assertTrue(t2 > t1)

        // 4. Short duration (<= 10s) linear interpolation & out-of-bounds/zero-duration guards
        val shortT1 = GeniusLyricsFetcher.resolveLineTimestampMs(unsynced, 1, 8_000L)
        assertEquals(4_000L, shortT1)
        val shortT2 = GeniusLyricsFetcher.resolveLineTimestampMs(unsynced, 2, 8_000L)
        assertEquals(7_500L, shortT2)
        assertEquals(-1L, GeniusLyricsFetcher.resolveLineTimestampMs(unsynced, 5, 180_000L))
        assertEquals(-1L, GeniusLyricsFetcher.resolveLineTimestampMs(unsynced, 0, 0L))
    }

    @Test
    fun testIslandAnimStyles() {
        val allStyles = IslandAnimStyle.values()
        assertEquals(5, allStyles.size)

        // ID lookup and default fallback
        assertEquals(IslandAnimStyle.FLY_IN, IslandAnimStyle.fromId("fly_in"))
        assertEquals(IslandAnimStyle.FADE, IslandAnimStyle.fromId("fade"))
        assertEquals(IslandAnimStyle.SLIDE_KINETIC, IslandAnimStyle.fromId("slide"))
        assertEquals(IslandAnimStyle.ELASTIC_POP, IslandAnimStyle.fromId("pop"))
        assertEquals(IslandAnimStyle.TYPEWRITER, IslandAnimStyle.fromId("typewriter"))
        assertEquals(IslandAnimStyle.FLY_IN, IslandAnimStyle.fromId(null))
        assertEquals(IslandAnimStyle.FLY_IN, IslandAnimStyle.fromId("unknown_style"))

        // Cycling wraps smoothly across all 5 styles
        var cursor = IslandAnimStyle.FLY_IN
        val seen = mutableListOf(cursor)
        for (i in 0 until allStyles.size - 1) {
            cursor = IslandAnimStyle.nextStyle(cursor)
            seen.add(cursor)
        }
        assertEquals(allStyles.toList(), seen)
        assertEquals(IslandAnimStyle.FLY_IN, IslandAnimStyle.nextStyle(cursor))

        // Uniqueness and non-blank Russian labels
        assertEquals(allStyles.size, allStyles.map { it.id }.toSet().size)
        for (style in allStyles) {
            assertTrue(style.label.isNotBlank())
            assertTrue(style.shortLabel.isNotBlank())
        }
    }

    @Test
    fun testWordTimingEngineAndSpringMath() {
        // 1. LyricsDisplayMode enum & cycling
        val modes = LyricsDisplayMode.values()
        assertEquals(3, modes.size)
        assertEquals(LyricsDisplayMode.KARAOKE, LyricsDisplayMode.fromId("karaoke"))
        assertEquals(LyricsDisplayMode.WORD_BY_WORD, LyricsDisplayMode.fromId("word"))
        assertEquals(LyricsDisplayMode.PLAIN_TEXT, LyricsDisplayMode.fromId("plain"))
        assertEquals(LyricsDisplayMode.KARAOKE, LyricsDisplayMode.fromId(null))
        assertEquals(LyricsDisplayMode.KARAOKE, LyricsDisplayMode.fromId("invalid"))

        assertEquals(LyricsDisplayMode.WORD_BY_WORD, LyricsDisplayMode.nextMode(LyricsDisplayMode.KARAOKE))
        assertEquals(LyricsDisplayMode.PLAIN_TEXT, LyricsDisplayMode.nextMode(LyricsDisplayMode.WORD_BY_WORD))
        assertEquals(LyricsDisplayMode.KARAOKE, LyricsDisplayMode.nextMode(LyricsDisplayMode.PLAIN_TEXT))

        // 2. SpringMath damped harmonic oscillator invariants
        assertEquals(0f, SpringMath.evaluateSpring(0f, 340f, 0.56f), 0.0001f)
        assertEquals(1f, SpringMath.evaluateSpring(1f, 340f, 0.56f), 0.0001f)
        var peakBouncy = 0f
        for (step in 1..40) {
            val v = SpringMath.evaluateSpring(step / 40f, 340f, 0.56f)
            if (v > peakBouncy) peakBouncy = v
        }
        assertTrue("Underdamped spring must overshoot 1.0f, got $peakBouncy", peakBouncy > 1.02f)

        var prevDamped = 0f
        for (step in 1..40) {
            val v = SpringMath.evaluateSpring(step / 40f, 400f, 1.0f)
            assertTrue("Critically damped spring stays in [0, 1]", v in 0f..1f)
            assertTrue("Critically damped spring is monotonic", v >= prevDamped - 0.0001f)
            prevDamped = v
        }

        // 3. WordTimingEngine tokenization & punctuation weighting
        val tokens = WordTimingEngine.tokenizeLine("Эти дни летят, как пули")
        assertEquals(5, tokens.size)
        assertEquals("Эти", tokens[0].text)
        assertEquals(0, tokens[0].start)
        assertEquals(3, tokens[0].end)
        assertEquals(0f, tokens.first().startFraction, 0.0001f)
        assertEquals(1f, tokens.last().endFraction, 0.0001f)
        for (i in 1 until tokens.size) {
            assertTrue(tokens[i].startFraction >= tokens[i - 1].endFraction - 0.0001f)
        }

        // 4. WordTimingEngine beat advancement & gap completion
        val lines = listOf(
            LyricLine(timeMs = 5_000L, text = "[Припев]"),
            LyricLine(timeMs = 10_000L, text = "Раз два три четыре пять"),
            LyricLine(timeMs = 30_000L, text = "Следующая строчка")
        )
        assertEquals(null, WordTimingEngine.computeBeatState(lines, 0, 5_500L, 60_000L))

        val bStart = WordTimingEngine.computeBeatState(lines, 1, 10_050L, 60_000L)!!
        val bMid = WordTimingEngine.computeBeatState(lines, 1, 11_600L, 60_000L)!!
        val bGap = WordTimingEngine.computeBeatState(lines, 1, 25_000L, 60_000L)!!

        assertEquals(0, bStart.activeWordIndex)
        assertFalse(bStart.isCompleted)
        assertTrue(bMid.activeWordIndex > bStart.activeWordIndex)
        assertFalse(bMid.isCompleted)
        assertTrue("Vocal window completes well before 20s instrumental gap", bGap.isCompleted)
        assertEquals(bGap.tokens.lastIndex, bGap.activeWordIndex)
    }

    @Test
    fun testVibeColorThemesAndStickers() {
        // 1. VibeColorTheme cycling & fromId
        val themes = VibeColorTheme.values()
        assertEquals(6, themes.size)
        for (i in themes.indices) {
            val t = themes[i]
            assertEquals(t, VibeColorTheme.fromId(t.id))
            val expectedNext = themes[(i + 1) % themes.size]
            assertEquals(expectedNext, VibeColorTheme.nextTheme(t))
        }
        assertEquals(VibeColorTheme.CYBER_EMERALD, VibeColorTheme.fromId("unknown_theme"))

        // 2. AUTO_VIBE dynamic section & lyric mood resolution
        val auto = VibeColorTheme.AUTO_VIBE
        assertEquals(VibeColorTheme.SUNSET_PHONK, auto.resolveEffective("ПРИПЕВ 1", "Обычная строка"))
        assertEquals(VibeColorTheme.TOKYO_NEON, auto.resolveEffective("БРИДЖ", "Обычная строка"))
        assertEquals(VibeColorTheme.ARCTIC_AURORA, auto.resolveEffective("ИНТРО", "Обычная строка"))
        assertEquals(VibeColorTheme.GOLD_GENIUS, auto.resolveEffective("АУТРО", "Обычная строка"))
        assertEquals(VibeColorTheme.SUNSET_PHONK, auto.resolveEffective("КУПЛЕТ", "Внутри горит огонь и бас"))
        assertEquals(VibeColorTheme.TOKYO_NEON, auto.resolveEffective("КУПЛЕТ", "Эта ночь и неон"))
        assertEquals(VibeColorTheme.GOLD_GENIUS, auto.resolveEffective("КУПЛЕТ", "Яркие звезды и золото"))
        assertEquals(VibeColorTheme.CYBER_EMERALD, auto.resolveEffective("КУПЛЕТ 1", "Спокойный шаг вперед"))

        // Explicit theme ignores section override
        assertEquals(
            VibeColorTheme.TOKYO_NEON,
            VibeColorTheme.TOKYO_NEON.resolveEffective("ПРИПЕВ", "Огонь")
        )

        // 3. VibeStickerStyle cycling & burst symbols
        val stickers = VibeStickerStyle.values()
        assertEquals(6, stickers.size)
        for (i in stickers.indices) {
            val s = stickers[i]
            assertEquals(s, VibeStickerStyle.fromId(s.id))
            assertTrue("Sticker burst symbols must not be empty", s.burstSymbols.isNotEmpty())
            val expectedNext = stickers[(i + 1) % stickers.size]
            assertEquals(expectedNext, VibeStickerStyle.nextSticker(s))
        }
        assertEquals(VibeStickerStyle.VINYL_DISC, VibeStickerStyle.fromId(null))

        // 4. VibeBackgroundStyle cycling & fromId
        val bgStyles = VibeBackgroundStyle.values()
        assertEquals(4, bgStyles.size)
        for (i in bgStyles.indices) {
            val b = bgStyles[i]
            assertEquals(b, VibeBackgroundStyle.fromId(b.id))
            val expectedNext = bgStyles[(i + 1) % bgStyles.size]
            assertEquals(expectedNext, VibeBackgroundStyle.nextStyle(b))
        }
        assertEquals(VibeBackgroundStyle.NEBULA_RINGS, VibeBackgroundStyle.fromId("unknown"))
    }
}
