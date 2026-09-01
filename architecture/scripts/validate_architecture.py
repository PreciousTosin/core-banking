#!/usr/bin/env python3
import argparse
from collections import Counter, defaultdict
import html
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Callable, Iterator, Sequence
from urllib.parse import unquote, urlsplit

CHECKS = frozenset({"adrs", "links", "metadata", "migration", "structure"})

MIGRATION_SOURCE = "architecture/modern-core-banking-comprehensive-design-revised.md"
MIGRATION_INVENTORY = "architecture/archive/comprehensive-design-migration-inventory.md"
MIGRATION_HEADER = (
    "Source key",
    "Source heading",
    "Covered blocks",
    "Disposition",
    "Destination map",
    "Evidence",
    "Rationale",
    "Resolution",
)
MIGRATION_DISPOSITIONS = frozenset({"current", "proposal", "decision", "service-detail", "plan-detail", "historical-only"})
MIGRATION_RESOLUTIONS = frozenset({"unresolved", "resolved"})
PREAMBLE_ROW = (
    "00.document-preamble",
    "Document title, status, version, date, currency, and audience preamble",
    "P01; P02; P03",
    "historical-only",
    "None",
    "None",
    "The source-document identity and revision metadata describe the archived publication itself; no maintained current or proposed destination is appropriate.",
    "resolved",
)

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

@dataclass(frozen=True)
class MigrationRow:
    source_key: str
    source_heading: str
    covered_blocks: str
    disposition: str
    destination_map: str
    evidence: str
    rationale: str
    resolution: str

@dataclass(frozen=True)
class MaterialHeading:
    source_key: str
    heading: str
    blocks: tuple[str, ...]

def _mask_markdown_code(text: str) -> str:
    chars = list(text)
    i = 0
    fence = None
    inline = None
    while i < len(text):
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

def _mask(text: str) -> str:
    masked = _mask_markdown_code(text)
    chars = list(masked)
    i = 0
    comment = False
    while i < len(masked):
        if comment:
            if masked.startswith("-->", i):
                chars[i:i+3] = "   "; comment = False; i += 3
            elif masked[i] != "\n": chars[i] = " "
            i += 1; continue
        if masked.startswith("<!--", i):
            chars[i:i+4] = "    "; comment = True; i += 4; continue
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

def _migration_error(message: str) -> str:
    return f"{MIGRATION_INVENTORY}: {message}"

def _split_inventory_row(line: str) -> tuple[str, ...] | None:
    if not line.startswith("|") or not line.endswith("|"):
        return None
    cells = tuple(cell.strip() for cell in line[1:-1].split("|"))
    return cells if len(cells) == len(MIGRATION_HEADER) else None

def _parse_migration_inventory(path: Path) -> tuple[list[MigrationRow], list[str]]:
    if not path.is_file():
        return [], [_migration_error("migration inventory is required")]
    lines = path.read_text().splitlines()
    errors = []
    header_index = None
    for index, line in enumerate(lines):
        cells = _split_inventory_row(line)
        if cells == MIGRATION_HEADER:
            header_index = index
            break
    if header_index is None:
        return [], [_migration_error("exact migration inventory table header is required")]
    if header_index + 1 >= len(lines) or _split_inventory_row(lines[header_index + 1]) != ("---",) * len(MIGRATION_HEADER):
        errors.append(_migration_error("exact migration inventory table separator is required"))
    rows = []
    for line_number, line in enumerate(lines[header_index + 2:], header_index + 3):
        if not line.strip():
            continue
        if not line.lstrip().startswith("|"):
            errors.append(_migration_error(f"malformed inventory row on line {line_number}: expected a pipe-delimited row"))
            continue
        cells = _split_inventory_row(line)
        if cells is None:
            errors.append(_migration_error(f"malformed inventory row on line {line_number}: expected 8 columns"))
            continue
        rows.append(MigrationRow(*cells))
    return rows, errors

def _block_kind(line: str) -> str:
    if re.match(r"^\s{0,3}(?:-{3,}|\*{3,}|_{3,})\s*$", line):
        return "rule"
    if re.match(r"^\s*(?:`{3,}|~{3,})", line):
        return "fence"
    if re.match(r"^\s*\|", line):
        return "table"
    if re.match(r"^\s{0,3}(?:[-+*]|\d+[.)])\s+", line):
        return "list"
    return "prose"

def _material_blocks(lines: list[str]) -> tuple[str, ...]:
    blocks = []
    index = 0
    while index < len(lines):
        if not lines[index].strip():
            index += 1
            continue
        kind = _block_kind(lines[index])
        if kind == "rule":
            index += 1
            continue
        if kind == "fence":
            opener = re.match(r"^\s*(`{3,}|~{3,})", lines[index]).group(1)
            fence_char = opener[0]
            fence_length = len(opener)
            index += 1
            while index < len(lines):
                if re.match(rf"^\s*{re.escape(fence_char)}{{{fence_length},}}\s*$", lines[index]):
                    index += 1
                    break
                index += 1
        elif kind == "table":
            index += 1
            while index < len(lines) and lines[index].strip() and _block_kind(lines[index]) == "table":
                index += 1
        elif kind == "list":
            index += 1
            while index < len(lines) and lines[index].strip():
                if _block_kind(lines[index]) in {"table", "fence", "rule"}:
                    break
                if _block_kind(lines[index]) == "prose" and not re.match(r"^\s{2,}", lines[index]):
                    break
                index += 1
        else:
            index += 1
            while index < len(lines) and lines[index].strip() and _block_kind(lines[index]) == "prose":
                index += 1
        blocks.append(f"B{len(blocks) + 1:02d}")
    return tuple(blocks)

def _numbered_source_key(heading: str) -> str | None:
    match = re.match(r"^(\d+(?:\.\d+)*)(?:\.)?\s+", heading)
    if not match:
        return None
    return ".".join(f"{int(part):02d}" for part in match.group(1).split("."))

def _material_headings(source_text: str) -> tuple[dict[str, MaterialHeading], set[str]]:
    lines = source_text.splitlines()
    masked_lines = _mask(source_text).splitlines()
    found = []
    context = None
    for index, line in enumerate(masked_lines):
        match = re.match(r"^\s{0,3}(#{2,4})\s+(.+?)\s*#*\s*$", line)
        if not match:
            continue
        raw_match = re.match(r"^\s{0,3}#{2,4}\s+(.+?)\s*#*\s*$", lines[index])
        heading = raw_match.group(1).strip()
        key = _numbered_source_key(heading)
        if key:
            context = key
        else:
            example = re.match(r"^Example\s+([A-J]):", heading)
            key = f"13.08.example-{example.group(1).lower()}" if example and context == "13.08" else None
        found.append((index, heading, key))
    material = {}
    roots = set()
    for position, (line_index, heading, key) in enumerate(found):
        if key is None:
            continue
        roots.add(key.split(".", 1)[0])
        end = found[position + 1][0] if position + 1 < len(found) else len(lines)
        blocks = _material_blocks(lines[line_index + 1:end])
        if blocks:
            material[key] = MaterialHeading(key, heading, blocks)
    return material, roots

