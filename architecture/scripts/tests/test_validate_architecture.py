import tempfile
import subprocess
import unittest
import os
import re
import json
from unittest import mock
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

    PROPOSAL_IDENTITIES = (
        "account-identifiers-and-nip-inbound",
        "conventional-deposit-products-and-accrual",
        "non-interest-banking-products",
        "full-poc-platform",
        "production-platform",
        "providers-and-reconciliation",
    )

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

    def proposal_record(
        self,
        identity,
        *,
        status=None,
        related_plans=None,
        related_adrs=None,
        implementation_status=None,
        replacement=None,
        implementation_evidence=None,
        extra_body="",
    ):
        status = status or ("approved" if identity in self.PROPOSAL_PLANS else "proposed")
        related_plans = related_plans if related_plans is not None else self.PROPOSAL_PLANS.get(identity, "None")
        related_adrs = related_adrs if related_adrs is not None else self.PROPOSAL_ADRS[identity]
        plan_lines = f"  - {related_plans}" if related_plans != "None" else "None"
        adr_lines = "\n".join(f"  - {adr}" for adr in related_adrs) if related_adrs != "None" else "None"
        terminal = ""
        if implementation_status is not None:
            terminal += f"implementation_status: {implementation_status}\n"
        if replacement is not None:
            terminal += f"replacement: {replacement}\n"
        if implementation_evidence is not None:
            terminal += f"implementation_evidence:\n  - {implementation_evidence}\n"
        return (
            "---\n"
            f"title: {identity}\n"
            f"status: {status}\n"
            "owners:\n  - architecture\n"
            "target_release: undecided\n"
            f"related_adrs:\n{adr_lines}\n"
            f"related_plans: {plan_lines if plan_lines == 'None' else ''}\n"
            + (f"{plan_lines}\n" if plan_lines != "None" else "")
            + terminal
            + "---\n"
            + f"# {identity}\n\n{extra_body}"
        )

    def write_proposal_registry(self, pointers=None, markers=None):
        pointers = pointers or {identity: f"{identity}.md" for identity in self.PROPOSAL_IDENTITIES}
        markers = markers or {}
        entries = []
        for identity in self.PROPOSAL_IDENTITIES:
            entries.append(f'<a id="{identity}"></a>')
            entries.extend(f"<!-- migration-source: {marker} -->" for marker in markers.get(identity, ()))
            pointer = pointers.get(identity)
            if pointer:
                entries.append(f"[{identity}]({pointer})")
            entries.append("")
        self.write("architecture/proposals/README.md", "# Proposals\n\n## Governed proposal registry\n\n" + "\n".join(entries))

    def write_proposal_fixture(self):
        self.write_proposal_registry()
        for identity in self.PROPOSAL_IDENTITIES:
            body = "## Relationships\n\n" + ", ".join(
                f"[{adr}](../adr/{adr[4:]}-test.md)" for adr in self.PROPOSAL_ADRS[identity]
            ) + "\n"
            if identity in self.PROPOSAL_PLANS:
                body += f"\n[Implementation plan](../../{self.PROPOSAL_PLANS[identity]})\n"
            if identity == "full-poc-platform":
                body += "\n[Infrastructure](../infrastructure/infra-ubuntu24.04-poc.md)\n"
            self.write(f"architecture/proposals/{identity}.md", self.proposal_record(identity, extra_body=body))
        for identity, plan in self.PROPOSAL_PLANS.items():
            proposal = f"architecture/proposals/README.md#{identity}"
            adrs = self.PROPOSAL_ADRS[identity]
            self.write(plan, f"# Plan\n\n**Proposal:** [Proposal](../../../{proposal})\n\n**Related ADRs:** " + ", ".join(f"[x](../../../architecture/adr/{adr[4:]}-test.md)" for adr in adrs) + "\n")
        self.write(
            "docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md",
            "# Accounting\n\n"
            "**Current architecture:** [05](../../../architecture/arc42/05-building-block-view.md), [06](../../../architecture/arc42/06-runtime-view.md), [08](../../../architecture/arc42/08-crosscutting-concepts.md)\n\n"
            "**Retrospective ADRs:** [2](../../../architecture/adr/0002-test.md), [3](../../../architecture/adr/0003-test.md), [4](../../../architecture/adr/0004-test.md), [5](../../../architecture/adr/0005-test.md), [6](../../../architecture/adr/0006-test.md)\n",
        )
        for name in ("05-building-block-view.md", "06-runtime-view.md", "08-crosscutting-concepts.md"):
            self.write(f"architecture/arc42/{name}", "# Arc42\n")
        for number in range(1, 9):
            adr = f"ADR-{number:04d}"
            proposal_links = []
            plan_links = []
            for identity in self.PROPOSAL_IDENTITIES:
                if adr in self.PROPOSAL_ADRS[identity]:
                    proposal_links.append(f"[Proposal](../proposals/README.md#{identity})")
            for identity, plan in self.PROPOSAL_PLANS.items():
                if adr in self.PROPOSAL_ADRS[identity]:
                    plan_links.append(f"[Plan](../../{plan})")
            if 2 <= number <= 6:
                plan_links.append("[Accounting](../../docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md)")
            self.write(
                f"architecture/adr/{number:04d}-test.md",
                f"# {adr}\n\n- Related proposals: {', '.join(proposal_links) or 'None'}\n"
                f"- Related implementation plans: {', '.join(plan_links) or 'None'}\n",
            )
        self.write(
            "architecture/infrastructure/infra-ubuntu24.04-poc.md",
            "---\nstatus: proposed\nowners:\n  - platform\nrelated_adrs:\n  - ADR-0008\nrelated_proposals:\n  - architecture/proposals/README.md#full-poc-platform\n---\n"
            "> **Architecture state: PROPOSED — non-current.**\n\n"
            "[Full PoC proposal](../proposals/README.md#full-poc-platform)\n",
        )

    def traceability_errors(self):
        self.assertTrue(hasattr(validator, "validate_traceability"), "validate_traceability must be implemented")
        return validator.validate_traceability(self.root)

    def test_proposal_bootstrap_requires_all_six_active_registry_targets_but_is_not_permanent(self):
        self.write_proposal_fixture()
        self.assertTrue(hasattr(validator, "validate_proposal_bootstrap"), "validate_proposal_bootstrap must be implemented")
        self.assertEqual([], validator.validate_proposal_bootstrap(self.root))
        self.assertNotIn("proposal-bootstrap", validator.CHECKS)
        self.assertNotIn("proposal-bootstrap", getattr(validator, "VALIDATORS", {}))
        identity = self.PROPOSAL_IDENTITIES[0]
        active = self.root / f"architecture/proposals/{identity}.md"
        archive = self.root / f"architecture/archive/proposals/{identity}.md"
        archive.parent.mkdir(parents=True, exist_ok=True)
        active.replace(archive)
        pointers = {name: f"{name}.md" for name in self.PROPOSAL_IDENTITIES}
        pointers[identity] = f"../archive/proposals/{identity}.md"
        self.write_proposal_registry(pointers)
        self.assertTrue(validator.validate_proposal_bootstrap(self.root))

    def test_metadata_requires_six_unique_same_basename_registry_locations(self):
        self.write_proposal_fixture()
        self.assertFalse(any("proposal registry" in error for error in validator.validate_metadata(self.root)))
        identity = self.PROPOSAL_IDENTITIES[0]
        cases = {
            "missing": (lambda: (self.root / f"architecture/proposals/{identity}.md").unlink(), "must have one sole active or archive record"),
            "active-and-archive": (lambda: self.write(f"architecture/archive/proposals/{identity}.md", self.proposal_record(identity, status="rejected", related_plans="None", implementation_status="Not applicable", replacement="None", implementation_evidence="architecture/proposals/README.md")), "must have one sole active or archive record"),
            "stale-pointer": (lambda: self.write_proposal_registry({**{name: f"{name}.md" for name in self.PROPOSAL_IDENTITIES}, identity: f"../archive/proposals/{identity}.md"}), "pointer target does not exist"),
            "duplicate-anchor": (lambda: self.write("architecture/proposals/README.md", (self.root / "architecture/proposals/README.md").read_text() + f'\n<a id="{identity}"></a>\n'), "must occur exactly once"),
            "wrong-basename": (lambda: self.write_proposal_registry({**{name: f"{name}.md" for name in self.PROPOSAL_IDENTITIES}, identity: "full-poc-platform.md"}), "pointer basename"),
        }
        for name, (mutate, fragment) in cases.items():
            with self.subTest(name=name):
                self.tmp.cleanup(); self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
                self.write_proposal_fixture(); mutate()
                errors = validator.validate_metadata(self.root)
                self.assertTrue(any(fragment in error for error in errors), errors)

    def test_metadata_enforces_proposal_required_fields_status_and_delivery_plan(self):
        self.write_proposal_fixture()
        identity = "full-poc-platform"
        cases = {
            "status": (self.proposal_record(identity, status="current"), "invalid proposal status"),
            "owners": (self.proposal_record(identity).replace("owners:\n  - architecture\n", ""), "owners is required"),
            "target": (self.proposal_record(identity).replace("target_release: undecided\n", ""), "target_release is required"),
            "adrs": (self.proposal_record(identity, related_adrs="None"), "related_adrs must contain"),
            "plans": (self.proposal_record(identity).replace("related_plans: None\n", ""), "related_plans is required"),
            "implementing-plan": (self.proposal_record(identity, status="implementing", related_plans="None"), "implementing proposal requires"),
        }
        for name, (record, fragment) in cases.items():
            with self.subTest(name=name):
                self.write(f"architecture/proposals/{identity}.md", record)
                errors = validator.validate_metadata(self.root)
                self.assertTrue(any(fragment in error for error in errors), errors)

    def test_metadata_accepts_terminal_proposal_variants_and_rejects_invalid_contracts(self):
        self.write_proposal_fixture()
        identity = "account-identifiers-and-nip-inbound"
        active = self.root / f"architecture/proposals/{identity}.md"
        archive_path = f"architecture/archive/proposals/{identity}.md"
        plan = self.PROPOSAL_PLANS[identity]
        implemented = self.proposal_record(identity, status="implemented", implementation_status="Complete", replacement="[Current](../../arc42/05-building-block-view.md)", implementation_evidence="docs/superpowers/plans/2026-08-30-account-identifiers-and-nip-inbound-implementation.md")
        active.unlink(); self.write(archive_path, implemented)
        pointers = {name: f"{name}.md" for name in self.PROPOSAL_IDENTITIES}; pointers[identity] = f"../archive/proposals/{identity}.md"; self.write_proposal_registry(pointers)
        self.assertFalse(any(identity in error for error in validator.validate_metadata(self.root)), validator.validate_metadata(self.root))
        for status, plans, replacement in (("rejected", plan, "None"), ("superseded", plan, "[Next](../../proposals/full-poc-platform.md)")):
            with self.subTest(status=status, plans=plans):
                self.write(archive_path, self.proposal_record(identity, status=status, related_plans=plans, implementation_status="Not applicable", replacement=replacement, implementation_evidence="architecture/proposals/README.md"))
                self.assertFalse(any(identity in error for error in validator.validate_metadata(self.root)), validator.validate_metadata(self.root))
        active_identity = "production-platform"
        (self.root / f"architecture/proposals/{active_identity}.md").unlink()
        none_archive = f"architecture/archive/proposals/{active_identity}.md"
        pointers[active_identity] = f"../archive/proposals/{active_identity}.md"
        self.write_proposal_registry(pointers)
        for status, replacement in (("rejected", "None"), ("superseded", "[Next](../../proposals/full-poc-platform.md)")):
            with self.subTest(status=status, plans="None"):
                self.write(none_archive, self.proposal_record(active_identity, status=status, related_plans="None", implementation_status="Not applicable", replacement=replacement, implementation_evidence="architecture/proposals/README.md"))
                self.assertFalse(any(active_identity in error for error in validator.validate_metadata(self.root)), validator.validate_metadata(self.root))
        invalid = {
            "implementation-status": self.proposal_record(identity, status="implemented", implementation_status="Partial", replacement="[Current](../../arc42/05-building-block-view.md)", implementation_evidence="architecture/proposals/README.md"),
            "replacement": self.proposal_record(identity, status="rejected", related_plans="None", implementation_status="Not applicable", replacement="[Wrong](../../arc42/05-building-block-view.md)", implementation_evidence="architecture/proposals/README.md"),
            "evidence": self.proposal_record(identity, status="rejected", related_plans="None", implementation_status="Not applicable", replacement="None"),
        }
        for name, record in invalid.items():
            with self.subTest(name=name):
                self.write(archive_path, record)
                errors = validator.validate_metadata(self.root)
                self.assertTrue(any(identity in error for error in errors), errors)

    def test_traceability_enforces_proposal_plan_and_adr_reciprocity(self):
        self.write_proposal_fixture()
        self.assertEqual([], self.traceability_errors())
        identity = "account-identifiers-and-nip-inbound"
        plan = self.root / self.PROPOSAL_PLANS[identity]
        plan.write_text(plan.read_text().replace("**Proposal:**", "**Former proposal:**"))
        self.assertTrue(any("proposal backlink" in error for error in self.traceability_errors()))
        self.write_proposal_fixture()
        adr = self.root / "architecture/adr/0002-test.md"
        adr.write_text(adr.read_text().replace(f"[Proposal](../proposals/README.md#{identity}), ", ""))
        self.assertTrue(any("Related proposals" in error for error in self.traceability_errors()))

    def test_traceability_enforces_direct_plan_adr_reciprocity_both_directions(self):
        self.write_proposal_fixture()
        plan = self.root / self.PROPOSAL_PLANS["conventional-deposit-products-and-accrual"]
        plan.write_text(plan.read_text().replace("[x](../../../architecture/adr/0003-test.md), ", ""))
        self.assertTrue(any("ADR plan backlink has no direct plan link" in error for error in self.traceability_errors()))
        self.write_proposal_fixture()
        adr = self.root / "architecture/adr/0003-test.md"
        target = "[Plan](../../docs/superpowers/plans/2026-08-30-conventional-deposit-products-and-accrual-implementation.md), "
        adr.write_text(adr.read_text().replace(target, ""))
        self.assertTrue(any("direct ADR link has no ADR plan backlink" in error for error in self.traceability_errors()))

    def test_traceability_enforces_accounting_kernel_current_and_retrospective_links_without_proposal(self):
        self.write_proposal_fixture()
        accounting = self.root / "docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md"
        accounting.write_text(accounting.read_text().replace("[05](../../../architecture/arc42/05-building-block-view.md), ", ""))
        self.assertTrue(any("Current architecture" in error for error in self.traceability_errors()))
        self.write_proposal_fixture(); accounting = self.root / "docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md"
        accounting.write_text(accounting.read_text().replace("[2](../../../architecture/adr/0002-test.md), ", ""))
        self.assertTrue(any("Retrospective ADRs" in error for error in self.traceability_errors()))
        self.write_proposal_fixture(); accounting = self.root / "docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md"
        accounting.write_text(accounting.read_text() + "\n**Proposal:** [Wrong](../../../architecture/proposals/README.md#full-poc-platform)\n")
        self.assertTrue(any("must not have a Proposal backlink" in error for error in self.traceability_errors()))

    def test_infrastructure_governance_contract_is_exact_and_reciprocal(self):
        self.write_proposal_fixture()
        self.assertFalse(any("infra-ubuntu24.04-poc.md" in error for error in self.traceability_errors()))
        infra = self.root / "architecture/infrastructure/infra-ubuntu24.04-poc.md"
        cases = {
            "marker": (lambda text: text.replace("> **Architecture state: PROPOSED — non-current.**", "> **Architecture state: CURRENT.**"), "PROPOSED — non-current"),
            "absent-marker": (lambda text: text.replace("> **Architecture state: PROPOSED — non-current.**\n\n", ""), "PROPOSED — non-current"),
            "status": (lambda text: text.replace("status: proposed", "status: current"), "status must be proposed"),
            "owner": (lambda text: text.replace("  - platform", "  - operations"), "owners must contain only platform"),
            "adr": (lambda text: text.replace("  - ADR-0008", "  - ADR-0007"), "related_adrs must contain only ADR-0008"),
            "proposal": (lambda text: text.replace("#full-poc-platform", "#production-platform"), "related_proposals must contain only"),
        }
        original = infra.read_text()
        for name, (mutate, fragment) in cases.items():
            with self.subTest(name=name):
                infra.write_text(mutate(original))
                self.assertTrue(any(fragment in error for error in self.traceability_errors()))
                infra.write_text(original)
        proposal = self.root / "architecture/proposals/full-poc-platform.md"
        proposal.write_text(proposal.read_text().replace("[Infrastructure](../infrastructure/infra-ubuntu24.04-poc.md)\n", ""))
        self.assertTrue(any("full-PoC proposal must link" in error for error in self.traceability_errors()))
        self.write_proposal_fixture()
        pointers = {identity: f"{identity}.md" for identity in self.PROPOSAL_IDENTITIES}
        pointers["full-poc-platform"] = "../archive/proposals/full-poc-platform.md"
        self.write_proposal_registry(pointers)
        self.assertTrue(any("pointer target does not exist" in error for error in self.traceability_errors()))
        self.write_proposal_fixture()
        adr = self.root / "architecture/adr/0008-test.md"
        adr.write_text(adr.read_text().replace("[Proposal](../proposals/README.md#full-poc-platform), ", ""))
        self.assertTrue(any("ADR-0008 Related proposals" in error for error in self.traceability_errors()))

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


