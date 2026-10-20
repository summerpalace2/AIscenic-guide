"""Rebuild the bundled static font from the pinned, unmodified Google Fonts variable TTF.

Tooling only: pip install fonttools==4.62.1. Application deployments use the checked-in TTF.
"""
import argparse
import hashlib
import json
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output_directory", type=Path)
    args = parser.parse_args()
    manifest_file = args.output_directory / "font-manifest.json"
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    if hashlib.sha256(args.source.read_bytes()).hexdigest() != manifest["sourceSha256"]:
        raise ValueError("Source font does not match the pinned upstream SHA256")
    font = instantiateVariableFont(TTFont(args.source, recalcTimestamp=False), {"wght": 400},
                                   inplace=True, updateFontNames=True)
    output = args.output_directory / manifest["file"]
    font.save(output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    if digest != manifest["sha256"]:
        raise ValueError("Rebuilt font differs from the manifest; review tool version before publishing")
    print(digest)


if __name__ == "__main__":
    main()
