package com.os4.musiccover;

import com.atilika.kuromoji.unidic.Token;
import com.atilika.kuromoji.unidic.Tokenizer;

import java.util.List;

/**
 * Local Japanese reading and Hepburn-style romaji.
 *
 * <p>The provider's romanisation remains on {@link LyricLine#roma}; this class supplies an
 * independently-derived display reading when a line is recognisably Japanese.  It intentionally
 * does not guess Chinese: a kanji-only Chinese lyric is better served by its provider fallback
 * than by a false Japanese reading.</p>
 */
final class JapaneseRomanizer {
    private JapaneseRomanizer() { }

    /** Bump whenever the reading/romanisation policy changes, for future derived-cache upgrades. */
    static final int REVISION = LocalRomanizer.REVISION;
    private static volatile Tokenizer tokenizer;
    private static volatile boolean unavailable;

    /** Fills local values in-place; safe to call more than once and intended for worker threads. */
    static void apply(List<LyricLine> lines) {
        if (!LockLyrics.sOnDeviceTransliteration || !LockLyrics.sLocalJapanese || unavailable || lines == null || lines.isEmpty()
                || !hasKana(lines) || tokenizer == null && !JapaneseDictionary.ready(Main.appContext())) return;
        for (LyricLine line : lines) apply(line);
    }

    static boolean needsApply(List<LyricLine> lines) {
        if (!LockLyrics.sOnDeviceTransliteration || !LockLyrics.sLocalJapanese || unavailable || lines == null || lines.isEmpty()
                || !hasKana(lines)) return false;
        if (tokenizer == null && !JapaneseDictionary.ready(Main.appContext())) return false;
        for (LyricLine line : lines) if (needsApply(line)) return true;
        return false;
    }

    private static boolean needsApply(LyricLine line) {
        return line != null && (!LocalRomanizer.currentReading(line) && hasKana(line.text)
                || needsApply(line.bg));
    }

    private static void apply(LyricLine line) {
        if (line == null) return;
        if (!LocalRomanizer.currentReading(line) && hasKana(line.text)) {
            String romanized = romanize(line.text);
            if (romanized != null) {
                line.localRoma = romanized;
                line.localRomaSettings = LocalRomanizer.settingsMask();
                line.localRomaRevision = REVISION;
            }
        }
        apply(line.bg);
    }

    private static boolean hasKana(List<LyricLine> lines) {
        for (LyricLine line : lines) {
            if (line != null && (hasKana(line.text) || line.bg != null && hasKana(line.bg.text))) return true;
        }
        return false;
    }

