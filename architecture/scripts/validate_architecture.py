#!/usr/bin/env python3
import argparse
import html
import re
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Callable, Iterator, Sequence
from urllib.parse import unquote, urlsplit

CHECKS = frozenset({"links", "metadata", "structure"})

ARC42_FILENAMES = frozenset({
    "01-introduction-and-goals.md",
    "02-constraints.md",
    "03-context-and-scope.md",
    "04-solution-strategy.md",
    "05-building-block-view.md",
    "06-runtime-view.md",
    "07-deployment-view.md",
    "08-crosscutting-concepts.md",
    "09-decisions.md",
    "10-quality-requirements.md",
    "11-risks-and-technical-debt.md",
    "12-glossary.md",
})
ARC42_REQUIRED_FIELDS = ("title", "status", "owners", "last_verified", "related_adrs", "code_refs")
ARC42_STATUSES = frozenset({"current", "deprecated"})
TERMINAL_PROPOSAL_STATUSES = frozenset({"implemented", "rejected", "superseded"})

REQUIRED_GOVERNANCE_FILES = (
    "ARCHITECTURE.md",
    "architecture/README.md",
    "architecture/adr/README.md",
    "architecture/adr/template.md",
    "architecture/archive/proposals/README.md",
    "architecture/diagrams/README.md",
    "architecture/proposals/README.md",
)

@dataclass(frozen=True)
class MarkdownLink:
    destination: str
    line: int

def _mask(text: str) -> str:
    chars = list(text)
    i = 0
    fence = None
    inline = None
    comment = False
    while i < len(text):
        if comment:
            if text.startswith("-->", i):
                chars[i:i+3] = "   "; comment = False; i += 3
            elif text[i] != "\n": chars[i] = " "
            i += 1; continue
        if fence:
            if text.startswith(fence, i) and (i == 0 or text[i-1] == "\n"):
                n = len(fence); chars[i:i+n] = " " * n; fence = None; i += n
            elif text[i] != "\n": chars[i] = " "
            i += 1; continue
        if inline:
            if text.startswith(inline, i):
                n = len(inline); chars[i:i+n] = " " * n; inline = None; i += n
            elif text[i] != "\n": chars[i] = " "
            i += 1; continue
        if text.startswith("<!--", i):
            chars[i:i+4] = "    "; comment = True; i += 4; continue
        if text[i] in "`~" and (i == 0 or text[i-1] == "\n"):
            ch = text[i]; n = 0
            while i+n < len(text) and text[i+n] == ch: n += 1
            if n >= 3:
                fence = ch*n; chars[i:i+n] = " "*n; i += n; continue
        if text[i] == "`":
            n = 1
            while i+n < len(text) and text[i+n] == "`": n += 1
            inline = "`"*n; chars[i:i+n] = " "*n; i += n; continue
        i += 1
    return "".join(chars)

def parse_front_matter(path: Path) -> dict[str, str | list[str]]:
    lines = path.read_text().splitlines()
    if not lines or lines[0].strip() != "---": return {}
    try: end = lines.index("---", 1)
    except ValueError: return {}
    result = {}; current = None
    for line in lines[1:end]:
        m = re.match(r"^([A-Za-z0-9_-]+):\s*(.*)$", line)
        if m:
            key, value = m.groups()
            if value == "[]": result[key] = []
            else: result[key] = value.strip('"\'')
            current = key if value == "" else None
        elif current and re.match(r"^\s+-\s+", line):
            if not isinstance(result.get(current), list): result[current] = []
            result[current].append(re.sub(r"^\s+-\s+", "", line).strip())
    return result

def _definitions(masked: str):
    defs = {}; dupes = []
    for n, line in enumerate(masked.splitlines(), 1):
        m = re.match(r"^\s{0,3}\[([^]]+)\]:\s*(?:<([^>]+)>|(\S+))", line)
        if not m: continue
        label = " ".join(m.group(1).casefold().split()); dest = m.group(2) or m.group(3)
        if label in defs: dupes.append((n, label, defs[label][1]))
        else: defs[label] = (dest, n)
    return defs, dupes

