"""Compile actual lyric query/matcher sources and run JVM regressions without Gradle/lint/downloads.

Fixtures cover metadata, JSON candidate containers and an identity ICU transform, not Android
runtime behavior or provider networking. HTTP is fail-closed. No fixture replaces query/match code.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


STUBS = {
    "android/media/MediaMetadata.java": """package android.media;
public class MediaMetadata {
 public static final String METADATA_KEY_TITLE="title", METADATA_KEY_ARTIST="artist",
 METADATA_KEY_ALBUM="album", METADATA_KEY_ALBUM_ARTIST="albumArtist", METADATA_KEY_DURATION="duration";
 public String getString(String key){return null;} public long getLong(String key){return 0;}
}
""",
    "android/media/session/MediaController.java": """package android.media.session;
public class MediaController {public android.media.MediaMetadata getMetadata(){return null;}}
""",
    "android/os/SystemClock.java": """package android.os;
public class SystemClock {public static long uptimeMillis(){return System.nanoTime()/1000000;}}
""",
    "android/icu/text/Transliterator.java": """package android.icu.text;
public class Transliterator {public static Transliterator getInstance(String id){return new Transliterator();}
 public String transliterate(String s){return s;}}
""",
    "com/os4/musiccover/Xp.java": """package com.os4.musiccover;
final class Xp {static void log(String s){}}
""",
    "com/os4/musiccover/Http.java": """package com.os4.musiccover;
final class Http {
 static Reply get(String url,String tag){throw new AssertionError("HTTP forbidden in matcher tests: "+url);}
 static final class Reply {String body; boolean ok(){return false;}}
}
""",
    "org/json/JSONObject.java": """package org.json;
import java.util.*;
public class JSONObject {
 private final Map<String,Object> values=new LinkedHashMap<>();
 public JSONObject(){}
 public JSONObject(String json){throw new AssertionError("JSON parsing outside fixture scope");}
 public JSONObject put(String key,Object value){values.put(key,value);return this;}
 public Object opt(String key){return values.get(key);}
 public JSONObject optJSONObject(String key){Object v=opt(key);return v instanceof JSONObject?(JSONObject)v:null;}
 public JSONArray optJSONArray(String key){Object v=opt(key);return v instanceof JSONArray?(JSONArray)v:null;}
 public JSONObject getJSONObject(String key){JSONObject v=optJSONObject(key);if(v==null)throw new IllegalArgumentException(key);return v;}
 public JSONArray getJSONArray(String key){JSONArray v=optJSONArray(key);if(v==null)throw new IllegalArgumentException(key);return v;}
 public long optLong(String key){return optLong(key,0);}
 public long optLong(String key,long fallback){Object v=opt(key);return v instanceof Number?((Number)v).longValue():fallback;}
 public String optString(String key,String fallback){Object v=opt(key);return v==null?fallback:String.valueOf(v);}
}
""",
    "org/json/JSONArray.java": """package org.json;
import java.util.*;
public class JSONArray {
 private final List<Object> values=new ArrayList<>();
 public JSONArray put(Object value){values.add(value);return this;}
 public int length(){return values.size();}
 public JSONObject optJSONObject(int i){Object v=i>=0&&i<values.size()?values.get(i):null;return v instanceof JSONObject?(JSONObject)v:null;}
 public JSONObject getJSONObject(int i){JSONObject v=optJSONObject(i);if(v==null)throw new IllegalArgumentException();return v;}
}
""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, help="Local JDK directory (otherwise uses PATH)")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    java = str(args.java_home / "bin" / ("java" + suffix)) if args.java_home else shutil.which("java")
    javac = str(args.java_home / "bin" / ("javac" + suffix)) if args.java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("A local JDK is required; supply --java-home")
    root = Path(__file__).resolve().parent.parent
    source = root / "app/src/main/java/com/os4/musiccover"
    test = root / "app/src/test/java/com/os4/musiccover/LyricSearchAliasesTest.java"
    with tempfile.TemporaryDirectory(prefix="musiccover-lyric-match-") as directory:
        work = Path(directory)
        sources = [source / (name + ".java") for name in ("NcmLyrics", "LyricMatch", "LyricSearchAliases")]
        sources.append(test)
        for relative, content in STUBS.items():
            stub = work / relative
            stub.parent.mkdir(parents=True, exist_ok=True)
            stub.write_text(content, encoding="utf-8")
            sources.append(stub)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([javac, "--release", "8", "-encoding", "UTF-8", "-d", str(classes),
                        *map(str, sources)], check=True, timeout=60)
        subprocess.run([java, "-cp", str(classes), "com.os4.musiccover.LyricSearchAliasesTest"],
                       check=True, timeout=30)


if __name__ == "__main__":
    main()
