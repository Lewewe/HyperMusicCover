package com.os4.musiccover;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Standalone, dependency-free regression suite; run tools/run-lyric-match-tests.py. */
public final class LyricSearchAliasesTest {
    private static int checks;
    private static final String ALBUM = "Honkai: Star Rail";
    private static final long DURATION = 210000L;

    public static void main(String[] args) throws Exception {
        prophecy();
        redhood();
        soundtrackAttributions();
        artistNormalization();
        boundedAliases();
        recordingGuards();
        titleAndArtistGuards();
        durationGuards();
        metadataAndSearchTerms();
        catalogueMatching();
        System.out.println("LyricSearchAliasesTest: " + checks + " checks passed");
    }

    private static NcmLyrics.Query query(String title, String artist) {
        return new NcmLyrics.Query(title, artist, ALBUM, DURATION);
    }

    private static LyricMatch.Candidate candidate(String title, String artist) {
        return new LyricMatch.Candidate("1", title, artist, ALBUM, DURATION, null);
    }

    private static boolean passes(NcmLyrics.Query q, LyricMatch.Candidate c) {
        return LyricMatch.best(Arrays.asList(c), new LyricMatch.Wanted(q)).passes();
    }

    private static void prophecy() {
        NcmLyrics.Query apple = query("Prophecy (feat. Gin Wigmore)", "San-Z & HOYO-MIX");
        NcmLyrics.Query spotify = query("Prophecy", "San-Z, HOYO-MiX, Gin Wigmore");
        check(passes(apple, candidate(spotify.title, spotify.artist)), "Apple -> Spotify credits");
        check(passes(spotify, candidate(apple.title, apple.artist)), "Spotify -> Apple credits");
        List<NcmLyrics.Query> a = apple.aliases();
        same(apple, a.get(0), "original instance first");
        equal("San-Z & HOYO-MIX", a.get(1).artist, "combined hint precedes individuals");
        equal("Prophecy", a.get(1).title, "combined hint strips only feature title credit");
        equal("san-z / hoyo-mix / gin wigmore", a.get(2).artist, "canonical hint retains all credits");
        individual(a, 3, "Prophecy", "San-Z", "HOYO-MIX", "Gin Wigmore");
        equal(8, a.size(), "featured-title individual searches fit the bounded aliases");
        equal(apple.title, a.get(6).title, "featured title retained for primary artist search");
        equal("San-Z", a.get(6).artist, "featured title searched with San-Z alone");
        equal(apple.title, a.get(7).title, "featured title retained for second primary artist");
        equal("HOYO-MIX", a.get(7).artist, "featured title searched with HOYO-MIX alone");
        List<NcmLyrics.Query> s = LyricSearchAliases.variants(spotify);
        same(spotify, s.get(0), "Spotify original first");
        equal("san-z / hoyo-mix / gin wigmore", s.get(1).artist, "combined Spotify hint first");
        individual(s, 2, "Prophecy", "San-Z", "HOYO-MiX", "Gin Wigmore");
        for (NcmLyrics.Query alias : s) {
            equal("Prophecy", alias.title, "no inferred feature title");
            check(!passes(alias, candidate("Prophecy", "Unrelated Artist")), "alias rejects wrong artist");
        }
        check(passes(s.get(2), candidate(apple.title, apple.artist)), "San-Z overlaps Apple credits");
        check(passes(s.get(3), candidate(apple.title, apple.artist)), "HOYO-MIX overlaps Apple credits");
        check(!passes(s.get(4), candidate(apple.title, apple.artist)), "guest alone need not overlap Apple artist");
        check(passes(s.get(4), candidate("Prophecy", "Gin Wigmore")), "guest search validates guest credit");
        List<NcmLyrics.Query> all = query("Prophecy (feat. Gin Wigmore)",
                "San-Z, HOYO-MiX, Gin Wigmore").aliases();
        individual(all, 3, "Prophecy", "San-Z", "HOYO-MiX", "Gin Wigmore");
        equal(8, all.size(), "combined, individual, and featured-title queries stay bounded");
        equal("San-Z", all.get(6).artist, "featured title paired with first primary artist");
        equal("HOYO-MiX", all.get(7).artist, "featured title paired with second primary artist");
    }