def extract_markdown_links(text: str) -> list[MarkdownLink]:
    masked = _mask(text); defs, _ = _definitions(masked); links = []
    definition_lines = {n for n, line in enumerate(masked.splitlines(), 1) if re.match(r"^\s{0,3}\[[^]]+\]:\s*(?:<[^>]+>|\S+)", line)}
    for n, line in enumerate(masked.splitlines(), 1):
        if n in definition_lines: continue
        for m in re.finditer(r"\[([^\]]+)\]\((?:<([^>]+)>|([^\s)]+))\)", line):
            links.append(MarkdownLink(m.group(2) or m.group(3), n))
        for m in re.finditer(r"\[([^\]]+)\]\[([^\]]*)\]", line):
            label = " ".join((m.group(2) or m.group(1)).casefold().split())
            if label in defs: links.append(MarkdownLink(defs[label][0], n))
            else: links.append(MarkdownLink(f"__undefined_reference__:{label}", n))
        for m in re.finditer(r"(?<![-\w])\[([^\]\n]+)\]", line):
            label = " ".join(m.group(1).casefold().split())
            if label in defs and not (m.start() and line[m.start()-1] == "]"): links.append(MarkdownLink(defs[label][0], n))
    return links

def extract_markdown_destinations(text: str) -> list[str]:
    return [x.destination for x in extract_markdown_links(text) if not x.destination.startswith("__undefined_reference__:")]

def _slug(value: str) -> str:
    value = re.sub(r"[*_`~]", "", html.unescape(value)).casefold()
    value = re.sub(r"[^\w -]", "", value, flags=re.UNICODE)
    return re.sub(r"\s+", "-", value.strip())

