# PDF Chinese font

Both PDF adapters embed a per-document subset of `NotoSansSC-Regular.ttf`. The full font asset is
shipped with the application; no system font directory, network download, or viewer CJK font pack
is needed. The font is a static weight-400 instance of the official Google Fonts Noto Sans SC source.
No glyphs were removed from the application asset. The subset embedded into each PDF only includes
the glyphs used in that document.

`OFL.txt` is the upstream SIL Open Font License, including copyright and the reserved name `Source`.
`font-manifest.json` records the pinned upstream commit, source URL, source/output SHA256 and build
procedure. FontTools is build tooling only, not an application runtime dependency.

To reproduce, download the source URL in the manifest, install `fonttools==4.62.1` in a tooling
environment, then run `tools/cp6-pdf/build_font.py <source-variable.ttf> ai/src/main/resources/fonts`.
The tool rejects a source hash or rebuilt output hash different from the manifest.

`PDF_FONT_FILE` is optional and empty by default. To override, provide an absolute readable Chinese
TTF path, or a TTC path including its face index (`...,0`), and restart the application. The configured
file is authoritative: missing, corrupt or non-Chinese fonts fail export instead of silently falling
back to Helvetica or non-embedded STSong. Custom font licenses and glyph coverage are the operator's
responsibility. The default font covers the tested Chinese text, punctuation and temperature symbol;
this is not a guarantee for every rare character, emoji, script or color glyph.
