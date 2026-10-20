"""Check PDF CJK font streams and render every sample page for visual review.

Tooling requirements: pypdf and Poppler's pdftoppm. Extraction is not a visual correctness proof.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

from pypdf import PdfReader


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdf_directory", type=Path)
    parser.add_argument("render_directory", type=Path)
    args = parser.parse_args()
    args.render_directory.mkdir(parents=True, exist_ok=True)
    results = []
    files = sorted(args.pdf_directory.glob("jar-*.pdf"))
    if not files:
        raise ValueError("No executable-JAR samples found")
    for path in files:
        reader = PdfReader(path)
        fonts = []
        text = ""
        for page in reader.pages:
            text += page.extract_text() or ""
            for reference in page["/Resources"]["/Font"].values():
                font = reference.get_object()
                if str(font["/Subtype"]) != "/Type0":
                    continue
                descriptor = font["/DescendantFonts"][0].get_object()["/FontDescriptor"].get_object()
                program = descriptor.get("/FontFile2") or descriptor.get("/FontFile3")
                assert program and program.get_object().get_data(), "Missing embedded font program"
                assert font.get("/ToUnicode") and font["/ToUnicode"].get_object().get_data()
                fonts.append(str(font["/BaseFont"]))
        assert fonts and re.search(r"[\u4e00-\u9fff]", text), "Chinese extraction failed"
        rendered = subprocess.run(["pdftoppm", "-scale-to", "1400", "-png", str(path),
                                   str(args.render_directory / path.stem)], capture_output=True, text=True)
        rendered.check_returncode()
        results.append({"file": path.name, "pages": len(reader.pages), "embeddedChineseFonts": fonts,
                        "extractedChinese": True, "rendered": True, "renderWarnings": rendered.stderr.strip()})
    (args.pdf_directory / "pdf-validation.json").write_text(json.dumps(results, ensure_ascii=False, indent=2),
                                                            encoding="utf-8")
    print(json.dumps(results, ensure_ascii=False))


if __name__ == "__main__":
    main()