def _validate_source_preamble(source_text: str) -> list[str]:
    first_numbered = re.search(r"^##\s+1\.\s+", source_text, re.M)
    if not first_numbered:
        return [_migration_error("document preamble cannot be delimited because section 1 is missing")]
    raw_lines = source_text[:first_numbered.start()].splitlines()
    material_lines = [(index, line.strip()) for index, line in enumerate(raw_lines) if line.strip()]
    lines = [line for _, line in material_lines]
    expected_prefix = [
        "# Modern Core Banking System",
        "## Comprehensive Architecture and Single-VPS Proof-of-Concept Design",
    ]
    metadata_labels = ("Status", "Version", "Date", "Base currency", "Audience")
    valid = len(lines) == 8 and lines[:2] == expected_prefix and lines[-1] == "---"
    valid = valid and all(re.match(rf"^\*\*{re.escape(label)}:\*\*\s+\S", lines[index + 2]) for index, label in enumerate(metadata_labels))
    metadata_positions = [index for index, _ in material_lines[2:7]]
    valid = valid and len(metadata_positions) == 5 and metadata_positions == list(range(metadata_positions[0], metadata_positions[0] + 5))
    return [] if valid else [_migration_error("document preamble must tokenize independently as exact P01, P02, and P03 material")]

def _explicit_anchor_lines(text: str) -> dict[str, list[int]]:
    result = defaultdict(list)
    for index, line in enumerate(_mask_markdown_code(text).splitlines()):
        match = re.match(r'^\s*<a\s+id=["\']([^"\']+)["\']\s*>\s*</a>\s*$', line, re.I)
        if match:
            result[match.group(1)].append(index)
    return result

def _marker_occurrences(root: Path) -> tuple[Counter, list[str]]:
    occurrences = Counter()
    errors = []
    anchor_re = re.compile(r'^\s*<a\s+id=["\']([^"\']+)["\']\s*>\s*</a>\s*$', re.I)
    marker_re = re.compile(r"^\s*<!--\s*migration-source:\s*([^\s]+)\s*-->\s*$")
    for path in iter_governed_markdown(root):
        rel = path.relative_to(root).as_posix()
        active_anchor = None
        for line_number, line in enumerate(_mask_markdown_code(path.read_text()).splitlines(), 1):
            anchor = anchor_re.match(line)
            marker = marker_re.match(line)
            if anchor:
                active_anchor = anchor.group(1)
            elif marker:
                source_key = marker.group(1)
                if active_anchor is None:
                    errors.append(f"{rel}:{line_number}: migration marker is not in the contiguous marker block after an explicit anchor")
                    occurrences[(rel, "", source_key)] += 1
                else:
                    occurrences[(rel, active_anchor, source_key)] += 1
            else:
                active_anchor = None
    return occurrences, errors

def _markers_after_anchor(text: str, anchor: str) -> list[str]:
    lines = _mask_markdown_code(text).splitlines()
    anchor_lines = _explicit_anchor_lines(text).get(anchor, [])
    if len(anchor_lines) != 1:
        return []
    markers = []
    index = anchor_lines[0] + 1
    pattern = re.compile(r"^\s*<!--\s*migration-source:\s*([^\s]+)\s*-->\s*$")
    while index < len(lines):
        match = pattern.match(lines[index])
        if not match:
            break
        markers.append(match.group(1))
        index += 1
    return markers

def _proposal_registry_pointer(root: Path, anchor: str) -> str | None:
    registry = root / "architecture/proposals/README.md"
    if not registry.is_file():
        return None
    lines = _mask_markdown_code(registry.read_text()).splitlines()
    anchor_lines = _explicit_anchor_lines(registry.read_text()).get(anchor, [])
    if len(anchor_lines) != 1:
        return None
    index = anchor_lines[0] + 1
    marker_re = re.compile(r"^\s*<!--\s*migration-source:\s*[^\s]+\s*-->\s*$")
    while index < len(lines) and marker_re.match(lines[index]):
        index += 1
    if index >= len(lines):
        return None
    pointer = re.fullmatch(r"\s*\[[^]]+\]\((?:<([^>]+)>|([^\s)]+))\)\s*", lines[index])
    if not pointer:
        return None
    if index + 1 < len(lines) and re.fullmatch(r"\s*\[[^]]+\]\((?:<([^>]+)>|([^\s)]+))\)\s*", lines[index + 1]):
        return None
    return pointer.group(1) or pointer.group(2)

def _parse_destination_map(row: MigrationRow, errors: list[str]) -> list[tuple[str, str]]:
    if not row.covered_blocks:
        errors.append(_migration_error(f"covered blocks must not be empty for {row.source_key}"))
        covered = []
    else:
        covered = [value.strip() for value in row.covered_blocks.split(";") if value.strip()]
    if any(not re.fullmatch(r"B\d{2}", block) for block in covered):
        errors.append(_migration_error(f"invalid covered block token for {row.source_key}"))
    if len(covered) != len(set(covered)):
        errors.append(_migration_error(f"coverage overlap within row {row.source_key}"))
    if row.disposition == "historical-only":
        if row.destination_map != "None":
            errors.append(_migration_error(f"historical-only destination must be literal None for {row.source_key}"))
        return []
    mappings = []
    if row.destination_map and row.destination_map != "None":
        for item in row.destination_map.split(";"):
            parts = item.strip().split("=", 1)
            if len(parts) != 2 or not parts[0].strip() or not parts[1].strip():
                errors.append(_migration_error(f"malformed destination map entry for {row.source_key}"))
                continue
            mappings.append((parts[0].strip(), parts[1].strip()))
    if Counter(block for block, _ in mappings) != Counter(covered):
        errors.append(_migration_error(f"destination map must cover each block exactly once for {row.source_key}"))
    return mappings

def _validate_destination(root: Path, row: MigrationRow, destination: str, expected: set[tuple[str, str, str]], errors: list[str]) -> None:
    match = re.fullmatch(r"([^#]+\.md)#([A-Za-z0-9][A-Za-z0-9._:-]*)", destination)
    if not match or destination.startswith("/") or ".." in Path(match.group(1)).parts:
        errors.append(_migration_error(f"destination must use repository/path.md#explicit-anchor for {row.source_key}: {destination or 'empty'}"))
        return
    path_name, anchor = match.groups()
    if path_name == MIGRATION_SOURCE:
        errors.append(_migration_error(f"destination must not point to the comprehensive source for {row.source_key}"))
        return
    target = root / path_name
    if not target.is_file():
        errors.append(_migration_error(f"destination does not exist for {row.source_key}: {path_name}"))
        return
    anchors = _explicit_anchor_lines(target.read_text())
    if len(anchors.get(anchor, [])) != 1:
        errors.append(_migration_error(f"destination anchor does not exist exactly once for {row.source_key}: {destination}"))
        return
    expected.add((path_name, anchor, row.source_key))
    if row.source_key not in _markers_after_anchor(target.read_text(), anchor):
        errors.append(_migration_error(f"missing migration marker for {row.source_key} at {destination}"))

