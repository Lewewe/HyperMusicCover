"""Run translation JVM tests without Gradle/Android SDK. Downloads test jars to a temp directory."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, help="JDK directory (otherwise uses PATH)")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    java = str(args.java_home / "bin" / ("java" + suffix)) if args.java_home else shutil.which("java")
    javac = str(args.java_home / "bin" / ("javac" + suffix)) if args.java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("A JDK is required; supply --java-home")
    root = Path(__file__).resolve().parent.parent
    source = root / "app/src/main/java/com/os4/musiccover"
    tests = root / "app/src/test/java/com/os4/musiccover"
    with tempfile.TemporaryDirectory(prefix="musiccover-translation-") as directory:
        work = Path(directory)
        jars = []
        for artifact in ("junit/junit/4.13.2/junit-4.13.2.jar",
                         "org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
                         "org/json/json/20231013/json-20231013.jar"):
            jar = work / artifact.rsplit("/", 1)[1]
            urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/" + artifact, jar)
            jars.append(str(jar))
        # Fail closed: test payloads/logic only. Any accidental network request fails the test.
        http = work / "Http.java"
        http.write_text('''package com.os4.musiccover;
final class Http {
    static Raw request(String url, String tag, String body, String... headers) {
        throw new AssertionError("Network calls are forbidden in pure translation tests");
    }
    static final class Raw {
        boolean ok() { return false; }
        String text() { return null; }
    }
}
''', encoding="utf-8")
        classpath = os.pathsep.join(jars)
        sources = [source / (name + ".java") for name in (
            "LyricLine", "LyricTranslator", "LyricTranslationLogic", "TranslationProvider",
            "LibreTranslateLyricTranslator", "CloudApiLyricTranslator")]
        sources.extend([http, tests / "LyricTranslationLogicTest.java", tests / "TranslationProviderTest.java",
                        tests / "LyricTranslatorEngineTest.java"])
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes),
                        *map(str, sources)], check=True)
        subprocess.run([java, "-cp", str(classes) + os.pathsep + classpath,
                        "org.junit.runner.JUnitCore", "com.os4.musiccover.LyricTranslationLogicTest",
                        "com.os4.musiccover.TranslationProviderTest",
                        "com.os4.musiccover.LyricTranslatorEngineTest"], check=True)


if __name__ == "__main__":
    main()