    private static void redhood() {
        NcmLyrics.Query q = query("THE REDHOOD", "Cosmograph, NieN, Djerv");
        List<NcmLyrics.Query> aliases = q.aliases();
        same(q, aliases.get(0), "REDHOOD original first");
        equal("cosmograph / nien / djerv", aliases.get(1).artist, "REDHOOD combined hint first");
        individual(aliases, 2, "THE REDHOOD", "Cosmograph", "NieN", "Djerv");
        for (NcmLyrics.Query alias : aliases) {
            equal(q.title, alias.title, "REDHOOD has no invented feature title");
            check(!passes(alias, candidate(q.title, "Unrelated Artist")), "REDHOOD rejects unrelated artist");
            equal(q.album, alias.album, "REDHOOD album retained");
            equal(q.durationMs, alias.durationMs, "REDHOOD duration retained");
        }
        check(passes(aliases.get(4), candidate(q.title, "Djerv")), "REDHOOD guest-only result");
        check(!passes(aliases.get(4), candidate(q.title, "Cosmograph, NieN")),
                "individual artist need not overlap other credited artists");
    }

    private static void soundtrackAttributions() {
        NcmLyrics.Query q = query("瞬息甜味 - From Punishing Gray Raven",
                "Punishing Gray Raven, 沐可Linda, Zoe");
        check(passes(q, candidate("瞬息甜味", "战双帕弥什, 沐可Linda")),
                "soundtrack attribution is not a different song title");
        check(passes(q, candidate("瞬息甜味（Sugar Time）", "沐可linda")),
                "bilingual catalogue subtitle matches reported track");
        same(q, q.aliases().get(0), "reported original full-credit query remains first");
        equal("瞬息甜味", q.aliases().get(1).title, "bare-title combined search precedes individual artists");
        equal(q.artist, q.aliases().get(1).artist, "bare-title search retains complete credits");
        equal("瞬息甜味", LyricSearchAliases.coreTitle("瞬息甜味 (From Game Punishing Gray Raven)"),
                "parenthetical source attribution");
        equal("Sugar Time", LyricSearchAliases.coreTitle("Sugar Time（瞬息甜味）"),
                "reverse bilingual subtitle");
        check(!passes(q, candidate("瞬息甜味（Sugar Time）（吉他版）", "沐可Linda")),
                "guitar version is still a distinct recording");
        check(!passes(q, candidate("瞬息甜味 (Guitar Version)", "沐可Linda")),
                "English version qualifier is not a translated subtitle");
        check(!passes(q, candidate("瞬息甜味 (Live)", "沐可Linda")), "live take still rejected");
        check(!passes(q, candidate("瞬息甜味", "Unrelated Singer")), "attribution normalization keeps artist guard");
        check(!passes(q, new LyricMatch.Candidate("1", "瞬息甜味（Sugar Time）", "沐可Linda", ALBUM,
                DURATION + 10000L, null)), "attribution normalization keeps duration guard");
        equal("瞬息甜味 (Part II)", LyricSearchAliases.coreTitle("瞬息甜味 (Part II)"),
                "bilingual-looking sequel label is retained");
        equal("Song (Live)", LyricSearchAliases.coreTitle("Song (Live) - From Game"),
                "source stripping preserves explicit recording labels");
        equal("From Here to Eternity", LyricSearchAliases.coreTitle("From Here to Eternity"),
                "ordinary title beginning with From is unchanged");
    }

    private static void individual(List<NcmLyrics.Query> aliases, int start, String title, String... artists) {
        for (int i = 0; i < artists.length; i++) {
            equal(title, aliases.get(start + i).title, "individual core title");
            equal(artists[i], aliases.get(start + i).artist, "individual artist order");
        }
    }