def _validate_resolved_proposal(root: Path, row: MigrationRow, destinations: list[str], errors: list[str]) -> None:
    for destination in set(destinations):
        path_name, _, anchor = destination.partition("#")
        if path_name in {f"architecture/proposals/{anchor}.md", f"architecture/archive/proposals/{anchor}.md"} or re.match(r"^architecture/(?:archive/)?proposals/[^/]+\.md$", path_name) and path_name != "architecture/proposals/README.md":
            errors.append(_migration_error(f"resolved proposal {row.source_key} must not use an active or archive proposal record as its destination"))
            continue
        if path_name != "architecture/proposals/README.md":
            errors.append(_migration_error(f"resolved proposal {row.source_key} must use a stable architecture/proposals/README.md registry identity"))
            continue
        pointer = _proposal_registry_pointer(root, anchor)
        if pointer is None:
            errors.append(_migration_error(f"proposal registry pointer must occur exactly once immediately after {anchor}"))
            continue
        local = _local_destination(pointer)
        if local is None:
            errors.append(_migration_error(f"proposal registry pointer must be local for {anchor}"))
            continue
        pointer_path, _ = local
        target = ((root / "architecture/proposals") / pointer_path).resolve()
        basename = f"{anchor}.md"
        if Path(pointer_path).name != basename:
            errors.append(_migration_error(f"proposal registry pointer basename must be {basename} for {anchor}"))
            continue
        if not target.is_file():
            errors.append(_migration_error(f"proposal registry pointer target does not exist for {anchor}: {pointer}"))
            continue
        allowed = {
            (root / "architecture/proposals" / basename).resolve(),
            (root / "architecture/archive/proposals" / basename).resolve(),
        }
        if target not in allowed:
            errors.append(_migration_error(f"proposal registry pointer must name the active or archive record for {anchor}"))
            continue
        existing = [candidate for candidate in allowed if candidate.is_file()]
        if existing != [target]:
            errors.append(_migration_error(f"proposal registry identity {anchor} must have one sole active or archive record"))

def validate_migration_inventory(root: Path) -> list[str]:
    source = root / MIGRATION_SOURCE
    if not source.is_file():
        return [_migration_error(f"source document is required: {MIGRATION_SOURCE}")]
    source_text = source.read_text()
    headings, source_roots = _material_headings(source_text)
    rows, errors = _parse_migration_inventory(root / MIGRATION_INVENTORY)
    errors.extend(_validate_source_preamble(source_text))

    keys = Counter(row.source_key for row in rows)
    for key, count in sorted(keys.items()):
        if count > 1:
            errors.append(_migration_error(f"duplicate source key {key}"))
    preamble_rows = [row for row in rows if row.source_key == PREAMBLE_ROW[0]]
    if len(preamble_rows) != 1:
        errors.append(_migration_error("document preamble row 00.document-preamble must occur exactly once"))
    elif tuple(preamble_rows[0].__dict__.values()) != PREAMBLE_ROW:
        errors.append(_migration_error("document preamble row must use the exact P01, P02, P03 historical-only contract and literal None destination"))

    valid_key = re.compile(r"^(?:00\.document-preamble|\d{2}(?:\.\d{2})*(?:\.example-[a-j])?(?:::\d{2})?)$")
    grouped = defaultdict(list)
    row_destinations = defaultdict(list)
    expected_markers = set()
    represented_roots = set()
    for row in rows:
        if row.source_key.startswith("00.") and row.source_key != "00.document-preamble":
            errors.append(_migration_error(f"document preamble key is reserved; unsupported key {row.source_key}"))
            continue
        if not valid_key.fullmatch(row.source_key):
            errors.append(_migration_error(f"malformed source key {row.source_key or 'empty'}"))
            continue
        if row.source_key == "00.document-preamble":
            continue
        base = row.source_key.split("::", 1)[0]
        grouped[base].append(row)
        represented_roots.add(base.split(".", 1)[0])
        if row.disposition not in MIGRATION_DISPOSITIONS:
            errors.append(_migration_error(f"unsupported disposition {row.disposition or 'empty'} for {row.source_key}"))
        if row.resolution not in MIGRATION_RESOLUTIONS:
            errors.append(_migration_error(f"unsupported resolution {row.resolution or 'empty'} for {row.source_key}"))
        if row.resolution == "unresolved" and row.disposition not in {"decision", "proposal"}:
            errors.append(_migration_error(f"unresolved resolution is allowed only for decision or proposal rows: {row.source_key}"))
        if not row.rationale:
            errors.append(_migration_error(f"rationale must not be empty for {row.source_key}"))
        if row.disposition == "historical-only" and not (re.search(r"archiv|histor", row.rationale, re.I) and re.search(r"no maintained[^.]*destination", row.rationale, re.I)):
            errors.append(_migration_error(f"historical-only rationale must explain archive retention and why no maintained destination exists for {row.source_key}"))
        mappings = _parse_destination_map(row, errors)
        if row.disposition != "historical-only":
            for _, destination in mappings:
                row_destinations[row.source_key].append(destination)
                _validate_destination(root, row, destination, expected_markers, errors)
        if row.disposition == "current":
            evidence = [value.strip() for value in row.evidence.split(";") if value.strip()] if row.evidence != "None" else []
            if not evidence:
                errors.append(_migration_error(f"current evidence is required for {row.source_key}"))
            for path_name in evidence:
                if not (root / path_name.split("#", 1)[0]).exists():
                    errors.append(_migration_error(f"current evidence does not exist for {row.source_key}: {path_name}"))
        if row.resolution == "unresolved":
            errors.append(_migration_error(f"unresolved migration row {row.source_key}"))

    for root_number in (f"{number:02d}" for number in range(1, 28)):
        if root_number not in represented_roots:
            errors.append(_migration_error(f"missing top-level source root {root_number}"))
    for root_number in sorted(source_roots - {f"{number:02d}" for number in range(1, 28)}):
        errors.append(_migration_error(f"source contains unsupported top-level root {root_number}"))

    for base, heading in sorted(headings.items()):
        heading_rows = grouped.get(base, [])
        if not heading_rows:
            errors.append(_migration_error(f"missing migration row for source heading {base}"))
            continue
        exact = [row for row in heading_rows if row.source_key == base]
        segmented = [row for row in heading_rows if "::" in row.source_key]
        if exact and segmented:
            errors.append(_migration_error(f"source heading {base} cannot mix an exact key with segment keys"))
        if segmented:
            suffixes = sorted(int(row.source_key.rsplit("::", 1)[1]) for row in segmented)
            if len(segmented) < 2 or suffixes != list(range(1, len(segmented) + 1)):
                errors.append(_migration_error(f"source heading {base} must use contiguous segment suffixes from ::01"))
        for row in heading_rows:
            if row.source_heading != heading.heading:
                errors.append(_migration_error(f"source heading text mismatch for {row.source_key}: expected {heading.heading}"))
        covered = []
        for row in heading_rows:
            covered.extend(value.strip() for value in row.covered_blocks.split(";") if value.strip())
        expected = set(heading.blocks)
        actual = set(covered)
        for block in sorted(expected - actual):
            errors.append(_migration_error(f"coverage gap for {base}: {block}"))
        for block, count in sorted(Counter(covered).items()):
            if count > 1:
                errors.append(_migration_error(f"coverage overlap for {base}: {block}"))
        for block in sorted(actual - expected):
            errors.append(_migration_error(f"unknown covered block for {base}: {block}"))
    for base in sorted(set(grouped) - set(headings)):
        errors.append(_migration_error(f"source key does not map to a material source heading: {base}"))

    for row in rows:
        if row.disposition == "proposal" and row.resolution == "resolved":
            _validate_resolved_proposal(root, row, row_destinations[row.source_key], errors)

    actual_markers, marker_errors = _marker_occurrences(root)
    errors.extend(marker_errors)
    expected_counter = Counter(expected_markers)
    if actual_markers != expected_counter:
        errors.append(_migration_error("migration marker mismatch between governed Markdown and active destination tuples"))
        for triple in sorted(expected_counter.keys() | actual_markers.keys()):
            expected_count = expected_counter[triple]
            actual_count = actual_markers[triple]
            path_name, anchor, source_key = triple
            if actual_count < expected_count:
                errors.append(_migration_error(f"missing migration marker ({path_name}, {anchor}, {source_key})"))
            elif expected_count and actual_count > expected_count:
                errors.append(_migration_error(f"duplicate migration marker ({path_name}, {anchor}, {source_key})"))
            elif not expected_count:
                errors.append(_migration_error(f"unexpected migration marker ({path_name}, {anchor or 'no-anchor'}, {source_key})"))
    return sorted(set(errors))