def extract_anchors(text: str) -> set[str]:
    anchors = set(re.findall(r"\bid\s*=\s*[\"']([^\"']+)[\"']", text, re.I)); counts = {}
    for heading in re.findall(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$", _mask(text), re.M):
        base = _slug(heading); idx = counts.get(base, 0); anchors.add(base if idx == 0 else f"{base}-{idx}"); counts[base] = idx + 1
    return anchors

def iter_governed_markdown(root: Path) -> Iterator[Path]:
    patterns = ["ARCHITECTURE.md", "architecture/**/*.md", "docs/superpowers/plans/*.md", "docs/superpowers/specs/*.md", "services/*/README.md", "services/*/docs/**/*.md", ".github/pull_request_template.md"]
    found = set()
    for pattern in patterns:
        for p in root.glob(pattern):
            if not p.is_file() or p.is_symlink(): continue
            rel = p.relative_to(root)
            if any(part in {".git", ".worktrees", "graft", "node_modules", "target", "build"} for part in rel.parts) or ".claude" in rel.parts or "generated" in rel.parts and "diagrams" in rel.parts: continue
            found.add(p)
    yield from sorted(found)

def _local_destination(dest):
    dest = dest.replace("\\ ", " ")
    if re.match(r"^[A-Za-z]:[\\/]", dest):
        pieces = dest.split("#", 1); return pieces[0], pieces[1] if len(pieces) > 1 else ""
    parts = urlsplit(dest)
    if parts.scheme: return None
    return parts.path, unquote(parts.fragment)

def validate_links(root: Path) -> list[str]:
    errors = []
    for path in iter_governed_markdown(root):
        text = path.read_text(); masked = _mask(text); defs, dupes = _definitions(masked); rel = path.relative_to(root).as_posix()
        for line, label, first in dupes: errors.append(f"{rel}:{line}: duplicate reference definition: {label} (first defined on line {first})")
        for link in extract_markdown_links(text):
            if link.destination.startswith("__undefined_reference__:"):
                errors.append(f"{rel}:{link.line}: undefined reference: {link.destination.split(':', 1)[1]}"); continue
            local = _local_destination(link.destination)
            if local is None: continue
            dest, fragment = local; target = path if not dest else (path.parent / dest).resolve()
            if not target.exists() or not target.is_file(): errors.append(f"{rel}:{link.line}: {dest} does not exist"); continue
            if fragment and fragment not in extract_anchors(target.read_text()):
                try: target_name = target.relative_to(root).as_posix()
                except ValueError: target_name = str(target)
                errors.append(f"{target_name}#{fragment} does not exist (linked from {rel}:{link.line})")
    return sorted(errors)

def validate_structure(root: Path) -> list[str]:
    errors = [f"{path} is required" for path in REQUIRED_GOVERNANCE_FILES if not (root / path).is_file()]
    architecture = root / "ARCHITECTURE.md"
    if architecture.is_file() and len(architecture.read_text().splitlines()) >= 180:
        errors.append("ARCHITECTURE.md must contain fewer than 180 lines")
    return sorted(errors)

def _metadata_error(path: Path, root: Path, message: str) -> str:
    return f"{path.relative_to(root).as_posix()}: {message}"

def _has_values(value: str | list[str] | None) -> bool:
    if isinstance(value, list):
        return bool(value) and all(item.strip() for item in value)
    return isinstance(value, str) and bool(value.strip())

def _replacement_target(path: Path, value: str, root: Path) -> str | None:
    links = extract_markdown_links(value)
    if len(links) != 1 or not re.fullmatch(r"\s*\[[^]]+\]\((?:<[^>]+>|[^\s)]+)\)\s*", value):
        return "must be one local Markdown link"
    local = _local_destination(links[0].destination)
    if local is None:
        return "must be a local Markdown link"
    destination, _ = local
    target = path if not destination else (path.parent / destination).resolve()
    if target == path.resolve():
        return "must not link to itself"
    if not target.is_file():
        return f"target does not exist: {destination}"
    return None

def _validate_arc42_document(path: Path, root: Path) -> list[str]:
    metadata = parse_front_matter(path)
    errors = []
    for field in ARC42_REQUIRED_FIELDS:
        if field not in metadata:
            errors.append(_metadata_error(path, root, f"{field} is required"))
    if "title" in metadata and not _has_values(metadata["title"]):
        errors.append(_metadata_error(path, root, "title must not be empty"))
    status = metadata.get("status")
    if status not in ARC42_STATUSES:
        errors.append(_metadata_error(path, root, "status must be current or deprecated"))
    if "owners" in metadata and not _has_values(metadata["owners"]):
        errors.append(_metadata_error(path, root, "owners must not be empty"))
    verified = metadata.get("last_verified")
    if not isinstance(verified, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", verified):
        errors.append(_metadata_error(path, root, "last_verified must use ISO YYYY-MM-DD"))
    else:
        try:
            date.fromisoformat(verified)
        except ValueError:
            errors.append(_metadata_error(path, root, "last_verified must use ISO YYYY-MM-DD"))
    refs = metadata.get("code_refs")
    if not _has_values(refs):
        errors.append(_metadata_error(path, root, "code_refs must not be empty"))
    else:
        for ref in refs if isinstance(refs, list) else [refs]:
            if not (root / ref).exists():
                errors.append(_metadata_error(path, root, f"code_refs path does not exist: {ref}"))
    if status == "deprecated":
        replacement = metadata.get("replacement")
        if not isinstance(replacement, str) or not replacement.strip():
            errors.append(_metadata_error(path, root, "deprecated replacement is required"))
        else:
            replacement_error = _replacement_target(path, replacement, root)
            if replacement_error:
                errors.append(_metadata_error(path, root, f"deprecated replacement {replacement_error}"))
    return errors

def validate_metadata(root: Path) -> list[str]:
    errors = []
    arc42 = root / "architecture/arc42"
    arc42_files = {path.relative_to(arc42).as_posix() for path in arc42.rglob("*.md")} if arc42.is_dir() else set()
    expected = set(ARC42_FILENAMES)
    for name in sorted(expected - arc42_files):
        errors.append(f"architecture/arc42/{name} is required")
    for name in sorted(arc42_files - expected):
        errors.append(f"unexpected arc42 file: architecture/arc42/{name}")
    for name in sorted(expected & arc42_files):
        errors.extend(_validate_arc42_document(arc42 / name, root))

    active = root / "architecture/proposals"
    archive = root / "architecture/archive/proposals"
    for directory, archived in ((active, False), (archive, True)):
        if not directory.is_dir():
            continue
        for path in sorted(directory.rglob("*.md")):
            if path.name == "README.md":
                continue
            status = parse_front_matter(path).get("status")
            if archived and status not in TERMINAL_PROPOSAL_STATUSES:
                errors.append(_metadata_error(path, root, f"status {status or 'missing'} is not terminal"))
            elif not archived and status in TERMINAL_PROPOSAL_STATUSES:
                errors.append(_metadata_error(path, root, f"terminal status {status} belongs in architecture/archive/proposals/"))
    return sorted(errors)

Validator = Callable[[Path], list[str]]
VALIDATORS: dict[str, Validator] = {"links": validate_links, "metadata": validate_metadata, "structure": validate_structure}

def validate_repository(root: Path, checks: frozenset[str] = CHECKS) -> list[str]:
    errors = []
    for check in sorted(checks):
        if check not in VALIDATORS: errors.append(f"unknown validation check: {check}")
        else: errors.extend(VALIDATORS[check](root))
    return sorted(errors)

def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(); parser.add_argument("--root", type=Path, default=Path(".")); parser.add_argument("--checks")
    args = parser.parse_args(argv); checks = frozenset(args.checks.split(",")) if args.checks else CHECKS
    unknown = sorted(checks - CHECKS)
    if unknown:
        print("unknown validation check(s): " + ", ".join(unknown), file=sys.stderr); return 2
    errors = validate_repository(args.root, checks)
    if errors:
        print("\n".join(errors), file=sys.stderr); return 1
    print("architecture validation passed"); return 0

if __name__ == "__main__": raise SystemExit(main())