    private static void artistNormalization() {
        String[] credits = {"San-Z & HOYO-MIX", "hoyo-mix, SAN-Z", "San-Z/HOYO-MiX",
                "San-Z feat. HOYO-MIX", "San-Z ft. HOYO-MIX", "San-Z featuring HOYO-MIX",
                "San-Z (feat. HOYO-MIX)", "Ｓａｎ－Ｚ，ＨＯＹＯ－ＭＩＸ", "San-Z、HOYO-MIX"};
        for (String local : credits) for (String remote : credits) {
            check(passes(query("Prophecy", local), candidate("PROPHECY", remote)),
                    "case/order/separator: " + local + " -> " + remote);
        }
        check(LyricSearchAliases.artistsOverlap("San-Z & HOYO-MIX", "HOYO-MiX, Gin Wigmore"),
                "exact shared token");
        check(!LyricSearchAliases.artistsOverlap("San", "San-Z"), "no artist substring overlap");
        check(!LyricSearchAliases.artistsOverlap("Ann", "Joanne"), "no contained artist identity");
        equal(2, LyricMatch.splitArtists("San-Z / SAN-Z, HOYO-MiX").size(), "deduplicated names");
        check(new LyricMatch.Wanted(query("Song", "A feat. B")).multiCredit(), "shared multi-credit parsing");
        check(!new LyricMatch.Wanted(query("Song", "A / a")).multiCredit(), "duplicate is not multi-credit");
    }