ADR_STATUSES = frozenset({"Proposed", "Accepted", "Rejected", "Superseded", "Deprecated"})
ADR_IMPLEMENTATION_STATUSES = frozenset({"Not started", "Partial", "Complete", "Not applicable"})
ADR_FIELDS = (
    "Status",
    "Retrospective",
    "Decision date",
    "Deciders",
    "Scope",
    "Implementation status",
    "Related proposals",
    "Related implementation plans",
    "Related pull requests",
    "Related commits",
    "Related architecture sections",
    "Supersedes",
    "Superseded by",
)
ADR_RELATIONSHIP_FIELDS = (
    "Related proposals",
    "Related implementation plans",
    "Related pull requests",
    "Related commits",
    "Related architecture sections",
    "Supersedes",
    "Superseded by",
)
ADR_SUBSTANTIVE_HEADINGS = (
    "## Context",
    "## Decision drivers",
    "## Considered options",
    "## Decision",
    "## Consequences",
    "### Positive",
    "### Negative",
    "### Risks",
    "## Compliance and verification",
    "## Implementation evidence",
)
ADR_PROTECTED_SECTIONS = (
    "## Context",
    "## Decision drivers",
    "## Considered options",
    "## Decision",
    "## Consequences",
)
ADR_EVIDENCE_LOCAL_RE = re.compile(
    r"^- ([0-9a-f]{40}) (changed|snapshot): ([^;\n]+(?:; [^;\n]+)*)$"
)
ADR_EVIDENCE_PR_RE = re.compile(
    r"^- https://github\.com/([^/]+)/([^/]+)/pull/([1-9][0-9]*)$", re.I
)
ADR_PATH_RE = re.compile(r"^(\d{4})-([a-z0-9]+(?:-[a-z0-9]+)*)\.md$")
ADR_BOOTSTRAP_PATH = "architecture/adr/0001-manage-architecture-as-versioned-code.md"
ADR_BOOTSTRAP_TITLE = "# ADR-0001: Manage architecture as versioned code"
ADR_BOOTSTRAP_DATE = "2026-09-01"
ADR_BOOTSTRAP_SCOPE = "Architecture documentation and ADR framework governance"
ADR_BOOTSTRAP_PLAN = "[Architecture Documentation and ADR Framework Implementation Plan](../../docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md)"
ADR_BOOTSTRAP_PLAN_PATH = "docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md"
ADR_BOOTSTRAP_PLAN_HEADER = "**Governing ADR:** [ADR-0001: Manage architecture as versioned code](../../../architecture/adr/0001-manage-architecture-as-versioned-code.md)"
ADR_BOOTSTRAP_EVIDENCE = "- 0e46650dcb382bf4ddc040e0ec73e98675dff40b changed: docs/superpowers/specs/2026-09-01-architecture-documentation-and-adr-framework-design.md"
ADR_BOOTSTRAP_DESIGN_HASH = "0e46650dcb382bf4ddc040e0ec73e98675dff40b"
ADR_BOOTSTRAP_DESIGN_PATH = "docs/superpowers/specs/2026-09-01-architecture-documentation-and-adr-framework-design.md"

@dataclass(frozen=True)
class AdrRecord:
    path: str
    raw: bytes
    title: str
    identifier: str
    number: int
    field_names: tuple[str, ...]
    fields: dict[str, str]
    sections: dict[str, str]

def _adr_error(path: str, message: str) -> str:
    return f"{path}: {message}"

def _run_git(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(root), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )

def _resolve_commit(root: Path, ref: str) -> tuple[str | None, str | None]:
    result = _run_git(root, "rev-parse", "--verify", f"{ref}^{{commit}}")
    commit = result.stdout.strip()
    if result.returncode or not re.fullmatch(r"[0-9a-f]{40}", commit):
        return None, f"ADR Git ref does not resolve to a commit: {ref}"
    return commit, None

def _kebab_title(value: str) -> str:
    value = value.casefold().replace("'", "")
    return re.sub(r"^-|-$", "", re.sub(r"[^a-z0-9]+", "-", value))

def _section_bodies(text: str) -> dict[str, str]:
    lines = text.splitlines()
    headings = []
    for index, line in enumerate(lines):
        match = re.match(r"^(#{2,3})\s+.+?\s*$", line)
        if match:
            headings.append((index, len(match.group(1)), line.strip()))
    sections = {}
    for position, (start, level, heading) in enumerate(headings):
        end = len(lines)
        for later_start, later_level, _ in headings[position + 1:]:
            if later_level <= level:
                end = later_start
                break
        body_lines = [line.rstrip() for line in lines[start + 1:end]]
        while body_lines and not body_lines[0].strip():
            body_lines.pop(0)
        while body_lines and not body_lines[-1].strip():
            body_lines.pop()
        sections[heading] = "\n".join(body_lines)
    return sections

