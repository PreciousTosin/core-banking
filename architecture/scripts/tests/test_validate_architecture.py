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


if __name__ == "__main__":
    unittest.main()
