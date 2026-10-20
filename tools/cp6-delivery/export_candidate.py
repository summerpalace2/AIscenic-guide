"""Export dirty source for local review without staging, committing or reading .env files."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from datetime import datetime, timezone


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def excluded(name):
    path = Path(name)
    if any(part in {".git", "__pycache__", "node_modules", "target", "dist", "coverage", "test-results", "playwright-report"} for part in path.parts):
        return "generated/runtime directory"
    if path.name.lower().startswith(".env") and not path.name.lower().endswith((".example", ".sample", ".template")):
        return "environment credentials"
    if path.suffix.lower() in {".db", ".sqlite", ".sqlite3", ".log", ".pem", ".key", ".p12", ".pfx"} or path.name.endswith(("-wal", "-shm")):
        return "runtime data or credentials"
    if name.startswith("server/data/") and not name.startswith("server/data/cities/"):
        return "BFF runtime data"
    if path.name == "cp6-runtime.json":
        return "temporary process metadata"
    return None


def explicit_files(root, names):
    result = set()
    for name in names:
        source = (root / name).resolve()
        if not source.is_relative_to(root.resolve()):
            raise ValueError(f"Explicit review file must remain inside its repository: {name}")
        relative = source.relative_to(root.resolve()).as_posix()
        reason = excluded(relative)
        if reason or not source.is_file() or (root / name).is_symlink():
            raise ValueError(f"Explicit review file is unavailable or excluded: {name}")
        result.add(relative)
    return result


def export_repo(label, root, output, includes=()):
    repository_root = Path(git(root, "rev-parse", "--show-toplevel").decode().strip()).resolve()
    if root.resolve() != repository_root:
        raise ValueError(f"{label}: use the Git repository root: {repository_root}")
    head = git(root, "rev-parse", "HEAD").decode().strip()
    branch = git(root, "branch", "--show-current").decode().strip()
    changed = set(git(root, "diff", "--no-renames", "HEAD", "--name-only", "-z").decode().split("\0"))
    untracked = set(git(root, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"))
    explicit = explicit_files(root, includes)
    tracked = set(git(root, "ls-files", "--cached", "-z").decode().split("\0"))
    untracked |= explicit - tracked
    names = sorted((changed | untracked | explicit) - {""})
    records, tracked_paths = [], []
    for name in names:
        source = root / name
        reason = excluded(name)
        if source.is_symlink():
            reason = "symlink requires separate review"
        if source.is_file() and source.stat().st_size > 5_000_000:
            reason = "file exceeds review export size limit"
        if reason:
            records.append({"path": name, "excluded": reason})
            continue
        record = {"path": name, "untracked": name in untracked, "deleted": not source.exists()}
        if source.is_file():
            content = source.read_bytes()
            destination = output / label / "files" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
            record.update(sha256=hashlib.sha256(content).hexdigest(), bytes=len(content))
        elif source.exists():
            records.append({"path": name, "excluded": "nested repository or non-file"})
            continue
        records.append(record)
        if name in tracked:
            tracked_paths.append(name)
    patch = output / label / "tracked-changes.patch"
    patch.parent.mkdir(parents=True, exist_ok=True)
    with patch.open("wb") as stream:
        for offset in range(0, len(tracked_paths), 20):
            stream.write(git(root, "diff", "--no-renames", "--binary", "HEAD", "--", *tracked_paths[offset:offset + 20]))
    return {"repository": str(root), "head": head, "branch": branch, "files": records,
            "explicitFiles": sorted(explicit),
            "patchSha256": hashlib.sha256(patch.read_bytes()).hexdigest()}


def verify_repo(label, snapshot, output):
    root = Path(snapshot["repository"])
    if git(root, "rev-parse", "HEAD").decode().strip() != snapshot["head"]:
        raise RuntimeError(f"{label}: HEAD changed during export; candidate is incomplete")
    current = set(git(root, "diff", "--no-renames", "HEAD", "--name-only", "-z").decode().split("\0"))
    current |= set(git(root, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"))
    current |= explicit_files(root, snapshot.get("explicitFiles", []))
    if current - {""} != {record["path"] for record in snapshot["files"]}:
        raise RuntimeError(f"{label}: changed file inventory changed during export")
    tracked_paths = []
    for record in snapshot["files"]:
        if "excluded" in record:
            continue
        source = root / record["path"]
        if record["deleted"]:
            valid = not source.exists()
        else:
            valid = source.is_file() and not source.is_symlink() and hashlib.sha256(source.read_bytes()).hexdigest() == record["sha256"]
        if not valid:
            raise RuntimeError(f"{label}: source changed during export: {record['path']}")
        if not record["untracked"]:
            tracked_paths.append(record["path"])
    current_patch = b"".join(git(root, "diff", "--no-renames", "--binary", "HEAD", "--", *tracked_paths[offset:offset + 20])
                             for offset in range(0, len(tracked_paths), 20))
    if hashlib.sha256(current_patch).hexdigest() != snapshot["patchSha256"]:
        raise RuntimeError(f"{label}: patch changed during export")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, type=Path)
    parser.add_argument("--bff", required=True, type=Path)
    parser.add_argument("--frontend", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--include", action="append", default=[], metavar="REPOSITORY:FILE",
                        help="Explicit source file to review even when Git ignores it (java, bff or frontend)")
    args = parser.parse_args()
    output = args.output.resolve()
    roots = {label: getattr(args, label).resolve() for label in ("java", "bff", "frontend")}
    includes = {label: [] for label in roots}
    for item in args.include:
        label, separator, name = item.partition(":")
        if not separator or label not in roots or not name:
            parser.error("--include must be java:FILE, bff:FILE or frontend:FILE")
        includes[label].extend(explicit_files(roots[label], [name]))
    if any(output == root or root in output.parents for root in roots.values()):
        parser.error("Output must be outside all source repositories")
    output.mkdir(parents=True, exist_ok=False)
    manifest = {"createdAt": datetime.now(timezone.utc).isoformat(),
                "scope": "All current dirty source, including pre-existing changes; not an attribution of this task's changes.",
                "delivery": "Local review only. No commit, push or deployment.", "repositories": {}}
    for label, root in roots.items():
        manifest["repositories"][label] = export_repo(label, root, output, includes[label])
    for label, snapshot in manifest["repositories"].items():
        verify_repo(label, snapshot, output)
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    (output / "README.md").write_text(
        "# Local review candidate\n\nThe manifest records each repository HEAD, changed source hash and explicit exclusions. "
        "This includes pre-existing dirty edits; explicitFiles records individually selected source files otherwise ignored by Git. Environment files, private-key extensions, runtime databases, logs and generated output are excluded by path rules; this is not a full secret scan. "
        "review exclusions before treating this as a complete rebuild bundle.\n\n"
        "Review each tracked-changes.patch against its recorded HEAD. The files directory contains current changed "
        "and untracked files; deleted paths are recorded in the manifest. Restore excluded environment settings separately. "
        "No remote or Git history operation was performed. Test results must be associated with the matching source hashes.\n",
        encoding="utf-8")
    print(json.dumps({"output": str(output), "repositories": len(roots)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