def _parse_adr(path: str, raw: bytes) -> AdrRecord | None:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        return None
    lines = text.splitlines()
    title = lines[0].strip() if lines else ""
    match = re.fullmatch(r"# (ADR-(\d{4})): (.+)", title)
    if not match:
        identifier, number = "", -1
    else:
        identifier, number = match.group(1), int(match.group(2))
    fields = {}
    field_names = []
    for line in lines[1:]:
        if line.startswith("## "):
            break
        field = re.match(r"^- ([^:]+):\s*(.*)$", line)
        if field:
            field_name = field.group(1).strip()
            field_names.append(field_name)
            fields[field_name] = field.group(2).strip()
    return AdrRecord(path, raw, title, identifier, number, tuple(field_names), fields, _section_bodies(text))

def _has_substantive_content(body: str) -> bool:
    unfenced_lines = []
    fence_character = None
    fence_length = 0
    for line in body.splitlines():
        if fence_character:
            closing = re.match(
                rf"^\s{{0,3}}{re.escape(fence_character)}{{{fence_length},}}\s*$",
                line,
            )
            unfenced_lines.append("")
            if closing:
                fence_character = None
                fence_length = 0
            continue
        opening = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if opening:
            fence_character = opening.group(1)[0]
            fence_length = len(opening.group(1))
            unfenced_lines.append("")
            continue
        unfenced_lines.append(line)

    lines = _mask("\n".join(unfenced_lines)).splitlines()
    syntax_only = set()
    for index, line in enumerate(lines):
        if line.expandtabs(4).startswith("    "):
            syntax_only.add(index)
        if re.match(r"^\s{0,3}#{1,6}(?:\s+|$)", line):
            syntax_only.add(index)
        if re.match(r"^\s{0,3}\[[^]]+\]:\s*(?:<[^>]+>|\S+)", line):
            syntax_only.add(index)
        if (
            index > 0
            and lines[index - 1].strip()
            and re.fullmatch(r"\s{0,3}(?:=+|-+)\s*", line)
        ):
            syntax_only.update((index - 1, index))
    content = "\n".join("" if index in syntax_only else line for index, line in enumerate(lines))
    content = re.sub(r"<[^>]+>", "", content)
    if extract_markdown_links(content):
        return True
    for line in content.splitlines():
        stripped = line.strip()
        if (
            not stripped
            or re.fullmatch(r"(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})", stripped)
        ):
            continue
        if re.match(r"^(?:[-+*]|[0-9]+[.)])\s+\S", stripped):
            return True
        if any(character.isalnum() for character in stripped):
            return True
    return False

def _adr_paths_filesystem(root: Path) -> dict[str, bytes]:
    directory = root / "architecture/adr"
    if not directory.is_dir():
        return {}
    return {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in sorted(directory.glob("[0-9][0-9][0-9][0-9]-*.md"))
        if path.is_file() and not path.is_symlink()
    }

