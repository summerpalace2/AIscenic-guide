"""Exercise all PDF renderers using font bytes inside the real Spring Boot JAR.

Requires a JDK on PATH or JAVA_HOME. No application env, database or suppliers are loaded.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("output_directory", type=Path)
    args = parser.parse_args()
    jar = args.jar.resolve()
    output = args.output_directory.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar) as archive:
        font = archive.read("BOOT-INF/classes/fonts/NotoSansSC-Regular.ttf")
        manifest = json.loads(archive.read("BOOT-INF/classes/fonts/font-manifest.json"))
        assert hashlib.sha256(font).hexdigest() == manifest["sha256"]
        assert b"SIL OPEN FONT LICENSE" in archive.read("BOOT-INF/classes/fonts/OFL.txt")
    java_home = os.environ.get("JAVA_HOME")
    extension = ".exe" if os.name == "nt" else ""
    java = str(Path(java_home) / "bin" / ("java" + extension)) if java_home else "java"
    javac = str(Path(java_home) / "bin" / ("javac" + extension)) if java_home else "javac"
    source = Path(__file__).with_name("PdfJarProbe.java")
    with tempfile.TemporaryDirectory(prefix="cp6-pdf-jar-") as directory:
        subprocess.run([javac, "-encoding", "UTF-8", "-d", directory, str(source)], check=True, timeout=30)
        result = subprocess.run([java, "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
                                 "-Dloader.path=" + directory, "-Dloader.main=PdfJarProbe", "-cp", str(jar),
                                 "org.springframework.boot.loader.launch.PropertiesLauncher", str(output)],
                                cwd=directory, capture_output=True, text=True, encoding="utf-8", timeout=60)
        (output / "jar-probe.log").write_text(result.stdout + result.stderr, encoding="utf-8")
        result.check_returncode()
    summary = {"jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
               "fontSha256": manifest["sha256"], "fontLoadedFromJar": "FONT_RESOURCE=jar:" in result.stdout,
               "pdfBytes": {path.name: path.stat().st_size for path in output.glob("jar-*.pdf")}}
    assert summary["fontLoadedFromJar"]
    (output / "jar-probe.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
