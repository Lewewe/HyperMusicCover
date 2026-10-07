"""Compile real lyric model/helper and run pure JVM tests; no Gradle, lint or downloads."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, help="Local JDK directory (otherwise uses JAVA_HOME/PATH)")
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
    sources = [source / (name + ".java") for name in ("LyricLine", "LyricKaraokeUpgrade")]
    sources.append(root / "app/src/test/java/com/os4/musiccover/LyricKaraokeUpgradeTest.java")
    with tempfile.TemporaryDirectory(prefix="musiccover-karaoke-upgrade-") as directory:
        subprocess.run([javac, "--release", "8", "-encoding", "UTF-8", "-d", directory,
                        *map(str, sources)], check=True, timeout=60)
        subprocess.run([java, "-cp", directory, "com.os4.musiccover.LyricKaraokeUpgradeTest"],
                       check=True, timeout=30)


if __name__ == "__main__":
    main()