def _adr_paths_commit(root: Path, commit: str) -> dict[str, bytes]:
    listing = _run_git(root, "ls-tree", "-r", "--name-only", commit, "--", "architecture/adr")
    if listing.returncode:
        return {}
    result = {}
    for path in listing.stdout.splitlines():
        if not re.fullmatch(r"architecture/adr/[0-9]{4}-[a-z0-9]+(?:-[a-z0-9]+)*\.md", path):
            continue
        shown = subprocess.run(
            ["git", "-C", str(root), "show", f"{commit}:{path}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if shown.returncode == 0:
            result[path] = shown.stdout
    return result

def _relationship_sequence(value: str) -> tuple[str, ...]:
    if value == "None":
        return ()
    return tuple(item.strip() for item in value.split(";") if item.strip())

def _body_sequence(body: str) -> tuple[str, ...]:
    if body == "None":
        return ()
    return tuple(line.rstrip() for line in body.splitlines() if line.strip())

def _is_prefix(parent: tuple[str, ...], child: tuple[str, ...]) -> bool:
    return len(child) >= len(parent) and child[:len(parent)] == parent

def _changed_paths(root: Path, commit: str) -> set[str] | None:
    parents_result = _run_git(root, "rev-list", "--parents", "-n", "1", commit)
    if parents_result.returncode:
        return None
    parts = parents_result.stdout.split()
    parents = parts[1:]
    if not parents:
        diff = _run_git(root, "diff-tree", "--root", "--no-commit-id", "--name-only", "-r", commit)
        return set(diff.stdout.splitlines()) if diff.returncode == 0 else None
    changed = set()
    for parent in parents:
        diff = _run_git(root, "diff", "--name-only", "--no-renames", parent, commit)
        if diff.returncode:
            return None
        changed.update(diff.stdout.splitlines())
    return changed

def _github_origin(root: Path) -> tuple[str, str] | None:
    result = _run_git(root, "config", "--get", "remote.origin.url")
    if result.returncode:
        return None
    value = result.stdout.strip()
    match = re.fullmatch(r"git@github\.com:([^/]+)/(.+?)(?:\.git)?", value, re.I)
    if not match:
        match = re.fullmatch(r"https://github\.com/([^/]+)/(.+?)(?:\.git)?/?", value, re.I)
    if not match:
        return None
    repository = match.group(2)
    if repository.casefold().endswith(".git"):
        repository = repository[:-4]
    return match.group(1).casefold(), repository.casefold()

def _evidence_entries(record: AdrRecord) -> tuple[list[tuple[str, str, tuple[str, ...]]], list[tuple[str, str]], list[str]]:
    local = []
    pull_requests = []
    invalid = []
    body = record.sections.get("## Implementation evidence", "")
    if body == "None":
        return local, pull_requests, invalid
    for line in (line.strip() for line in body.splitlines() if line.strip()):
        match = ADR_EVIDENCE_LOCAL_RE.fullmatch(line)
        if match:
            paths = tuple(item.strip() for item in match.group(3).split(";"))
            if any(not item or Path(item).is_absolute() or ".." in Path(item).parts for item in paths):
                invalid.append(line)
            else:
                local.append((match.group(1), match.group(2), paths))
            continue
        pr = ADR_EVIDENCE_PR_RE.fullmatch(line)
        if pr:
            pull_requests.append((pr.group(1), pr.group(2)))
        else:
            invalid.append(line)
    return local, pull_requests, invalid

def _validate_evidence(root: Path, record: AdrRecord) -> list[str]:
    errors = []
    local, pull_requests, invalid = _evidence_entries(record)
    for line in invalid:
        errors.append(_adr_error(record.path, f"Implementation evidence entry is invalid: {line}"))
    for commit, mode, paths in local:
        exists = _run_git(root, "cat-file", "-e", f"{commit}^{{commit}}")
        if exists.returncode:
            errors.append(_adr_error(record.path, f"evidence hash does not resolve to a commit: {commit}"))
            continue
        changed = _changed_paths(root, commit) if mode == "changed" else None
        if mode == "changed" and changed is None:
            errors.append(_adr_error(record.path, f"could not derive changed paths for {commit}"))
        for evidence_path in paths:
            tree = _run_git(root, "cat-file", "-e", f"{commit}:{evidence_path}")
            if tree.returncode:
                errors.append(_adr_error(record.path, f"evidence path does not exist at {commit}: {evidence_path}"))
            elif mode == "changed" and changed is not None and evidence_path not in changed:
                errors.append(_adr_error(record.path, f"evidence path was not changed by {commit}: {evidence_path}"))
    origin = _github_origin(root)
    for owner, repository in pull_requests:
        if origin != (owner.casefold(), repository.casefold()):
            errors.append(_adr_error(record.path, "pull-request evidence does not match a normalized GitHub origin"))
    return errors

def _resolve_markdown_targets(record: AdrRecord, field: str, root: Path) -> list[Path]:
    targets = []
    for item in _relationship_sequence(record.fields.get(field, "")):
        links = extract_markdown_links(item)
        if len(links) != 1:
            continue
        local = _local_destination(links[0].destination)
        if local is None:
            continue
        destination, _ = local
        targets.append(((root / record.path).parent / destination).resolve())
    return targets

def validate_adrs(root: Path) -> list[str]:
    root = root.resolve()
    raw_records = _adr_paths_filesystem(root)
    records = []
    errors = []
    for path, raw in raw_records.items():
        record = _parse_adr(path, raw)
        if record is None:
            errors.append(_adr_error(path, "ADR must be UTF-8 Markdown"))
            continue
        records.append(record)
        filename = Path(path).name
        match = ADR_PATH_RE.fullmatch(filename)
        if not match or record.number != int(match.group(1)) or not record.identifier or _kebab_title(record.title.split(":", 1)[-1].strip()) != match.group(2):
            errors.append(_adr_error(path, "ADR filename/title must agree on number and kebab-case title"))
        if record.field_names != ADR_FIELDS:
            errors.append(_adr_error(path, "ADR metadata fields must occur exactly once in the required order"))
        for field in ADR_FIELDS:
            if field not in record.fields or not record.fields[field]:
                errors.append(_adr_error(path, f"{field} is required"))
        if record.fields.get("Status") not in ADR_STATUSES:
            errors.append(_adr_error(path, "Status must be one of Proposed, Accepted, Rejected, Superseded, Deprecated"))
        if record.fields.get("Retrospective") not in {"Yes", "No"}:
            errors.append(_adr_error(path, "Retrospective must be Yes or No"))
        if record.fields.get("Implementation status") not in ADR_IMPLEMENTATION_STATUSES:
            errors.append(_adr_error(path, "Implementation status must be one of Not started, Partial, Complete, Not applicable"))
        decision_date = record.fields.get("Decision date", "")
        try:
            date.fromisoformat(decision_date)
        except ValueError:
            errors.append(_adr_error(path, "Decision date must use ISO YYYY-MM-DD"))
        for field in ADR_RELATIONSHIP_FIELDS:
            value = record.fields.get(field, "")
            if value != "None" and not _relationship_sequence(value):
                errors.append(_adr_error(path, f"{field} must be None or a non-empty ordered sequence"))
        for heading in ADR_SUBSTANTIVE_HEADINGS:
            if not _has_substantive_content(record.sections.get(heading, "")):
                errors.append(_adr_error(path, f"{heading} must contain prose, a list item, or a link"))
        heading_lines = [line.strip() for line in raw.decode("utf-8").splitlines() if line.strip() in ADR_SUBSTANTIVE_HEADINGS]
        if heading_lines != list(ADR_SUBSTANTIVE_HEADINGS):
            errors.append(_adr_error(path, "ADR substantive headings must occur once in the required order"))
        for field in ("Related architecture sections", "Related implementation plans"):
            value = record.fields.get(field, "")
            if value != "None":
                for item in _relationship_sequence(value):
                    links = extract_markdown_links(item)
                    if len(links) != 1 or not re.fullmatch(r"\[[^]]+\]\((?:<[^>]+>|[^\s)]+)\)", item):
                        errors.append(_adr_error(path, f"{field} must contain exact Markdown-link items separated by semicolon-space"))
        evidence_body = record.sections.get("## Implementation evidence", "")
        implementation = record.fields.get("Implementation status")
        if evidence_body == "None" and implementation not in {"Not started", "Not applicable"}:
            errors.append(_adr_error(path, "Implementation evidence may be None only for Not started or Not applicable"))
        if evidence_body != "None" and implementation in {"Partial", "Complete"} and not _body_sequence(evidence_body):
            errors.append(_adr_error(path, "Partial and Complete require implementation evidence"))
        local, prs, invalid = _evidence_entries(record)
        if evidence_body != "None" and not local and not prs:
            errors.append(_adr_error(path, "Implementation evidence entry must use an exact path-bound local or pull-request form"))
        if invalid:
            for line in invalid:
                errors.append(_adr_error(path, f"Implementation evidence entry is invalid: {line}"))
        errors.extend(_validate_evidence(root, record))
        if 1 <= record.number <= 8 and record.fields.get("Related architecture sections") == "None":
            errors.append(_adr_error(path, "foundational ADR must link at least one architecture section"))

    numbers = sorted(record.number for record in records if record.number >= 0)
    if numbers and numbers != list(range(numbers[0], numbers[-1] + 1)):
        errors.append("architecture/adr: ADR numbering must be contiguous")
    by_id = {record.identifier: record for record in records if record.identifier}

    arc42_dir = (root / "architecture/arc42").resolve()
    for record in records:
        for target in _resolve_markdown_targets(record, "Related architecture sections", root):
            try:
                relative = target.relative_to(arc42_dir)
            except ValueError:
                errors.append(_adr_error(record.path, f"architecture-section link is outside architecture/arc42: {target}")); continue
            if not target.is_file() or target.parent != arc42_dir or target.suffix != ".md":
                errors.append(_adr_error(record.path, f"architecture-section target does not exist: {relative.as_posix()}")); continue
            related = parse_front_matter(target).get("related_adrs", [])
            values = related if isinstance(related, list) else [related]
            if record.identifier not in values:
                errors.append(_adr_error(record.path, f"{target.relative_to(root).as_posix()} does not list {record.identifier}"))
    if arc42_dir.is_dir():
        for arc in sorted(arc42_dir.glob("*.md")):
            related = parse_front_matter(arc).get("related_adrs", [])
            for identifier in related if isinstance(related, list) else [related]:
                record = by_id.get(identifier)
                if record is None:
                    errors.append(f"{arc.relative_to(root).as_posix()}: related_adrs references missing ADR target {identifier}")
                elif arc.resolve() not in _resolve_markdown_targets(record, "Related architecture sections", root):
                    errors.append(f"{arc.relative_to(root).as_posix()}: {identifier} does not link back to this exact architecture section")

    plans_dir = (root / "docs/superpowers/plans").resolve()
    adr_dir = (root / "architecture/adr").resolve()
    for record in records:
        for target in _resolve_markdown_targets(record, "Related implementation plans", root):
            try:
                target.relative_to(plans_dir)
            except ValueError:
                errors.append(_adr_error(record.path, f"implementation plan link is outside docs/superpowers/plans: {target}")); continue
            if not target.is_file():
                errors.append(_adr_error(record.path, f"implementation plan target does not exist: {target.name}")); continue
            backlink = False
            for destination in extract_markdown_destinations(target.read_text()):
                local = _local_destination(destination)
                if local is not None and (target.parent / local[0]).resolve() == (root / record.path).resolve():
                    backlink = True
            if not backlink:
                errors.append(_adr_error(record.path, f"implementation plan does not link back to {record.identifier}: {target.relative_to(root).as_posix()}"))
    if plans_dir.is_dir():
        for plan in sorted(plans_dir.glob("*.md")):
            for destination in extract_markdown_destinations(plan.read_text()):
                local = _local_destination(destination)
                if local is None:
                    continue
                target = (plan.parent / local[0]).resolve()
                try:
                    target.relative_to(adr_dir)
                except ValueError:
                    continue
                if not target.is_file():
                    errors.append(f"{plan.relative_to(root).as_posix()}: direct ADR target does not exist: {local[0]}"); continue
                target_record = next((record for record in records if (root / record.path).resolve() == target), None)
                if target_record and plan.resolve() not in _resolve_markdown_targets(target_record, "Related implementation plans", root):
                    errors.append(f"{plan.relative_to(root).as_posix()}: ADR backlink is missing for {target_record.identifier}")

    framework_plan = root / ADR_BOOTSTRAP_PLAN_PATH
    bootstrap = by_id.get("ADR-0001")
    if framework_plan.is_file() or (bootstrap and bootstrap.fields.get("Related implementation plans") == ADR_BOOTSTRAP_PLAN):
        if bootstrap is None or bootstrap.fields.get("Related implementation plans") != ADR_BOOTSTRAP_PLAN:
            errors.append(f"{ADR_BOOTSTRAP_PATH}: ADR-0001 must name the exact framework implementation plan")
        if not framework_plan.is_file():
            errors.append(f"{ADR_BOOTSTRAP_PLAN_PATH}: framework implementation plan is required")
        elif _mask_markdown_code(framework_plan.read_text()).splitlines().count(ADR_BOOTSTRAP_PLAN_HEADER) != 1:
            errors.append(f"{ADR_BOOTSTRAP_PLAN_PATH}: framework plan must contain exactly one governing ADR-0001 header")

    graph = {}
    for record in records:
        successors = _relationship_sequence(record.fields.get("Superseded by", "None"))
        predecessors = _relationship_sequence(record.fields.get("Supersedes", "None"))
        if len(successors) > 1:
            errors.append(_adr_error(record.path, "a predecessor may name only one successor"))
        for field, targets in (("Superseded by", successors), ("Supersedes", predecessors)):
            for target_id in targets:
                if target_id == record.identifier:
                    errors.append(_adr_error(record.path, f"{field} self-reference is forbidden")); continue
                target = by_id.get(target_id)
                if target is None:
                    errors.append(_adr_error(record.path, f"{field} names missing ADR target {target_id}")); continue
                if field == "Superseded by":
                    graph[record.identifier] = target_id
                    if record.fields.get("Status") != "Superseded": errors.append(_adr_error(record.path, "a predecessor with Superseded by must be Superseded"))
                    if target.fields.get("Status") != "Accepted": errors.append(_adr_error(record.path, f"successor {target_id} must be Accepted"))
                    if record.identifier not in _relationship_sequence(target.fields.get("Supersedes", "None")): errors.append(_adr_error(record.path, f"non-reciprocal supersession edge to {target_id}"))
                else:
                    graph[target_id] = record.identifier
                    if target.fields.get("Superseded by") != record.identifier: errors.append(_adr_error(record.path, f"non-reciprocal supersession edge from {target_id}"))
        if record.fields.get("Status") == "Deprecated" and successors:
            errors.append(_adr_error(record.path, "Deprecated records must not name a superseding ADR"))
    for start in sorted(graph):
        seen = set(); current = start
        while current in graph:
            if current in seen:
                errors.append("architecture/adr: supersession graph contains a cycle")
                break
            seen.add(current); current = graph[current]
    return sorted(set(errors))

def _qualified_historical_introduction(root: Path, record: AdrRecord, base_commit: str, child_commit: str | None) -> bool:
    if record.fields.get("Retrospective") != "Yes":
        return False
    local, _, invalid = _evidence_entries(record)
    if invalid:
        return False
    for commit, _mode, _paths in local:
        if _validate_evidence(root, record):
            return False
        comparison = child_commit or base_commit
        if commit == child_commit:
            continue
        ancestor = _run_git(root, "merge-base", "--is-ancestor", commit, comparison)
        if ancestor.returncode == 0:
            return True
    return False

def _bootstrap_introduction(root: Path, record: AdrRecord, base_commit: str, child_commit: str | None) -> bool:
    if not (
        record.path == ADR_BOOTSTRAP_PATH
        and record.title == ADR_BOOTSTRAP_TITLE
        and record.fields.get("Status") == "Accepted"
        and record.fields.get("Retrospective") == "No"
        and record.fields.get("Decision date") == ADR_BOOTSTRAP_DATE
        and record.fields.get("Related implementation plans") == ADR_BOOTSTRAP_PLAN
        and record.fields.get("Scope") == ADR_BOOTSTRAP_SCOPE
        and _body_sequence(record.sections.get("## Implementation evidence", ""))[:1] == (ADR_BOOTSTRAP_EVIDENCE,)
    ):
        return False
    if _validate_evidence(root, record):
        return False
    comparison = child_commit or base_commit
    if ADR_BOOTSTRAP_DESIGN_HASH == child_commit:
        return False
    if _run_git(root, "merge-base", "--is-ancestor", ADR_BOOTSTRAP_DESIGN_HASH, comparison).returncode:
        return False
    design = _run_git(root, "show", f"{ADR_BOOTSTRAP_DESIGN_HASH}:{ADR_BOOTSTRAP_DESIGN_PATH}")
    return design.returncode == 0 and "**Status:** Approved design" in design.stdout.splitlines()

def _validate_adr_edge(root: Path, parent_commit: str, child_commit: str | None) -> list[str]:
    parent_raw = _adr_paths_commit(root, parent_commit)
    child_raw = _adr_paths_commit(root, child_commit) if child_commit else _adr_paths_filesystem(root)
    parent_records = {path: _parse_adr(path, raw) for path, raw in parent_raw.items()}
    child_records = {path: _parse_adr(path, raw) for path, raw in child_raw.items()}
    errors = []
    allowed = {
        "Proposed": {"Proposed", "Accepted", "Rejected"},
        "Accepted": {"Accepted", "Superseded", "Deprecated"},
        "Superseded": {"Superseded"},
        "Deprecated": {"Deprecated"},
        "Rejected": {"Rejected"},
    }
    protected = {"Accepted", "Superseded", "Deprecated"}
    for path, parent in parent_records.items():
        if parent is None:
            continue
        child = child_records.get(path)
        parent_status = parent.fields.get("Status", "")
        if child is None:
            if parent_status in protected | {"Rejected"}:
                errors.append(_adr_error(path, f"{parent_status} ADR was deleted or renamed"))
            continue
        child_status = child.fields.get("Status", "")
        if child_status not in allowed.get(parent_status, set()):
            errors.append(_adr_error(path, f"forbidden ADR status edge {parent_status} -> {child_status}"))
        if parent_status == "Rejected":
            if parent.raw != child.raw:
                errors.append(_adr_error(path, "Rejected record must remain byte-identical at the same path"))
            continue
        if parent_status == "Proposed":
            continue
        if parent_status in protected:
            for heading in ADR_PROTECTED_SECTIONS:
                if parent.sections.get(heading, "") != child.sections.get(heading, ""):
                    excerpt = child.sections.get(heading, "").splitlines()[0] if child.sections.get(heading, "") else "empty"
                    errors.append(_adr_error(path, f"immutable section changed: {heading} ({excerpt})"))
            mutable_fields = set(ADR_RELATIONSHIP_FIELDS) | {"Status", "Implementation status"}
            if parent.title != child.title:
                errors.append(_adr_error(path, "accepted ADR title changed"))
            if parent.field_names != child.field_names:
                errors.append(_adr_error(path, "accepted ADR metadata field layout changed"))
            for field in (set(parent.fields) | set(child.fields)) - mutable_fields:
                if parent.fields.get(field) != child.fields.get(field):
                    errors.append(_adr_error(path, f"accepted ADR field changed: {field}"))
            parent_impl = parent.fields.get("Implementation status", "")
            child_impl = child.fields.get("Implementation status", "")
            if parent_impl == "Not applicable" or child_impl == "Not applicable":
                if parent_impl != child_impl:
                    errors.append(_adr_error(path, f"implementation status cannot change {parent_impl} -> {child_impl}"))
            else:
                order = {"Not started": 0, "Partial": 1, "Complete": 2}
                if parent_impl not in order or child_impl not in order or order[child_impl] < order[parent_impl]:
                    errors.append(_adr_error(path, f"implementation status cannot regress {parent_impl} -> {child_impl}"))
            for field in ADR_RELATIONSHIP_FIELDS:
                if not _is_prefix(_relationship_sequence(parent.fields.get(field, "None")), _relationship_sequence(child.fields.get(field, "None"))):
                    errors.append(_adr_error(path, f"append-only sequence changed: {field}"))
            for heading in ("## Compliance and verification", "## Implementation evidence"):
                if not _is_prefix(_body_sequence(parent.sections.get(heading, "None")), _body_sequence(child.sections.get(heading, "None"))):
                    errors.append(_adr_error(path, f"append-only sequence changed: {heading}"))
            mutable_sections = set(ADR_PROTECTED_SECTIONS) | {"## Compliance and verification", "## Implementation evidence", "### Positive", "### Negative", "### Risks"}
            for heading in (set(parent.sections) | set(child.sections)) - mutable_sections:
                if parent.sections.get(heading) != child.sections.get(heading):
                    errors.append(_adr_error(path, f"accepted ADR section changed: {heading}"))
            errors.extend(_validate_evidence(root, child))
    for path, child in child_records.items():
        if path in parent_records or child is None:
            continue
        status = child.fields.get("Status")
        if status == "Proposed":
            continue
        if path == ADR_BOOTSTRAP_PATH:
            if status == "Accepted" and _bootstrap_introduction(root, child, parent_commit, child_commit):
                continue
        elif status in {"Accepted", "Rejected"} and _qualified_historical_introduction(root, child, parent_commit, child_commit):
            continue
        errors.append(_adr_error(path, f"new ADR must be Proposed; unqualified direct introduction as {status or 'unknown'} is forbidden"))
    return sorted(set(errors))

def validate_accepted_adr_immutability(root: Path, base_ref: str, head_ref: str | None = None) -> list[str]:
    base_commit, error = _resolve_commit(root, base_ref)
    if error:
        return [error]
    head_commit = None
    if head_ref is not None:
        head_commit, error = _resolve_commit(root, head_ref)
        if error:
            return [error]
    return _validate_adr_edge(root, base_commit, head_commit)

def validate_accepted_adr_edge_range(root: Path, range_base: str, range_head: str) -> list[str]:
    base_commit, base_error = _resolve_commit(root, range_base)
    head_commit, head_error = _resolve_commit(root, range_head)
    errors = [error for error in (base_error, head_error) if error]
    if errors:
        return errors
    if _run_git(root, "merge-base", "--is-ancestor", base_commit, head_commit).returncode:
        return [f"ADR edge range base is not an ancestor of head: {base_commit}..{head_commit}"]
    history = _run_git(root, "rev-list", "--reverse", "--topo-order", "--parents", f"{base_commit}..{head_commit}")
    if history.returncode:
        return [f"unable to enumerate ADR edge range {base_commit}..{head_commit}"]
    for line in history.stdout.splitlines():
        parts = line.split()
        child, parents = parts[0], parts[1:]
        for parent in parents:
            for diagnostic in _validate_adr_edge(root, parent, child):
                errors.append(f"{parent} -> {child}: {diagnostic}")
    return sorted(set(errors))

Validator = Callable[[Path], list[str]]
VALIDATORS: dict[str, Validator] = {"adrs": validate_adrs, "links": validate_links, "metadata": validate_metadata, "migration": validate_migration_inventory, "structure": validate_structure}

def validate_repository(root: Path, checks: frozenset[str] = CHECKS) -> list[str]:
    errors = []
    for check in sorted(checks):
        if check not in VALIDATORS: errors.append(f"unknown validation check: {check}")
        else: errors.extend(VALIDATORS[check](root))
    return sorted(errors)

def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(); parser.add_argument("--root", type=Path, default=Path(".")); parser.add_argument("--checks")
    parser.add_argument("--adr-base-ref"); parser.add_argument("--adr-head-ref")
    parser.add_argument("--adr-edge-base-ref"); parser.add_argument("--adr-edge-head-ref")
    args = parser.parse_args(argv)
    if bool(args.adr_edge_base_ref) != bool(args.adr_edge_head_ref):
        print("--adr-edge-base-ref and --adr-edge-head-ref must be provided together", file=sys.stderr); return 2
    if args.adr_head_ref and not args.adr_base_ref:
        print("--adr-head-ref requires --adr-base-ref", file=sys.stderr); return 2
    checks = frozenset(args.checks.split(",")) if args.checks else CHECKS
    unknown = sorted(checks - CHECKS)
    if unknown:
        print("unknown validation check(s): " + ", ".join(unknown), file=sys.stderr); return 2
    errors = validate_repository(args.root, checks)
    if args.adr_base_ref:
        errors.extend(validate_accepted_adr_immutability(args.root, args.adr_base_ref, args.adr_head_ref))
    if args.adr_edge_base_ref:
        errors.extend(validate_accepted_adr_edge_range(args.root, args.adr_edge_base_ref, args.adr_edge_head_ref))
    if errors:
        print("\n".join(errors), file=sys.stderr); return 1
    print("architecture validation passed"); return 0

if __name__ == "__main__": raise SystemExit(main())
