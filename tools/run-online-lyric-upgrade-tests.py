"""Run actual OnlineLyrics.upgrade on a local JVM; no Gradle, lint or downloads.

Compiles unmodified OnlineLyrics, LyricKaraokeUpgrade, LyricLine and LyricSearchAliases.
The test supplies fake catalogue transports, a Query shape delegating to real aliases,
and a token-to-lines parser fixture: no HTTP, provider matching, Kotlin parsing or
Android runtime is exercised. Use run-lyric-match-tests.py separately for real Query
and metadata/matcher regressions. Request counts mean catalogue load calls, not HTTP
requests inside a real provider. The clock shim uses real monotonic elapsed time.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, help="Local JDK directory (otherwise JAVA_HOME/PATH)")
    args = parser.parse_args()
    home = args.java_home
    if home is None and os.environ.get("JAVA_HOME"):
        home = Path(os.environ["JAVA_HOME"])
    suffix = ".exe" if os.name == "nt" else ""
    java = str(home / "bin" / ("java" + suffix)) if home else shutil.which("java")
    javac = str(home / "bin" / ("javac" + suffix)) if home else shutil.which("javac")
    if not java or not javac or not Path(java).is_file() or not Path(javac).is_file():
        parser.error("A local JDK is required; supply --java-home")
    root = Path(__file__).resolve().parent.parent
    source = root / "app/src/main/java/com/os4/musiccover"
    sources = [source / (name + ".java") for name in
               ("OnlineLyrics", "LyricKaraokeUpgrade", "LyricLine", "LyricSearchAliases")]
    sources.append(root / "app/src/test/java/com/os4/musiccover/OnlineLyricsUpgradeTest.java")
    with tempfile.TemporaryDirectory(prefix="musiccover-online-upgrade-") as directory:
        work = Path(directory)
        clock = work / "android/os/SystemClock.java"
        clock.parent.mkdir(parents=True)
        clock.write_text("""package android.os;
public final class SystemClock {
    public static long uptimeMillis() { return System.nanoTime() / 1000000L; }
}
""", encoding="utf-8")
        sources.append(clock)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([javac, "--release", "8", "-encoding", "UTF-8", "-d", str(classes),
                        *map(str, sources)], check=True, timeout=60)
        subprocess.run([java, "-cp", str(classes), "com.os4.musiccover.OnlineLyricsUpgradeTest"],
                       check=True, timeout=45)


if __name__ == "__main__":
    main()
