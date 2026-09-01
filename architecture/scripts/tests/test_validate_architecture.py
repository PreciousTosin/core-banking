import tempfile
import unittest
from pathlib import Path

from architecture.scripts import validate_architecture as validator


class ValidatorTest(unittest.TestCase):
    ARC42_FILES = (
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
    )

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, text):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def write_arc42(self, name, *, status="current", owners="  - architecture", last_verified="2026-09-01", code_refs="  - services/funds-core/", replacement=None):
        replacement_line = "" if replacement is None else f"replacement: {replacement}\n"
        return self.write(
            f"architecture/arc42/{name}",
            "---\n"
            f"title: {name}\n"
            f"status: {status}\n"
            f"owners:\n{owners}\n"
            f"last_verified: {last_verified}\n"
            "related_adrs: []\n"
            f"code_refs:\n{code_refs}\n"
            f"{replacement_line}"
            "---\n"
            "# Arc42\n",
        )

    def write_complete_arc42(self):
        self.write("services/funds-core/.keep", "")
        for name in self.ARC42_FILES:
            self.write_arc42(name)

    def write_inventory(self, rows):
        return self.write(
            "architecture/archive/comprehensive-design-migration-inventory.md",
            "| Source key | Source heading | Covered blocks | Disposition | Destination map | Evidence | Rationale | Resolution |\n"
            "|---|---|---|---|---|---|---|---|\n"
            + "\n".join(rows)
            + "\n",
        )

    def write_complete_migration_fixture(self):
        self.write(
            "architecture/modern-core-banking-comprehensive-design-revised.md",
            "# Modern Core Banking System\n\n"
            "## Comprehensive Architecture and Single-VPS Proof-of-Concept Design\n\n"
            "**Status:** Architecture-review revision for PoC approval\n"
            "**Version:** 3.1\n"
            "**Date:** 2026-08-30\n"
            "**Base currency:** NGN (Naira)\n"
            "**Audience:** Architects, engineers, and reviewers\n\n---\n\n"
            + "\n\n".join(
                (
                    f"## {section}. Section {section}\n\nCurrent paragraph.\n\nProposed paragraph."
                    if section == 8
                    else f"## {section}. Section {section}\n\nMaterial paragraph for section {section}."
                )
                for section in range(1, 28)
            )
            + "\n",
        )
        self.write(
            "architecture/proposals/README.md",
            '# Proposals\n<a id="full-poc-platform"></a>\n'
            "<!-- migration-source: 08::02 -->\n"
            "[Full PoC platform](full-poc-platform.md)\n",
        )
        self.write("architecture/proposals/full-poc-platform.md", "# Full PoC platform\n")
        self.write("services/funds-core/README.md", "# Funds core\n")
        rows = [
            "| 00.document-preamble | Document title, status, version, date, currency, and audience preamble | P01; P02; P03 | historical-only | None | None | The source-document identity and revision metadata describe the archived publication itself; no maintained current or proposed destination is appropriate. | resolved |"
        ]
        for section in range(1, 28):
            if section == 8:
                self.write(
                    "architecture/current-08.md",
                    '# Current\n<a id="block-01"></a>\n<!-- migration-source: 08::01 -->\n',
                )
                rows.extend(
                    [
                        "| 08::01 | 8. Section 8 | B01 | current | B01=architecture/current-08.md#block-01 | services/funds-core/README.md | B01 is verified current behavior. | resolved |",
                        "| 08::02 | 8. Section 8 | B02 | proposal | B02=architecture/proposals/README.md#full-poc-platform | None | B02 is an unimplemented design. | resolved |",
                    ]
                )
                continue
            if section == 27:
                self.write(
                    "architecture/adr/README.md",
                    '# Decisions\n<a id="deferred-27"></a>\n<!-- migration-source: 27 -->\n',
                )
                rows.append(
                    "| 27 | 27. Section 27 | B01 | decision | B01=architecture/adr/README.md#deferred-27 | None | B01 requires a governed decision record. | unresolved |"
                )
                continue
            self.write(
                f"architecture/destination-{section:02d}.md",
                f'# Destination {section}\n<a id="source-{section:02d}"></a>\n<!-- migration-source: {section:02d} -->\n',
            )
            rows.append(
                f"| {section:02d} | {section}. Section {section} | B01 | service-detail | B01=architecture/destination-{section:02d}.md#source-{section:02d} | None | B01 belongs in detailed service documentation. | resolved |"
            )
        self.write_inventory(rows)
        return rows

    def reset_migration_fixture(self):
        self.tmp.cleanup()
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        return self.write_complete_migration_fixture()

    def assert_migration_error(self, fragment):
        errors = validator.validate_migration_inventory(self.root)
        self.assertTrue(any(fragment in error for error in errors), errors)

    def test_metadata_requires_exact_arc42_collection(self):
        self.write_complete_arc42()
        self.write("architecture/arc42/unexpected.md", "# Unexpected\n")
        errors = validator.validate_metadata(self.root)
        self.assertTrue(any("unexpected arc42 file: architecture/arc42/unexpected.md" in error for error in errors))

    def test_metadata_rejects_invalid_arc42_status_owner_code_reference_and_date(self):
        self.write_complete_arc42()
        self.write_arc42("01-introduction-and-goals.md", status="proposed")
        self.write_arc42("02-constraints.md", owners="")
        self.write_arc42("03-context-and-scope.md", code_refs="  - missing/source")
        self.write_arc42("04-solution-strategy.md", last_verified="2026-9-1")
        errors = validator.validate_metadata(self.root)
        self.assertTrue(any("01-introduction-and-goals.md: status must be current or deprecated" in error for error in errors))
        self.assertTrue(any("02-constraints.md: owners must not be empty" in error for error in errors))
        self.assertTrue(any("03-context-and-scope.md: code_refs path does not exist: missing/source" in error for error in errors))
        self.assertTrue(any("04-solution-strategy.md: last_verified must use ISO YYYY-MM-DD" in error for error in errors))

    def test_metadata_accepts_deprecated_arc42_with_existing_replacement_link(self):
        self.write_complete_arc42()
        self.write_arc42(
            "01-introduction-and-goals.md",
            status="deprecated",
            replacement="[Replacement](02-constraints.md)",
        )
        self.assertEqual([], validator.validate_metadata(self.root))

    def test_metadata_rejects_invalid_deprecated_arc42_replacements(self):
        cases = {
            "missing": None,
            "empty": "",
            "non-link": "02-constraints.md",
            "missing-target": "[Missing](missing.md)",
            "self": "[Self](01-introduction-and-goals.md)",
        }
        for name, replacement in cases.items():
            with self.subTest(name=name):
                self.tmp.cleanup()
                self.tmp = tempfile.TemporaryDirectory()
                self.root = Path(self.tmp.name)
                self.write_complete_arc42()
                self.write_arc42(
                    "01-introduction-and-goals.md",
                    status="deprecated",
                    replacement=replacement,
                )
                errors = validator.validate_metadata(self.root)
                self.assertTrue(any("01-introduction-and-goals.md: deprecated replacement" in error for error in errors))

    def test_metadata_enforces_proposal_placement_terminal_statuses(self):
        self.write("architecture/proposals/active.md", "---\nstatus: implemented\n---\n# Active\n")
        self.write("architecture/archive/proposals/archive.md", "---\nstatus: proposed\n---\n# Archive\n")
        errors = validator.validate_metadata(self.root)
        self.assertTrue(any("architecture/proposals/active.md: terminal status implemented belongs in architecture/archive/proposals/" in error for error in errors))
        self.assertTrue(any("architecture/archive/proposals/archive.md: status proposed is not terminal" in error for error in errors))

    def test_required_governance_files(self):
        errors = validator.validate_structure(self.root)
        self.assertEqual(
            [
                "ARCHITECTURE.md is required",
                "architecture/README.md is required",
                "architecture/adr/README.md is required",
                "architecture/adr/template.md is required",
                "architecture/archive/proposals/README.md is required",
                "architecture/diagrams/README.md is required",
                "architecture/proposals/README.md is required",
            ],
            errors,
        )

    def test_root_architecture_must_be_fewer_than_180_lines(self):
        required_files = [
            "architecture/README.md",
            "architecture/adr/README.md",
            "architecture/adr/template.md",
            "architecture/archive/proposals/README.md",
            "architecture/diagrams/README.md",
            "architecture/proposals/README.md",
        ]
        for path in required_files:
            self.write(path, "\n")

        self.write("ARCHITECTURE.md", "line\n" * 179)
        self.assertEqual([], validator.validate_structure(self.root))

        self.write("ARCHITECTURE.md", "line\n" * 180)
        self.assertEqual(
            ["ARCHITECTURE.md must contain fewer than 180 lines"],
            validator.validate_structure(self.root),
        )

    def test_relative_link_rejects_a_missing_target(self):
        self.write("ARCHITECTURE.md", "[missing](architecture/missing.md)\n")
        errors = validator.validate_links(self.root)
        self.assertTrue(any("architecture/missing.md does not exist" in e for e in errors))

    def test_nested_links_resolve_from_containing_file(self):
        self.write("architecture/arc42/target file.md", "# Section\n")
        self.write("architecture/guides/nested.md", "[target](<../arc42/target file.md?view=full#section>)\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_escaped_space_link_resolves_from_containing_file(self):
        self.write("architecture/arc42/target file.md", "# Section\n")
        self.write("architecture/guides/nested.md", "[target](../arc42/target\\ file.md#section)\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_missing_same_file_and_cross_file_anchors_fail(self):
        self.write("architecture/target.md", "# Existing\n")
        self.write("architecture/source.md", "# Source\n[local](#missing)\n[remote](target.md#missing)\n")
        errors = validator.validate_links(self.root)
        self.assertTrue(any("source.md#missing" in e for e in errors))
        self.assertTrue(any("target.md#missing" in e for e in errors))

    def test_github_heading_slugs_are_deterministic_for_duplicates(self):
        self.write("architecture/target.md", "# Repeated heading\n## Repeated heading\n## Repeated heading\n")
        self.write("architecture/source.md", "[first](target.md#repeated-heading) [second](target.md#repeated-heading-1) [third](target.md#repeated-heading-2)\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_explicit_html_id_is_a_valid_anchor(self):
        self.write("architecture/target.md", '<a id="stable-destination"></a>\n# Display title\n')
        self.write("architecture/source.md", "[target](target.md#stable-destination)\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_reference_style_link_and_definition_are_resolved(self):
        self.write("architecture/target.md", "# Stable section\n")
        self.write("architecture/source.md", "[target][architecture target]\n\n[architecture target]: <target.md#stable-section> \"Title\"\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_task_list_checkbox_and_ordinary_brackets_are_not_references(self):
        self.write("architecture/source.md", "- [ ] pending\n\nOrdinary [text] remains prose.\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_shortcut_reference_resolves_only_with_a_definition(self):
        self.write("architecture/target.md", "# Stable section\n")
        self.write("architecture/source.md", "See [target].\n\n[target]: target.md#stable-section\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_undefined_full_reference_link_fails(self):
        self.write("architecture/source.md", "[target][missing definition]\n")
        self.assertTrue(any("undefined reference: missing definition" in e for e in validator.validate_links(self.root)))

    def test_undefined_collapsed_reference_link_fails(self):
        self.write("architecture/source.md", "[target][]\n")
        self.assertTrue(any("undefined reference: target" in e for e in validator.validate_links(self.root)))

    def test_duplicate_reference_definitions_fail_deterministically(self):
        self.write("architecture/first.md", "# First\n")
        self.write("architecture/second.md", "# Second\n")
        self.write("architecture/source.md", "[target][id]\n\n[id]: first.md\n[ID]: second.md\n")
        self.assertEqual(["architecture/source.md:4: duplicate reference definition: id (first defined on line 3)"], validator.validate_links(self.root))

    def test_duplicate_definition_lines_are_not_scanned_as_shortcut_links(self):
        self.write("architecture/first.md", "# First\n")
        self.write("architecture/second.md", "# Second\n")
        self.write("architecture/source.md", "[target][id]\n\n[id]: first.md\n[ID]: second.md\n")
        self.assertEqual(["architecture/source.md:4: duplicate reference definition: id (first defined on line 3)"], validator.validate_links(self.root))

    def test_broken_links_inside_fenced_and_inline_code_are_examples(self):
        self.write("architecture/examples.md", "`[inline](missing-inline.md)`\n\n```markdown\n[fenced](missing-fenced.md)\n```\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_broken_links_inside_html_comments_are_examples(self):
        self.write("architecture/examples.md", "<!--\n[commented](missing-commented.md)\n-->\n[real](target.md)\n")
        self.write("architecture/target.md", "# Target\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_destination_extraction_masks_code_and_comments_but_keeps_prose_links(self):
        text = "`[inline](missing-inline.md)`\n```md\n[fenced](missing-fenced.md)\n```\n<!--\n[commented](missing-commented.md)\n-->\n[real](real.md)\n"
        self.assertEqual(["real.md"], validator.extract_markdown_destinations(text))

    def test_all_valid_uri_schemes_are_non_local(self):
        self.write("architecture/source.md", "[ftp](ftp://example.test/file) [telephone](tel:+2348000000000) [custom](bank+ledger:v1/account)\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_windows_drive_paths_remain_local_paths(self):
        self.write("architecture/source.md", "[drive](C:/missing/local.md)\n")
        self.assertTrue(any("C:/missing/local.md does not exist" in e for e in validator.validate_links(self.root)))

    def test_external_file_with_fragment_reports_actionable_error(self):
        external = self.root.parent / "non-repository-target.md"
        external.write_text("# Existing\n")
        try:
            self.write("architecture/source.md", f"[outside]({external}#missing)\n")
            errors = validator.validate_links(self.root)
            self.assertTrue(any("missing" in e and "source.md" in e for e in errors))
        finally:
            external.unlink()

    def test_unknown_cli_check_is_actionable(self):
        self.assertEqual(2, validator.main(["--root", str(self.root), "--checks", "unknown"]))

    def test_link_scan_includes_new_untracked_governed_markdown(self):
        self.write("docs/superpowers/plans/new-task-not-added-to-git.md", "[missing](governed-missing.md)\n")
        self.assertTrue(any("governed-missing.md does not exist" in e for e in validator.validate_links(self.root)))

    def test_link_scan_ignores_unrelated_untracked_markdown(self):
        for path in ("NOTES.md", ".claude/scratch.md", "user-notes/draft.md"):
            self.write(path, "[ignored](missing.md)\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_link_scan_prunes_build_worktree_and_cache_trees(self):
        for path in (".git/objects/example.md", ".worktrees/feature/example.md", ".claude/worktrees/mirror/example.md", "graft/cache/example.md", "architecture/tooling/node_modules/pkg/example.md", "services/funds-core/target/site/example.md", "services/funds-core/docs/build/reports/example.md", "architecture/diagrams/generated/example.md"):
            self.write(path, "[ignored](missing.md)\n")
        self.assertEqual([], validator.validate_links(self.root))

    def test_front_matter_parses_supported_subset(self):
        path = self.write("architecture/example.md", "---\ntitle: Example\nowners:\n  - architecture\nrelated_adrs: []\n---\n# Example\n")
        self.assertEqual({"title": "Example", "owners": ["architecture"], "related_adrs": []}, validator.parse_front_matter(path))

    def test_migration_inventory_requires_sections_one_through_twenty_seven(self):
        self.assertIn("migration", validator.CHECKS)
        self.assertIn("migration", validator.VALIDATORS)
        rows = self.write_complete_migration_fixture()
        errors = validator.validate_migration_inventory(self.root)
        self.assertEqual(1, sum("unresolved migration row" in error for error in errors), errors)
        self.assertTrue(any("unresolved migration row 27" in error for error in errors), errors)
        self.assertFalse(any("missing top-level source root" in error for error in errors), errors)
        self.assertEqual(29, len(rows))

        self.write_inventory([row for row in rows if not row.startswith("| 17 |")])
        self.assert_migration_error("missing top-level source root 17")

    def test_migration_inventory_enforces_reserved_document_preamble(self):
        rows = self.write_complete_migration_fixture()
        cases = {
            "omitted": [row for row in rows if not row.startswith("| 00.document-preamble |")],
            "missing-token": [rows[0].replace("P01; P02; P03", "P01; P02"), *rows[1:]],
            "extra-token": [rows[0].replace("P01; P02; P03", "P01; P02; P03; P04"), *rows[1:]],
            "duplicate-token": [rows[0].replace("P01; P02; P03", "P01; P02; P02; P03"), *rows[1:]],
            "other-zero-key": [*rows, rows[0].replace("00.document-preamble", "00.other")],
            "wrong-disposition": [rows[0].replace("| historical-only |", "| current |"), *rows[1:]],
            "wrong-destination": [rows[0].replace("| None | None |", "| P01=architecture/README.md#architecture | None |"), *rows[1:]],
        }
        for name, changed in cases.items():
            with self.subTest(name=name):
                if name != "omitted":
                    self.reset_migration_fixture()
                self.write_inventory(changed)
                self.assert_migration_error("document preamble")

    def test_migration_source_preamble_requires_contiguous_metadata_lines(self):
        self.write_complete_migration_fixture()
        source = self.root / "architecture/modern-core-banking-comprehensive-design-revised.md"
        source.write_text(source.read_text().replace("**Version:** 3.1\n**Date:**", "**Version:** 3.1\n\n**Date:**"))
        self.assert_migration_error("document preamble must tokenize independently")

    def test_migration_inventory_rejects_duplicate_malformed_and_unsupported_fields(self):
        rows = self.write_complete_migration_fixture()
        cases = {
            "duplicate": ([*rows, rows[1]], "duplicate source key 01"),
            "malformed": ([rows[0], rows[1].replace("| 01 |", "| 1 |"), *rows[2:]], "malformed source key 1"),
            "disposition": ([rows[0], rows[1].replace("| service-detail |", "| imagined |"), *rows[2:]], "unsupported disposition imagined"),
            "resolution": ([rows[0], rows[1].replace("| resolved |", "| pending |"), *rows[2:]], "unsupported resolution pending"),
        }
        for name, (changed, error) in cases.items():
            with self.subTest(name=name):
                if name != "duplicate":
                    self.reset_migration_fixture()
                self.write_inventory(changed)
                self.assert_migration_error(error)

    def test_migration_inventory_allows_unresolved_only_for_decisions_and_proposals(self):
        self.write_complete_migration_fixture()
        for disposition in ("current", "service-detail", "plan-detail", "historical-only"):
            with self.subTest(disposition=disposition):
                rows = self.reset_migration_fixture()
                replacement = rows[-1].replace("| decision |", f"| {disposition} |")
                if disposition == "historical-only":
                    replacement = replacement.replace("| B01=architecture/adr/README.md#deferred-27 |", "| None |")
                self.write_inventory([*rows[:-1], replacement])
                self.assert_migration_error("unresolved resolution is allowed only for decision or proposal rows: 27")

    def test_migration_inventory_requires_existing_destination_anchor_and_current_evidence(self):
        rows = self.write_complete_migration_fixture()
        cases = {
            "missing-destination": ([rows[0], rows[1].replace("architecture/destination-01.md", "architecture/missing.md"), *rows[2:]], "destination does not exist"),
            "missing-anchor": ([rows[0], rows[1].replace("#source-01", "#missing"), *rows[2:]], "destination anchor does not exist"),
            "missing-evidence": ([*rows[:8], rows[8].replace("services/funds-core/README.md", "services/funds-core/missing.md"), *rows[9:]], "current evidence does not exist"),
        }
        for name, (changed, error) in cases.items():
            with self.subTest(name=name):
                if name != "missing-destination":
                    self.reset_migration_fixture()
                self.write_inventory(changed)
                self.assert_migration_error(error)

    def test_migration_inventory_maps_every_non_historical_block_exactly_once(self):
        rows = self.write_complete_migration_fixture()
        cases = {
            "empty-map": ([rows[0], rows[1].replace("B01=architecture/destination-01.md#source-01", ""), *rows[2:]], "destination map must cover each block exactly once"),
            "missing-block-map": ([*rows[:8], rows[8].replace("B01=architecture/current-08.md#block-01", "B02=architecture/current-08.md#block-01"), *rows[9:]], "destination map must cover each block exactly once"),
            "duplicate-block-map": ([rows[0], rows[1].replace("B01=architecture/destination-01.md#source-01", "B01=architecture/destination-01.md#source-01; B01=architecture/destination-02.md#source-02"), *rows[2:]], "destination map must cover each block exactly once"),
            "wrong-key-marker": (rows, "missing migration marker"),
            "missing-marker": (rows, "missing migration marker"),
        }
        for name, (changed, error) in cases.items():
            with self.subTest(name=name):
                self.reset_migration_fixture()
                if name == "wrong-key-marker":
                    self.write("architecture/destination-01.md", '# Destination\n<a id="source-01"></a>\n<!-- migration-source: 02 -->\n')
                elif name == "missing-marker":
                    self.write("architecture/destination-01.md", '# Destination\n<a id="source-01"></a>\n')
                else:
                    self.write_inventory(changed)
                self.assert_migration_error(error)

        rows = self.reset_migration_fixture()
        source = (self.root / "architecture/modern-core-banking-comprehensive-design-revised.md").read_text()
        self.write("architecture/modern-core-banking-comprehensive-design-revised.md", source.replace("Material paragraph for section 7.", "First material block.\n\nSecond material block."))
        self.write("architecture/destination-07-a.md", '# First\n<a id="first"></a>\n<!-- migration-source: 07 -->\n')
        self.write("architecture/destination-07-b.md", '# Second\n<a id="second"></a>\n<!-- migration-source: 07 -->\n')
        rows[7] = "| 07 | 7. Section 7 | B01; B02 | service-detail | B01=architecture/destination-07-a.md#first; B02=architecture/destination-07-b.md#second | None | Both blocks belong in exact service destinations. | resolved |"
        self.write("architecture/destination-07.md", "# Superseded fixture destination\n")
        self.write_inventory(rows)
        errors = validator.validate_migration_inventory(self.root)
        self.assertFalse(any("07" in error and "unresolved migration row" not in error for error in errors), errors)

    def test_migration_inventory_marker_multiset_is_global_exact_and_code_aware(self):
        self.write_complete_migration_fixture()
        self.write(
            "architecture/code-examples.md",
            "```markdown\n<a id=\"fake\"></a>\n<!-- migration-source: 99 -->\n```\n"
            "Inline `<!-- migration-source: 98 -->` syntax is inert.\n",
        )
        errors = validator.validate_migration_inventory(self.root)
        self.assertFalse(any("98" in error or "99" in error for error in errors), errors)

        cases = {
            "orphan": ("architecture/orphan.md", '# Orphan\n<a id="extra"></a>\n<!-- migration-source: 01 -->\n', "unexpected migration marker"),
            "duplicate": ("architecture/destination-01.md", '# Destination\n<a id="source-01"></a>\n<!-- migration-source: 01 -->\n<!-- migration-source: 01 -->\n', "duplicate migration marker"),
            "wrong-anchor": ("architecture/destination-01.md", '# Destination\n<a id="wrong"></a>\n<!-- migration-source: 01 -->\n<a id="source-01"></a>\n', "migration marker mismatch"),
            "stale-provisional": ("architecture/adr/README.md", '# ADR\n<a id="temporary-01"></a>\n<!-- migration-source: 01 -->\n', "unexpected migration marker"),
        }
        for name, (path, text, error) in cases.items():
            with self.subTest(name=name):
                self.reset_migration_fixture()
                self.write(path, text)
                self.assert_migration_error(error)

    def test_resolved_proposal_destinations_use_stable_registry_identity_and_pointer(self):
        rows = self.write_complete_migration_fixture()
        cases = {
            "active-destination": "active or archive proposal record",
            "archive-destination": "active or archive proposal record",
            "missing-pointer": "proposal registry pointer",
            "duplicate-pointer": "proposal registry pointer",
            "stale-pointer": "proposal registry pointer target does not exist",
            "wrong-basename": "proposal registry pointer basename",
            "marker-in-record": "missing migration marker",
        }
        for name, error in cases.items():
            with self.subTest(name=name):
                rows = self.reset_migration_fixture()
                if name == "active-destination":
                    self.write("architecture/proposals/full-poc-platform.md", '# Full\n<a id="design"></a>\n<!-- migration-source: 08::02 -->\n')
                    rows[9] = rows[9].replace("architecture/proposals/README.md#full-poc-platform", "architecture/proposals/full-poc-platform.md#design")
                    self.write("architecture/proposals/README.md", "# Proposals\n")
                elif name == "archive-destination":
                    self.write("architecture/archive/proposals/full-poc-platform.md", '# Full\n<a id="design"></a>\n<!-- migration-source: 08::02 -->\n')
                    rows[9] = rows[9].replace("architecture/proposals/README.md#full-poc-platform", "architecture/archive/proposals/full-poc-platform.md#design")
                    self.write("architecture/proposals/README.md", "# Proposals\n")
                elif name == "missing-pointer":
                    self.write("architecture/proposals/README.md", '# Proposals\n<a id="full-poc-platform"></a>\n<!-- migration-source: 08::02 -->\n')
                elif name == "duplicate-pointer":
                    self.write("architecture/proposals/README.md", '# Proposals\n<a id="full-poc-platform"></a>\n<!-- migration-source: 08::02 -->\n[One](full-poc-platform.md)\n[Two](full-poc-platform.md)\n')
                elif name == "stale-pointer":
                    self.write("architecture/proposals/README.md", '# Proposals\n<a id="full-poc-platform"></a>\n<!-- migration-source: 08::02 -->\n[Missing](../archive/proposals/full-poc-platform.md)\n')
                elif name == "wrong-basename":
                    self.write("architecture/proposals/wrong.md", "# Wrong\n")
                    self.write("architecture/proposals/README.md", '# Proposals\n<a id="full-poc-platform"></a>\n<!-- migration-source: 08::02 -->\n[Wrong](wrong.md)\n')
                else:
                    self.write("architecture/proposals/README.md", '# Proposals\n<a id="full-poc-platform"></a>\n[Full](full-poc-platform.md)\n')
                    self.write("architecture/proposals/full-poc-platform.md", '# Full\n<a id="design"></a>\n<!-- migration-source: 08::02 -->\n')
                self.write_inventory(rows)
                self.assert_migration_error(error)

    def test_migration_inventory_requires_blocks_rationale_and_historical_explanation(self):
        rows = self.write_complete_migration_fixture()
        cases = {
            "empty-blocks": ([rows[0], rows[1].replace("| B01 |", "|  |"), *rows[2:]], "covered blocks must not be empty"),
            "empty-rationale": ([rows[0], rows[1].replace("| B01 belongs in detailed service documentation. |", "|  |"), *rows[2:]], "rationale must not be empty"),
            "historical-explanation": ([rows[0], rows[1].replace("| service-detail | B01=architecture/destination-01.md#source-01 | None | B01 belongs in detailed service documentation.", "| historical-only | None | None | Superseded detail."), *rows[2:]], "historical-only rationale must explain"),
        }
        for name, (changed, error) in cases.items():
            with self.subTest(name=name):
                if name != "empty-blocks":
                    self.reset_migration_fixture()
                self.write_inventory(changed)
                self.assert_migration_error(error)

    def test_migration_inventory_enforces_block_coverage_and_contiguous_segments(self):
        rows = self.write_complete_migration_fixture()
        cases = {
            "gap": ([*rows[:8], rows[8].replace("B01", "B02"), *rows[9:]], "coverage gap"),
            "overlap": ([*rows[:9], rows[9].replace("B02", "B01"), *rows[10:]], "coverage overlap"),
            "segment-gap": ([*rows[:9], rows[9].replace("08::02", "08::03"), *rows[10:]], "contiguous segment suffixes"),
            "segment-duplicate": ([*rows[:9], rows[9].replace("08::02", "08::01"), *rows[10:]], "duplicate source key 08::01"),
        }
        for name, (changed, error) in cases.items():
            with self.subTest(name=name):
                if name != "gap":
                    self.reset_migration_fixture()
                self.write_inventory(changed)
                self.assert_migration_error(error)

    def test_migration_source_tokenizer_ignores_section_rules_and_preserves_inline_code_headings(self):
        headings, _ = validator._material_headings(
            "## 21. Deployment\n\n"
            "### 21.9 Java `funds-core` memory controls\n\n"
            "Material paragraph.\n\n---\n"
        )
        self.assertEqual("21.9 Java `funds-core` memory controls", headings["21.09"].heading)
        self.assertEqual(("B01",), headings["21.09"].blocks)

    def test_migration_inventory_reports_malformed_table_rows(self):
        rows = self.write_complete_migration_fixture()
        self.write_inventory(rows)
        path = self.root / "architecture/archive/comprehensive-design-migration-inventory.md"
        path.write_text(path.read_text().replace("| 01 | 1. Section 1", "01 | 1. Section 1", 1))
        self.assert_migration_error("malformed inventory row")

    def test_migration_inventory_covers_nested_numbered_headings_and_13_8_examples(self):
        rows = self.write_complete_migration_fixture()
        source_path = self.root / "architecture/modern-core-banking-comprehensive-design-revised.md"
        source_path.write_text(
            source_path.read_text().replace(
                "Material paragraph for section 13.",
                "Material paragraph for section 13.\n\n"
                "### 13.8 Worked examples\n\nExample introduction.\n\n"
                "#### Example A: example inflow\n\nExample material.\n\n"
                "#### 13.8.1 Numbered detail\n\nNumbered detail material.",
            )
        )
        errors = validator.validate_migration_inventory(self.root)
        self.assertTrue(any("missing migration row for source heading 13.08" in error for error in errors), errors)
        self.assertTrue(any("missing migration row for source heading 13.08.example-a" in error for error in errors), errors)
        self.assertTrue(any("missing migration row for source heading 13.08.01" in error for error in errors), errors)

        additions = (
            ("13.08", "13.8 Worked examples", "nested-13-08"),
            ("13.08.example-a", "Example A: example inflow", "example-a"),
            ("13.08.01", "13.8.1 Numbered detail", "numbered-detail"),
        )
        for source_key, heading, anchor in additions:
            self.write(
                f"architecture/{anchor}.md",
                f'# Destination\n<a id="{anchor}"></a>\n<!-- migration-source: {source_key} -->\n',
            )
            rows.append(
                f"| {source_key} | {heading} | B01 | service-detail | B01=architecture/{anchor}.md#{anchor} | None | B01 belongs in a nested destination. | resolved |"
            )
        self.write_inventory(rows)
        errors = validator.validate_migration_inventory(self.root)
        self.assertFalse(any("missing migration row for source heading 13.08" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