    private static boolean hasJapanese(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (isKana(cp) || isHan(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** This Japanese-only engine owns kana/kanji lines; their provider roma is never rendered. */
    static boolean handles(String text) {
        return hasKana(text);
    }

    private static boolean hasKana(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (isKana(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isKana(int cp) {
        return cp >= 0x3040 && cp <= 0x30ff || cp >= 0x31f0 && cp <= 0x31ff
                || cp >= 0xff66 && cp <= 0xff9f;
    }

    private static boolean isHan(int cp) {
        return cp >= 0x3400 && cp <= 0x9fff || cp >= 0xf900 && cp <= 0xfaff;
    }

    static String romanize(String text) {
        if (text == null || text.trim().isEmpty() || unavailable || !LockLyrics.sOnDeviceTransliteration || !LockLyrics.sLocalJapanese) return null;
        try {
            Tokenizer t = tokenizer();
            if (t == null) return null;
            StringBuilder out = new StringBuilder(text.length() * 2);
            boolean word = false;
            for (Token token : t.tokenize(text)) {
                String surface = token.getSurface();
                // A lemma reading preserves conventional spellings such as きょう -> kyou;
                // pronunciation turns those into キョー, which is phonetically right but not
                // the readable romaji people expect below a lyric.
                String reading = token.getLemmaReadingForm();
                if (reading == null || reading.equals("*")) reading = token.getPronunciation();
                if (reading == null || reading.equals("*") || !hasJapanese(surface)) {
                    appendLiteral(out, surface);
                    word = endsWord(surface);
                    continue;
                }
                String roma = kanaToRomaji(reading, surface, token.getPartOfSpeechLevel1());
                if (roma.isEmpty()) continue;
                if ((word && needsSpace(out, roma)) || followsSeparator(out)) out.append(' ');
                out.append(roma);
                word = true;
            }
            String result = tidy(out);
            return result.isEmpty() ? null : result;
        } catch (Throwable failure) {
            unavailable = true;
            Xp.w("Japanese romaniser unavailable: " + failure);
            return null;
        }
    }

    private static Tokenizer tokenizer() throws Exception {
        Tokenizer local = tokenizer;
        if (local != null) return local;
        synchronized (JapaneseRomanizer.class) {
            tokenizer = JapaneseDictionary.tokenizer(Main.appContext());
            return tokenizer;
        }
    }

    /** Host-test seam: production always obtains its tokenizer from the downloaded dictionary. */
    static void setTokenizerForTesting(Tokenizer value) {
        tokenizer = value;
        unavailable = false;
    }

    static void clearTokenizer() {
        tokenizer = null;
        unavailable = false;
    }

    private static void appendLiteral(StringBuilder out, String literal) {
        if (literal == null || literal.isEmpty()) return;
        literal = literal.replace('、', ',').replace('。', '.').replace('！', '!').replace('？', '?')
                .replace('（', '(').replace('）', ')');
        if (out.length() > 0 && needsSpace(out, literal) && !Character.isWhitespace(literal.charAt(0))) {
            out.append(' ');
        }
        out.append(literal);
    }

    private static boolean endsWord(String s) {
        return s != null && !s.isEmpty() && Character.isLetterOrDigit(s.codePointBefore(s.length()));
    }

    private static boolean needsSpace(StringBuilder before, String after) {
        if (before.length() == 0 || after == null || after.isEmpty()) return false;
        char left = before.charAt(before.length() - 1);
        char right = after.charAt(0);
        return Character.isLetterOrDigit(left) && Character.isLetterOrDigit(right);
    }

    private static boolean followsSeparator(StringBuilder before) {
        if (before.length() == 0) return false;
        char last = before.charAt(before.length() - 1);
        return last == ',' || last == '.' || last == '!' || last == '?' || last == ')';
    }

    private static String tidy(StringBuilder value) {
        int begin = 0, end = value.length();
        while (begin < end && Character.isWhitespace(value.charAt(begin))) begin++;
        while (end > begin && Character.isWhitespace(value.charAt(end - 1))) end--;
        return value.substring(begin, end).replaceAll(" {2,}", " ");
    }

    private static String kanaToRomaji(String kana, String surface, String pos) {
        StringBuilder out = new StringBuilder(kana.length() * 2);
        boolean geminate = false;
        for (int i = 0; i < kana.length();) {
            char c = toHiragana(kana.charAt(i));
            if (c == 'っ') { geminate = true; i++; continue; }
            if (c == 'ー') {
                appendLongVowel(out);
                i++;
                continue;
            }
            String roma = null;
            if (i + 1 < kana.length()) {
                roma = syllable(c, toHiragana(kana.charAt(i + 1)));
                if (roma != null) i += 2;
            }
            if (roma == null) { roma = syllable(c, '\0'); i++; }
            if (roma == null) continue;
            if (geminate) { appendGeminate(out, roma); geminate = false; }
            out.append(roma);
        }
        String result = out.toString();
        // UniDic keeps the conventional greetings as one interjection token, so its POS does not
        // identify their final は as a particle even though it is pronounced wa.
        if ("こんにちは".equals(surface) || "こんばんは".equals(surface)) {
            return result.endsWith("ha") ? result.substring(0, result.length() - 2) + "wa" : result;
        }
        // Orthographic particles are pronounced wa/e/o in modern Japanese.
        if (pos != null && pos.contains("助詞")) {
            if ("は".equals(surface)) return "wa";
            if ("へ".equals(surface)) return "e";
            if ("を".equals(surface)) return "o";
        }
        return result;
    }

    private static char toHiragana(char c) {
        if (c >= 'ァ' && c <= 'ヶ') return (char) (c - 0x60);
        if (c >= 0xff66 && c <= 0xff9d) return c; // Rare half-width input is retained by the tokeniser.
        return c;
    }

    private static void appendGeminate(StringBuilder out, String next) {
        if (next.startsWith("ch")) out.append('c');
        else if (next.startsWith("sh")) out.append('s');
        else if (next.startsWith("ts")) out.append('t');
        else if (!next.isEmpty() && Character.isLetter(next.charAt(0)) && next.charAt(0) != 'n') out.append(next.charAt(0));
    }

    private static void appendLongVowel(StringBuilder out) {
        for (int i = out.length() - 1; i >= 0; i--) {
            char c = out.charAt(i);
            if (c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o') { out.append(c); return; }
        }
    }

    private static String syllable(char a, char b) {
        if (b != '\0') {
            if (b != 'ゃ' && b != 'ゅ' && b != 'ょ') return null;
            String stem;
            switch (a) {
                case 'き': stem = "ky"; break; case 'ぎ': stem = "gy"; break;
                case 'し': stem = "sh"; break; case 'じ': stem = "j"; break;
                case 'ち': stem = "ch"; break; case 'に': stem = "ny"; break;
                case 'ひ': stem = "hy"; break; case 'び': stem = "by"; break;
                case 'ぴ': stem = "py"; break; case 'み': stem = "my"; break;
                case 'り': stem = "ry"; break; default: return null;
            }
            return stem + (b == 'ゃ' ? "a" : b == 'ゅ' ? "u" : "o");
        }
        switch (a) {
            case 'あ': return "a"; case 'い': return "i"; case 'う': return "u"; case 'え': return "e"; case 'お': return "o";
            case 'か': return "ka"; case 'き': return "ki"; case 'く': return "ku"; case 'け': return "ke"; case 'こ': return "ko";
            case 'が': return "ga"; case 'ぎ': return "gi"; case 'ぐ': return "gu"; case 'げ': return "ge"; case 'ご': return "go";
            case 'さ': return "sa"; case 'し': return "shi"; case 'す': return "su"; case 'せ': return "se"; case 'そ': return "so";
            case 'ざ': return "za"; case 'じ': return "ji"; case 'ず': return "zu"; case 'ぜ': return "ze"; case 'ぞ': return "zo";
            case 'た': return "ta"; case 'ち': return "chi"; case 'つ': return "tsu"; case 'て': return "te"; case 'と': return "to";
            case 'だ': return "da"; case 'ぢ': return "ji"; case 'づ': return "zu"; case 'で': return "de"; case 'ど': return "do";
            case 'な': return "na"; case 'に': return "ni"; case 'ぬ': return "nu"; case 'ね': return "ne"; case 'の': return "no";
            case 'は': return "ha"; case 'ひ': return "hi"; case 'ふ': return "fu"; case 'へ': return "he"; case 'ほ': return "ho";
            case 'ば': return "ba"; case 'び': return "bi"; case 'ぶ': return "bu"; case 'べ': return "be"; case 'ぼ': return "bo";
            case 'ぱ': return "pa"; case 'ぴ': return "pi"; case 'ぷ': return "pu"; case 'ぺ': return "pe"; case 'ぽ': return "po";
            case 'ま': return "ma"; case 'み': return "mi"; case 'む': return "mu"; case 'め': return "me"; case 'も': return "mo";
            case 'や': return "ya"; case 'ゆ': return "yu"; case 'よ': return "yo";
            case 'ら': return "ra"; case 'り': return "ri"; case 'る': return "ru"; case 'れ': return "re"; case 'ろ': return "ro";
            case 'わ': return "wa"; case 'ゐ': return "wi"; case 'ゑ': return "we"; case 'を': return "o"; case 'ん': return "n";
            case 'ゔ': return "vu";
            case 'ぁ': return "a"; case 'ぃ': return "i"; case 'ぅ': return "u"; case 'ぇ': return "e"; case 'ぉ': return "o";
            default: return Character.isWhitespace(a) ? " " : null;
        }
    }
}