class DiagramAndToolingValidatorTest(unittest.TestCase):
    DIAGRAMS = {
        "context.mmd": ("CURRENT", "system-context", "architecture/arc42/03-context-and-scope.md", "ADR-0004"),
        "containers.mmd": ("PROPOSED", "container", "architecture/arc42/03-context-and-scope.md", "ADR-0001"),
        "funds-core-components.mmd": ("CURRENT", "component", "architecture/arc42/05-building-block-view.md", "ADR-0002"),
        "posting-sequence.mmd": ("CURRENT", "runtime-sequence", "architecture/arc42/06-runtime-view.md", "ADR-0006"),
        "single-vm-deployment.mmd": ("PROPOSED", "deployment", "architecture/arc42/07-deployment-view.md", "ADR-0008"),
    }

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.render_tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.render_tmp.cleanup()
        self.tmp.cleanup()

    def write(self, rel, text):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def write_diagram(self, name, *, state=None, abstraction=None, question="What does this show?", owner="architecture", title_state=None, arc42=None, adrs=None, last_verified="2026-09-01"):
        expected_state, expected_abstraction, expected_arc42, expected_adrs = self.DIAGRAMS[name]
        state = expected_state if state is None else state
        abstraction = expected_abstraction if abstraction is None else abstraction
        title_state = state if title_state is None else title_state
        arc42 = expected_arc42 if arc42 is None else arc42
        adrs = expected_adrs if adrs is None else adrs
        return self.write(
            f"architecture/diagrams/{name}",
            f"---\ntitle: {title_state} — {name}\n---\n"
            f"%% state: {state}\n%% abstraction: {abstraction}\n%% question: {question}\n"
            f"%% owner: {owner}\n%% arc42: {arc42}\n%% adrs: {adrs}\n%% last_verified: {last_verified}\n"
            "flowchart LR\n  A --> B\n",
        )

    def write_valid_render_script(self):
        path = self.write(
            "architecture/scripts/render-diagrams.sh",
            "#!/usr/bin/env bash\nset -euo pipefail\n"
            "script_dir=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd -P)\"\n"
            "repository_root=\"$(cd \"$script_dir/../..\" && pwd -P)\"\n"
            "tooling_dir=\"$repository_root/architecture/tooling\"\n"
            "temp_root=\"$(mktemp -d)\"\ninstall_dir=\"$temp_root/install\"\noutput_dir=\"\"\n"
            "cleanup() {\n  rm -rf -- \"$temp_root\"\n}\ntrap cleanup EXIT\n"
            "if [[ $# -gt 1 ]]; then exit 2; fi\n"
            "if [[ $# -eq 1 ]]; then output_dir=\"$1\"; mkdir -p -- \"$output_dir\"; else output_dir=\"$temp_root/output\"; fi\n"
            "mkdir -p -- \"$install_dir\" \"$output_dir\" \"$temp_root/npm-cache\" \"$temp_root/puppeteer-cache\" \"$temp_root/xdg-cache\" \"$temp_root/xdg-config\" \"$temp_root/xdg-data\"\n"
            "cp -- \"$tooling_dir/package.json\" \"$tooling_dir/package-lock.json\" \"$install_dir/\"\n"
            "owned_env=(\"npm_config_cache=$temp_root/npm-cache\" \"PUPPETEER_CACHE_DIR=$temp_root/puppeteer-cache\" \"XDG_CACHE_HOME=$temp_root/xdg-cache\" \"XDG_CONFIG_HOME=$temp_root/xdg-config\" \"XDG_DATA_HOME=$temp_root/xdg-data\")\n"
            "env \"${owned_env[@]}\" npm ci --prefix \"$install_dir\"\n"
            "mmdc=\"$install_dir/node_modules/.bin/mmdc\"\ntest -x \"$mmdc\"\n"
            "mapfile -t sources < <(find \"$repository_root/architecture/diagrams\" -maxdepth 1 -type f -name '*.mmd' -print | LC_ALL=C sort)\n"
            "test \"${#sources[@]}\" -gt 0\nfor source in \"${sources[@]}\"; do output=\"$output_dir/$(basename \"${source%.mmd}\").svg\"; env \"${owned_env[@]}\" \"$mmdc\" -i \"$source\" -o \"$output\"; done\n",
        )
        path.chmod(0o755)
        return path

    def write_complete_diagram_fixture(self):
        links = {}
        for name, (_, _, arc42, _) in self.DIAGRAMS.items():
            self.write_diagram(name)
            links.setdefault(arc42, []).append(f"[Diagram](../diagrams/{name})")
        for arc42, diagram_links in links.items():
            self.write(arc42, "# Arc42\n\n" + "\n".join(diagram_links) + "\n")
        for identifier in {adr for _, (_, _, _, adr) in self.DIAGRAMS.items()} | {"ADR-0002", "ADR-0004", "ADR-0006"}:
            self.write(f"architecture/adr/{identifier[4:]}-fixture.md", "# ADR\n")
        return self.write_valid_render_script()

    def test_diagrams_registry_and_valid_fixture(self):
        self.write_complete_diagram_fixture()
        self.assertIn("diagrams", validator.CHECKS)
        self.assertIn("diagrams", validator.VALIDATORS)
        self.assertEqual([], validator.validate_diagrams(self.root))

    def test_diagrams_reject_required_metadata_and_title_state_failures(self):
        cases = {
            "missing-state": {"state": ""},
            "missing-abstraction": {"abstraction": ""},
            "missing-question": {"question": ""},
            "missing-owner": {"owner": ""},
            "missing-arc42": {"arc42": ""},
            "missing-adrs": {"adrs": ""},
            "missing-last-verified": {"last_verified": ""},
            "missing-title-state": {"title_state": ""},
            "mismatched-title-state": {"state": "CURRENT", "title_state": "PROPOSED"},
        }
        for case, kwargs in cases.items():
            with self.subTest(case=case):
                self.tmp.cleanup(); self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
                self.write_complete_diagram_fixture()
                self.write_diagram("context.mmd", **kwargs)
                errors = validator.validate_diagrams(self.root)
                self.assertTrue(errors, errors)

    def test_diagrams_reject_invalid_state_arc42_target_adr_date_and_missing_source(self):
        cases = {
            "invalid-state": {"state": "FUTURE"},
            "missing-arc42-target": {"arc42": "architecture/arc42/missing.md"},
            "non-arc42-target": {"arc42": "architecture/not-arc42.md"},
            "missing-adr": {"adrs": "ADR-9999"},
            "invalid-date": {"last_verified": "2026-13-01"},
        }
        for case, kwargs in cases.items():
            with self.subTest(case=case):
                self.tmp.cleanup(); self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
                self.write_complete_diagram_fixture()
                if case == "non-arc42-target":
                    self.write("architecture/not-arc42.md", "[Diagram](diagrams/context.mmd)\n")
                self.write_diagram("context.mmd", **kwargs)
                self.assertTrue(validator.validate_diagrams(self.root))
        self.tmp.cleanup(); self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        self.write_complete_diagram_fixture()
        (self.root / "architecture/diagrams/context.mmd").unlink()
        errors = validator.validate_diagrams(self.root)
        self.assertTrue(any("context.mmd is required" in error for error in errors), errors)

    def test_diagrams_reject_non_executable_render_script_and_missing_reciprocal_link(self):
        script = self.write_complete_diagram_fixture()
        script.chmod(0o644)
        errors = validator.validate_diagrams(self.root)
        self.assertTrue(any("must be executable" in error for error in errors), errors)
        script.chmod(0o755)
        self.write("architecture/arc42/03-context-and-scope.md", "# Arc42\n")
        errors = validator.validate_diagrams(self.root)
        self.assertTrue(any("must link back to architecture/diagrams/context.mmd" in error for error in errors), errors)

    def test_render_script_contract_rejects_non_owned_state_and_external_cleanup(self):
        self.write_complete_diagram_fixture()
        script = self.root / "architecture/scripts/render-diagrams.sh"
        text = script.read_text()
        for case, bad in {
            "repository-node-modules": text + "\narchitecture/tooling/node_modules\n",
            "missing-puppeteer-binding": text.replace('env "${owned_env[@]}" "$mmdc"', '"$mmdc"'),
            "unsafe-cleanup": text.replace('rm -rf -- "$temp_root"', 'rm -rf -- "$output_dir"'),
            "home-cache": text.replace('$temp_root/npm-cache', '~/.npm'),
        }.items():
            with self.subTest(case=case):
                script.write_text(bad)
                errors = validator.validate_render_script_contract(self.root)
                self.assertTrue(errors, errors)
                script.write_text(text)

    def assert_single_invocation_root(self, records, *, temp_parent, repository, test_home):
        values = [
            Path(record[key])
            for record in records
            for key in ("cache", "puppeteer", "xdg_cache", "xdg_config", "xdg_data")
        ]
        roots = {value.parent for value in values}
        self.assertEqual(1, len(roots), roots)
        temp_root = roots.pop()
        self.assertTrue(temp_root.is_relative_to(temp_parent), temp_root)
        self.assertFalse(temp_root.is_relative_to(repository), (temp_root, repository))
        self.assertFalse(temp_root.is_relative_to(test_home), (temp_root, test_home))
        for value in values:
            self.assertTrue(value.is_relative_to(temp_root), (value, temp_root))
            self.assertFalse(value.is_relative_to(repository), (value, repository))
            self.assertFalse(value.is_relative_to(test_home), (value, test_home))
        self.assertFalse(temp_root.exists(), temp_root)

    def test_fake_render_single_root_assertion_rejects_split_cache_roots(self):
        temp_parent = Path(self.render_tmp.name)
        repository = self.root
        test_home = self.root / "home"
        root = temp_parent / "invocation"
        sibling = temp_parent / "sibling"
        first = {"cache": str(root / "npm-cache"), "puppeteer": str(root / "puppeteer-cache"), "xdg_cache": str(root / "xdg-cache"), "xdg_config": str(root / "xdg-config"), "xdg_data": str(root / "xdg-data")}
        split = first | {"puppeteer": str(sibling / "puppeteer-cache")}
        with self.assertRaises(AssertionError):
            self.assert_single_invocation_root([first, split], temp_parent=temp_parent, repository=repository, test_home=test_home)

    def test_render_script_fake_tools_keep_state_under_removed_owned_root(self):
        script = self.write_complete_diagram_fixture()
        self.write("architecture/tooling/package.json", "{}\n")
        self.write("architecture/tooling/package-lock.json", "{}\n")
        fake_bin = self.root / "fake-bin"; fake_bin.mkdir()
        log = self.root / "tool-log.jsonl"
        npm = fake_bin / "npm"
        npm.write_text(
            "#!/usr/bin/env bash\nset -euo pipefail\n"
            "printf '%s\\n' \"{\\\"tool\\\":\\\"npm\\\",\\\"cache\\\":\\\"$npm_config_cache\\\",\\\"puppeteer\\\":\\\"$PUPPETEER_CACHE_DIR\\\",\\\"xdg_cache\\\":\\\"$XDG_CACHE_HOME\\\",\\\"xdg_config\\\":\\\"$XDG_CONFIG_HOME\\\",\\\"xdg_data\\\":\\\"$XDG_DATA_HOME\\\"}\" >> \"$TOOL_LOG\"\n"
            "prefix=\"\"; while [[ $# -gt 0 ]]; do if [[ $1 == --prefix ]]; then prefix=$2; shift 2; else shift; fi; done\n"
            "mkdir -p \"$prefix/node_modules/.bin\"\n"
            "cat > \"$prefix/node_modules/.bin/mmdc\" <<'EOF'\n#!/usr/bin/env bash\nset -euo pipefail\nprintf '%s\\n' \"{\\\"tool\\\":\\\"mmdc\\\",\\\"cache\\\":\\\"$npm_config_cache\\\",\\\"puppeteer\\\":\\\"$PUPPETEER_CACHE_DIR\\\",\\\"xdg_cache\\\":\\\"$XDG_CACHE_HOME\\\",\\\"xdg_config\\\":\\\"$XDG_CONFIG_HOME\\\",\\\"xdg_data\\\":\\\"$XDG_DATA_HOME\\\"}\" >> \"$TOOL_LOG\"\nif [[ ${FAIL_RENDER:-0} == 1 ]]; then exit 71; fi\nwhile [[ $# -gt 0 ]]; do if [[ $1 == -o ]]; then mkdir -p \"$(dirname \"$2\")\"; printf '<svg/>\\n' > \"$2\"; exit 0; fi; shift; done\nEOF\nchmod +x \"$prefix/node_modules/.bin/mmdc\"\n"
        )
        npm.chmod(0o755)
        caller_output = self.root / "caller-output"
        test_home = self.root / "home"
        test_home.mkdir()
        temp_parent = Path(self.render_tmp.name)
        environment = os.environ | {"PATH": f"{fake_bin}:{os.environ['PATH']}", "TOOL_LOG": str(log), "TMPDIR": str(temp_parent), "HOME": str(test_home)}
        for failure in (False, True):
            with self.subTest(failure=failure):
                log.unlink(missing_ok=True)
                result = subprocess.run([str(script), str(caller_output)], cwd=self.root, env=environment | ({"FAIL_RENDER": "1"} if failure else {}), text=True, capture_output=True)
                self.assertEqual(71 if failure else 0, result.returncode, result.stderr)
                records = [json.loads(line) for line in log.read_text().splitlines()]
                self.assertGreaterEqual(len(records), 2)
                self.assert_single_invocation_root(
                    records,
                    temp_parent=temp_parent,
                    repository=self.root,
                    test_home=test_home,
                )
                self.assertTrue(caller_output.is_dir())

    def write_valid_tooling(self):
        self.write("architecture/tooling/package.json", json.dumps({"name": "core-banking-architecture-tooling", "private": True, "version": "1.0.0", "engines": {"node": ">=20"}, "devDependencies": {"@mermaid-js/mermaid-cli": "11.16.0"}}))
        self.write("architecture/tooling/package-lock.json", json.dumps({"name": "core-banking-architecture-tooling", "version": "1.0.0", "lockfileVersion": 3, "requires": True, "packages": {"": {"name": "core-banking-architecture-tooling", "version": "1.0.0", "devDependencies": {"@mermaid-js/mermaid-cli": "11.16.0"}}, "node_modules/@mermaid-js/mermaid-cli": {"version": "11.16.0"}}}))

    def init_git(self):
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    def test_tooling_requires_exact_json_manifest_and_lock_resolution(self):
        self.write_valid_tooling()
        self.assertEqual([], validator.validate_tooling(self.root))
        for rel, mutate in {
            "range": lambda: self.write("architecture/tooling/package.json", '{"devDependencies":{"@mermaid-js/mermaid-cli":"^11.16.0"}}'),
            "extra": lambda: self.write("architecture/tooling/package.json", '{"devDependencies":{"@mermaid-js/mermaid-cli":"11.16.0","other":"1"}}'),
            "lock-root": lambda: self.write("architecture/tooling/package-lock.json", '{"packages":{"":{"devDependencies":{"@mermaid-js/mermaid-cli":"1.0.0"}},"node_modules/@mermaid-js/mermaid-cli":{"version":"11.16.0"}}}'),
            "lock-resolved": lambda: self.write("architecture/tooling/package-lock.json", '{"packages":{"":{"devDependencies":{"@mermaid-js/mermaid-cli":"11.16.0"}},"node_modules/@mermaid-js/mermaid-cli":{"version":"1.0.0"}}}'),
        }.items():
            with self.subTest(rel=rel):
                self.write_valid_tooling(); mutate()
                self.assertTrue(validator.validate_tooling(self.root))

    def test_tooling_rejects_tracked_output_and_enforces_classified_architecture_svg(self):
        self.write_valid_tooling(); self.init_git()
        for rel in ("architecture/tooling/node_modules/any.file", "architecture/diagrams/generated/any.file"):
            self.write(rel, "x")
            subprocess.run(["git", "add", rel], cwd=self.root, check=True)
        errors = validator.validate_tooling(self.root)
        self.assertTrue(any("tracked generated or dependency path" in error for error in errors), errors)
        subprocess.run(["git", "rm", "--cached", "-qr", "architecture/tooling/node_modules", "architecture/diagrams/generated"], cwd=self.root, check=True)
        self.write("architecture/diagrams/source.mmd", "flowchart LR\n")
        self.write("architecture/derived.svg", "<svg/>")
        self.write("architecture/README.md", "<!-- approved-architecture-derivative: architecture/derived.svg source=architecture/diagrams/source.mmd -->\n[Derivative](derived.svg)\n")
        subprocess.run(["git", "add", "architecture/diagrams/source.mmd", "architecture/derived.svg", "architecture/README.md"], cwd=self.root, check=True)
        self.assertEqual([], validator.validate_tooling(self.root))
        self.write("architecture/README.md", "[Derivative](derived.svg)\n")
        self.assertTrue(any("unclassified tracked SVG" in error for error in validator.validate_tooling(self.root)))


class AdrValidatorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel: str, text: str) -> Path:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def git(self, *args: str, check: bool = True) -> str:
        result = subprocess.run(
            ["git", "-C", str(self.root), *args],
            check=check,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        return result.stdout.strip()

    def init_git(self) -> None:
        self.git("init", "-q")
        self.git("config", "user.email", "architecture@example.invalid")
        self.git("config", "user.name", "Architecture Tests")

    def commit_all(self, message: str) -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        commit = self.git("rev-parse", "HEAD")
        self.assertRegex(commit, r"^[0-9a-f]{40}$")
        return commit

    def valid_adr(
        self,
        *,
        number: int = 9,
        title: str = "Test decision",
        status: str = "Proposed",
        retrospective: str = "No",
        implementation_status: str = "Not started",
        context: str = "Context version one.",
        related_architecture: str = "None",
        related_plans: str = "None",
        related_pull_requests: str = "None",
        related_commits: str = "None",
        related_proposals: str = "None",
        supersedes: str = "None",
        superseded_by: str = "None",
        compliance: str = "- Validation is pending.",
        evidence: str = "None",
    ) -> str:
        return (
            f"# ADR-{number:04d}: {title}\n\n"
            f"- Status: {status}\n"
            f"- Retrospective: {retrospective}\n"
            "- Decision date: 2026-09-01\n"
            "- Deciders: Architecture\n"
            "- Scope: Test scope\n"
            f"- Implementation status: {implementation_status}\n"
            f"- Related proposals: {related_proposals}\n"
            f"- Related implementation plans: {related_plans}\n"
            f"- Related pull requests: {related_pull_requests}\n"
            f"- Related commits: {related_commits}\n"
            f"- Related architecture sections: {related_architecture}\n"
            f"- Supersedes: {supersedes}\n"
            f"- Superseded by: {superseded_by}\n\n"
            "## Context\n\n"
            f"{context}\n\n"
            "## Decision drivers\n\n"
            "- Preserve the contract.\n\n"
            "## Considered options\n\n"
            "- Keep the current boundary.\n"
            "- Reject a weaker boundary.\n\n"
            "## Decision\n\n"
            "Use the governed boundary.\n\n"
            "## Consequences\n\n"
            "### Positive\n\n"
            "The boundary is explicit.\n\n"
            "### Negative\n\n"
            "The contract requires maintenance.\n\n"
            "### Risks\n\n"
            "Drift is rejected by validation.\n\n"
            "## Compliance and verification\n\n"
            f"{compliance}\n\n"
            "## Implementation evidence\n\n"
            f"{evidence}\n"
        )

    def write_valid_adr(self, status: str, context: str) -> None:
        self.write(
            "architecture/adr/0009-test-decision.md",
            self.valid_adr(
                status=status,
                implementation_status="Partial" if status != "Proposed" else "Not started",
                context=context,
                evidence="None" if status == "Proposed" else "- https://github.com/acme/bank/pull/1",
            ),
        )

    def replace_section(self, text: str, heading: str, replacement: str) -> str:
        lines = text.splitlines()
        start = lines.index(heading)
        level = len(heading) - len(heading.lstrip("#"))
        end = len(lines)
        for index in range(start + 1, len(lines)):
            match = re.match(r"^(#{2,3})\s+", lines[index])
            if match and len(match.group(1)) <= level:
                end = index
                break
        replacement_lines = replacement.splitlines() if replacement else []
        return "\n".join(lines[: start + 1] + [""] + replacement_lines + [""] + lines[end:]).rstrip() + "\n"

    def test_adr_contract_rejects_number_filename_title_fields_and_empty_sections(self):
        self.write("architecture/adr/0001-wrong.md", self.valid_adr(number=2))
        errors = validator.validate_adrs(self.root)
        self.assertTrue(any("filename/title" in error or "ADR-0001" in error for error in errors), errors)

        for heading in validator.ADR_SUBSTANTIVE_HEADINGS:
            with self.subTest(heading=heading):
                self.root.joinpath("architecture/adr/0001-wrong.md").unlink(missing_ok=True)
                text = self.valid_adr(number=1, title="Manage architecture as versioned code")
                replacement = "### Positive\n\n### Negative\n\n### Risks" if heading == "## Consequences" else ""
                text = self.replace_section(text, heading, replacement)
                self.write("architecture/adr/0001-manage-architecture-as-versioned-code.md", text)
                errors = validator.validate_adrs(self.root)
                self.assertTrue(any(f"{heading} must contain prose, a list item, or a link" in error for error in errors), errors)

        text = self.valid_adr()
        decision = validator._section_bodies(text)["## Decision"]
        drivers = validator._section_bodies(text)["## Decision drivers"]
        text = text.replace(f"## Decision drivers\n\n{drivers}", "SECTION-A", 1)
        text = text.replace(f"## Decision\n\n{decision}", f"## Decision drivers\n\n{drivers}", 1)
        text = text.replace("SECTION-A", f"## Decision\n\n{decision}", 1)
        self.write("architecture/adr/0009-test-decision.md", text)
        errors = validator.validate_adrs(self.root)
        self.assertTrue(any("headings must occur once in the required order" in error for error in errors), errors)

    def test_substantive_sections_reject_comment_anchor_and_rule_only_content(self):
        for name, body in (
            ("comment", "<!-- comment only -->"),
            ("anchor", '<a id="anchor-only"></a>'),
            ("rule", "---"),
        ):
            with self.subTest(name=name):
                text = self.replace_section(self.valid_adr(), "## Context", body)
                self.write("architecture/adr/0009-test-decision.md", text)
                errors = validator.validate_adrs(self.root)
                self.assertTrue(any("## Context must contain prose, a list item, or a link" in error for error in errors), errors)

    def test_substantive_sections_reject_code_and_heading_syntax_only_content(self):
        for name, body in (
            ("unclosed-fence-info-only", "```python"),
            ("fence-info-only", "```python\n```"),
            ("fenced-code", "```python\nprint('not prose')\n```"),
            ("indented-tilde-fence", "   ~~~sql\n   SELECT account_id FROM accounts;\n   ~~~"),
            ("fenced-link", "```markdown\n[Not a substantive link](reference.md)\n```"),
            ("inline-code", "`print('not prose')`"),
            ("multiple-inline-code", "`first` / `second`"),
            ("atx-heading", "### [Heading link](reference.md)"),
            ("setext-heading", "Heading syntax\n---"),
            ("reference-definition", "[reference]: reference.md"),
        ):
            with self.subTest(name=name):
                text = self.replace_section(self.valid_adr(), "## Context", body)
                self.write("architecture/adr/0009-test-decision.md", text)
                errors = validator.validate_adrs(self.root)
                self.assertTrue(any("## Context must contain prose, a list item, or a link" in error for error in errors), errors)

    def test_substantive_sections_reject_indented_code_only_content(self):
        for name, body in (
            (
                "four-space-block-with-blank-continuation",
                '    print("not prose")\n    account_id = 1\n\n    raise RuntimeError(account_id)',
            ),
            ("leading-tab-block", "\tSELECT account_id FROM accounts;"),
        ):
            with self.subTest(name=name):
                text = self.replace_section(self.valid_adr(), "## Context", body)
                self.write("architecture/adr/0009-test-decision.md", text)
                errors = validator.validate_adrs(self.root)
                self.assertTrue(any("## Context must contain prose, a list item, or a link" in error for error in errors), errors)

    def test_substantive_sections_accept_prose_list_items_and_links(self):
        for name, body in (
            ("prose", "A prose statement."),
            ("prose-with-inline-code", "Use `validate_architecture.py` to verify the repository."),
            ("list", "- A list item."),
            ("link", "[A reference](reference.md)"),
        ):
            with self.subTest(name=name):
                text = self.replace_section(self.valid_adr(), "## Context", body)
                self.write("architecture/adr/0009-test-decision.md", text)
                errors = validator.validate_adrs(self.root)
                self.assertFalse(any("## Context must contain" in error for error in errors), errors)

    def test_adr_metadata_fields_are_exact_ordered_and_unique(self):
        cases = (
            ("duplicate", "- Status: Proposed", "- Status: Proposed\n- Status: Proposed"),
            ("reordered", "- Status: Proposed\n- Retrospective: No", "- Retrospective: No\n- Status: Proposed"),
            ("unexpected", "- Scope: Test scope", "- Scope: Test scope\n- Unexpected: value"),
            ("unexpected-punctuation", "- Scope: Test scope", "- Scope: Test scope\n- Unexpected-field: value"),
        )
        for name, old, new in cases:
            with self.subTest(name=name):
                self.write("architecture/adr/0009-test-decision.md", self.valid_adr().replace(old, new, 1))
                errors = validator.validate_adrs(self.root)
                self.assertTrue(any("metadata fields must occur exactly once in the required order" in error for error in errors), errors)

    def test_adr_contract_rejects_invalid_statuses_relationships_and_unbound_evidence(self):
        cases = (
            ("- Status: Proposed", "- Status: Draft", "Status must be one of"),
            ("- Retrospective: No", "- Retrospective: Maybe", "Retrospective must be Yes or No"),
            ("- Implementation status: Not started", "- Implementation status: Started", "Implementation status must be one of"),
            ("- Related commits: None", "- Related commits: ", "Related commits must be None or"),
            ("## Implementation evidence\n\nNone\n", "## Implementation evidence\n\ndeadbeef\n", "Implementation evidence entry"),
            ("## Implementation evidence\n\nNone\n", "## Implementation evidence\n\n- 0000000000000000000000000000000000000000\n- path/to/file\n", "Implementation evidence entry"),
        )
        for old, new, fragment in cases:
            with self.subTest(fragment=fragment):
                self.root.joinpath("architecture/adr/0009-test-decision.md").unlink(missing_ok=True)
                self.write("architecture/adr/0009-test-decision.md", self.valid_adr().replace(old, new, 1))
                errors = validator.validate_adrs(self.root)
                self.assertTrue(any(fragment in error for error in errors), errors)

    def test_evidence_verifies_commit_tree_changed_mode_and_snapshot(self):
        self.init_git()
        self.write("evidence/changed.txt", "v1\n")
        self.write("evidence/unchanged.txt", "stable\n")
        root_commit = self.commit_all("evidence root")
        self.write("evidence/changed.txt", "v2\n")
        changed_commit = self.commit_all("change evidence")
        evidence = (
            f"- {changed_commit} changed: evidence/changed.txt\n"
            f"- {changed_commit} snapshot: evidence/unchanged.txt"
        )
        self.write("architecture/adr/0009-test-decision.md", self.valid_adr(implementation_status="Partial", evidence=evidence))
        self.assertEqual([], validator.validate_adrs(self.root))

        cases = (
            (evidence.replace(changed_commit, "0" * 40, 1), "does not resolve to a commit"),
            (evidence.replace("evidence/changed.txt", "evidence/missing.txt", 1), "does not exist at"),
            (evidence.replace("evidence/changed.txt", "evidence/unchanged.txt", 1), "was not changed by"),
        )
        for invalid, fragment in cases:
            with self.subTest(fragment=fragment):
                self.write("architecture/adr/0009-test-decision.md", self.valid_adr(implementation_status="Partial", evidence=invalid))
                errors = validator.validate_adrs(self.root)
                self.assertTrue(any(fragment in error for error in errors), errors)
        self.assertRegex(root_commit, r"^[0-9a-f]{40}$")

    def test_changed_evidence_fails_when_changed_paths_cannot_be_derived(self):
        self.init_git()
        self.write("evidence/changed.txt", "v1\n")
        commit = self.commit_all("evidence")
        evidence = f"- {commit} changed: evidence/changed.txt"
        self.write(
            "architecture/adr/0009-test-decision.md",
            self.valid_adr(implementation_status="Partial", evidence=evidence),
        )
        with mock.patch.object(validator, "_changed_paths", return_value=None):
            errors = validator.validate_adrs(self.root)
        self.assertTrue(any(f"could not derive changed paths for {commit}" in error for error in errors), errors)

    def test_pull_request_evidence_requires_matching_normalized_github_origin(self):
        for origin in ("git@github.com:Acme/Bank.git", "https://github.com/acme/bank.git"):
            with self.subTest(origin=origin):
                self.tmp.cleanup(); self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
                self.init_git(); self.git("remote", "add", "origin", origin)
                self.write("architecture/adr/0009-test-decision.md", self.valid_adr(implementation_status="Partial", evidence="- https://github.com/ACME/BANK/pull/42"))
                self.assertEqual([], validator.validate_adrs(self.root))
        for origin in (None, "https://gitlab.com/acme/bank.git", "https://github.com/other/bank.git"):
            with self.subTest(origin=origin):
                self.tmp.cleanup(); self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
                self.init_git()
                if origin:
                    self.git("remote", "add", "origin", origin)
                self.write("architecture/adr/0009-test-decision.md", self.valid_adr(implementation_status="Partial", evidence="- https://github.com/acme/bank/pull/42"))
                errors = validator.validate_adrs(self.root)
                self.assertTrue(any("pull-request evidence" in error for error in errors), errors)

    def test_architecture_and_plan_links_are_reciprocal_by_exact_pair(self):
        arc1 = "[Arc one](../arc42/01-introduction-and-goals.md)"
        arc2 = "[Arc two](../arc42/02-constraints.md)"
        plan1 = "[Plan one](../../docs/superpowers/plans/one.md)"
        plan2 = "[Plan two](../../docs/superpowers/plans/two.md)"
        self.write("architecture/adr/0001-manage-architecture-as-versioned-code.md", self.valid_adr(number=1, title="Manage architecture as versioned code", related_architecture=arc1, related_plans=plan1))
        self.write("architecture/adr/0002-second.md", self.valid_adr(number=2, title="Second", related_architecture=arc2, related_plans=plan2))
        self.write("architecture/arc42/01-introduction-and-goals.md", "---\nrelated_adrs:\n  - ADR-0001\n---\n# One\n")
        self.write("architecture/arc42/02-constraints.md", "---\nrelated_adrs:\n  - ADR-0002\n---\n# Two\n")
        self.write("docs/superpowers/plans/one.md", "# One\n\n**Governing ADR:** [ADR-0001: Manage architecture as versioned code](../../../architecture/adr/0001-manage-architecture-as-versioned-code.md)\n")
        self.write("docs/superpowers/plans/two.md", "# Two\n\n[ADR-0002](../../../architecture/adr/0002-second.md)\n")
        self.assertEqual([], validator.validate_adrs(self.root))

        mutations = (
            ("architecture/arc42/01-introduction-and-goals.md", "ADR-0001", "ADR-0002", "does not list ADR-0001"),
            ("docs/superpowers/plans/two.md", "0002-second", "missing", "direct ADR target does not exist"),
            ("architecture/adr/0002-second.md", plan2, "[Missing](../../docs/superpowers/plans/missing.md)", "implementation plan target does not exist"),
        )
        for path, old, new, fragment in mutations:
            with self.subTest(fragment=fragment):
                original = (self.root / path).read_text()
                (self.root / path).write_text(original.replace(old, new, 1))
                errors = validator.validate_adrs(self.root)
                self.assertTrue(any(fragment in error for error in errors), errors)
                (self.root / path).write_text(original)

    def test_adr_relationship_link_fields_reject_ambiguous_multiple_links(self):
        self.write(
            "architecture/adr/0009-test-decision.md",
            self.valid_adr(
                related_architecture="[One](../arc42/one.md) [Two](../arc42/two.md)",
                related_plans="[One](../../docs/superpowers/plans/one.md) [Two](../../docs/superpowers/plans/two.md)",
            ),
        )
        errors = validator.validate_adrs(self.root)
        self.assertTrue(any("must contain exact Markdown-link items" in error for error in errors), errors)

    def test_adr_validation_accepts_a_relative_repository_root(self):
        relative_root = Path(os.path.relpath(self.root, Path.cwd()))
        self.write("architecture/adr/0009-test-decision.md", self.valid_adr())
        self.write(
            "architecture/arc42/01-introduction-and-goals.md",
            "---\nrelated_adrs:\n  - ADR-0009\n---\n# Arc\n",
        )
        errors = validator.validate_adrs(relative_root)
        self.assertTrue(any("does not link back" in error for error in errors), errors)

    def test_foundational_adr_requires_architecture_section(self):
        self.write("architecture/adr/0001-manage-architecture-as-versioned-code.md", self.valid_adr(number=1, title="Manage architecture as versioned code"))
        errors = validator.validate_adrs(self.root)
        self.assertTrue(any("foundational ADR must link" in error for error in errors), errors)

    def test_supersession_edges_require_targets_reciprocity_statuses_and_no_cycles(self):
        self.write("architecture/adr/0009-old.md", self.valid_adr(number=9, title="Old", status="Superseded", superseded_by="ADR-0010"))
        self.write("architecture/adr/0010-new.md", self.valid_adr(number=10, title="New", status="Accepted", supersedes="ADR-0009", implementation_status="Partial", evidence="- https://github.com/acme/bank/pull/1"))
        self.init_git(); self.git("remote", "add", "origin", "https://github.com/acme/bank.git")
        self.assertEqual([], validator.validate_adrs(self.root))
        cases = (
            ("ADR-0010", "ADR-0011", "missing ADR target"),
            ("ADR-0010", "ADR-0009", "self-reference"),
            ("Supersedes: ADR-0009", "Supersedes: None", "non-reciprocal"),
            ("Status: Accepted", "Status: Deprecated", "must be Accepted"),
        )
        for old, new, fragment in cases:
            with self.subTest(fragment=fragment):
                target = self.root / ("architecture/adr/0010-new.md" if old.startswith("Supersedes") or old.startswith("Status") else "architecture/adr/0009-old.md")
                original = target.read_text(); target.write_text(original.replace(old, new, 1))
                errors = validator.validate_adrs(self.root)
                self.assertTrue(any(fragment in error for error in errors), errors)
                target.write_text(original)
        old = self.root / "architecture/adr/0009-old.md"
        old.write_text(old.read_text().replace("Supersedes: None", "Supersedes: ADR-0010"))
        errors = validator.validate_adrs(self.root)
        self.assertTrue(any("cycle" in error for error in errors), errors)

    def test_introduction_requires_proposed_or_qualified_historical_evidence(self):
        self.init_git()
        self.write("evidence/history.txt", "historical\n")
        base = self.commit_all("historical evidence")
        for status in ("Accepted", "Rejected"):
            with self.subTest(status=status):
                evidence = f"- {base} snapshot: evidence/history.txt"
                self.write("architecture/adr/0009-test-decision.md", self.valid_adr(status=status, retrospective="Yes", implementation_status="Partial", evidence=evidence))
                self.assertEqual([], validator.validate_accepted_adr_immutability(self.root, base))
                self.write("architecture/adr/0009-test-decision.md", self.valid_adr(status=status, retrospective="No", implementation_status="Partial", evidence=evidence))
                errors = validator.validate_accepted_adr_immutability(self.root, base)
                self.assertTrue(any("new ADR must be Proposed" in error for error in errors), errors)

        invalid_cases = (
            ("missing", self.valid_adr(status="Accepted", retrospective="Yes", implementation_status="Not started", evidence="None")),
            ("nonexistent", self.valid_adr(status="Accepted", retrospective="Yes", implementation_status="Partial", evidence=f"- {'0' * 40} snapshot: evidence/history.txt")),
            ("path-missing", self.valid_adr(status="Accepted", retrospective="Yes", implementation_status="Partial", evidence=f"- {base} snapshot: evidence/missing.txt")),
            ("mode-invalid", self.valid_adr(status="Accepted", retrospective="Yes", implementation_status="Partial", evidence=f"- {base} observed: evidence/history.txt")),
        )
        for name, text in invalid_cases:
            with self.subTest(name=name):
                self.write("architecture/adr/0009-test-decision.md", text)
                errors = validator.validate_accepted_adr_immutability(self.root, base)
                self.assertTrue(any("new ADR must be Proposed" in error for error in errors), errors)

        self.write("evidence/future.txt", "future\n")
        future = self.commit_all("future evidence")
        future_text = self.valid_adr(
            status="Accepted",
            retrospective="Yes",
            implementation_status="Partial",
            evidence=f"- {future} snapshot: evidence/future.txt",
        )
        self.write("architecture/adr/0009-test-decision.md", future_text)
        future_errors = validator.validate_accepted_adr_immutability(self.root, base)
        self.assertTrue(any("new ADR must be Proposed" in error for error in future_errors), future_errors)
        record = validator._parse_adr("architecture/adr/0009-test-decision.md", future_text.encode())
        self.assertIsNotNone(record)
        self.assertFalse(validator._qualified_historical_introduction(self.root, record, base, future))

    def test_introduction_protection_starts_after_qualified_child(self):
        self.init_git()
        self.write("evidence/history.txt", "historical\n")
        range_base = self.commit_all("historical evidence")
        evidence = f"- {range_base} snapshot: evidence/history.txt"
        path = "architecture/adr/0009-test-decision.md"
        qualified_text = self.valid_adr(
            status="Accepted",
            retrospective="Yes",
            implementation_status="Partial",
            evidence=evidence,
            context="context-v1",
        )
        self.write(path, qualified_text)
        qualified = self.commit_all("qualified retrospective introduction")
        self.write(path, qualified_text.replace("context-v1", "context-v2", 1))
        mutated = self.commit_all("mutate qualified introduction")
        errors = validator.validate_accepted_adr_edge_range(self.root, range_base, mutated)
        self.assertTrue(any(qualified in error and mutated in error and path in error for error in errors), errors)
        self.assertFalse(any(range_base in error and qualified in error for error in errors), errors)

        branch = self.git("branch", "--show-current") or "master"
        self.git("checkout", "-q", "-b", "non-retrospective", range_base)
        self.write(path, qualified_text.replace("Retrospective: Yes", "Retrospective: No", 1))
        non_retrospective = self.commit_all("non-retrospective introduction")
        non_retrospective_errors = validator.validate_accepted_adr_edge_range(self.root, range_base, non_retrospective)
        self.assertTrue(
            any(range_base in error and non_retrospective in error and "new ADR must be Proposed" in error for error in non_retrospective_errors),
            non_retrospective_errors,
        )
        self.git("checkout", "-q", branch)

    def test_exact_adr_0001_bootstrap_exception_is_bound_to_approved_design(self):
        repository = Path(__file__).resolve().parents[3]
        path = "architecture/adr/0001-manage-architecture-as-versioned-code.md"
        task_commit = subprocess.run(
            ["git", "-C", str(repository), "log", "--format=%H", "--fixed-strings", "--grep=docs: record foundational architecture decisions", "-n", "1"],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        ).stdout.strip()
        base = f"{task_commit}^"
        errors = validator.validate_accepted_adr_immutability(repository, base, task_commit)
        self.assertFalse(any(path in error and "new ADR must be Proposed" in error for error in errors), errors)

        clone = self.root / "repository"
        subprocess.run(["git", "clone", "-q", "--shared", str(repository), str(clone)], check=True)
        subprocess.run(["git", "-C", str(clone), "checkout", "-q", base], check=True)
        original = subprocess.run(
            ["git", "-C", str(repository), "show", f"{task_commit}:{path}"],
            check=True,
            stdout=subprocess.PIPE,
        ).stdout
        original_text = original.decode("utf-8")
        mutations = (
            ("title", "# ADR-0001: Manage architecture as versioned code", "# ADR-0001: Manage architecture differently"),
            ("status-rejected", "- Status: Accepted", "- Status: Rejected"),
            ("retrospective", "- Retrospective: No", "- Retrospective: Yes"),
            ("decision-date", "- Decision date: 2026-09-01", "- Decision date: 2026-09-02"),
            ("scope", f"- Scope: {validator.ADR_BOOTSTRAP_SCOPE}", "- Scope: Different scope"),
            ("plan", f"- Related implementation plans: {validator.ADR_BOOTSTRAP_PLAN}", "- Related implementation plans: None"),
            ("evidence-mode", " changed: docs/superpowers/specs/", " snapshot: docs/superpowers/specs/"),
            ("evidence-hash", validator.ADR_BOOTSTRAP_DESIGN_HASH, "0" * 40),
            ("evidence-path", validator.ADR_BOOTSTRAP_DESIGN_PATH, "docs/superpowers/specs/missing.md"),
        )
        target = clone / path
        target.parent.mkdir(parents=True, exist_ok=True)
        for name, old, new in mutations:
            with self.subTest(name=name):
                target.write_text(original_text.replace(old, new, 1))
                mutation_errors = validator.validate_accepted_adr_immutability(clone, base)
                self.assertTrue(any(path in error and "new ADR must be Proposed" in error for error in mutation_errors), mutation_errors)
                target.unlink()

        other_path = "architecture/adr/0002-manage-architecture-as-versioned-code.md"
        other = clone / other_path
        other.write_bytes(original)
        other_errors = validator.validate_accepted_adr_immutability(clone, base)
        self.assertTrue(any(other_path in error and "new ADR must be Proposed" in error for error in other_errors), other_errors)

    def test_accepted_content_status_and_sequences_are_protected_but_legal_appends_pass(self):
        self.init_git(); self.git("remote", "add", "origin", "https://github.com/acme/bank.git")
        self.write("evidence/changed.txt", "v1\n")
        self.write("evidence/stable.txt", "stable\n")
        self.write_valid_adr("Proposed", "context-v1")
        base = self.commit_all("proposed")
        self.write_valid_adr("Accepted", "context-v1")
        accepted = self.commit_all("accepted")
        self.write("evidence/changed.txt", "v2\n")
        evidence_change = self.commit_all("change implementation evidence")
        path = self.root / "architecture/adr/0009-test-decision.md"
        text = path.read_text().replace("Implementation status: Partial", "Implementation status: Complete")
        text = text.replace("- Validation is pending.", "- Validation is pending.\n- Validation completed successfully.")
        text = text.replace(
            "- https://github.com/acme/bank/pull/1",
            "- https://github.com/acme/bank/pull/1\n"
            f"- {evidence_change} changed: evidence/changed.txt\n"
            f"- {evidence_change} snapshot: evidence/stable.txt",
        )
        path.write_text(text)
        self.assertEqual([], validator.validate_accepted_adr_immutability(self.root, evidence_change))
        self.assertEqual([], validator.validate_accepted_adr_edge_range(self.root, base, accepted))
        for old, new, fragment in (
            ("context-v1", "context-v2", "immutable section changed"),
            ("Implementation status: Complete", "Implementation status: Not started", "implementation status"),
            ("pull/1", "pull/9", "append-only sequence"),
            ("- Scope: Test scope", "- Scope: Test scope\n- Extra accepted field: changed", "accepted ADR field changed"),
            ("- Status: Accepted", "- Status: Accepted\n- Status: Accepted", "accepted ADR metadata field layout changed"),
            ("snapshot: evidence/stable.txt", "snapshot: evidence/stable.txt\n- invalid evidence", "Implementation evidence entry is invalid"),
        ):
            original = path.read_text(); path.write_text(original.replace(old, new, 1))
            errors = validator.validate_accepted_adr_immutability(self.root, accepted)
            self.assertTrue(any(fragment in error for error in errors), errors)
            path.write_text(original)

    def test_edge_range_protects_newly_accepted_adr_after_introduction(self):
        self.init_git(); self.git("remote", "add", "origin", "https://github.com/acme/bank.git")
        self.write("README.md", "base\n"); range_base = self.commit_all("base")
        self.write_valid_adr("Proposed", "context-v1"); self.commit_all("introduce proposed")
        self.write_valid_adr("Accepted", "context-v1"); accepted = self.commit_all("accept")
        self.write_valid_adr("Accepted", "context-v2"); mutated = self.commit_all("mutate")
        errors = validator.validate_accepted_adr_edge_range(self.root, range_base, mutated)
        self.assertTrue(any(accepted in error and mutated in error and "context-v2" in error for error in errors), errors)

    def test_edge_range_protects_proposed_adr_accepted_after_base(self):
        self.init_git(); self.git("remote", "add", "origin", "https://github.com/acme/bank.git")
        self.write_valid_adr("Proposed", "context-v1"); base = self.commit_all("base proposed")
        self.write_valid_adr("Accepted", "context-v1"); accepted = self.commit_all("accept")
        self.write_valid_adr("Accepted", "context-v2"); mutated = self.commit_all("mutate")
        self.assertEqual([], validator.validate_accepted_adr_immutability(self.root, base, mutated))
        errors = validator.validate_accepted_adr_edge_range(self.root, base, mutated)
        self.assertTrue(any(accepted in error and mutated in error for error in errors), errors)

    def test_superseded_and_deprecated_records_remain_protected(self):
        for terminal in ("Superseded", "Deprecated"):
            with self.subTest(terminal=terminal):
                self.tmp.cleanup(); self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
                self.init_git(); self.git("remote", "add", "origin", "https://github.com/acme/bank.git")
                self.write_valid_adr("Accepted", "context-v1"); accepted = self.commit_all("accepted")
                self.write_valid_adr(terminal, "context-v1"); terminal_commit = self.commit_all("terminal")
                self.assertEqual([], validator.validate_accepted_adr_immutability(self.root, accepted, terminal_commit))
                path = self.root / "architecture/adr/0009-test-decision.md"
                path.write_text(path.read_text().replace("Related commits: None", "Related commits: terminal-evidence"))
                appended = self.commit_all("append terminal relationship")
                self.assertEqual([], validator.validate_accepted_adr_immutability(self.root, terminal_commit, appended))
                path.write_text(path.read_text().replace("context-v1", "context-v2", 1))
                mutated = self.commit_all("mutate terminal")
                edge_errors = validator.validate_accepted_adr_immutability(self.root, appended, mutated)
                self.assertTrue(any("immutable section changed" in error for error in edge_errors), edge_errors)
                self.assertFalse(any("append-only sequence" in error for error in edge_errors), edge_errors)
                errors = validator.validate_accepted_adr_edge_range(self.root, accepted, mutated)
                self.assertTrue(any(appended in error and mutated in error for error in errors), errors)

    def test_terminal_statuses_cannot_reverse(self):
        statuses = ("Proposed", "Accepted", "Rejected", "Superseded", "Deprecated")
        allowed = {
            ("Proposed", "Proposed"),
            ("Proposed", "Accepted"),
            ("Proposed", "Rejected"),
            ("Accepted", "Accepted"),
            ("Accepted", "Superseded"),
            ("Accepted", "Deprecated"),
            ("Rejected", "Rejected"),
            ("Superseded", "Superseded"),
            ("Deprecated", "Deprecated"),
        }
        forbidden = tuple(
            (parent_status, child_status)
            for parent_status in statuses
            for child_status in statuses
            if (parent_status, child_status) not in allowed
        )
        self.assertEqual(16, len(forbidden))
        for parent_status, child_status in forbidden:
            with self.subTest(pair=(parent_status, child_status)):
                self.tmp.cleanup(); self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
                self.init_git(); self.git("remote", "add", "origin", "https://github.com/acme/bank.git")
                self.write_valid_adr(parent_status, "context-v1"); parent = self.commit_all("parent")
                self.write_valid_adr(child_status, "context-v1")
                errors = validator.validate_accepted_adr_immutability(self.root, parent)
                self.assertTrue(any(f"{parent_status} -> {child_status}" in error for error in errors), errors)

    def test_accepted_record_cannot_be_deleted_or_renamed(self):
        self.init_git(); self.git("remote", "add", "origin", "https://github.com/acme/bank.git")
        self.write_valid_adr("Accepted", "context-v1"); base = self.commit_all("accepted")
        original = self.root / "architecture/adr/0009-test-decision.md"
        content = original.read_text(); original.unlink()
        errors = validator.validate_accepted_adr_immutability(self.root, base)
        self.assertTrue(any("deleted or renamed" in error and "0009-test-decision.md" in error for error in errors), errors)
        self.write("architecture/adr/0010-moved.md", content)
        errors = validator.validate_accepted_adr_immutability(self.root, base)
        self.assertTrue(any("deleted or renamed" in error and "0009-test-decision.md" in error for error in errors), errors)

    def test_proposed_records_may_remain_unchanged_or_be_revised(self):
        self.init_git()
        self.write_valid_adr("Proposed", "context-v1"); base = self.commit_all("proposed")
        self.write("unrelated.txt", "unchanged ADR\n"); unchanged = self.commit_all("unchanged proposed")
        self.assertEqual([], validator.validate_accepted_adr_immutability(self.root, base, unchanged))
        self.assertEqual([], validator.validate_accepted_adr_edge_range(self.root, base, unchanged))
        text = self.valid_adr(context="context-v2", implementation_status="Not applicable", related_commits="abc", compliance="- revised", evidence="None")
        self.write("architecture/adr/0009-test-decision.md", text)
        revised = self.commit_all("revised proposed")
        self.assertEqual([], validator.validate_accepted_adr_immutability(self.root, base, revised))
        self.assertEqual([], validator.validate_accepted_adr_edge_range(self.root, base, revised))
        self.assertNotEqual(base, unchanged)

    def test_rejected_records_are_permanent_same_path_bytes(self):
        self.init_git(); self.git("remote", "add", "origin", "https://github.com/acme/bank.git")
        self.write_valid_adr("Proposed", "context-v1"); base = self.commit_all("proposed")
        self.write_valid_adr("Rejected", "context-final"); rejected = self.commit_all("rejected")
        self.write("unrelated.txt", "one\n"); unchanged = self.commit_all("unchanged rejected")
        self.assertEqual([], validator.validate_accepted_adr_immutability(self.root, rejected, unchanged))
        path = self.root / "architecture/adr/0009-test-decision.md"
        original = path.read_bytes()
        for name, mutate in (
            ("rationale", lambda: path.write_bytes(original.replace(b"context-final", b"context-mutated"))),
            ("relationship", lambda: path.write_bytes(original.replace(b"Related commits: None", b"Related commits: later"))),
            ("evidence", lambda: path.write_bytes(original + b"\n- evidence append\n")),
            ("deletion", lambda: path.unlink()),
        ):
            with self.subTest(name=name):
                mutate(); errors = validator.validate_accepted_adr_immutability(self.root, rejected)
                self.assertTrue(any("Rejected record must remain byte-identical" in error or "deleted or renamed" in error for error in errors), errors)
                path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(original)
        path.rename(self.root / "architecture/adr/0010-renamed.md")
        errors = validator.validate_accepted_adr_immutability(self.root, rejected)
        self.assertTrue(any("deleted or renamed" in error for error in errors), errors)
        self.assertEqual([], validator.validate_accepted_adr_edge_range(self.root, base, unchanged))

    def test_merge_checks_every_parent(self):
        self.init_git(); self.git("remote", "add", "origin", "https://github.com/acme/bank.git")
        self.write_valid_adr("Accepted", "context-v1"); base = self.commit_all("accepted")
        first_branch = self.git("branch", "--show-current") or "master"
        self.git("checkout", "-q", "-b", "second")
        self.write_valid_adr("Accepted", "context-v2"); second = self.commit_all("second mutates")
        self.git("checkout", "-q", first_branch)
        self.write("unrelated.txt", "first\n"); first = self.commit_all("first unrelated")
        self.git("merge", "--no-commit", "--no-ff", "second", check=False)
        self.git("checkout", first, "--", "architecture/adr/0009-test-decision.md")
        self.git("add", "-A"); self.git("commit", "-q", "-m", "merge restored")
        merge = self.git("rev-parse", "HEAD")
        parents = self.git("show", "-s", "--format=%P", merge).split()
        self.assertEqual([first, second], parents)
        self.assertEqual([], validator.validate_accepted_adr_immutability(self.root, base, merge))
        self.assertEqual([], validator.validate_accepted_adr_immutability(self.root, first, merge))
        second_errors = validator.validate_accepted_adr_immutability(self.root, second, merge)
        self.assertTrue(any("immutable section changed" in error for error in second_errors), second_errors)
        errors = validator.validate_accepted_adr_edge_range(self.root, base, merge)
        self.assertTrue(any(second in error and merge in error for error in errors), errors)

    def test_cli_requires_paired_edge_refs(self):
        self.assertEqual(2, validator.main(["--root", str(self.root), "--adr-edge-base-ref", "HEAD"]))
        self.assertEqual(2, validator.main(["--root", str(self.root), "--adr-edge-head-ref", "HEAD"]))
        self.assertEqual(2, validator.main(["--root", str(self.root), "--adr-head-ref", "HEAD"]))

    def test_cli_git_aware_endpoint_and_range_checks_are_additive(self):
        self.init_git()
        self.write("README.md", "ordinary repository remains invalid\n")
        commit = self.commit_all("base")
        self.assertEqual(
            1,
            validator.main(
                [
                    "--root",
                    str(self.root),
                    "--adr-base-ref",
                    commit,
                    "--adr-head-ref",
                    commit,
                ]
            ),
        )
        self.assertEqual(
            1,
            validator.main(
                [
                    "--root",
                    str(self.root),
                    "--adr-edge-base-ref",
                    commit,
                    "--adr-edge-head-ref",
                    commit,
                ]
            ),
        )


if __name__ == "__main__":
    unittest.main()
