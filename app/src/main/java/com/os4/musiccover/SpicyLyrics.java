package com.os4.musiccover;

import android.media.session.MediaController;
import android.media.MediaMetadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Small adapter for Spicy Lyrics' track endpoint. */
final class SpicyLyrics {
    private SpicyLyrics() {}

    static String spotifyId(MediaController controller) {
        if (controller == null) return null;
        try {
            MediaMetadata md = controller.getMetadata();
            String id = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (id == null && md != null) {
                id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
            }
            if (id == null) {
                Xp.log("[MCLyric] Spicy Lyrics metadata has no media id or uri");
                return null;
            }
            Xp.log("[MCLyric] Spicy Lyrics media id candidate: " + id);
            int at = id.lastIndexOf(':');
            if (at >= 0) id = id.substring(at + 1);
            if (!id.matches("[A-Za-z0-9]{22}")) {
                Xp.log("[MCLyric] Spicy Lyrics media id is not a Spotify track id");
                return null;
            }
            return id;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static List<LyricLine> fetch(String trackId, String apiKey) {
        if (trackId == null || apiKey == null || apiKey.trim().isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(
                    "https://api.spicylyrics.org/v1/lyrics/" + trackId).openConnection();
            c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            c.setConnectTimeout(4000);
            c.setReadTimeout(7000);
            int status = c.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                Xp.log("[MCLyric] Spicy Lyrics HTTP " + status + " for " + trackId);
                return null;
            }
            StringBuilder body = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return parse(new JSONObject(body.toString()));
        } catch (Throwable t) {
            Xp.log("[MCLyric] Spicy Lyrics request failed: " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static List<LyricLine> parse(JSONObject response) {
        JSONObject body = response.optJSONObject("Body");
        if (body == null) return null;
        JSONArray content = body.optJSONArray("Content");
        if (content == null) return null;
        Xp.log("[MCLyric] Spicy Lyrics response type=" + body.optString("Type", "?")
                + " contentItems=" + content.length());
        if (content.length() > 0 && content.opt(0) instanceof JSONObject) {
            JSONObject first = content.optJSONObject(0);
            Xp.log("[MCLyric] Spicy Lyrics first content keys=" + first.names());
            Object lead = first.opt("Lead");
            if (lead instanceof JSONObject) {
                Xp.log("[MCLyric] Spicy Lyrics lead keys="
                        + ((JSONObject) lead).names());
                JSONArray leadContent = array((JSONObject) lead, "Content", "content",
                        "Syllables", "syllables", "Words", "words");
                if (leadContent != null && leadContent.length() > 0
                        && leadContent.opt(0) instanceof JSONObject) {
                    Xp.log("[MCLyric] Spicy Lyrics lead item keys="
                            + leadContent.optJSONObject(0).names());
                    StringBuilder wordFlags = new StringBuilder();
                    int sample = Math.min(24, leadContent.length());
                    for (int i = 0; i < sample; i++) {
                        JSONObject item = leadContent.optJSONObject(i);
                        if (item == null) continue;
                        if (wordFlags.length() > 0) wordFlags.append(',');
                        wordFlags.append(item.has("IsPartOfWord")
                                ? (item.optBoolean("IsPartOfWord") ? '1' : '0') : '-');
                    }
                    Xp.log("[MCLyric] Spicy Lyrics first word flags=" + wordFlags);
                }
            }
        }
        ArrayList<LyricLine> result = new ArrayList<>();
        for (int i = 0; i < content.length(); i++) {
            JSONObject row = content.optJSONObject(i);
            if (row == null) continue;
            LyricLine parsed = row(row);
            if (parsed != null) result.add(parsed);
        }
        return result.isEmpty() ? null : result;
    }

    private static LyricLine row(JSONObject row) {
        Object lead = row.opt("Lead");
        if (lead instanceof JSONObject) {
            JSONObject leadObject = (JSONObject) lead;
            LyricLine parsed = timedRow(leadObject, row.optBoolean("OppositeAligned", false));
            if (parsed != null) {
                LyricLine secondary = secondaryRow(leadObject, parsed.opposite, parsed.start,
                        parsed.end);
                if (secondary == null) {
                    secondary = secondaryRow(row, parsed.opposite, parsed.start, parsed.end);
                }
                if (secondary != null) parsed.bg = secondary;
                return parsed;
            }
        }
        return timedRow(row, row.optBoolean("OppositeAligned", false));
    }

    private static LyricLine secondaryRow(JSONObject row, boolean opposite, int start, int end) {
        String[] keys = {"Background", "background", "Backing", "backing", "Bg", "bg",
                "Secondary", "secondary", "Aside", "aside", "Parenthetical", "parenthetical"};
        for (String key : keys) {
            Object value = row.opt(key);
            LyricLine parsed = timedValue(value, opposite, start, end);
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static LyricLine timedValue(Object value, boolean opposite, int fallbackStart,
                                        int fallbackEnd) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            LyricLine parsed = timedRow(object, opposite);
            if (parsed != null) {
                if (parsed.start == 0 && fallbackStart != 0) {
                    return new LyricLine(parsed.text, parsed.translation, fallbackStart,
                            Math.max(fallbackEnd, parsed.end), opposite, parsed.sylStart,
                            parsed.sylEnd, parsed.charEnd);
                }
                return parsed;
            }
        } else if (value instanceof String && !((String) value).trim().isEmpty()) {
            return new LyricLine(((String) value).trim(), null, fallbackStart, fallbackEnd,
                    opposite, null, null, null);
        }
        return null;
    }

    private static LyricLine timedRow(JSONObject row, boolean opposite) {
        JSONArray parts = array(row, "Content", "content", "Syllables", "syllables",
                "Words", "words");
        int start = time(row, "StartTime", "startTime", "start");
        int end = time(row, "EndTime", "endTime", "end");
        if (parts == null) {
            String text = text(row);
            return text.isEmpty() ? null : new LyricLine(text, null, start, end, false,
                    null, null, null);
        }
        StringBuilder text = new StringBuilder();
        ArrayList<Integer> starts = new ArrayList<>();
        ArrayList<Integer> ends = new ArrayList<>();
        ArrayList<Integer> chars = new ArrayList<>();
        int previousEnd = -1;
        boolean previousExplicitWordState = false;
        boolean previousIsPartOfWord = true;
        for (int i = 0; i < parts.length(); i++) {
            Object rawPart = parts.opt(i);
            JSONObject part = rawPart instanceof JSONObject ? (JSONObject) rawPart : null;
            if (part == null) {
                if (rawPart instanceof String) {
                    String value = (String) rawPart;
                    appendFragment(text, value, previousEnd, start);
                    starts.add(start);
                    ends.add(end);
                    chars.add(text.length());
                    previousEnd = end;
                }
                continue;
            }
            String value = text(part);
            if (value.isEmpty()) continue;
            int partStart = time(part, "StartTime", "startTime", "start");
            int partEnd = time(part, "EndTime", "endTime", "end");
            boolean explicitWordState = part.has("IsPartOfWord");
            if (explicitWordState) {
                if (previousExplicitWordState && !previousIsPartOfWord && text.length() > 0
                        && !Character.isWhitespace(text.charAt(text.length() - 1))
                        && !Character.isWhitespace(value.charAt(0))) {
                    text.append(' ');
                }
                text.append(value);
            } else {
                appendFragment(text, value, previousEnd, partStart, false, false);
            }
            starts.add(partStart);
            ends.add(partEnd);
            chars.add(text.length());
            previousEnd = partEnd;
            previousExplicitWordState = explicitWordState;
            previousIsPartOfWord = part.optBoolean("IsPartOfWord", true);
        }
        if (text.length() == 0) return null;
        int[] ss = new int[starts.size()];
        int[] ee = new int[ends.size()];
        int[] cc = new int[chars.size()];
        for (int i = 0; i < ss.length; i++) {
            ss[i] = starts.get(i);
            ee[i] = ends.get(i);
            cc[i] = chars.get(i);
        }
        if (!starts.isEmpty()) {
            if (start <= 0) start = ss[0];
            if (end <= start) end = ee[ee.length - 1];
        }
        normalizeTiming(ss, ee, cc, text.length());
        LyricLine result = new LyricLine(text.toString(), null, start, end, opposite, ss, ee, cc);
        return splitInlineBacking(result);
    }

    private static void normalizeTiming(int[] starts, int[] ends, int[] chars, int textLength) {
        int previousStart = 0;
        int previousChar = 0;
        int corrected = 0;
        for (int i = 0; i < starts.length; i++) {
            int start = Math.max(previousStart, starts[i]);
            int end = Math.max(start, ends[i]);
            int character = Math.max(previousChar, Math.min(textLength, chars[i]));
            if (start != starts[i] || end != ends[i] || character != chars[i]) corrected++;
            starts[i] = start;
            ends[i] = end;
            chars[i] = character;
            previousStart = start;
            previousChar = character;
        }
        if (corrected > 0) {
            Xp.log("[MCLyric] Spicy normalized " + corrected
                    + " malformed syllable intervals");
        }
    }

    private static String text(JSONObject o) {
        String[] keys = {"Text", "text", "Word", "word", "Lyric", "lyric", "Content",
                "content"};
        for (String key : keys) {
            Object raw = o.opt(key);
            String value = raw instanceof String ? (String) raw : "";
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static void appendFragment(StringBuilder out, String fragment, int previousEnd,
                                       int nextStart) {
        appendFragment(out, fragment, previousEnd, nextStart, false, false);
    }

    private static void appendFragment(StringBuilder out, String fragment, int previousEnd,
                                       int nextStart, boolean startsWord,
                                       boolean explicitWordState) {
        boolean timedWordGap = !explicitWordState && previousEnd >= 0
                && nextStart - previousEnd >= 110;
        char previous = out.length() == 0 ? 0 : out.charAt(out.length() - 1);
        boolean punctuationGap = previous == ',' || previous == ';' || previous == ':'
                || previous == '!' || previous == '?' || previous == ')';
        if (out.length() > 0 && fragment.length() > 0
                && (Character.isWhitespace(fragment.charAt(0)) || startsWord || timedWordGap
                || punctuationGap)
                && (Character.isLetterOrDigit(out.charAt(out.length() - 1)) || punctuationGap)
                && Character.isLetterOrDigit(fragment.charAt(0))) {
            out.append(' ');
        }
        out.append(fragment);
    }

    private static LyricLine splitInlineBacking(LyricLine line) {
        int open = line.text.indexOf('(');
        int close = open < 0 ? -1 : line.text.indexOf(')', open + 1);
        if (open <= 0 || close <= open + 1) return line;
        String backing = line.text.substring(open + 1, close).trim();
        if (backing.isEmpty()) return line;

        String mainText = (line.text.substring(0, open) + line.text.substring(close + 1)).trim();
        if (mainText.isEmpty()) return line;
        ArrayList<Integer> mainStarts = new ArrayList<>();
        ArrayList<Integer> mainEnds = new ArrayList<>();
        ArrayList<Integer> mainChars = new ArrayList<>();
        ArrayList<Integer> bgStarts = new ArrayList<>();
        ArrayList<Integer> bgEnds = new ArrayList<>();
        ArrayList<Integer> bgChars = new ArrayList<>();
        int mainLength = 0;
        int bgLength = 0;
        for (int i = 0; i < line.charEnd.length; i++) {
            int from = i == 0 ? 0 : line.charEnd[i - 1];
            int to = line.charEnd[i];
            int bgFrom = Math.max(from, open + 1);
            int bgTo = Math.min(to, close);
            if (bgTo > bgFrom) {
                bgStarts.add(line.sylStart[i]);
                bgEnds.add(line.sylEnd[i]);
                bgLength += bgTo - bgFrom;
                bgChars.add(bgLength);
            }
            int visibleChars = visibleMainChars(from, to, open, close);
            if (visibleChars > 0) {
                mainLength += visibleChars;
                mainStarts.add(line.sylStart[i]);
                mainEnds.add(line.sylEnd[i]);
                mainChars.add(mainLength);
            }
        }
        LyricLine bg = new LyricLine(backing, null, line.start, line.end, line.opposite,
                toArray(bgStarts), toArray(bgEnds), toArray(bgChars));
        LyricLine main = new LyricLine(mainText, null, line.start, line.end, line.opposite,
                toArray(mainStarts), toArray(mainEnds), toArray(mainChars));
        main.bg = bg;
        return main;
    }

    private static int visibleMainChars(int from, int to, int open, int close) {
        int before = Math.max(0, Math.min(to, open) - from);
        int after = Math.max(0, to - Math.max(from, close + 1));
        return before + after;
    }

    private static int[] toArray(ArrayList<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    private static JSONArray array(JSONObject o, String... keys) {
        for (String key : keys) {
            JSONArray value = o.optJSONArray(key);
            if (value != null) return value;
            Object nested = o.opt(key);
            if (nested instanceof JSONObject) {
                JSONObject object = (JSONObject) nested;
                JSONArray children = array(object, "Content", "content", "Syllables",
                        "syllables", "Words", "words");
                if (children != null) return children;
            }
        }
        return null;
    }

    private static int time(JSONObject o, String... keys) {
        for (String key : keys) {
            Object value = o.opt(key);
            if (value instanceof Number) return (int) Math.round(((Number) value).doubleValue() * 1000d);
            if (value instanceof String) {
                try { return (int) Math.round(Double.parseDouble((String) value) * 1000d); }
                catch (NumberFormatException ignored) { }
            }
        }
        return 0;
    }
}