    private static void boundedAliases() {
        for (String artist : Arrays.asList("A", "A, B", "A & B / C", "A/B/C/D", "A/B/C/D/E",
                "A/B/C/D/E/F/G/H/I", "A / a / B", "Unknown Artist", "", "A feat. B")) {
            for (String title : Arrays.asList("Song", "Song (feat. B)", "Song (Live)", "")) {
                NcmLyrics.Query q = query(title, artist);
                List<NcmLyrics.Query> aliases = LyricSearchAliases.variants(q);
                check(aliases.size() >= 1 && aliases.size() <= 8, "bounded variant count");
                same(q, aliases.get(0), "original retained");
                HashSet<String> keys = new HashSet<>();
                for (NcmLyrics.Query alias : aliases) {
                    check(keys.add(alias.key()), "no duplicate queries");
                    equal(q.album, alias.album, "album retained");
                    equal(q.durationMs, alias.durationMs, "duration retained");
                }
                List<String> again = new ArrayList<>();
                for (NcmLyrics.Query alias : LyricSearchAliases.variants(q)) again.add(alias.key());
                equal(new ArrayList<>(keys).size(), again.size(), "repeat count stable");
                for (int i = 0; i < aliases.size(); i++) equal(aliases.get(i).key(), again.get(i), "deterministic order");
            }
        }
        check(LyricSearchAliases.variants(null).isEmpty(), "null query");
        for (NcmLyrics.Query alias : query("Song", "A/B/C/D/E").aliases()) {
            check(!alias.title.contains("feat."), "no guessed guest for long ambiguous credits");
        }
        List<NcmLyrics.Query> explicit = query("Song (feat. B)", "A/C").aliases();
        individual(explicit, 3, "Song", "A", "C", "B");
        for (NcmLyrics.Query alias : explicit) {
            check(!alias.title.contains("feat. C"), "no invented guest on explicit title");
            check(!passes(alias, candidate("Song", "Unrelated Artist")), "explicit guest aliases reject unrelated");
        }
        List<NcmLyrics.Query> capped = query("Song", "A/B/C/D/E/F/G/H/I").aliases();
        equal(8, LyricSearchAliases.MAX_VARIANTS, "configured alias limit");
        equal(8, capped.size(), "original and combined hint retained within cap");
        equal("a / b / c / d / e / f / g / h / i", capped.get(1).artist, "combined hint survives cap");
        individual(capped, 2, "Song", "A", "B", "C", "D", "E", "F", "G");
        List<NcmLyrics.Query> guestAtLimit = query("Song (feat. G)", "A/B/C/D/E/F").aliases();
        equal(8, guestAtLimit.size(), "combined-credit queries take priority within cap");
        equal("a / b / c / d / e / f / g", guestAtLimit.get(2).artist, "combined hint includes explicit guest");
        individual(guestAtLimit, 3, "Song", "A", "B", "C", "D", "E");
        NcmLyrics.Query single = query("Song", "Artist");
        equal(1, single.aliases().size(), "single identical artist search deduplicated");
        same(single, single.aliases().get(0), "single original instance retained");
        List<NcmLyrics.Query> duplicateGuest = query("Song (feat. b)", "A/B").aliases();
        individual(duplicateGuest, 3, "Song", "A", "B");
        equal(7, duplicateGuest.size(), "title guest deduplicated and original title paired with primary artists");
        equal("Song (feat. b)", duplicateGuest.get(5).title, "original feature wording retained");
        equal("A", duplicateGuest.get(5).artist, "original feature title searched with primary artist");
        equal("B", duplicateGuest.get(6).artist, "original feature title searched with second artist");
        List<NcmLyrics.Query> multipleGuests = query("Song feat. B & C", "A").aliases();
        equal("A", multipleGuests.get(1).artist, "feature-free primary search retained");
        equal("a / b / c", multipleGuests.get(2).artist, "combined explicit guests retained");
        individual(multipleGuests, 3, "Song", "B", "C");
        try {
            query("Song", "A/B").aliases().clear();
            throw new AssertionError("aliases must be immutable");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
    }

    private static void recordingGuards() {
        for (String label : Arrays.asList("Live", "Remix", "Acoustic", "2011 Remaster", "Remastered",
                "Cover", "Instrumental", "Demo", "现场版", "混音版")) {
            String title = "Prophecy (" + label + ")";
            check(!passes(query("Prophecy", "San-Z"), candidate(title, "San-Z")), "studio vs " + label);
            check(!passes(query(title, "San-Z"), candidate("Prophecy", "San-Z")), label + " vs studio");
            check(passes(query(title, "San-Z"), candidate("Prophecy [" + label + "]", "San-Z")),
                    "same recording label, different brackets");
            for (NcmLyrics.Query alias : query(title + " (feat. Guest)", "San-Z & HOYO-MIX").aliases()) {
                check(alias.title.contains(label), "alias keeps " + label);
            }
            for (NcmLyrics.Query alias : query(title, "San-Z, HOYO-MIX, Guest").aliases()) {
                check(alias.title.contains(label), "multi-credit alias keeps " + label);
                check(!alias.title.contains("feat."), "no speculative guest for recording label");
            }
        }
        check(!passes(query("Prophecy (Live at A)", "San-Z"), candidate("Prophecy (Live at B)", "San-Z")),
                "different live recordings");
        check(!passes(query("Prophecy (Remix A)", "San-Z"), candidate("Prophecy (Remix B)", "San-Z")),
                "different remixes");
        equal("Song (feat. Guest - Live)", LyricSearchAliases.coreTitle("Song (feat. Guest - Live)"),
                "mixed recording/feature label not stripped");
        equal("Song (Acoustic)", LyricSearchAliases.coreTitle("Song (Acoustic) feat. Guest"),
                "bare feature tail removed, recording preserved");
        equal("Alive", LyricSearchAliases.coreTitle("Alive"), "live substring is not a label");
        equal("Song (Part II)", LyricSearchAliases.coreTitle("Song (Part II)"), "other brackets preserved");
    }

    private static void titleAndArtistGuards() {
        NcmLyrics.Query q = query("Prophecy", "San-Z");
        check(!passes(q, candidate("Prophecy II", "San-Z")), "no title substring match");
        check(!passes(q, candidate("Prophecy", "San")), "no artist substring match");
        check(!passes(q, candidate("Prophecy", "Wrong Artist")), "same album/duration cannot prove artist");
        check(!passes(q, candidate("Other Song", "San-Z")), "same artist/album/duration cannot prove title");
        for (String unknown : Arrays.asList("", "Unknown Artist", "<unknown>", "null", "未知歌手")) {
            check(!passes(query("Prophecy", unknown), candidate("Prophecy", "San-Z")), "unknown wanted artist");
            check(!passes(q, candidate("Prophecy", unknown)), "unknown provider artist");
            check(!passes(query("Prophecy (Live)", unknown), candidate("Prophecy (Live)", unknown)),
                    "recording bonus cannot bypass unknown artist");
        }
        check(!passes(query("", "San-Z"), candidate("Prophecy", "San-Z")), "empty title");
        check(!passes(q, candidate("", "San-Z")), "empty candidate title");
        check(passes(query("I'm So Tired...", "LAUV"), candidate("i’m so tired", "lauv")),
                "punctuation/case normalized exactly");
    }

    private static void durationGuards() {
        for (long diff : new long[]{0L, 1499L, 1500L, 4800L, 5000L, 5001L, 25000L}) {
            LyricMatch.Candidate c = new LyricMatch.Candidate("1", "Song (Live)", "Artist", ALBUM,
                    DURATION + diff, null);
            equal(diff <= 5000L, passes(query("Song (Live)", "Artist"), c), "duration guard " + diff);
        }
        check(!passes(new NcmLyrics.Query("Song", "Artist", "", 0L),
                new LyricMatch.Candidate("1", "Song", "Artist", "", 0L, null)), "no album/length evidence");
        check(passes(new NcmLyrics.Query("Song", "Artist", ALBUM, 0L), candidate("Song", "Artist")),
                "existing exact album evidence when duration unknown");
    }

    private static void metadataAndSearchTerms() {
        equal("San-Z", NcmLyrics.firstArtist("San-Z & HOYO-MIX"), "first ampersand credit");
        equal("San-Z", NcmLyrics.firstArtist("San-Z, HOYO-MiX, Gin Wigmore"), "first comma credit");
        equal("San-Z", NcmLyrics.firstArtist("San-Z feat. Gin Wigmore"), "first feature credit");
        equal("", NcmLyrics.firstArtist(null), "null artist safe");
        equal("", NcmLyrics.firstArtist("<unknown>"), "unknown not a search name");
        equal("Prophecy San-Z, HOYO-MiX", NcmLyrics.terms(query("Prophecy", "San-Z, HOYO-MiX")),
                "song search retains complete artist credit");
        equal("Prophecy HOYO-MiX", NcmLyrics.terms(query("Prophecy", "HOYO-MiX")),
                "individual search remains additional");
        NcmLyrics.Query salt = NcmLyrics.build("", "Artist - Song (Live)", "Album", DURATION);
        equal("Song (Live)", salt.title, "existing Salt metadata repair retained");
        equal("Artist", salt.artist, "Salt artist");
        NcmLyrics.Query alias = salt.withNames("Other", "Other Artist");
        equal(salt.album, alias.album, "withNames album");
        equal(salt.durationMs, alias.durationMs, "withNames duration");
    }

    private static void catalogueMatching() throws Exception {
        Method choose = NcmLyrics.class.getDeclaredMethod("choose", org.json.JSONArray.class, NcmLyrics.Query.class);
        choose.setAccessible(true);
        org.json.JSONArray songs = new org.json.JSONArray();
        songs.put(song(2L, "Prophecy (Live)", "San-Z"));
        songs.put(song(3L, "Prophecy", "San"));
        songs.put(song(4L, "Prophecy", "Wrong Artist"));
        songs.put(song(5L, "Prophecy", "HOYO-MiX, San-Z, Gin Wigmore"));
        equal("5", choose.invoke(null, songs, query("Prophecy (feat. Gin Wigmore)", "San-Z & HOYO-MIX")),
                "real Ncm candidate conversion uses shared rules");
        Method byArtist = NcmLyrics.class.getDeclaredMethod("byArtist", String.class, org.json.JSONObject.class);
        byArtist.setAccessible(true);
        equal(true, byArtist.invoke(null, "San-Z & HOYO-MIX", song(1L, "Prophecy", "HOYO-MiX, Gin Wigmore")),
                "album artist exact shared overlap");
        equal(false, byArtist.invoke(null, "San", song(1L, "Prophecy", "San-Z")), "album no substrings");
        equal(false, byArtist.invoke(null, "Unknown Artist", song(1L, "Prophecy", "Unknown Artist")),
                "album unknown cannot prove artist");
        equal(false, byArtist.invoke(null, "San-Z", new org.json.JSONObject()), "album missing credits");
    }

    private static org.json.JSONObject song(long id, String title, String artist) throws Exception {
        return new org.json.JSONObject().put("id", id).put("name", title).put("duration", DURATION)
                .put("album", new org.json.JSONObject().put("name", ALBUM))
                .put("artists", new org.json.JSONArray().put(new org.json.JSONObject().put("name", artist)));
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    private static void same(Object expected, Object actual, String message) {
        check(expected == actual, message);
    }
}
