"""Run real parser/JUnit tests without Gradle, lint, Android SDK, or parser stubs.

Downloads the catalog's Kotlin compiler and lyrics-core JVM artifact, plus their
runtime dependencies and JUnit, from Maven Central into a temporary directory.
Requires Python 3.11+ (tomllib), a local JDK, and Java 21+ to run lyrics-core.
A separate portable JRE can be supplied for tests; XML APIs come from Java.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, help="JDK directory (otherwise uses PATH)")
    parser.add_argument("--runtime-java-home", type=Path,
                        help="Optional Java 21+ JRE/JDK for tests when the compilation JDK is older")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    java = str(args.java_home / "bin" / ("java" + suffix)) if args.java_home else shutil.which("java")
    javac = str(args.java_home / "bin" / ("javac" + suffix)) if args.java_home else shutil.which("javac")
    if not java or not javac or not Path(java).is_file() or not Path(javac).is_file():
        parser.error("A local JDK is required; supply --java-home")
    runtime_java = (str(args.runtime_java_home / "bin" / ("java" + suffix))
                    if args.runtime_java_home else java)
    if not Path(runtime_java).is_file():
        parser.error("Test runtime java executable does not exist")
    root = Path(__file__).resolve().parent.parent
    with (root / "gradle/libs.versions.toml").open("rb") as catalog:
        versions = tomllib.load(catalog)["versions"]
    kotlin = versions["kotlin"]
    lyrics = versions["lyricsCore"]
    # Transitive versions from the published 2.4.10 compiler / 0.4.7 library POMs.
    # Recheck these when changing either catalog version.
    if (kotlin, lyrics) != ("2.4.10", "0.4.7"):
        parser.error("Catalog versions changed; review transitive dependencies in this harness")
    print("JDK:", Path(java).parent.parent, flush=True)
    subprocess.run([java, "-version"], check=True, timeout=30)
    if runtime_java != java:
        print("Test runtime:", Path(runtime_java).parent.parent, flush=True)
        subprocess.run([runtime_java, "-version"], check=True, timeout=30)
    with tempfile.TemporaryDirectory(prefix="musiccover-lyric-parser-") as directory:
        work = Path(directory)

        def download(group, name, version):
            jar = work / f"{name}-{version}.jar"
            url = ("https://repo.maven.apache.org/maven2/" + group.replace(".", "/")
                   + f"/{name}/{version}/{jar.name}")
            print("Download:", url, flush=True)
            with urllib.request.urlopen(url, timeout=60) as response, jar.open("wb") as output:
                shutil.copyfileobj(response, output)
            return str(jar)

        stdlib = download("org.jetbrains.kotlin", "kotlin-stdlib", kotlin)
        annotations = download("org.jetbrains", "annotations", "13.0")
        compiler = [stdlib, annotations]
        for name in ("kotlin-compiler-embeddable", "kotlin-build-tools-api",
                     "kotlin-script-runtime", "kotlin-daemon-embeddable"):
            compiler.append(download("org.jetbrains.kotlin", name, kotlin))
        compiler.append(download("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"))
        compiler.append(download("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"))
        runtime = [stdlib, annotations,
                   download("com.mocharealm.accompanist", "lyrics-core-jvm", lyrics),
                   download("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.10.0"),
                   download("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.10.0"),
                   download("junit", "junit", "4.13.2"),
                   download("org.hamcrest", "hamcrest-core", "1.3")]
        classes = work / "classes"
        classes.mkdir()
        source = root / "app/src/main/java/com/os4/musiccover"
        tests = root / "app/src/test/java/com/os4/musiccover"
        subprocess.run([javac, "-encoding", "UTF-8", "--release", "11", "-d", str(classes),
                        str(source / "LyricLine.java")], check=True, timeout=60)
        classpath = os.pathsep.join([str(classes), *runtime])
        print("Compile LyricParse.kt and LyricParseTest.kt against real lyrics-core", flush=True)
        subprocess.run([java, "-cp", os.pathsep.join(compiler),
                        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                        "-jvm-target", "11", "-classpath", classpath, "-d", str(classes),
                        str(source / "LyricParse.kt"), str(tests / "LyricParseTest.kt")],
                       check=True, timeout=120)
        subprocess.run([runtime_java, "-cp", classpath, "org.junit.runner.JUnitCore",
                        "com.os4.musiccover.LyricParseTest"], check=True, timeout=60)


if __name__ == "__main__":
    main()
