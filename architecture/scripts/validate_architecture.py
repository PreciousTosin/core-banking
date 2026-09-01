#!/usr/bin/env python3
import argparse
from collections import Counter, defaultdict
import hashlib
import html
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Callable, Iterator, Sequence
from urllib.parse import unquote, urlsplit

CHECKS = frozenset({"adrs", "archive", "archive-review", "diagrams", "links", "metadata", "migration", "structure", "tooling", "traceability", "workflow"})

MIGRATION_SOURCE = "architecture/modern-core-banking-comprehensive-design-revised.md"
MIGRATION_ARCHIVE_SOURCE = "architecture/archive/modern-core-banking-comprehensive-design-revised.md"
MIGRATION_ARCHIVE_BANNER = "Historical source document — non-authoritative; see /ARCHITECTURE.md and the migration inventory."
MIGRATION_INVENTORY = "architecture/archive/comprehensive-design-migration-inventory.md"
MIGRATION_REVIEW = "architecture/archive/comprehensive-design-migration-review.md"
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
PROPOSAL_IDENTITIES = (
    "account-identifiers-and-nip-inbound",
    "conventional-deposit-products-and-accrual",
    "non-interest-banking-products",
    "full-poc-platform",
    "production-platform",
    "providers-and-reconciliation",
)
PROPOSAL_STATUSES = frozenset({"draft", "proposed", "approved", "implementing", "implemented", "rejected", "superseded"})
ACTIVE_PROPOSAL_STATUSES = PROPOSAL_STATUSES - frozenset({"implemented", "rejected", "superseded"})
TERMINAL_PROPOSAL_STATUSES = frozenset({"implemented", "rejected", "superseded"})
PROPOSAL_REQUIRED_FIELDS = ("title", "status", "owners", "target_release", "related_adrs", "related_plans")
PROPOSAL_PLANS = {
    "account-identifiers-and-nip-inbound": "docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md",
    "conventional-deposit-products-and-accrual": "docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md",
    "non-interest-banking-products": "docs/superpowers/plans/2026-08-30-non-interest-banking-products-implementation.md",
}
PROPOSAL_ADRS = {
    "account-identifiers-and-nip-inbound": ("ADR-0002", "ADR-0004", "ADR-0006", "ADR-0007"),
    "conventional-deposit-products-and-accrual": ("ADR-0002", "ADR-0003", "ADR-0004", "ADR-0005", "ADR-0006"),
    "non-interest-banking-products": ("ADR-0002", "ADR-0003", "ADR-0004", "ADR-0005", "ADR-0006"),
    "full-poc-platform": ("ADR-0001", "ADR-0002", "ADR-0004", "ADR-0006", "ADR-0008"),
    "production-platform": ("ADR-0001", "ADR-0004", "ADR-0008"),
    "providers-and-reconciliation": ("ADR-0002", "ADR-0004", "ADR-0006", "ADR-0007", "ADR-0008"),
}
ACCOUNTING_PLAN = "docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md"
FRAMEWORK_PLAN = "docs/superpowers/plans/2026-09-01-architecture-documentation-and-adr-framework-implementation.md"
GOVERNED_PLANS = (FRAMEWORK_PLAN, ACCOUNTING_PLAN, *PROPOSAL_PLANS.values())
DIAGRAM_FILENAMES = frozenset({
    "containers.mmd",
    "context.mmd",
    "funds-core-components.mmd",
    "posting-sequence.mmd",
    "single-vm-deployment.mmd",
})
DIAGRAM_STATES = frozenset({"CURRENT", "PROPOSED"})
DIAGRAM_METADATA_KEYS = ("state", "abstraction", "question", "owner", "arc42", "adrs", "last_verified")
MERMAID_CLI_PACKAGE = "@mermaid-js/mermaid-cli"
MERMAID_CLI_VERSION = "11.16.0"
RENDER_SCRIPT_SHA256 = "83e5e543e7d6fbe4afb0ea6fce8d94825c81af18e364bd78c5864dc0977363b6"
WORKFLOW_HISTORY_RUN_SHA256 = "ecd9f8a065fcd2e6b3ad83d4d8dd619d4d024497d1cad88c8757da00f73b483e"

REQUIRED_GOVERNANCE_FILES = (
    ".github/pull_request_template.md",
    "ARCHITECTURE.md",
    "architecture/README.md",
    "architecture/adr/README.md",
    "architecture/adr/template.md",
    "architecture/archive/proposals/README.md",
    "architecture/diagrams/README.md",
    "architecture/proposals/README.md",
)
PR_ARCHITECTURE_HEADING = "## Architecture impact"
PR_ARCHITECTURE_CHECKBOXES = (
    "No architecture impact",
    "Architecture changed; linked below",
)
PR_ARCHITECTURE_FIELDS = (
    "Related ADRs:",
    "Current-state arc42 sections changed:",
    "Proposals implemented, invalidated, or superseded:",
    "Diagrams changed:",
    "Verification evidence:",
)
PR_TEMPLATE_LITERALS = (
    PR_ARCHITECTURE_HEADING,
    "- [ ] No architecture impact",
    "- [ ] Architecture changed; linked below",
    *PR_ARCHITECTURE_FIELDS,
)
PR_TEMPLATE_BLOCK = (
    "## Architecture impact\n"
    "- [ ] No architecture impact\n"
    "- [ ] Architecture changed; linked below\n\n"
    "Related ADRs:\n"
    "Current-state arc42 sections changed:\n"
    "Proposals implemented, invalidated, or superseded:\n"
    "Diagrams changed:\n"
    "Verification evidence:"
)

@dataclass(frozen=True)
class MarkdownLink:
    """A prose Markdown destination paired with its source line."""

    destination: str
    line: int

@dataclass(frozen=True)
class MigrationRow:
    """One classified material block from the comprehensive-design migration."""

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
    """A numbered source heading and its deterministic material blocks."""

    source_key: str
    heading: str
    blocks: tuple[str, ...]

@dataclass(frozen=True)
class StaleWarning:
    """A non-blocking age report for a governed current-state artifact."""

    path: Path
    last_verified: date
    age_days: int
    threshold_days: int

def _mask_markdown_code(text: str) -> str:
    chars = list(text)
    offset = 0
    fence_character = None
    fence_length = 0
    for raw_line in text.splitlines(keepends=True):
        line = raw_line.rstrip("\r\n")
        if fence_character:
            closing = re.match(
                rf"^ {{0,3}}{re.escape(fence_character)}{{{fence_length},}}[ \t]*$",
                line,
            )
            for index in range(offset, offset + len(line)):
                chars[index] = " "
            if closing:
                fence_character = None
                fence_length = 0
            offset += len(raw_line)
            continue
        opening = re.fullmatch(r" {0,3}(`{3,}|~{3,})(.*)", line)
        if opening and not (opening.group(1)[0] == "`" and "`" in opening.group(2)):
            fence_character = opening.group(1)[0]
            fence_length = len(opening.group(1))
            for index in range(offset, offset + len(line)):
                chars[index] = " "
            offset += len(raw_line)
            continue
        offset += len(raw_line)
    index = 0
    while index < len(text):
        if text[index] != "`" or chars[index] != "`":
            index += 1
            continue
        run_end = index + 1
        while run_end < len(text) and text[run_end] == "`" and chars[run_end] == "`":
            run_end += 1
        run_length = run_end - index
        search = run_end
        closing_end = None
        while search < len(text):
            if text[search] != "`" or chars[search] != "`":
                search += 1
                continue
            candidate_end = search + 1
            while candidate_end < len(text) and text[candidate_end] == "`" and chars[candidate_end] == "`":
                candidate_end += 1
            if candidate_end - search == run_length:
                closing_end = candidate_end
                break
            search = candidate_end
        if closing_end is None:
            index = run_end
            continue
        for masked_index in range(index, closing_end):
            if text[masked_index] not in "\r\n":
                chars[masked_index] = " "
        index = closing_end
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

def _parse_front_matter_text(text: str) -> dict[str, str | list[str]]:
    lines = text.splitlines()
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

def parse_front_matter(path: Path) -> dict[str, str | list[str]]:
    return _parse_front_matter_text(path.read_text())

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
    masked = _mask(text)
    anchors = set(re.findall(r"\bid\s*=\s*[\"']([^\"']+)[\"']", masked, re.I)); counts = {}
    for heading in re.findall(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$", masked, re.M):
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
    template = root / ".github/pull_request_template.md"
    if template.is_file():
        text = template.read_text()
        for literal in PR_TEMPLATE_LITERALS:
            if text.splitlines().count(literal) != 1:
                errors.append(f".github/pull_request_template.md must contain exactly one literal prompt: {literal}")
        if text.count(PR_TEMPLATE_BLOCK) != 1:
            errors.append(".github/pull_request_template.md must contain the canonical architecture block exactly once and in order")
    return sorted(errors)

def validate_pr_body(body: str) -> list[str]:
    masked = _mask(body)
    lines = masked.splitlines()
    errors = []
    section_starts = [index for index, line in enumerate(lines) if line == PR_ARCHITECTURE_HEADING]
    if len(section_starts) != 1:
        errors.append("pull-request body must contain exactly one canonical ## Architecture impact section")
        return errors

    start = section_starts[0]
    end = len(lines)
    for index in range(start + 1, len(lines)):
        if re.match(r"^#{1,2}\s+\S", lines[index]):
            end = index
            break
    section_lines = lines[start:end]
    outside_lines = lines[:start] + lines[end:]

    selections = {}
    for label in PR_ARCHITECTURE_CHECKBOXES:
        pattern = re.compile(rf"^- \[([ xX])\] {re.escape(label)}$")
        matches = [pattern.fullmatch(line) for line in section_lines]
        matches = [match for match in matches if match]
        occurrence_pattern = re.compile(rf"- \[[ xX]\] {re.escape(label)}")
        occurrences = sum(len(occurrence_pattern.findall(line)) for line in section_lines)
        if len(matches) != 1 or occurrences != 1:
            errors.append(f"pull-request architecture section must contain exactly one checkbox prompt: {label}")
        else:
            selections[label] = matches[0].group(1).casefold() == "x"
        if any(occurrence_pattern.search(line) for line in outside_lines):
            errors.append(f"pull-request body contains canonical checkbox literal outside architecture section: - [ ] {label}")

    values = {}
    section_text = "\n".join(section_lines)
    outside_text = "\n".join(outside_lines)
    for label in PR_ARCHITECTURE_FIELDS:
        count = section_text.count(label)
        field_lines = [line for line in section_lines if line.startswith(label)]
        if count != 1 or len(field_lines) != 1:
            errors.append(f"pull-request architecture section must contain exactly one field label: {label}")
        else:
            values[label] = field_lines[0][len(label):].strip()
        if label in outside_text:
            errors.append(f"pull-request body contains canonical field literal outside architecture section: {label}")

    if len(selections) == len(PR_ARCHITECTURE_CHECKBOXES) and sum(selections.values()) != 1:
        errors.append("pull-request architecture section must select exactly one architecture-impact checkbox")

    changed = selections.get("Architecture changed; linked below", False)
    if changed and len(values) == len(PR_ARCHITECTURE_FIELDS):
        for label in PR_ARCHITECTURE_FIELDS:
            if not values[label]:
                errors.append(f"pull-request architecture field requires a non-empty value: {label}")
        artifacts = [values[label] for label in PR_ARCHITECTURE_FIELDS[:4]]
        if all(value == "None" for value in artifacts):
            errors.append("pull-request architecture change must name at least one affected ADR, arc42 section, proposal, or diagram")
        evidence = values["Verification evidence:"]
        if evidence == "None":
            errors.append("pull-request Verification evidence: must not be None for an architecture change")
    return sorted(set(errors))

def validate_pr_event(path: Path) -> list[str]:
    try:
        event = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return ["pull-request event must contain valid JSON"]
    if not isinstance(event, dict) or not isinstance(event.get("pull_request"), dict):
        return ["pull-request event must contain a pull_request object"]
    body = event["pull_request"].get("body")
    if body is None:
        body = ""
    if not isinstance(body, str):
        return ["pull-request event pull_request.body must be a string or null"]
    return validate_pr_body(body)

def _strip_yaml_comment(value: str) -> str:
    single_quoted = False
    double_quoted = False
    escaped = False
    for index, character in enumerate(value):
        if escaped:
            escaped = False
            continue
        if character == "\\" and double_quoted:
            escaped = True
            continue
        if character == "'" and not double_quoted:
            single_quoted = not single_quoted
            continue
        if character == '"' and not single_quoted:
            double_quoted = not double_quoted
            continue
        if character == "#" and not single_quoted and not double_quoted and (index == 0 or value[index - 1].isspace()):
            return value[:index].rstrip()
    return value.rstrip()

def _split_yaml_pair(value: str) -> tuple[str, str] | None:
    single_quoted = False
    double_quoted = False
    escaped = False
    for index, character in enumerate(value):
        if escaped:
            escaped = False
            continue
        if character == "\\" and double_quoted:
            escaped = True
            continue
        if character == "'" and not double_quoted:
            single_quoted = not single_quoted
            continue
        if character == '"' and not single_quoted:
            double_quoted = not double_quoted
            continue
        if character == ":" and not single_quoted and not double_quoted:
            key = value[:index].strip()
            if key:
                return key, value[index + 1:].strip()
            return None
    return None

def _split_yaml_inline_sequence(value: str) -> list[str] | None:
    if not value.startswith("[") or not value.endswith("]"):
        return None
    items = []
    current = []
    single_quoted = False
    double_quoted = False
    escaped = False
    for character in value[1:-1]:
        if escaped:
            current.append(character)
            escaped = False
            continue
        if character == "\\" and double_quoted:
            current.append(character)
            escaped = True
            continue
        if character == "'" and not double_quoted:
            single_quoted = not single_quoted
            current.append(character)
            continue
        if character == '"' and not single_quoted:
            double_quoted = not double_quoted
            current.append(character)
            continue
        if character == "," and not single_quoted and not double_quoted:
            items.append(_yaml_scalar("".join(current).strip()))
            current = []
        else:
            current.append(character)
    if single_quoted or double_quoted:
        return None
    final = "".join(current).strip()
    if final or items:
        items.append(_yaml_scalar(final))
    return items

def _yaml_scalar(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        return value[1:-1]
    return value

class _WorkflowYamlParser:
    def __init__(self, text: str):
        self.lines = text.splitlines()
        self.errors: list[str] = []

    def _line(self, index: int) -> tuple[int, str] | None:
        raw = self.lines[index]
        prefix = raw[:len(raw) - len(raw.lstrip(" \t"))]
        if "\t" in prefix:
            self.errors.append(f"workflow:{index + 1}: tabs are forbidden in YAML indentation")
            return None
        content = _strip_yaml_comment(raw[len(prefix):])
        if not content:
            return None
        return len(prefix), content

    def _next(self, index: int) -> tuple[int, tuple[int, str] | None]:
        while index < len(self.lines):
            parsed = self._line(index)
            if parsed is not None:
                return index, parsed
            index += 1
        return index, None

    def _duplicate(self, path: str, key: str, line: int) -> None:
        labels = {
            "": "top-level",
            "jobs": "jobs",
            "jobs.architecture-docs": "architecture-docs job",
        }
        label = labels.get(path, f"{path} mapping")
        self.errors.append(f"workflow:{line}: duplicate {label} key: {key}")

    def _key(self, value: str, line: int) -> str:
        normalized = _yaml_scalar(value)
        if normalized != value:
            self.errors.append(f"workflow:{line}: quoted YAML keys are forbidden in governed mappings: {value}")
        return normalized

    def _block(self, index: int, key_indent: int) -> tuple[str, int]:
        end = index
        while end < len(self.lines):
            raw = self.lines[end]
            if raw.strip():
                indent = len(raw) - len(raw.lstrip(" "))
                if "\t" in raw[:len(raw) - len(raw.lstrip(" \t"))] or indent <= key_indent:
                    break
            end += 1
        content = self.lines[index:end]
        indents = [len(line) - len(line.lstrip(" ")) for line in content if line.strip()]
        content_indent = min(indents) if indents else key_indent + 2
        body = "\n".join(line[content_indent:] if line.strip() else "" for line in content)
        return body.rstrip("\n"), end

    def _value(self, value: str, index: int, key_indent: int, path: str):
        if value in {">", ">-", ">+"}:
            self.errors.append(f"workflow: folded block scalar is forbidden at {path}; use literal |")
            return self._block(index, key_indent)
        if value in {"|", "|-", "|+"}:
            return self._block(index, key_indent)
        if value:
            sequence = _split_yaml_inline_sequence(value)
            return (sequence if sequence is not None else _yaml_scalar(value)), index
        next_index, next_line = self._next(index)
        if next_line is None or next_line[0] <= key_indent:
            return {}, index
        if next_line[1].startswith("- "):
            return self._sequence(next_index, next_line[0], path)
        return self._mapping(next_index, next_line[0], path)

    def _mapping(self, index: int, indent: int, path: str, initial: tuple[str, int] | None = None):
        result = {}
        if initial is not None:
            pair = _split_yaml_pair(initial[0])
            if pair is None:
                self.errors.append(f"workflow:{initial[1] + 1}: expected mapping entry")
            else:
                raw_key, value = pair
                key = self._key(raw_key, initial[1] + 1)
                result[key], index = self._value(value, index, indent, f"{path}.{key}" if path else key)
        while True:
            next_index, parsed = self._next(index)
            if parsed is None:
                return result, next_index
            line_indent, content = parsed
            if line_indent < indent or content.startswith("- "):
                return result, next_index
            if line_indent > indent:
                self.errors.append(f"workflow:{next_index + 1}: unexpected indentation in {path or 'top-level'} mapping")
                index = next_index + 1
                continue
            pair = _split_yaml_pair(content)
            if pair is None:
                self.errors.append(f"workflow:{next_index + 1}: expected mapping entry")
                index = next_index + 1
                continue
            raw_key, value = pair
            key = self._key(raw_key, next_index + 1)
            if key in result:
                self._duplicate(path, key, next_index + 1)
            parsed_value, index = self._value(
                value,
                next_index + 1,
                indent,
                f"{path}.{key}" if path else key,
            )
            if key not in result:
                result[key] = parsed_value

    def _sequence(self, index: int, indent: int, path: str):
        result = []
        while True:
            next_index, parsed = self._next(index)
            if parsed is None:
                return result, next_index
            line_indent, content = parsed
            if line_indent < indent:
                return result, next_index
            if line_indent != indent or not content.startswith("- "):
                self.errors.append(f"workflow:{next_index + 1}: malformed sequence in {path}")
                index = next_index + 1
                continue
            item = content[2:].strip()
            if not item:
                value, index = self._value("", next_index + 1, indent, f"{path}[{len(result)}]")
                result.append(value)
                continue
            if _split_yaml_pair(item) is not None:
                value, index = self._mapping(
                    next_index + 1,
                    indent + 2,
                    f"{path}[{len(result)}]",
                    (item, next_index),
                )
                result.append(value)
                continue
            result.append(_yaml_scalar(item))
            index = next_index + 1

    def parse(self) -> tuple[dict[str, object], list[str]]:
        parsed, index = self._mapping(0, 0, "")
        next_index, remaining = self._next(index)
        if remaining is not None:
            self.errors.append(f"workflow:{next_index + 1}: unparsed YAML content")
        return parsed, sorted(set(self.errors))

def _workflow_map(value: object) -> dict[str, object]:
    return value if isinstance(value, dict) else {}

def _workflow_steps(value: object) -> list[dict[str, object]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]

def _workflow_run_values(value: object) -> Iterator[str]:
    if isinstance(value, dict):
        for key, nested in value.items():
            if key == "run" and isinstance(nested, str):
                yield nested
            else:
                yield from _workflow_run_values(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from _workflow_run_values(nested)

def _workflow_step_contract(steps: list[dict[str, object]], errors: list[str]) -> None:
    if len(steps) != 10:
        errors.append("architecture workflow must contain exactly ten ordered architecture-docs steps")
        return
    checkout, setup_python, setup_node = steps[:3]
    allowed_keys = (
        ({"uses", "with"}, "checkout step keys"),
        ({"uses", "with"}, "Python setup step keys"),
        ({"uses", "with"}, "Node setup step keys"),
        ({"run"}, "unit-test step keys"),
        ({"if", "run"}, "pull-request body step keys"),
        ({"run"}, "full-validation step keys"),
        ({"run"}, "tooling step keys"),
        ({"run"}, "render step keys"),
        ({"run"}, "stale-report step keys"),
        ({"name", "shell", "run"}, "history step keys"),
    )
    for step, (allowed, label) in zip(steps, allowed_keys):
        if set(step) != allowed:
            errors.append(f"architecture workflow {label} must be exact")
    if checkout.get("uses") != "actions/checkout@v4" or _workflow_map(checkout.get("with")).get("fetch-depth") != "0":
        errors.append("architecture workflow checkout step must use actions/checkout@v4 with fetch-depth: 0")
    if set(_workflow_map(checkout.get("with"))) != {"fetch-depth"}:
        errors.append("architecture workflow checkout inputs must contain only fetch-depth")
    if setup_python.get("uses") != "actions/setup-python@v5" or _workflow_map(setup_python.get("with")).get("python-version") != "3.12":
        errors.append("architecture workflow must set up Python 3.12 with actions/setup-python@v5")
    if set(_workflow_map(setup_python.get("with"))) != {"python-version"}:
        errors.append("architecture workflow Python setup inputs must contain only python-version")
    if setup_node.get("uses") != "actions/setup-node@v4" or _workflow_map(setup_node.get("with")).get("node-version") != "22":
        errors.append("architecture workflow must set up Node 22 with actions/setup-node@v4")
    if set(_workflow_map(setup_node.get("with"))) != {"node-version"}:
        errors.append("architecture workflow Node setup inputs must contain only node-version")
    commands = (
        "python3 -m unittest discover -s architecture/scripts/tests -p 'test_*.py' -v",
        'python3 architecture/scripts/validate_architecture.py --root . --pr-event "$GITHUB_EVENT_PATH"',
        "python3 architecture/scripts/validate_architecture.py --root .",
        "python3 architecture/scripts/validate_architecture.py --root . --checks tooling",
        "architecture/scripts/render-diagrams.sh",
    )
    diagnostics = (
        "unit tests",
        "pull-request body step",
        "full repository validation",
        "permanent tooling validation",
        "direct executable diagram rendering",
    )
    for index, (command, diagnostic) in enumerate(zip(commands, diagnostics), 3):
        if steps[index].get("run") != command:
            errors.append(f"architecture workflow ordered {diagnostic} command is required")
    if steps[4].get("if") != "github.event_name == 'pull_request'":
        errors.append("architecture workflow pull-request body step must use the exact pull-request event guard")
    stale = str(steps[8].get("run", ""))
    expected_stale = (
        "set -o pipefail\n"
        'python3 architecture/scripts/validate_architecture.py --root . --report-stale --as-of "$(date -u +%F)" | tee -a "$GITHUB_STEP_SUMMARY"'
    )
    if stale != expected_stale:
        if "date -u +%F" not in stale:
            errors.append("architecture workflow stale reporting must use an explicit UTC date")
        else:
            errors.append("architecture workflow stale-report command is required")

def _validate_whitespace_step(step: dict[str, object]) -> list[str]:
    errors = []
    if step.get("name") != "Check changed-tree whitespace" or step.get("shell") != "bash":
        errors.append("architecture workflow changed-tree whitespace step must be the final named bash step")
    run = str(step.get("run", ""))
    if hashlib.sha256(run.encode()).hexdigest() != WORKFLOW_HISTORY_RUN_SHA256:
        errors.append("architecture workflow requires the exact final history shell")
    if not run.startswith("set -euo pipefail\n"):
        errors.append("architecture workflow whitespace step must enable strict bash mode")
    if "git diff-tree --check --root" in run:
        errors.append("architecture workflow unavailable-base fallback forbids tip-only git diff-tree --check --root")
    if re.search(r"(?m)^\s*git diff --check\s*$", run):
        errors.append("architecture workflow forbids a bare working-tree-only git diff --check")
    if "git rev-list --first-parent" in run or "--first-parent" in run:
        errors.append("architecture workflow history walk must use all-parent semantics")
    required_counts = (
        ('git rev-list --reverse --topo-order --parents "$range_base..$range_head"', 1, "ranged all-parent history enumeration"),
        ('git rev-list --reverse --topo-order --parents "$GITHUB_SHA"', 1, "reachable all-parent history enumeration"),
        ('git diff --check "$parent" "$child"', 2, "parent-to-child whitespace checks"),
        ('git diff --check "$empty_tree" "$child"', 2, "empty-tree root whitespace checks"),
        ('python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$parent" --adr-head-ref "$child"', 2, "parent-to-child ADR checks"),
        ('check_proposal_edge "$child" "$child"', 2, "root proposal endpoint and edge checks"),
        ('check_proposal_edge "$parent" "$child"', 2, "parent-to-child proposal endpoint and edge checks"),
        ('python3 architecture/scripts/validate_architecture.py --root . --proposal-base-ref "$edge_base" --proposal-head-ref "$edge_head"', 1, "proposal edge-helper endpoint validation"),
        ('python3 architecture/scripts/validate_architecture.py --root . --proposal-edge-base-ref "$edge_base" --proposal-edge-head-ref "$edge_head"', 1, "proposal edge-helper range validation"),
    )
    for literal, count, label in required_counts:
        if run.count(literal) != count:
            errors.append(f"architecture workflow requires {label} on every enumerated edge")
    required_once = (
        ('base_sha="$(jq -r \'.pull_request.base.sha // empty\' "$GITHUB_EVENT_PATH")"', "pull-request base SHA extraction"),
        ('head_sha="$(jq -r \'.pull_request.head.sha // empty\' "$GITHUB_EVENT_PATH")"', "pull-request head SHA extraction"),
        ('merge_base="$(git merge-base "$base_sha" "$head_sha")"', "pull-request merge-base calculation"),
        ('git diff --check "$merge_base" "$head_sha"', "pull-request endpoint whitespace summary"),
        ('check_ranged_edges "$merge_base" "$head_sha"', "pull-request ranged edge helper"),
        ('python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$merge_base" --adr-head-ref "$head_sha"', "pull-request endpoint ADR comparison"),
        ('python3 architecture/scripts/validate_architecture.py --root . --adr-edge-base-ref "$merge_base" --adr-edge-head-ref "$head_sha"', "pull-request ADR edge range"),
        ('python3 architecture/scripts/validate_architecture.py --root . --proposal-base-ref "$merge_base" --proposal-head-ref "$head_sha"', "pull-request endpoint proposal comparison"),
        ('python3 architecture/scripts/validate_architecture.py --root . --proposal-edge-base-ref "$merge_base" --proposal-edge-head-ref "$head_sha"', "pull-request proposal edge range"),
        ('before_sha="$(jq -r \'.before // empty\' "$GITHUB_EVENT_PATH")"', "push before SHA extraction"),
        ('git merge-base --is-ancestor "$before_sha" "$GITHUB_SHA"', "known-base push ancestry check"),
        ('git diff --check "$before_sha" "$GITHUB_SHA"', "known-base push endpoint whitespace summary"),
        ('check_ranged_edges "$before_sha" "$GITHUB_SHA"', "known-base push ranged edge helper"),
        ('python3 architecture/scripts/validate_architecture.py --root . --adr-base-ref "$before_sha" --adr-head-ref "$GITHUB_SHA"', "push endpoint ADR comparison"),
        ('python3 architecture/scripts/validate_architecture.py --root . --adr-edge-base-ref "$before_sha" --adr-edge-head-ref "$GITHUB_SHA"', "push ADR edge range"),
        ('python3 architecture/scripts/validate_architecture.py --root . --proposal-base-ref "$before_sha" --proposal-head-ref "$GITHUB_SHA"', "push endpoint proposal comparison"),
        ('python3 architecture/scripts/validate_architecture.py --root . --proposal-edge-base-ref "$before_sha" --proposal-edge-head-ref "$GITHUB_SHA"', "push proposal edge range"),
        ('git diff --check "$empty_tree" "$GITHUB_SHA"', "unavailable-base complete-tree summary"),
        ('[[ "$base_sha" =~ $sha_pattern ]]', "pull-request event SHAs validation"),
        ('[[ "$head_sha" =~ $sha_pattern ]]', "pull-request event SHAs validation"),
        ('[[ "$merge_base" =~ $sha_pattern ]]', "pull-request merge-base SHA validation"),
        ('[[ "$GITHUB_SHA" =~ $sha_pattern ]]', "push head SHA validation"),
        ('if [[ "$before_sha" =~ $sha_pattern ]] && [[ "$before_sha" != "$zero_sha" ]] && git cat-file -e "$before_sha^{commit}" 2>/dev/null && git merge-base --is-ancestor "$before_sha" "$GITHUB_SHA"; then', "known-base push guard"),
    )
    for literal, label in required_once:
        if run.count(literal) != 1:
            errors.append(f"architecture workflow requires {label}")
    if "sha_pattern='^[0-9a-f]{40}$'" not in run or run.count("git cat-file -e") < 6:
        errors.append("architecture workflow must validate every event and history commit as an available lowercase full SHA")
    return errors

def validate_workflow_contract(root: Path) -> list[str]:
    path = root / ".github/workflows/architecture-docs.yml"
    if not path.is_file():
        return [".github/workflows/architecture-docs.yml is required"]
    document, errors = _WorkflowYamlParser(path.read_text()).parse()
    if set(document) != {"name", "on", "permissions", "jobs"}:
        errors.append("architecture workflow top-level keys must be exactly name, on, permissions, and jobs")
    if document.get("name") != "Architecture documentation":
        errors.append("architecture workflow name must be Architecture documentation")
    triggers = _workflow_map(document.get("on"))
    pull_request = _workflow_map(triggers.get("pull_request"))
    push = _workflow_map(triggers.get("push"))
    expected_types = ["opened", "synchronize", "reopened", "edited", "ready_for_review"]
    if not triggers:
        errors.append("architecture workflow top-level on mapping is required")
    elif set(triggers) != {"pull_request", "push"}:
        errors.append("architecture workflow top-level on mapping may contain only pull_request and push")
    if pull_request.get("types") != expected_types:
        errors.append("architecture workflow pull_request types must be [opened, synchronize, reopened, edited, ready_for_review]")
    if pull_request and set(pull_request) != {"types"}:
        errors.append("architecture workflow pull_request mapping may contain only types and no path filters")
    if any(key in pull_request for key in ("paths", "paths-ignore")):
        errors.append("architecture workflow pull_request path filters are forbidden")
    if push.get("branches") != ["master"]:
        errors.append("architecture workflow push branches must be [master]")
    if push and set(push) != {"branches"}:
        errors.append("architecture workflow push mapping may contain only branches and no path filters")
    if any(key in push for key in ("paths", "paths-ignore")):
        errors.append("architecture workflow push path filters are forbidden")

    permissions = _workflow_map(document.get("permissions"))
    if permissions != {"contents": "read"}:
        errors.append("architecture workflow top-level permissions must contain only contents: read")
    jobs = _workflow_map(document.get("jobs"))
    if set(jobs) != {"architecture-docs"}:
        errors.append("architecture workflow jobs must contain exactly the architecture-docs job")
    job = _workflow_map(jobs.get("architecture-docs"))
    if not job:
        errors.append("architecture workflow architecture-docs job is required")
        return sorted(set(errors))
    if job.get("runs-on") != "ubuntu-24.04":
        errors.append("architecture workflow architecture-docs job must run on ubuntu-24.04")
    if set(job) != {"runs-on", "steps"}:
        errors.append("architecture workflow architecture-docs job keys must be exactly runs-on and steps")
    raw_steps = job.get("steps")
    steps = _workflow_steps(raw_steps)
    if not isinstance(raw_steps, list) or len(steps) != len(raw_steps):
        errors.append("architecture workflow architecture-docs steps must be ordered mappings")
        return sorted(set(errors))
    _workflow_step_contract(steps, errors)
    if len(steps) == 10:
        errors.extend(_validate_whitespace_step(steps[9]))
    all_runs = "\n".join(_workflow_run_values(document))
    if re.search(r"architecture/tooling/node_modules/|\b(?:npm|npx)\s+", all_runs):
        errors.append("architecture workflow must not invoke npm or depend on repository-local architecture tooling")
    if re.search(r"(?:npm_config_cache|PUPPETEER_CACHE_DIR|XDG_(?:CACHE|CONFIG|DATA)_HOME)=", all_runs):
        errors.append("architecture workflow must not bind npm, Puppeteer, or XDG cache state outside the render script's owned root")
    render = root / "architecture/scripts/render-diagrams.sh"
    if not render.is_file() or not render.stat().st_mode & 0o111:
        errors.append("architecture workflow direct render script must exist and be executable")
    else:
        errors.extend(validate_render_script_contract(root))
    return sorted(set(errors))

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

def _proposal_registry_target(root: Path, identity: str, errors: list[str] | None = None) -> Path | None:
    registry = root / "architecture/proposals/README.md"
    prefix = f"architecture/proposals/README.md#{identity}"
    if not registry.is_file():
        if errors is not None:
            errors.append(f"{prefix}: proposal registry is required")
        return None
    text = registry.read_text()
    section = re.search(r"^## Governed proposal registry\s*$", _mask(text), re.M)
    anchor_lines = _explicit_anchor_lines(text).get(identity, [])
    if len(anchor_lines) != 1:
        if errors is not None:
            errors.append(f"{prefix}: proposal registry anchor must occur exactly once")
        return None
    if section is None:
        if errors is not None:
            errors.append(f"{prefix}: ## Governed proposal registry section is required")
        return None
    section_line = _mask(text)[:section.start()].count("\n")
    next_section = next(
        (index for index, line in enumerate(_mask(text).splitlines()[section_line + 1:], section_line + 1) if re.match(r"^##\s+", line)),
        len(text.splitlines()),
    )
    if not section_line < anchor_lines[0] < next_section:
        if errors is not None:
            errors.append(f"{prefix}: proposal registry anchor must be inside ## Governed proposal registry")
        return None
    pointer = _proposal_registry_pointer(root, identity)
    if pointer is None:
        if errors is not None:
            errors.append(f"{prefix}: exactly one proposal record pointer must occur immediately after the anchor marker block")
        return None
    local = _local_destination(pointer)
    if local is None or local[1]:
        if errors is not None:
            errors.append(f"{prefix}: proposal registry pointer must be a fragment-free local link")
        return None
    destination, _ = local
    if Path(destination).name != f"{identity}.md":
        if errors is not None:
            errors.append(f"{prefix}: proposal registry pointer basename must be {identity}.md")
        return None
    target = (registry.parent / destination).resolve()
    allowed = {
        (root / "architecture/proposals" / f"{identity}.md").resolve(),
        (root / "architecture/archive/proposals" / f"{identity}.md").resolve(),
    }
    if target not in allowed:
        if errors is not None:
            errors.append(f"{prefix}: proposal registry pointer must name the active or archive same-basename record")
        return None
    if not target.is_file():
        if errors is not None:
            errors.append(f"{prefix}: proposal registry pointer target does not exist: {destination}")
        return None
    return target

def _registry_section_bounds(text: str) -> tuple[list[str], int, int] | None:
    lines = _mask_markdown_code(text).splitlines()
    headings = [index for index, line in enumerate(lines) if re.fullmatch(r"## Governed proposal registry\s*", line)]
    if len(headings) != 1:
        return None
    start = headings[0]
    end = next((index for index in range(start + 1, len(lines)) if re.match(r"^##\s+", lines[index])), len(lines))
    return lines, start, end

def _registry_owned_block_end(lines: list[str], anchor_line: int, section_end: int) -> int:
    anchor_re = re.compile(r'^\s*<a\s+id=["\'][^"\']+["\']\s*>\s*</a>\s*$', re.I)
    return next((index for index in range(anchor_line + 1, section_end) if anchor_re.match(lines[index])), section_end)

def _standalone_link_destination(line: str) -> str | None:
    match = re.fullmatch(r"\s*\[[^]]+\]\((?:<([^>]+)>|([^\s)]+))\)\s*", line)
    return (match.group(1) or match.group(2)) if match else None

def _is_proposal_record_pointer(root: Path, destination: str) -> bool:
    local = _local_destination(destination)
    if local is None or local[1]:
        return False
    target = ((root / "architecture/proposals") / local[0]).resolve()
    allowed_parents = {
        (root / "architecture/proposals").resolve(),
        (root / "architecture/archive/proposals").resolve(),
    }
    return target.parent in allowed_parents and target.suffix == ".md" and target.name != "README.md"

def _unexpected_registry_identities(root: Path) -> list[str]:
    registry = root / "architecture/proposals/README.md"
    if not registry.is_file():
        return []
    text = registry.read_text()
    section = _registry_section_bounds(text)
    if section is None:
        return []
    _, start, end = section
    anchors = _explicit_anchor_lines(text)
    errors = []
    for identity, positions in anchors.items():
        for anchor_line in positions:
            if not start < anchor_line < end or identity in PROPOSAL_IDENTITIES:
                continue
            errors.append(f"architecture/proposals/README.md#{identity}: unexpected governed proposal registry identity")
    return errors

def _proposal_locations(root: Path, identity: str) -> tuple[Path, Path, list[Path]]:
    active = root / "architecture/proposals" / f"{identity}.md"
    archive = root / "architecture/archive/proposals" / f"{identity}.md"
    return active, archive, [path for path in (active, archive) if path.is_file()]

def _proposal_values(metadata: dict[str, str | list[str]], field: str) -> list[str] | None:
    value = metadata.get(field)
    if value == "None":
        return None
    if isinstance(value, list) and value and all(item.strip() for item in value):
        return value
    return []

def _replacement_targets(path: Path, value: str | list[str] | None, root: Path) -> tuple[list[Path], str | None]:
    values = value if isinstance(value, list) else [value] if isinstance(value, str) else []
    if not values:
        return [], "must contain one or more local Markdown links"
    targets = []
    for item in values:
        links = extract_markdown_links(item)
        if len(links) != 1 or not re.fullmatch(r"\s*\[[^]]+\]\((?:<[^>]+>|[^\s)]+)\)\s*", item):
            return [], "must contain only local Markdown links"
        local = _local_destination(links[0].destination)
        if local is None:
            return [], "must contain only local Markdown links"
        destination, _ = local
        target = path if not destination else (path.parent / destination).resolve()
        if not target.is_file():
            return [], f"target does not exist: {destination}"
        targets.append(target)
    return targets, None

def _valid_proposal_evidence(root: Path, path: Path, value: str | list[str] | None) -> bool:
    entries = value if isinstance(value, list) else [value] if isinstance(value, str) else []
    if not entries or any(not entry.strip() for entry in entries):
        return False
    local_entries = []
    pull_requests = []
    for entry in entries:
        normalized = entry.strip()
        local = ADR_EVIDENCE_LOCAL_RE.fullmatch(f"- {normalized}")
        if local:
            paths = tuple(item.strip() for item in local.group(3).split(";"))
            if any(not item or Path(item).is_absolute() or ".." in Path(item).parts for item in paths):
                return False
            local_entries.append((local.group(1), local.group(2), paths))
            continue
        pull_request = ADR_EVIDENCE_PR_RE.fullmatch(f"- {normalized}")
        if pull_request:
            pull_requests.append((pull_request.group(1), pull_request.group(2)))
            continue
        return False

    for commit, mode, paths in local_entries:
        if _run_git(root, "cat-file", "-e", f"{commit}^{{commit}}").returncode:
            return False
        changed = _changed_paths(root, commit) if mode == "changed" else None
        if mode == "changed" and changed is None:
            return False
        for evidence_path in paths:
            if _run_git(root, "cat-file", "-e", f"{commit}:{evidence_path}").returncode:
                return False
            if mode == "changed" and evidence_path not in changed:
                return False

    origin = _github_origin(root)
    for owner, repository in pull_requests:
        if origin != (owner.casefold(), repository.casefold()):
            return False
    return True

def _validate_proposal_document(path: Path, identity: str, archived: bool, root: Path) -> list[str]:
    metadata = parse_front_matter(path)
    errors = []
    for field in PROPOSAL_REQUIRED_FIELDS:
        if field not in metadata:
            errors.append(_metadata_error(path, root, f"{field} is required"))
    for field in ("title", "owners", "target_release"):
        if field in metadata and not _has_values(metadata[field]):
            errors.append(_metadata_error(path, root, f"{field} must not be empty"))
    status = metadata.get("status")
    if status not in PROPOSAL_STATUSES:
        errors.append(_metadata_error(path, root, f"invalid proposal status: {status or 'missing'}"))
    elif archived and status not in TERMINAL_PROPOSAL_STATUSES:
        errors.append(_metadata_error(path, root, f"status {status} is not terminal"))
    elif not archived and status not in ACTIVE_PROPOSAL_STATUSES:
        errors.append(_metadata_error(path, root, f"terminal status {status} belongs in architecture/archive/proposals/"))
    adrs = _proposal_values(metadata, "related_adrs")
    if not adrs:
        errors.append(_metadata_error(path, root, "related_adrs must contain one or more ADR IDs"))
    else:
        known = _adr_ids(root)
        for adr in adrs:
            if not re.fullmatch(r"ADR-\d{4}", adr) or adr not in known:
                errors.append(_metadata_error(path, root, f"related_adrs ID does not exist: {adr}"))
    plans = _proposal_values(metadata, "related_plans")
    if plans == []:
        errors.append(_metadata_error(path, root, "related_plans must be a non-empty list or literal None"))
    elif plans:
        for plan in plans:
            if plan.startswith("/") or ".." in Path(plan).parts or not (root / plan).is_file():
                errors.append(_metadata_error(path, root, f"related_plans path does not exist: {plan}"))
    expected_plan = PROPOSAL_PLANS.get(identity)
    if expected_plan and plans != [expected_plan]:
        errors.append(_metadata_error(path, root, f"related_plans must retain the governed delivery plan {expected_plan}"))
    if status in {"implementing", "implemented"} and not plans:
        errors.append(_metadata_error(path, root, f"{status} proposal requires one or more related_plans because delivery began"))
    if status in TERMINAL_PROPOSAL_STATUSES:
        for field in ("implementation_status", "replacement", "implementation_evidence"):
            if field not in metadata:
                errors.append(_metadata_error(path, root, f"terminal proposal {field} is required"))
        implementation_status = metadata.get("implementation_status")
        replacement = metadata.get("replacement")
        if status == "implemented":
            if implementation_status != "Complete":
                errors.append(_metadata_error(path, root, "implemented proposal implementation_status must be Complete"))
            targets, replacement_error = _replacement_targets(path, replacement, root)
            arc42 = (root / "architecture/arc42").resolve()
            if replacement_error or not targets or any(target.parent != arc42 or target.suffix != ".md" for target in targets):
                errors.append(_metadata_error(path, root, f"implemented replacement must link existing architecture/arc42 documents{': ' + replacement_error if replacement_error else ''}"))
        elif status == "superseded":
            if implementation_status != "Not applicable":
                errors.append(_metadata_error(path, root, "superseded proposal implementation_status must be Not applicable"))
            targets, replacement_error = _replacement_targets(path, replacement, root)
            allowed_parents = {(root / "architecture/proposals").resolve(), (root / "architecture/archive/proposals").resolve()}
            if replacement_error or not targets or any(target.parent not in allowed_parents or target.name == path.name for target in targets):
                errors.append(_metadata_error(path, root, f"superseded replacement must link existing proposal records{': ' + replacement_error if replacement_error else ''}"))
        elif status == "rejected":
            if implementation_status != "Not applicable":
                errors.append(_metadata_error(path, root, "rejected proposal implementation_status must be Not applicable"))
            if replacement != "None":
                errors.append(_metadata_error(path, root, "rejected proposal replacement must be literal None"))
        if not _valid_proposal_evidence(root, path, metadata.get("implementation_evidence")):
            errors.append(_metadata_error(path, root, "terminal proposal implementation_evidence must contain valid path-bound local or same-repository pull-request evidence"))
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

    governed_paths = set()
    if (root / "architecture/proposals/README.md").is_file():
        errors.extend(_unexpected_registry_identities(root))
        for identity in PROPOSAL_IDENTITIES:
            active, archive, locations = _proposal_locations(root, identity)
            governed_paths.update(path.resolve() for path in locations)
            if len(locations) != 1:
                errors.append(f"architecture/proposals/README.md#{identity}: proposal registry identity must have one sole active or archive record")
            target = _proposal_registry_target(root, identity, errors)
            if target is not None and (len(locations) != 1 or target != locations[0].resolve()):
                errors.append(f"architecture/proposals/README.md#{identity}: proposal registry pointer must resolve to the sole record")
            for path in locations:
                errors.extend(_validate_proposal_document(path, identity, path == archive, root))
    for directory, archived in ((root / "architecture/proposals", False), (root / "architecture/archive/proposals", True)):
        if not directory.is_dir():
            continue
        for path in sorted(directory.rglob("*.md")):
            if path.name == "README.md" or path.resolve() in governed_paths:
                continue
            errors.append(_metadata_error(path, root, "unexpected governed proposal record; exactly six same-basename records are allowed"))
            errors.extend(_validate_proposal_document(path, path.stem, archived, root))
            status = parse_front_matter(path).get("status")
            if archived and status not in TERMINAL_PROPOSAL_STATUSES:
                errors.append(_metadata_error(path, root, f"status {status or 'missing'} is not terminal"))
            elif not archived and status in TERMINAL_PROPOSAL_STATUSES:
                errors.append(_metadata_error(path, root, f"terminal status {status} belongs in architecture/archive/proposals/"))
    return sorted(errors)

def _diagram_error(path: Path, root: Path, message: str) -> str:
    return f"{path.relative_to(root).as_posix()}: {message}"

def _diagram_metadata(path: Path, root: Path) -> tuple[dict[str, str], list[str]]:
    lines = path.read_text().splitlines()
    errors = []
    if len(lines) < 10 or lines[0].strip() != "---" or lines[2].strip() != "---":
        return {}, [_diagram_error(path, root, "first ten lines must begin with Mermaid YAML front matter and seven metadata comments")]
    title = re.fullmatch(r"title:\s*(.+)", lines[1].strip())
    if not title:
        errors.append(_diagram_error(path, root, "front matter title is required"))
    values = {"title": title.group(1) if title else ""}
    for offset, key in enumerate(DIAGRAM_METADATA_KEYS, 3):
        match = re.fullmatch(rf"%%\s+{re.escape(key)}:\s*(.*)", lines[offset])
        if not match:
            errors.append(_diagram_error(path, root, f"metadata comment {key} is required in the first ten lines"))
        else:
            values[key] = match.group(1).strip()
    return values, errors

def _adr_ids(root: Path) -> set[str]:
    directory = root / "architecture/adr"
    if not directory.is_dir():
        return set()
    return {f"ADR-{path.name[:4]}" for path in directory.glob("[0-9][0-9][0-9][0-9]-*.md")}

def validate_render_script_contract(root: Path) -> list[str]:
    path = root / "architecture/scripts/render-diagrams.sh"
    if not path.is_file():
        return ["architecture/scripts/render-diagrams.sh is required"]
    text = path.read_text()
    errors = []
    actual_digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual_digest != RENDER_SCRIPT_SHA256:
        errors.append(
            "architecture/scripts/render-diagrams.sh: content differs from the exact reviewed script "
            f"(expected sha256 {RENDER_SCRIPT_SHA256}, got {actual_digest}); review every script byte and "
            "the unsafe-mutation tests before updating RENDER_SCRIPT_SHA256"
        )
    required = (
        "#!/usr/bin/env bash\nset -euo pipefail",
        'temp_root="$(mktemp -d)"',
        'install_dir="$temp_root/install"',
        '"$temp_root/npm-cache"',
        '"$temp_root/puppeteer-cache"',
        '"$temp_root/xdg-cache"',
        '"$temp_root/xdg-config"',
        '"$temp_root/xdg-data"',
        'cp -- "$tooling_dir/package.json" "$tooling_dir/package-lock.json" "$install_dir/"',
        'env "${owned_env[@]}" npm ci --prefix "$install_dir"',
        'env "${owned_env[@]}" "$mmdc"',
        'rm -rf -- "$temp_root"',
    )
    for required_text in required:
        if required_text not in text:
            errors.append(f"architecture/scripts/render-diagrams.sh: isolated render contract is missing {required_text}")
    for variable in ("npm_config_cache", "PUPPETEER_CACHE_DIR", "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME"):
        if text.count(f'"{variable}=$temp_root/') != 1:
            errors.append(f"architecture/scripts/render-diagrams.sh: {variable} must be bound exactly once below temp_root")
    if text.count("trap cleanup EXIT") != 1:
        errors.append("architecture/scripts/render-diagrams.sh: exactly one cleanup trap is required")
    if re.search(r"architecture/tooling/node_modules|--prefix\s+architecture/tooling|~/(?:\.npm|\.cache)|\$HOME/(?:\.npm|\.cache)", text):
        errors.append("architecture/scripts/render-diagrams.sh: must not use repository-local dependencies or user caches")
    cleanup_match = re.search(r"cleanup\s*\(\)\s*\{(?P<body>.*?)\n\}", text, re.S)
    if not cleanup_match or re.sub(r"\s+", " ", cleanup_match.group("body")).strip() != 'rm -rf -- "$temp_root"':
        errors.append("architecture/scripts/render-diagrams.sh: cleanup may remove only the invocation-owned temp_root")
    return sorted(errors)

def validate_diagrams(root: Path) -> list[str]:
    errors = []
    directory = root / "architecture/diagrams"
    found = {path.name for path in directory.glob("*.mmd")} if directory.is_dir() else set()
    for name in sorted(DIAGRAM_FILENAMES - found):
        errors.append(f"architecture/diagrams/{name} is required")
    for name in sorted(found - DIAGRAM_FILENAMES):
        errors.append(f"unexpected diagram source: architecture/diagrams/{name}")
    existing_adrs = _adr_ids(root)
    for name in sorted(found & DIAGRAM_FILENAMES):
        path = directory / name
        metadata, metadata_errors = _diagram_metadata(path, root)
        errors.extend(metadata_errors)
        state = metadata.get("state", "")
        if state not in DIAGRAM_STATES:
            errors.append(_diagram_error(path, root, "state must be CURRENT or PROPOSED"))
        for key in ("abstraction", "question", "owner", "arc42", "adrs"):
            if not metadata.get(key, ""):
                errors.append(_diagram_error(path, root, f"{key} must not be empty"))
        verified = metadata.get("last_verified", "")
        try:
            valid_date = bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}", verified)) and date.fromisoformat(verified)
        except ValueError:
            valid_date = False
        if not valid_date:
            errors.append(_diagram_error(path, root, "last_verified must use ISO YYYY-MM-DD"))
        if state and state not in metadata.get("title", ""):
            errors.append(_diagram_error(path, root, "front matter title must contain the metadata state"))
        arc42_name = metadata.get("arc42", "")
        arc42_rel = Path(arc42_name)
        arc42_directory = (root / "architecture/arc42").resolve()
        valid_arc42_path = (
            bool(arc42_name)
            and arc42_name == arc42_rel.as_posix()
            and not arc42_rel.is_absolute()
            and arc42_rel.suffix == ".md"
            and len(arc42_rel.parts) >= 3
            and arc42_rel.parts[:2] == ("architecture", "arc42")
            and ".." not in arc42_rel.parts
        )
        arc42_path = (root / arc42_rel).resolve() if valid_arc42_path else root / "architecture/arc42/__invalid__"
        if not valid_arc42_path:
            errors.append(_diagram_error(path, root, "arc42 must be an exact repository-relative Markdown path under architecture/arc42/"))
        elif not arc42_path.is_relative_to(arc42_directory) or not arc42_path.is_file():
            errors.append(_diagram_error(path, root, f"arc42 path does not exist: {arc42_name or 'missing'}"))
        else:
            destinations = extract_markdown_destinations(arc42_path.read_text())
            targets = {(arc42_path.parent / _local_destination(destination)[0]).resolve() for destination in destinations if _local_destination(destination) is not None}
            if path.resolve() not in targets:
                errors.append(_diagram_error(path, root, f"declared arc42 section must link back to {path.relative_to(root).as_posix()}"))
        ids = [value.strip() for value in metadata.get("adrs", "").split(",") if value.strip()]
        if not ids or any(identifier not in existing_adrs for identifier in ids):
            errors.append(_diagram_error(path, root, "adrs must name existing ADR IDs"))
    script = root / "architecture/scripts/render-diagrams.sh"
    if not script.is_file():
        errors.append("architecture/scripts/render-diagrams.sh is required")
    elif not script.stat().st_mode & 0o111:
        errors.append("architecture/scripts/render-diagrams.sh must be executable")
    errors.extend(validate_render_script_contract(root))
    return sorted(set(errors))

def _verified_dates(root: Path) -> Iterator[tuple[Path, date]]:
    arc42 = root / "architecture/arc42"
    for name in sorted(ARC42_FILENAMES):
        path = arc42 / name
        if not path.is_file():
            continue
        verified = parse_front_matter(path).get("last_verified")
        if not isinstance(verified, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", verified):
            continue
        try:
            parsed = date.fromisoformat(verified)
        except ValueError:
            continue
        yield path, parsed

    diagrams = root / "architecture/diagrams"
    for name in sorted(DIAGRAM_FILENAMES):
        path = diagrams / name
        if not path.is_file():
            continue
        verified = _diagram_metadata(path, root)[0].get("last_verified", "")
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", verified):
            continue
        try:
            parsed = date.fromisoformat(verified)
        except ValueError:
            continue
        yield path, parsed

def report_stale(root: Path, as_of: date, threshold_days: int = 90) -> list[StaleWarning]:
    warnings = []
    for path, last_verified in _verified_dates(root):
        age_days = (as_of - last_verified).days
        if age_days > threshold_days:
            warnings.append(StaleWarning(path, last_verified, age_days, threshold_days))
    return sorted(warnings, key=lambda warning: warning.path.relative_to(root).as_posix())

def validate_stale_dates(root: Path, as_of: date) -> list[str]:
    errors = []
    for path, last_verified in _verified_dates(root):
        if last_verified > as_of:
            relative = path.relative_to(root).as_posix()
            errors.append(
                f"{relative}: last_verified {last_verified.isoformat()} is in the future relative to {as_of.isoformat()}"
            )
    return sorted(errors)

def _load_json(path: Path, label: str, errors: list[str]) -> object | None:
    if not path.is_file():
        errors.append(f"{label} is required")
        return None
    try:
        return json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        errors.append(f"{label} must contain valid JSON")
        return None

def _tracked_paths(root: Path) -> list[str]:
    result = subprocess.run(["git", "ls-files", "-z"], cwd=root, text=False, capture_output=True)
    return result.stdout.decode().split("\0")[:-1] if result.returncode == 0 else []

def validate_tooling(root: Path) -> list[str]:
    errors = []
    package = _load_json(root / "architecture/tooling/package.json", "architecture/tooling/package.json", errors)
    lock = _load_json(root / "architecture/tooling/package-lock.json", "architecture/tooling/package-lock.json", errors)
    expected_package = {
        "name": "core-banking-architecture-tooling", "private": True, "version": "1.0.0",
        "engines": {"node": ">=20"}, "devDependencies": {MERMAID_CLI_PACKAGE: MERMAID_CLI_VERSION},
    }
    if package is not None and package != expected_package:
        errors.append("architecture/tooling/package.json must contain only the exact Mermaid CLI development pin")
    if isinstance(lock, dict):
        packages = lock.get("packages")
        root_package = packages.get("") if isinstance(packages, dict) else None
        resolved = packages.get(f"node_modules/{MERMAID_CLI_PACKAGE}") if isinstance(packages, dict) else None
        if not isinstance(root_package, dict) or root_package.get("devDependencies") != {MERMAID_CLI_PACKAGE: MERMAID_CLI_VERSION}:
            errors.append("architecture/tooling/package-lock.json root metadata must declare the exact Mermaid CLI pin")
        if not isinstance(resolved, dict) or resolved.get("version") != MERMAID_CLI_VERSION:
            errors.append("architecture/tooling/package-lock.json must resolve Mermaid CLI exactly 11.16.0")
    elif lock is not None:
        errors.append("architecture/tooling/package-lock.json must be an object")
    tracked = _tracked_paths(root)
    for path in tracked:
        if path.startswith("architecture/tooling/node_modules/") or path.startswith("architecture/diagrams/generated/"):
            errors.append(f"tracked generated or dependency path: {path}")
    for svg in (path for path in tracked if path.startswith("architecture/") and path.endswith(".svg") and not path.startswith("architecture/diagrams/generated/")):
        marked = False
        for markdown in iter_governed_markdown(root):
            rel = markdown.relative_to(root).as_posix()
            text = markdown.read_text()
            marker = f"<!-- approved-architecture-derivative: {svg} source="
            for line in text.splitlines():
                match = re.fullmatch(r"<!-- approved-architecture-derivative: (\S+\.svg) source=(\S+\.mmd) -->", line)
                if not match or match.group(1) != svg:
                    continue
                source = root / match.group(2)
                links = extract_markdown_destinations(text)
                targets = {(markdown.parent / _local_destination(destination)[0]).resolve() for destination in links if _local_destination(destination) is not None}
                if source.is_file() and (root / svg).is_file() and (root / svg).resolve() in targets:
                    marked = True
                else:
                    errors.append(f"invalid approved architecture derivative: {svg} in {rel}")
        if not marked:
            errors.append(f"unclassified tracked SVG: {svg}")
    return sorted(set(errors))

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

def _validate_source_preamble(source_text: str, archived: bool = False) -> list[str]:
    first_numbered = re.search(r"^##\s+1\.\s+", source_text, re.M)
    if not first_numbered:
        return [_migration_error("document preamble cannot be delimited because section 1 is missing")]
    raw_lines = source_text[:first_numbered.start()].splitlines()
    material_lines = [(index, line.strip()) for index, line in enumerate(raw_lines) if line.strip()]
    lines = [line for _, line in material_lines]
    expected_prefix = ["# Modern Core Banking System"]
    if archived:
        expected_prefix.append(MIGRATION_ARCHIVE_BANNER)
    expected_prefix.append("## Comprehensive Architecture and Single-VPS Proof-of-Concept Design")
    metadata_labels = ("Status", "Version", "Date", "Base currency", "Audience")
    metadata_start = len(expected_prefix)
    valid = len(lines) == len(expected_prefix) + 6 and lines[:metadata_start] == expected_prefix and lines[-1] == "---"
    valid = valid and all(re.match(rf"^\*\*{re.escape(label)}:\*\*\s+\S", lines[index + metadata_start]) for index, label in enumerate(metadata_labels))
    metadata_positions = [index for index, _ in material_lines[metadata_start:metadata_start + 5]]
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
    anchor_line = anchor_lines[0]
    section = _registry_section_bounds(registry.read_text())
    if section is None:
        section_start, section_end = -1, len(lines)
    else:
        _, section_start, section_end = section
        if not section_start < anchor_line < section_end:
            return None
    block_end = _registry_owned_block_end(lines, anchor_line, section_end)
    index = anchor_line + 1
    marker_re = re.compile(r"^\s*<!--\s*migration-source:\s*[^\s]+\s*-->\s*$")
    while index < len(lines) and marker_re.match(lines[index]):
        index += 1
    if index >= block_end:
        return None
    pointer = re.fullmatch(r"\s*\[[^]]+\]\((?:<([^>]+)>|([^\s)]+))\)\s*", lines[index])
    if not pointer:
        return None
    proposal_pointers = [
        destination
        for line in lines[anchor_line + 1:block_end]
        for destination in [_standalone_link_destination(line)]
        if destination and _is_proposal_record_pointer(root, destination)
    ]
    if len(proposal_pointers) != 1:
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
    if path_name in {MIGRATION_SOURCE, MIGRATION_ARCHIVE_SOURCE}:
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

def select_comprehensive_source(root: Path, all_rows_resolved: bool) -> tuple[Path | None, list[str]]:
    old_source = root / MIGRATION_SOURCE
    archived_source = root / MIGRATION_ARCHIVE_SOURCE
    old_exists = old_source.is_file()
    archived_exists = archived_source.is_file()
    if old_exists and archived_exists:
        return None, [_migration_error("both comprehensive source paths exist; exactly one is required")]
    if not old_exists and not archived_exists:
        return None, [_migration_error("neither comprehensive source path exists; exactly one is required")]
    if archived_exists and not all_rows_resolved:
        return None, [_migration_error("archived comprehensive source requires all migration rows resolved")]
    return (archived_source if archived_exists else old_source), []

def validate_migration_inventory(root: Path) -> list[str]:
    rows, errors = _parse_migration_inventory(root / MIGRATION_INVENTORY)
    all_rows_resolved = bool(rows) and all(row.resolution == "resolved" for row in rows)
    source, source_errors = select_comprehensive_source(root, all_rows_resolved)
    errors.extend(source_errors)
    if source is None:
        return sorted(set(errors))
    source_text = source.read_text()
    headings, source_roots = _material_headings(source_text)
    errors.extend(_validate_source_preamble(source_text, source == root / MIGRATION_ARCHIVE_SOURCE))

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

ARCHIVE_REVIEW_FIELDS = (
    "Reviewed commit",
    "Reviewer",
    "Implementer",
    "Outcome",
    "Unresolved rows",
    "Inventory path",
    "Inventory blob",
)

def _archive_review_error(message: str) -> str:
    return f"{MIGRATION_REVIEW}: {message}"

def _parse_archive_review(path: Path) -> tuple[dict[str, str], list[str]]:
    if not path.is_file():
        return {}, [_archive_review_error("migration review is required")]
    values = defaultdict(list)
    for line in path.read_text().splitlines():
        match = re.match(r"^- ([^:]+):\s*(.*)$", line)
        if match and match.group(1) in ARCHIVE_REVIEW_FIELDS:
            values[match.group(1)].append(match.group(2).strip())
    errors = []
    fields = {}
    for field in ARCHIVE_REVIEW_FIELDS:
        occurrences = values[field]
        if len(occurrences) != 1:
            errors.append(_archive_review_error(f"{field} must occur exactly once"))
        else:
            fields[field] = occurrences[0]
    return fields, errors

def _run_git_bytes(root: Path, *args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", "-C", str(root), *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )

def validate_archive_review(root: Path) -> list[str]:
    fields, errors = _parse_archive_review(root / MIGRATION_REVIEW)
    if len(fields) != len(ARCHIVE_REVIEW_FIELDS):
        return sorted(set(errors))

    reviewer = fields["Reviewer"]
    implementer = fields["Implementer"]
    if not reviewer:
        errors.append(_archive_review_error("Reviewer must be non-empty"))
    if not implementer:
        errors.append(_archive_review_error("Implementer must be non-empty"))
    if reviewer and reviewer == implementer:
        errors.append(_archive_review_error("Reviewer and Implementer must be distinct"))
    if fields["Outcome"] != "APPROVED":
        errors.append(_archive_review_error("Outcome must be literal APPROVED"))
    try:
        unresolved_rows = int(fields["Unresolved rows"])
    except ValueError:
        unresolved_rows = None
    if unresolved_rows != 0:
        errors.append(_archive_review_error("Unresolved rows must be integer zero"))
    if fields["Inventory path"] != MIGRATION_INVENTORY:
        errors.append(_archive_review_error(f"Inventory path must be {MIGRATION_INVENTORY}"))

    inventory_rows, inventory_errors = _parse_migration_inventory(root / MIGRATION_INVENTORY)
    errors.extend(inventory_errors)
    if any(row.resolution != "resolved" for row in inventory_rows):
        errors.append(_archive_review_error("current inventory has unresolved rows"))

    reviewed_commit = fields["Reviewed commit"]
    inventory_blob = fields["Inventory blob"]
    commit_valid = re.fullmatch(r"[0-9a-f]{40}", reviewed_commit) is not None
    blob_valid = re.fullmatch(r"[0-9a-f]{40}", inventory_blob) is not None
    if not commit_valid:
        errors.append(_archive_review_error("Reviewed commit must be lowercase 40-hex"))
    if not blob_valid:
        errors.append(_archive_review_error("Inventory blob must be lowercase 40-hex"))

    commit_exists = commit_valid and _run_git(root, "cat-file", "-e", f"{reviewed_commit}^{{commit}}").returncode == 0
    blob_exists = blob_valid and _run_git(root, "cat-file", "-e", f"{inventory_blob}^{{blob}}").returncode == 0
    if commit_valid and not commit_exists:
        errors.append(_archive_review_error("Reviewed commit does not exist"))
    if blob_valid and not blob_exists:
        errors.append(_archive_review_error("Inventory blob does not exist"))

    reviewed_inventory_blob = None
    reviewed_inventory = None
    if commit_exists:
        resolved_blob = _run_git(root, "rev-parse", "--verify", f"{reviewed_commit}:{MIGRATION_INVENTORY}")
        if resolved_blob.returncode or not re.fullmatch(r"[0-9a-f]{40}", resolved_blob.stdout.strip()):
            errors.append(_archive_review_error("Reviewed commit does not contain the inventory path"))
        else:
            reviewed_inventory_blob = resolved_blob.stdout.strip()
            shown = _run_git_bytes(root, "show", f"{reviewed_commit}:{MIGRATION_INVENTORY}")
            if shown.returncode:
                errors.append(_archive_review_error("Reviewed commit inventory bytes cannot be read"))
            else:
                reviewed_inventory = shown.stdout
        old_at_review = _run_git(root, "cat-file", "-e", f"{reviewed_commit}:{MIGRATION_SOURCE}").returncode == 0
        archive_at_review = _run_git(root, "cat-file", "-e", f"{reviewed_commit}:{MIGRATION_ARCHIVE_SOURCE}").returncode == 0
        if not old_at_review or archive_at_review:
            errors.append(_archive_review_error("Reviewed commit must be the pre-cutover comprehensive source state"))
    if reviewed_inventory_blob is not None and reviewed_inventory_blob != inventory_blob:
        errors.append(_archive_review_error("Inventory blob does not match reviewed commit inventory"))

    inventory_path = root / MIGRATION_INVENTORY
    if reviewed_inventory is not None and inventory_path.is_file() and inventory_path.read_bytes() != reviewed_inventory:
        errors.append(_archive_review_error("current filesystem inventory differs from reviewed inventory"))
    head_inventory = _run_git_bytes(root, "show", f"HEAD:{MIGRATION_INVENTORY}")
    if head_inventory.returncode:
        errors.append(_archive_review_error("current committed inventory cannot be read at HEAD"))
    elif reviewed_inventory is not None and head_inventory.stdout != reviewed_inventory:
        errors.append(_archive_review_error("current committed inventory differs from reviewed inventory"))

    head = _run_git(root, "rev-parse", "--verify", "HEAD^{commit}")
    head_commit = head.stdout.strip() if head.returncode == 0 else ""
    tracked = _run_git(root, "ls-files", "--error-unmatch", "--", MIGRATION_REVIEW).returncode == 0
    if not tracked:
        if commit_valid and reviewed_commit != head_commit:
            errors.append(_archive_review_error("Reviewed commit must equal HEAD before review evidence is tracked"))
    else:
        introduction = _run_git(root, "log", "--diff-filter=A", "--format=%H", "--", MIGRATION_REVIEW)
        introduction_commits = [line for line in introduction.stdout.splitlines() if line]
        if introduction.returncode or len(introduction_commits) != 1:
            errors.append(_archive_review_error("review evidence must have one unique introduction commit"))
        else:
            parents = _run_git(root, "rev-list", "--parents", "-n", "1", introduction_commits[0])
            parent_parts = parents.stdout.split()
            if parents.returncode or len(parent_parts) != 2:
                errors.append(_archive_review_error("review evidence introduction commit must have one parent"))
            elif commit_valid and reviewed_commit != parent_parts[1]:
                errors.append(_archive_review_error("Reviewed commit must equal the review evidence introduction parent"))
    return sorted(set(errors))

def validate_archive_state(root: Path) -> list[str]:
    rows, errors = _parse_migration_inventory(root / MIGRATION_INVENTORY)
    if not rows and not errors:
        errors.append(_migration_error("migration inventory must contain at least one row for archive state"))
    invalid_resolutions = [row for row in rows if row.resolution not in MIGRATION_RESOLUTIONS]
    for row in invalid_resolutions:
        errors.append(_migration_error(f"unsupported resolution {row.resolution or 'empty'} for {row.source_key}"))
    all_rows_resolved = bool(rows) and not invalid_resolutions and all(row.resolution == "resolved" for row in rows)
    source, source_errors = select_comprehensive_source(root, all_rows_resolved)
    errors.extend(source_errors)
    if source == root / MIGRATION_ARCHIVE_SOURCE:
        errors.extend(validate_archive_review(root))
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
    """A parsed ADR together with the bytes protected across lifecycle edges."""

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
            elif value != "None" and ";" in value and "; ".join(_relationship_sequence(value)) != value:
                errors.append(_adr_error(path, f"{field} must use canonical '; ' separators"))
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

def validate_proposal_bootstrap(root: Path) -> list[str]:
    """Assert the one-time Task 7 state in which all governed records are active."""
    errors = []
    for identity in PROPOSAL_IDENTITIES:
        active, archive, _ = _proposal_locations(root, identity)
        target = _proposal_registry_target(root, identity, errors)
        if not active.is_file():
            errors.append(f"architecture/proposals/{identity}.md: active bootstrap proposal is required")
        if archive.is_file():
            errors.append(f"architecture/archive/proposals/{identity}.md: archive record is forbidden during proposal bootstrap")
        if target is not None and target != active.resolve():
            errors.append(f"architecture/proposals/README.md#{identity}: bootstrap pointer must resolve to the active record")
    return sorted(errors)

@dataclass(frozen=True)
class ProposalSnapshot:
    """The lifecycle fields compared for one governed proposal identity."""

    identity: str
    path: str
    status: str
    related_plans: tuple[str, ...]

def _proposal_snapshot(identity: str, path: str, text: str) -> ProposalSnapshot:
    metadata = _parse_front_matter_text(text)
    plans = _proposal_values(metadata, "related_plans")
    return ProposalSnapshot(identity, path, str(metadata.get("status", "")), tuple(plans or ()))

def _proposal_snapshots_commit(root: Path, commit: str) -> tuple[dict[str, ProposalSnapshot], list[str]]:
    snapshots = {}
    errors = []
    for identity in PROPOSAL_IDENTITIES:
        paths = (
            f"architecture/proposals/{identity}.md",
            f"architecture/archive/proposals/{identity}.md",
        )
        found = []
        for path in paths:
            result = _run_git(root, "show", f"{commit}:{path}")
            if result.returncode == 0:
                found.append(_proposal_snapshot(identity, path, result.stdout))
        if len(found) > 1:
            errors.append(f"{commit}: proposal {identity} must not have both active and archive records")
        elif found:
            snapshots[identity] = found[0]
    return snapshots, errors

def _proposal_snapshots_filesystem(root: Path) -> tuple[dict[str, ProposalSnapshot], list[str]]:
    snapshots = {}
    errors = []
    for identity in PROPOSAL_IDENTITIES:
        _, _, locations = _proposal_locations(root, identity)
        if len(locations) != 1:
            errors.append(f"filesystem: proposal {identity} must have exactly one active or archive record")
        else:
            path = locations[0]
            rel = path.relative_to(root).as_posix()
            snapshots[identity] = _proposal_snapshot(identity, rel, path.read_text())
    return snapshots, errors

def _validate_proposal_edge(root: Path, parent_commit: str, child_commit: str | None) -> list[str]:
    parent, errors = _proposal_snapshots_commit(root, parent_commit)
    if child_commit is None:
        child, child_errors = _proposal_snapshots_filesystem(root)
        child_label = "filesystem"
    else:
        child, child_errors = _proposal_snapshots_commit(root, child_commit)
        child_label = child_commit
    errors.extend(child_errors)
    for identity in PROPOSAL_IDENTITIES:
        before = parent.get(identity)
        after = child.get(identity)
        if before is None and after is None:
            continue
        if before is None:
            if after.path != f"architecture/proposals/{identity}.md" or after.status not in ACTIVE_PROPOSAL_STATUSES:
                errors.append(
                    f"{parent_commit} -> {child_label}: proposal {identity} must be introduced as an active record"
                )
            continue
        if after is None:
            errors.append(f"{parent_commit} -> {child_label}: proposal {identity} record was deleted without an archive successor")
            continue
        if not _is_prefix(before.related_plans, after.related_plans):
            errors.append(
                f"{parent_commit} -> {child_label}: proposal {identity} related_plans history cannot be erased or reordered"
            )
    return sorted(set(errors))

def validate_proposal_history(root: Path, base_ref: str, head_ref: str | None = None) -> list[str]:
    base_commit, error = _resolve_commit(root, base_ref)
    if error:
        return [error]
    head_commit = None
    if head_ref is not None:
        head_commit, error = _resolve_commit(root, head_ref)
        if error:
            return [error]
    return _validate_proposal_edge(root, base_commit, head_commit)

def validate_proposal_edge_range(root: Path, range_base: str, range_head: str) -> list[str]:
    base_commit, base_error = _resolve_commit(root, range_base)
    head_commit, head_error = _resolve_commit(root, range_head)
    errors = [error for error in (base_error, head_error) if error]
    if errors:
        return errors
    if _run_git(root, "merge-base", "--is-ancestor", base_commit, head_commit).returncode:
        return [f"proposal edge range base is not an ancestor of head: {base_commit}..{head_commit}"]
    history = _run_git(root, "rev-list", "--reverse", "--topo-order", "--parents", f"{base_commit}..{head_commit}")
    if history.returncode:
        return [f"unable to enumerate proposal edge range {base_commit}..{head_commit}"]
    for line in history.stdout.splitlines():
        parts = line.split()
        child, parents = parts[0], parts[1:]
        for parent in parents:
            errors.extend(_validate_proposal_edge(root, parent, child))
    return sorted(set(errors))

def _path_destinations(path: Path, root: Path) -> set[Path]:
    if not path.is_file():
        return set()
    result = set()
    for destination in extract_markdown_destinations(path.read_text()):
        local = _local_destination(destination)
        if local is None:
            continue
        name, _ = local
        target = path.resolve() if not name else (path.parent / name).resolve()
        try:
            target.relative_to(root.resolve())
        except ValueError:
            continue
        result.add(target)
    return result

def _label_destinations(path: Path, label: str) -> set[Path]:
    if not path.is_file():
        return set()
    result = set()
    pattern = re.compile(rf"^\*\*{re.escape(label)}:\*\*\s*(.*)$")
    for line in _mask_markdown_code(path.read_text()).splitlines():
        match = pattern.match(line)
        if not match:
            continue
        for destination in extract_markdown_destinations(match.group(1)):
            local = _local_destination(destination)
            if local is not None:
                name, _ = local
                result.add(path.resolve() if not name else (path.parent / name).resolve())
    return result

def _adr_field_destinations(path: Path, field: str) -> set[Path]:
    if not path.is_file():
        return set()
    result = set()
    prefix = f"- {field}:"
    for line in _mask_markdown_code(path.read_text()).splitlines():
        if not line.startswith(prefix):
            continue
        for destination in extract_markdown_destinations(line[len(prefix):]):
            local = _local_destination(destination)
            if local is not None:
                name, _ = local
                result.add(path.resolve() if not name else (path.parent / name).resolve())
    return result

def _link_resolves(path: Path, destination: str, target: Path, fragment: str = "") -> bool:
    local = _local_destination(destination)
    if local is None:
        return False
    name, actual_fragment = local
    actual_target = path.resolve() if not name else (path.parent / name).resolve()
    return actual_target == target.resolve() and actual_fragment == fragment

def _document_has_link(path: Path, target: Path, fragment: str = "") -> bool:
    return path.is_file() and any(
        _link_resolves(path, destination, target, fragment)
        for destination in extract_markdown_destinations(path.read_text())
    )

def _label_has_link(path: Path, label: str, target: Path, fragment: str = "") -> bool:
    if not path.is_file():
        return False
    pattern = re.compile(rf"^\*\*{re.escape(label)}:\*\*\s*(.*)$")
    return any(
        _link_resolves(path, destination, target, fragment)
        for line in _mask_markdown_code(path.read_text()).splitlines()
        for match in [pattern.match(line)]
        if match
        for destination in extract_markdown_destinations(match.group(1))
    )

def _adr_field_has_link(path: Path, field: str, target: Path, fragment: str = "") -> bool:
    if not path.is_file():
        return False
    prefix = f"- {field}:"
    return any(
        _link_resolves(path, destination, target, fragment)
        for line in _mask_markdown_code(path.read_text()).splitlines()
        if line.startswith(prefix)
        for destination in extract_markdown_destinations(line[len(prefix):])
    )

def _adr_path(root: Path, adr_id: str) -> Path | None:
    matches = sorted((root / "architecture/adr").glob(f"{adr_id[4:]}-*.md"))
    return matches[0] if len(matches) == 1 else None

def _label_bodies(path: Path, label: str) -> list[str]:
    if not path.is_file():
        return []
    pattern = re.compile(rf"^\*\*{re.escape(label)}:\*\*\s*(.*)$")
    return [match.group(1) for line in _mask_markdown_code(path.read_text()).splitlines() for match in [pattern.match(line)] if match]

def _resolved_link_pairs(path: Path, text: str) -> list[tuple[Path, str]]:
    result = []
    for destination in extract_markdown_destinations(text):
        local = _local_destination(destination)
        if local is None:
            continue
        name, fragment = local
        result.append((path.resolve() if not name else (path.parent / name).resolve(), fragment))
    return result

def _document_link_pairs(path: Path) -> list[tuple[Path, str]]:
    return _resolved_link_pairs(path, path.read_text()) if path.is_file() else []

def _exact_label_links(path: Path, label: str, expected: list[tuple[Path, str]], errors: list[str], message: str) -> None:
    bodies = _label_bodies(path, label)
    rel = path.as_posix()
    if len(bodies) != 1:
        errors.append(f"{rel}: exactly one {label} header is required")
        return
    destinations = extract_markdown_destinations(bodies[0])
    if len(destinations) != len(expected) or Counter(_resolved_link_pairs(path, bodies[0])) != Counter(expected):
        errors.append(f"{rel}: {message}")

def _validate_exact_plan_contracts(root: Path) -> list[str]:
    errors = []
    registry = (root / "architecture/proposals/README.md").resolve()
    adr_dir = (root / "architecture/adr").resolve()
    for identity, plan_name in PROPOSAL_PLANS.items():
        plan = root / plan_name
        if not plan.is_file():
            errors.append(f"{plan_name}: governed implementation plan is required")
            continue
        expected_adrs = [(_adr_path(root, adr_id).resolve(), "") for adr_id in PROPOSAL_ADRS[identity] if _adr_path(root, adr_id)]
        _exact_label_links(plan, "Proposal", [(registry, identity)], errors, "Proposal mapping must be exact")
        _exact_label_links(plan, "Related ADRs", expected_adrs, errors, "Related ADRs mapping must be exact")
        all_pairs = _document_link_pairs(plan)
        direct_adrs = [pair for pair in all_pairs if pair[0].parent == adr_dir]
        if Counter(direct_adrs) != Counter(expected_adrs):
            errors.append(f"{plan_name}: direct ADR mapping must be exact")
        stable_links = [pair for pair in all_pairs if pair[0] == registry]
        if Counter(stable_links) != Counter([(registry, identity)]):
            errors.append(f"{plan_name}: extra stable proposal link or missing governed proposal link")

    accounting = root / ACCOUNTING_PLAN
    if accounting.is_file():
        expected_current = [
            ((root / f"architecture/arc42/{name}").resolve(), "")
            for name in ("05-building-block-view.md", "06-runtime-view.md", "08-crosscutting-concepts.md")
        ]
        expected_adrs = [(_adr_path(root, f"ADR-{number:04d}").resolve(), "") for number in range(2, 7) if _adr_path(root, f"ADR-{number:04d}")]
        _exact_label_links(accounting, "Current architecture", expected_current, errors, "Current architecture mapping must be exact")
        _exact_label_links(accounting, "Retrospective ADRs", expected_adrs, errors, "Retrospective ADRs mapping must be exact")
        all_pairs = _document_link_pairs(accounting)
        direct_adrs = [pair for pair in all_pairs if pair[0].parent == adr_dir]
        if Counter(direct_adrs) != Counter(expected_adrs):
            errors.append(f"{ACCOUNTING_PLAN}: direct retrospective ADR mapping must be exact")
        if any(pair[0] == registry for pair in all_pairs):
            errors.append(f"{ACCOUNTING_PLAN}: implemented accounting-kernel plan must not link a stable Proposal identity")
        if _label_bodies(accounting, "Proposal"):
            errors.append(f"{ACCOUNTING_PLAN}: implemented accounting-kernel plan must not have a Proposal backlink")
    return errors

def _validate_reverse_proposal_edges(root: Path) -> list[str]:
    errors = []
    registry = (root / "architecture/proposals/README.md").resolve()
    for adr in sorted((root / "architecture/adr").glob("[0-9][0-9][0-9][0-9]-*.md")):
        adr_id = f"ADR-{adr.name[:4]}"
        lines = [line for line in _mask_markdown_code(adr.read_text()).splitlines() if line.startswith("- Related proposals:")]
        for line in lines:
            value = line.split(":", 1)[1].strip()
            if value == "None":
                continue
            for destination in extract_markdown_destinations(value):
                local = _local_destination(destination)
                if local is None:
                    errors.append(f"{adr.relative_to(root).as_posix()}: Related proposals must use a stable governed proposal identity")
                    continue
                name, identity = local
                target = adr.resolve() if not name else (adr.parent / name).resolve()
                if target != registry or identity not in PROPOSAL_IDENTITIES:
                    errors.append(f"{adr.relative_to(root).as_posix()}: Related proposals names an unknown governed proposal identity: {destination}")
                    continue
                record = _proposal_registry_target(root, identity, errors)
                if record is None:
                    continue
                if adr_id not in (_proposal_values(parse_front_matter(record), "related_adrs") or []):
                    errors.append(f"{adr.relative_to(root).as_posix()}: proposal {identity} metadata does not name {adr_id}")

    plans_dir = root / "docs/superpowers/plans"
    for plan in sorted(plans_dir.glob("*.md")) if plans_dir.is_dir() else []:
        bodies = _label_bodies(plan, "Proposal")
        if not bodies:
            continue
        rel = plan.relative_to(root).as_posix()
        if len(bodies) != 1:
            errors.append(f"{rel}: exactly one Proposal header is required")
            continue
        destinations = extract_markdown_destinations(bodies[0])
        if len(destinations) != 1:
            errors.append(f"{rel}: Proposal header must contain exactly one stable governed proposal identity")
            continue
        local = _local_destination(destinations[0])
        if local is None:
            errors.append(f"{rel}: Proposal header names an unknown governed proposal identity")
            continue
        name, identity = local
        target = plan.resolve() if not name else (plan.parent / name).resolve()
        if target != registry or identity not in PROPOSAL_IDENTITIES:
            errors.append(f"{rel}: Proposal header names an unknown governed proposal identity")
            continue
        record = _proposal_registry_target(root, identity, errors)
        if record is None:
            continue
        if rel not in (_proposal_values(parse_front_matter(record), "related_plans") or []):
            errors.append(f"{rel}: proposal {identity} metadata does not name this plan")
    return errors

def _validate_infrastructure_traceability(root: Path) -> list[str]:
    rel = "architecture/infrastructure/infra-ubuntu24.04-poc.md"
    path = root / rel
    if not path.is_file():
        return [f"{rel}: governed full-PoC infrastructure detail is required"]
    metadata = parse_front_matter(path)
    errors = []
    if metadata.get("status") != "proposed":
        errors.append(f"{rel}: status must be proposed")
    if metadata.get("owners") != ["platform"]:
        errors.append(f"{rel}: owners must contain only platform")
    if metadata.get("related_adrs") != ["ADR-0008"]:
        errors.append(f"{rel}: related_adrs must contain only ADR-0008")
    stable = "architecture/proposals/README.md#full-poc-platform"
    if metadata.get("related_proposals") != [stable]:
        errors.append(f"{rel}: related_proposals must contain only {stable}")
    if "> **Architecture state: PROPOSED — non-current.**" not in path.read_text():
        errors.append(f"{rel}: visible marker PROPOSED — non-current is required")
    proposal = _proposal_registry_target(root, "full-poc-platform", errors)
    if proposal is not None and not _document_has_link(proposal, path):
        errors.append(f"{rel}: full-PoC proposal must link directly to the infrastructure document")
    registry = (root / "architecture/proposals/README.md").resolve()
    if not _document_has_link(path, registry, "full-poc-platform"):
        errors.append(f"{rel}: infrastructure document must link directly to the full-PoC stable identity")
    adr = _adr_path(root, "ADR-0008")
    if adr is None or not _adr_field_has_link(adr, "Related proposals", registry, "full-poc-platform"):
        errors.append(f"{rel}: ADR-0008 Related proposals must link the full-PoC stable identity")
    return errors

def _validate_numbered_source_citations(root: Path) -> list[str]:
    governed = (
        "architecture/infrastructure/infra-ubuntu24.04-poc.md",
        ACCOUNTING_PLAN,
    )
    stale = re.compile(
        r"\]\([^)]*ARCHITECTURE\.md[^)]*\)|\bparent\s+(?:architecture|design)\b|\barchitecture\s+§§?",
        re.I,
    )
    errors = []
    for relative in governed:
        path = root / relative
        if not path.is_file():
            continue
        for line_number, line in enumerate(_mask(path.read_text()).splitlines(), 1):
            if stale.search(line):
                errors.append(f"{relative}:{line_number}: stale numbered-source citation must use a maintained anchor or an explicit non-link historical label")
    return errors

def validate_traceability(root: Path) -> list[str]:
    errors = []
    registry = (root / "architecture/proposals/README.md").resolve()
    for identity in PROPOSAL_IDENTITIES:
        target = _proposal_registry_target(root, identity, errors)
        if target is None:
            continue
        metadata = parse_front_matter(target)
        stable_fragment = f"architecture/proposals/README.md#{identity}"
        adrs = _proposal_values(metadata, "related_adrs") or []
        plans = _proposal_values(metadata, "related_plans")
        if tuple(adrs) != PROPOSAL_ADRS[identity]:
            errors.append(f"{target.relative_to(root).as_posix()}: related_adrs must match the governed {identity} mapping exactly")
        expected_plan = PROPOSAL_PLANS.get(identity)
        if expected_plan and plans != [expected_plan]:
            errors.append(f"{target.relative_to(root).as_posix()}: related_plans must name only {expected_plan}")
        record_destinations = _path_destinations(target, root)
        for adr_id in adrs:
            adr = _adr_path(root, adr_id)
            if adr is None:
                errors.append(f"{target.relative_to(root).as_posix()}: related ADR does not resolve uniquely: {adr_id}")
                continue
            if adr.resolve() not in record_destinations:
                errors.append(f"{target.relative_to(root).as_posix()}: proposal must retain a direct link to {adr_id}")
            if not _adr_field_has_link(adr, "Related proposals", registry, identity):
                errors.append(f"{adr.relative_to(root).as_posix()}: Related proposals must link {stable_fragment}")
        for plan_name in plans or []:
            plan = root / plan_name
            if not plan.is_file():
                errors.append(f"{target.relative_to(root).as_posix()}: related plan does not exist: {plan_name}")
                continue
            if plan.resolve() not in record_destinations:
                errors.append(f"{target.relative_to(root).as_posix()}: proposal must retain a direct link to {plan_name}")
            if not _label_has_link(plan, "Proposal", registry, identity):
                errors.append(f"{plan_name}: proposal backlink must link {stable_fragment}")

    adr_dir = (root / "architecture/adr").resolve()
    for plan_name in GOVERNED_PLANS:
        plan = root / plan_name
        if not plan.is_file():
            continue
        direct_adrs = {path for path in _path_destinations(plan, root) if path.parent == adr_dir and re.match(r"^\d{4}-.*\.md$", path.name)}
        for adr in direct_adrs:
            if plan.resolve() not in _adr_field_destinations(adr, "Related implementation plans"):
                errors.append(f"{plan_name}: direct ADR link has no ADR plan backlink: {adr.relative_to(root).as_posix()}")
        for adr in sorted((root / "architecture/adr").glob("[0-9][0-9][0-9][0-9]-*.md")):
            if plan.resolve() in _adr_field_destinations(adr, "Related implementation plans") and adr.resolve() not in direct_adrs:
                errors.append(f"{adr.relative_to(root).as_posix()}: ADR plan backlink has no direct plan link: {plan_name}")

    accounting = root / ACCOUNTING_PLAN
    if accounting.is_file():
        expected_current = {(root / f"architecture/arc42/{name}").resolve() for name in ("05-building-block-view.md", "06-runtime-view.md", "08-crosscutting-concepts.md")}
        if _label_destinations(accounting, "Current architecture") != expected_current:
            errors.append(f"{ACCOUNTING_PLAN}: Current architecture must link arc42 sections 05, 06, and 08 exactly")
        retrospective = _label_destinations(accounting, "Retrospective ADRs")
        retrospective_ids = {f"ADR-{path.name[:4]}" for path in retrospective if re.match(r"^\d{4}-", path.name)}
        if retrospective_ids != {f"ADR-{number:04d}" for number in range(2, 7)}:
            errors.append(f"{ACCOUNTING_PLAN}: Retrospective ADRs must link ADR-0002 through ADR-0006 exactly")
        if re.search(r"^\*\*Proposal:\*\*", _mask_markdown_code(accounting.read_text()), re.M):
            errors.append(f"{ACCOUNTING_PLAN}: implemented accounting-kernel plan must not have a Proposal backlink")

    errors.extend(_validate_reverse_proposal_edges(root))
    errors.extend(_validate_exact_plan_contracts(root))
    errors.extend(_validate_infrastructure_traceability(root))
    errors.extend(_validate_numbered_source_citations(root))
    return sorted(set(errors))

Validator = Callable[[Path], list[str]]
VALIDATORS: dict[str, Validator] = {
    "adrs": validate_adrs,
    "archive": validate_archive_state,
    "archive-review": validate_archive_review,
    "diagrams": validate_diagrams,
    "links": validate_links,
    "metadata": validate_metadata,
    "migration": validate_migration_inventory,
    "structure": validate_structure,
    "tooling": validate_tooling,
    "traceability": validate_traceability,
    "workflow": validate_workflow_contract,
}

def validate_repository(root: Path, checks: frozenset[str] = CHECKS) -> list[str]:
    errors = []
    for check in sorted(checks):
        if check not in VALIDATORS: errors.append(f"unknown validation check: {check}")
        else: errors.extend(VALIDATORS[check](root))
    return sorted(errors)

def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(); parser.add_argument("--root", type=Path, default=Path(".")); parser.add_argument("--checks")
    parser.add_argument("--pr-event", type=Path)
    parser.add_argument("--report-stale", action="store_true")
    parser.add_argument("--as-of")
    parser.add_argument("--adr-base-ref"); parser.add_argument("--adr-head-ref")
    parser.add_argument("--adr-edge-base-ref"); parser.add_argument("--adr-edge-head-ref")
    parser.add_argument("--proposal-base-ref"); parser.add_argument("--proposal-head-ref")
    parser.add_argument("--proposal-edge-base-ref"); parser.add_argument("--proposal-edge-head-ref")
    args = parser.parse_args(argv)
    if bool(args.adr_edge_base_ref) != bool(args.adr_edge_head_ref):
        print("--adr-edge-base-ref and --adr-edge-head-ref must be provided together", file=sys.stderr); return 2
    if args.adr_head_ref and not args.adr_base_ref:
        print("--adr-head-ref requires --adr-base-ref", file=sys.stderr); return 2
    if bool(args.proposal_edge_base_ref) != bool(args.proposal_edge_head_ref):
        print("--proposal-edge-base-ref and --proposal-edge-head-ref must be provided together", file=sys.stderr); return 2
    if args.proposal_head_ref and not args.proposal_base_ref:
        print("--proposal-head-ref requires --proposal-base-ref", file=sys.stderr); return 2
    if args.report_stale and not args.as_of:
        print("--report-stale requires --as-of YYYY-MM-DD", file=sys.stderr); return 2
    as_of = None
    if args.as_of:
        try:
            as_of = date.fromisoformat(args.as_of)
        except ValueError:
            print("--as-of must use ISO YYYY-MM-DD", file=sys.stderr); return 2
    checks = frozenset(args.checks.split(",")) if args.checks else CHECKS
    unknown = sorted(checks - CHECKS)
    if unknown:
        print("unknown validation check(s): " + ", ".join(unknown), file=sys.stderr); return 2
    errors = validate_repository(args.root, checks)
    if args.pr_event:
        errors.extend(validate_pr_event(args.pr_event))
    if args.adr_base_ref:
        errors.extend(validate_accepted_adr_immutability(args.root, args.adr_base_ref, args.adr_head_ref))
    if args.adr_edge_base_ref:
        errors.extend(validate_accepted_adr_edge_range(args.root, args.adr_edge_base_ref, args.adr_edge_head_ref))
    if args.proposal_base_ref:
        errors.extend(validate_proposal_history(args.root, args.proposal_base_ref, args.proposal_head_ref))
    if args.proposal_edge_base_ref:
        errors.extend(validate_proposal_edge_range(args.root, args.proposal_edge_base_ref, args.proposal_edge_head_ref))
    warnings = report_stale(args.root, as_of) if args.report_stale else []
    if args.report_stale:
        errors.extend(validate_stale_dates(args.root, as_of))
        github_actions = os.environ.get("GITHUB_ACTIONS") == "true"
        for warning in warnings:
            relative = warning.path.relative_to(args.root).as_posix()
            message = f"last_verified {warning.last_verified.isoformat()} is {warning.age_days} days old (threshold: {warning.threshold_days})"
            if github_actions:
                print(f"::warning file={relative}::{message}")
            else:
                print(f"WARNING: {relative}: {message}")
    if errors:
        print("\n".join(errors), file=sys.stderr); return 1
    print("architecture validation passed"); return 0

if __name__ == "__main__": raise SystemExit(main())
