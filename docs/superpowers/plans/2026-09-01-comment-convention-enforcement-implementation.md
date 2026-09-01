# Comment Convention Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the machine-checkable parts of `docs/conventions/code-comments.md` fail the `services/funds-core` build, so that a public type without a purpose comment, a malformed Javadoc summary, a migration without a header block, or a work item left in source is caught in seconds instead of in review.

**Architecture:** One Checkstyle configuration file plus one plugin execution in the funds-core POM. Java rules run under `TreeWalker` (`fileExtensions=java`); the migration-header rule and the work-item rule are Checker-level file rules scoped by file extension, so a single configuration covers `.java` and `.sql` with no second tool and no shell script. The execution binds to `validate`, ahead of the slow Testcontainers gate. No rule is added without a canary: each one is proven load-bearing by planting a violation, watching the build fail, then reverting.

**Tech Stack:** Maven 3.9.16+ (wrapper), maven-checkstyle-plugin 3.6.0, Checkstyle 14.1.0, Java 25 (mise-pinned by `services/funds-core/mise.toml`), Quarkus 3.33.3.1.

**Governing document:** `docs/conventions/code-comments.md`. There is no separate spec. The "Enforcement" section of that document (lines 154-164 at commit `3812f00`) is the requirement this plan implements; Task 4 rewrites that section to describe what shipped.

**Base commit:** `3812f00` on `master` ("Ignore native tool worktrees under .claude").

## Global Constraints

- Every command runs inside whichever checkout the executor was given — the main checkout or an isolated worktree. Each command block anchors itself with `cd "$(git rev-parse --show-toplevel)"` instead of an absolute path, because this repository executes implementation plans in worktrees (`superpowers:using-git-worktrees`), and a worktree-isolated session is refused outright if a git command targets the shared checkout. Never substitute a literal path.
- Checkstyle is pinned to exactly `14.1.0` and the plugin to exactly `3.6.0`. The plugin bundles Checkstyle `9.3` (2022), which cannot parse Java 25 sources, so the `<dependencies>` override is required for the build to work at all — it is not a preference.
- Java 25 only. `services/funds-core/mise.toml` pins `java = "25"` and the POM's enforcer rule is `[25,26)`. Every Maven command in this plan is run from `services/funds-core`, never from the repository root with `-f`, because mise resolves the toolchain by working directory and the root selects JDK 27.
- The Checkstyle configuration contains no Maven property expansion (`${...}`), so the same file can be run standalone with the Checkstyle CLI. Do not introduce `propertyExpansion`.
- The only source-file edits in this plan are two Javadoc comments in one test-support file (Task 3). No production Java, SQL, or configuration behaviour changes. `git diff` on `services/funds-core/src/main` must be empty at the end.
- No canary probe file may survive its task. Each canary step is followed by a delete step and a `git status --porcelain` check before the commit.
- Never run `./mvnw verify` (or any test goal) while a canary migration file exists under `src/main/resources/db/migration/`; Flyway would apply it in the Testcontainers database. Canary migrations are validated with `checkstyle:check` only.
- Rules that cannot be checked mechanically stay in human review. "A comment that restates its code" and "accuracy over coverage" are review rules, not build rules, and this plan does not attempt to automate them.
- `./mvnw clean verify` requires Docker (PostgreSQL Testcontainers). Docker is not reachable from Claude Code sessions on this host, so the full gate is the human partner's step and must be reported as "not run" by any agent that could not run it.
- Every task ends with its own verification and a commit before the next task starts.

## Baseline evidence

Measured on 2026-09-01 against commit `3812f00`, using the Checkstyle 14.1.0 CLI (reproduction commands in the Appendix). These numbers are why this PR needs no remediation work on `src/main`.

| Measurement | Result |
|---|---|
| Public types in `src/main` under `MissingJavadocType` (scope `public`) | 44 types, **0 violations** |
| `InvalidJavadocPosition` on `src/main` | 0 violations |
| `SummaryJavadoc`, `JavadocContentLocation`, `JavadocMissingWhitespaceAfterAsterisk`, `NonEmptyAtclauseDescription` on `src/main` | 0 violations |
| `JavadocParagraph`, `SingleLineJavadoc` on `src/main` (measured, then excluded by decision D4) | 0 violations |
| `MissingJavadocType` on `src/test` | **2 violations** — nested records `ModelLine` (`ReferenceLedgerModel.java:234`) and `SuccessfulCommand` (`:258`) |
| Migration header blocks | All 8 migrations open with a `-- Vxxx:` block, 3 to 12 lines long; all pass `RegexpHeader` |
| `TODO` / `FIXME` / `XXX` in `services/funds-core` | 0 occurrences |
| `JavadocStyle` (named in the convention document) | **Does not exist.** Removed in Checkstyle 13; both 13.11.0 and 14.1.0 fail with `cannot initialize module JavadocStyle`. This is the one factual error in the current convention document |
| Checkstyle 14.1.0 parsing Java 25 sources | 0 parse errors across all 43 main and 25 test files |
| Checkstyle bundled with maven-checkstyle-plugin 3.6.0 | `9.3` (confirmed from the plugin POM's `checkstyleVersion` property) |

Parameter names used in the POM below were confirmed against `META-INF/maven/plugin.xml` inside `maven-checkstyle-plugin-3.6.0.jar`. `configLocation`, `sourceDirectories`, `testSourceDirectories`, `includeResources`, `resourceIncludes`, `includeTestResources`, `includeTestSourceDirectory`, `violationSeverity`, `failOnViolation`, `failsOnError`, `consoleOutput` and `skip` all exist on the `check` goal. **`linkXRef` does not exist on `check`** (it is a reporting-goal parameter) and must not be added. Relevant defaults: `includeResources=true`, `includeTestResources=true`, `resourceIncludes=**/*.properties`, `includeTestSourceDirectory=false`, `consoleOutput=false`, `failsOnError=false`, `violationSeverity=error`, `failOnViolation=true`, `configLocation=sun_checks.xml`.

## Decisions

These were settled before the plan was written. Do not re-open them during execution.

- **D1 — Test sources are in scope.** The convention already requires test-support classes to carry type comments, and the cost is exactly two short Javadoc blocks (Task 3). Test sources get `MissingJavadocType` and the rest of the `TreeWalker` set on the same terms as main sources.
- **D2 — The execution binds to `validate`, not `verify`.** It fails in seconds rather than after the Testcontainers run. `mvn verify` runs `validate` first, so the checklist statement "the gate runs in `./mvnw clean verify`" stays true.
- **D3 — The migration-header check is Checkstyle `RegexpHeader`, not a shell script.** No bash/Windows split, no `exec-maven-plugin`, no second failure format, and it is proven to catch both a missing header and a one-line header.
- **D4 — Excluded rules.** `MissingJavadocMethod` is excluded because it pushes authors toward restating code, which the convention explicitly forbids. `JavadocParagraph` and `SingleLineJavadoc` are excluded as layout policing: both pass today, but they generate friction without protecting any invariant.
- **D5 — The configuration lives at `services/funds-core/config/checkstyle/checkstyle.xml`.** funds-core is the only Java module and there is no aggregator POM. When a second module lands, move the file to a repository-root `config/checkstyle/` and reference it from a parent POM.

## File Structure

- Create `services/funds-core/config/checkstyle/checkstyle.xml` — the entire ruleset. Grows across Tasks 1 and 2; nothing else in the repository is allowed to define comment rules.
- Modify `services/funds-core/pom.xml` — one plugin block, extended in Tasks 1, 2 and 3.
- Modify `services/funds-core/src/test/java/com/corebanking/funds/testsupport/ReferenceLedgerModel.java` — two Javadoc blocks (Task 3).
- Modify `docs/conventions/code-comments.md` — the Enforcement section (Task 4).
- Modify `AGENTS.md` — the pull-request checklist (Task 4).

---

### Task 1: Java Javadoc rules on main sources

**Files:**
- Create: `services/funds-core/config/checkstyle/checkstyle.xml`
- Modify: `services/funds-core/pom.xml` (inside `<build><plugins>`, after the surefire plugin)
- Canary (created and deleted within this task): `services/funds-core/src/main/java/com/corebanking/funds/CanaryProbe.java`

**Interfaces:**
- Produces: the `Checker` → `TreeWalker` skeleton that Task 2 adds Checker-level modules to, and the plugin block that Tasks 2 and 3 extend.

- [ ] **Step 1: Confirm the toolchain before touching anything**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw -v
```

Expected: `Java version: 25.0.2`. If it reports 27, you are in the wrong directory or mise is not resolving; prefix every later Maven command with `mise exec java@25 --`.

- [ ] **Step 2: Create the Checkstyle configuration**

Create `services/funds-core/config/checkstyle/checkstyle.xml`:

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
  "https://checkstyle.org/dtds/configuration_1_3.dtd">

<!--
  The machine-checkable subset of docs/conventions/code-comments.md. Rules that a tool
  cannot judge - whether a comment restates its code, whether a claim is true - stay in
  human review; this file is deliberately not a style guide for the module.
-->
<module name="Checker">
  <property name="severity" value="error"/>

  <module name="TreeWalker">
    <property name="fileExtensions" value="java"/>

    <!-- Convention: every public type carries a one-block purpose comment. Nested public
         types count; they are the ones authors forget. -->
    <module name="MissingJavadocType">
      <property name="scope" value="public"/>
    </module>

    <!-- A Javadoc block between an annotation and its declaration documents nothing: the
         compiler and the IDE both drop it. -->
    <module name="InvalidJavadocPosition"/>

    <!-- Convention: the first sentence is a real summary and ends with a period. These four
         replace JavadocStyle, which the convention document named but which was removed in
         Checkstyle 13. -->
    <module name="SummaryJavadoc"/>
    <module name="JavadocContentLocation"/>
    <module name="JavadocMissingWhitespaceAfterAsterisk"/>
    <module name="NonEmptyAtclauseDescription"/>

    <!-- MissingJavadocMethod is deliberately absent: requiring Javadoc on every method
         produces comments that restate the signature, which the convention forbids. -->
  </module>
</module>
```

The DTD is resolved from Checkstyle's own jar, not fetched over the network, so this works offline.

- [ ] **Step 3: Wire the plugin into the POM**

In `services/funds-core/pom.xml`, add inside `<build><plugins>` after the `maven-surefire-plugin` block:

```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId><artifactId>maven-checkstyle-plugin</artifactId><version>3.6.0</version>
                <!-- The plugin bundles Checkstyle 9.3, which cannot parse Java 25 sources. -->
                <dependencies>
                    <dependency>
                        <groupId>com.puppycrawl.tools</groupId><artifactId>checkstyle</artifactId><version>14.1.0</version>
                    </dependency>
                </dependencies>
                <!-- Plugin-level rather than execution-level so that `mvnw checkstyle:check` on
                     the command line behaves exactly like the bound execution. -->
                <configuration>
                    <configLocation>${project.basedir}/config/checkstyle/checkstyle.xml</configLocation>
                    <!-- Pinned: the default is the project's compile source roots, which Quarkus
                         extends with generated code that no convention applies to. -->
                    <sourceDirectories>
                        <sourceDirectory>${project.basedir}/src/main/java</sourceDirectory>
                    </sourceDirectories>
                    <includeResources>false</includeResources>
                    <includeTestResources>false</includeTestResources>
                    <consoleOutput>true</consoleOutput>
                    <violationSeverity>error</violationSeverity>
                    <failOnViolation>true</failOnViolation>
                    <!-- A Checkstyle crash - a parse failure on new syntax, a missing config -
                         must fail the build rather than pass silently. The default is false. -->
                    <failsOnError>true</failsOnError>
                </configuration>
                <executions>
                    <execution>
                        <id>check-comment-conventions</id>
                        <phase>validate</phase>
                        <goals><goal>check</goal></goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 4: Prove the gate passes on the untouched module**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw checkstyle:check
```

Expected: `BUILD SUCCESS`, no `[ERROR]` lines from Checkstyle. First run downloads the plugin and Checkstyle 14.1.0 (~15 MB). If it fails with `cannot initialize module <name>`, the rule name is wrong for Checkstyle 14 — fix the config, do not downgrade.

- [ ] **Step 5: Plant the first canary**

Create `services/funds-core/src/main/java/com/corebanking/funds/CanaryProbe.java`:

```java
package com.corebanking.funds;

@Deprecated
/** */
public class CanaryProbe {

    public record Inner(int a) {}
}
```

- [ ] **Step 6: Run the gate and confirm it fails on all three rules**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw checkstyle:check
```

Expected: `BUILD FAILURE`, with three Checkstyle violations. Match on the bracketed rule names rather than whole lines — the console format comes from Checkstyle's `DefaultLogger`:

```
CanaryProbe.java:3:1: Missing a Javadoc comment for 'CanaryProbe'. [MissingJavadocType]
CanaryProbe.java:4:1: Javadoc comment is placed in the wrong location. [InvalidJavadocPosition]
CanaryProbe.java:7:5: Missing a Javadoc comment for 'Inner'. [MissingJavadocType]
```

If the build passes here, the gate is not wired: check that `configLocation` resolved (a wrong path silently falls back to `sun_checks.xml`, which produces hundreds of unrelated violations instead — a pass means the file was not scanned at all).

- [ ] **Step 7: Replace the canary to prove the summary rule**

Overwrite `services/funds-core/src/main/java/com/corebanking/funds/CanaryProbe.java` with:

```java
package com.corebanking.funds;

/** */
public class CanaryProbe {

    /** returns a value */
    public int value() {
        return 1;
    }
}
```

- [ ] **Step 8: Run the gate and confirm the summary rule fires**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw checkstyle:check
```

Expected: `BUILD FAILURE` with:

```
CanaryProbe.java:3:4: Summary javadoc is missing. [SummaryJavadoc]
CanaryProbe.java:6:8: First sentence of Javadoc is missing an ending period. [SummaryJavadoc]
```

This is also the proof that `MissingJavadocMethod` is absent: the undocumented-method case is not reported, only the malformed-Javadoc case.

- [ ] **Step 9: Remove the canary and confirm a clean run**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
rm src/main/java/com/corebanking/funds/CanaryProbe.java
./mvnw checkstyle:check
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 10: Prove the lifecycle binding, not just the goal**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw validate
```

Expected: `BUILD SUCCESS`, and the log contains `--- checkstyle:3.6.0:check (check-comment-conventions) @ funds-core ---`. This also proves the enforcer accepted the JDK.

- [ ] **Step 11: Confirm no canary survived, then commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git status --porcelain
```

Expected: exactly two entries — `?? services/funds-core/config/` and ` M services/funds-core/pom.xml`. If `CanaryProbe.java` appears, delete it before continuing.

```bash
git add services/funds-core/config/checkstyle/checkstyle.xml services/funds-core/pom.xml
git commit -m "Enforce Javadoc conventions on funds-core main sources"
```

---

### Task 2: Migration headers and the work-item ban

**Files:**
- Modify: `services/funds-core/config/checkstyle/checkstyle.xml` (add two Checker-level modules)
- Modify: `services/funds-core/pom.xml` (turn resource scanning on and narrow it to migrations)
- Canary (created and deleted within this task): `services/funds-core/src/main/resources/db/migration/V999__canary.sql`

**Interfaces:**
- Consumes: the `Checker` element and the plugin block from Task 1.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Add the two Checker-level rules**

In `services/funds-core/config/checkstyle/checkstyle.xml`, insert immediately after `<property name="severity" value="error"/>` and before `<module name="TreeWalker">`:

```xml
  <!-- Convention: work items live in the tracker or the plan documents, never in source. -->
  <module name="RegexpSingleline">
    <property name="format" value="\b(TODO|FIXME|XXX)\b"/>
    <property name="fileExtensions" value="java,sql"/>
    <property name="message" value="Work items belong in the tracker, not in source."/>
  </module>

  <!-- Convention: every migration opens with a SQL line-comment header block, whose first line
       is 'Vxxx:' plus what the migration changes and why, relative to the previous version.
       Two lines is the floor, not the target. -->
  <module name="RegexpHeader">
    <property name="fileExtensions" value="sql"/>
    <property name="header" value="^-- V[0-9_]+.*$\n^--.*$"/>
  </module>
```

Two things in that block are easy to get wrong:

- **Never write a doubled hyphen inside an XML comment.** XML forbids the sequence anywhere in a comment body, not just at its end, and Checkstyle then rejects the whole file with `CheckstyleException: unable to parse configuration stream - The string "--" is not permitted within comments.` — which loads no rules at all, rather than failing one. That is why the comment above says "SQL line-comment header block" instead of quoting the marker. The `--` inside the `header` property *value* is fine: attribute values are not comments.
- The `\n` inside the `header` value is Checkstyle's own line separator for multi-line headers; leave it as a literal two-character escape in the XML.

- [ ] **Step 2: Let the plugin see the migration resources**

In `services/funds-core/pom.xml`, inside the checkstyle plugin's `<configuration>`, replace

```xml
                    <includeResources>false</includeResources>
```

with

```xml
                    <includeResources>true</includeResources>
                    <!-- Default is **/*.properties; migrations are the only resources with a
                         comment convention. -->
                    <resourceIncludes>db/migration/*.sql</resourceIncludes>
```

Leave `<includeTestResources>false</includeTestResources>` as it is.

- [ ] **Step 3: Prove the eight real migrations pass**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw checkstyle:check
```

Expected: `BUILD SUCCESS`. The log lists the audited resource count; if it does not grow by 8 relative to Task 1, `resourceIncludes` is not matching — the pattern is relative to `src/main/resources`, not to the module root.

- [ ] **Step 4: Plant a header-less migration canary**

Create `services/funds-core/src/main/resources/db/migration/V999__canary.sql`:

```sql
CREATE TABLE funds.canary(id int);
```

Do not run `verify` or any test goal while this file exists.

- [ ] **Step 5: Confirm the header rule fires**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw checkstyle:check
```

Expected: `BUILD FAILURE` with `V999__canary.sql:1: Missing a header - not enough lines in file. [RegexpHeader]`.

- [ ] **Step 6: Weaken the canary to a one-line header and re-run**

Overwrite `services/funds-core/src/main/resources/db/migration/V999__canary.sql` with:

```sql
-- V999: canary.
CREATE TABLE funds.canary(id int);
```

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw checkstyle:check
```

Expected: `BUILD FAILURE` with `V999__canary.sql:2: Line does not match expected header line of '^--.*$'. [RegexpHeader]`. This proves a token one-line header does not satisfy the rule.

- [ ] **Step 7: Convert the canary into a work-item canary**

Overwrite `services/funds-core/src/main/resources/db/migration/V999__canary.sql` with:

```sql
-- V999: canary.
-- FIXME tighten this constraint
CREATE TABLE funds.canary(id int);
```

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw checkstyle:check
```

Expected: `BUILD FAILURE` with `V999__canary.sql:2: Work items belong in the tracker, not in source. [RegexpSingleline]`, and no `RegexpHeader` violation — the header is now valid.

- [ ] **Step 8: Prove the work-item rule also covers Java**

Create `services/funds-core/src/main/java/com/corebanking/funds/CanaryProbe.java`:

```java
package com.corebanking.funds;

/** Canary type, deleted in the next step. */
public class CanaryProbe {

    // TODO handle overflow
    private int x;
}
```

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw checkstyle:check
```

Expected: `BUILD FAILURE` with `CanaryProbe.java:6: Work items belong in the tracker, not in source. [RegexpSingleline]`.

- [ ] **Step 9: Delete both canaries and confirm a clean run**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
rm src/main/resources/db/migration/V999__canary.sql
rm src/main/java/com/corebanking/funds/CanaryProbe.java
./mvnw checkstyle:check
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 10: Confirm no canary survived, then commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git status --porcelain
```

Expected: exactly two modified files, `services/funds-core/config/checkstyle/checkstyle.xml` and `services/funds-core/pom.xml`. No untracked file under `src/`.

```bash
git add services/funds-core/config/checkstyle/checkstyle.xml services/funds-core/pom.xml
git commit -m "Enforce migration headers and the work-item ban"
```

---

### Task 3: Extend the gate to test sources

**Files:**
- Modify: `services/funds-core/pom.xml`
- Modify: `services/funds-core/src/test/java/com/corebanking/funds/testsupport/ReferenceLedgerModel.java` (two Javadoc blocks)

**Interfaces:**
- Consumes: the plugin block from Tasks 1 and 2.

- [ ] **Step 1: Turn test sources on and watch the gate fail**

In `services/funds-core/pom.xml`, inside the checkstyle plugin's `<configuration>`, add after the `<sourceDirectories>` block:

```xml
                    <includeTestSourceDirectory>true</includeTestSourceDirectory>
                    <testSourceDirectories>
                        <testSourceDirectory>${project.basedir}/src/test/java</testSourceDirectory>
                    </testSourceDirectories>
```

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw checkstyle:check
```

Expected: `BUILD FAILURE` with exactly two violations:

```
ReferenceLedgerModel.java:234:5: Missing a Javadoc comment for 'ModelLine'. [MissingJavadocType]
ReferenceLedgerModel.java:258:5: Missing a Javadoc comment for 'SuccessfulCommand'. [MissingJavadocType]
```

This failure is the test for this task: it proves test sources are genuinely being scanned. If more than these two appear, stop and report — the baseline has changed since `3812f00` and the extra violations need reading, not silencing.

- [ ] **Step 2: Document `ModelLine`**

In `services/funds-core/src/test/java/com/corebanking/funds/testsupport/ReferenceLedgerModel.java`, above `public record ModelLine(` at line 234, add:

```java
    /** One posting line as the model stores it; the dimension map is copied so a stored journal cannot change afterwards. */
```

- [ ] **Step 3: Document `SuccessfulCommand`**

Above `public record SuccessfulCommand(` at what is now line 259, add:

```java
    /** A command the model accepted: the stored hash separates a retry from a conflict, and the stored result is what a same-hash retry must return. */
```

- [ ] **Step 4: Confirm the gate is green and nothing else moved**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw checkstyle:check
```

Expected: `BUILD SUCCESS`.

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw -q -DskipTests compile test-compile
```

Expected: success. Comments cannot break compilation, but this catches a stray edit.

- [ ] **Step 5: Prove the edit was comment-only**

```bash
cd "$(git rev-parse --show-toplevel)"
git diff -U0 -- services/funds-core/src/test/java/com/corebanking/funds/testsupport/ReferenceLedgerModel.java
```

Expected: exactly two added lines, both beginning with `+` then whitespace then `/**`. No other change of any kind.

- [ ] **Step 6: Commit, comments separately from wiring**

Two commits, not one. The comments are documentation that stands on its own; the POM change is the gate. Splitting them is what makes the Rollback section true — reverting the gate must not delete the two Javadoc blocks.

```bash
cd "$(git rev-parse --show-toplevel)"
git add services/funds-core/src/test/java/com/corebanking/funds/testsupport/ReferenceLedgerModel.java
git commit -m "Document the nested records in ReferenceLedgerModel"
git add services/funds-core/pom.xml
git commit -m "Extend comment enforcement to funds-core test sources"
```

Expected: `git status --porcelain` is empty afterwards.

---

### Task 4: Record what shipped in the conventions

**Files:**
- Modify: `docs/conventions/code-comments.md:154-164`
- Modify: `AGENTS.md` (the "Before opening a pull request" checklist)

**Interfaces:**
- Consumes: the shipped configuration from Tasks 1-3. Do not write this task before they are committed; the text must describe what exists.

- [ ] **Step 1: Replace the Enforcement section**

In `docs/conventions/code-comments.md`, replace the whole `## Enforcement` section (from `## Enforcement` to the end of the file) with:

```markdown
## Enforcement

The machine-checkable part of this document runs as Checkstyle in the
`services/funds-core` build, bound to `validate` so it fails before the test
gate. The ruleset is `services/funds-core/config/checkstyle/checkstyle.xml`:

- `MissingJavadocType` (scope `public`, main and test sources) — every public
  type, including nested ones, carries a purpose comment.
- `InvalidJavadocPosition` — a Javadoc block wedged between an annotation and
  its declaration documents nothing.
- `SummaryJavadoc`, `JavadocContentLocation`,
  `JavadocMissingWhitespaceAfterAsterisk`, `NonEmptyAtclauseDescription` — the
  first sentence is a real summary and ends with a period. These replace
  `JavadocStyle`, which Checkstyle removed in version 13.
- `RegexpHeader` on `db/migration/*.sql` — every migration opens with a
  `-- Vxxx:` header block of at least two lines.
- `RegexpSingleline` on `.java` and `.sql` — no `TODO`, `FIXME` or `XXX`.

`MissingJavadocMethod` is deliberately not enabled: requiring Javadoc on every
method produces comments that restate the signature.

Run it alone with `./mvnw checkstyle:check` from `services/funds-core`. The
Checkstyle version is pinned in the POM because the plugin's bundled 9.3 cannot
parse Java 25.

The rest of this document — whether a comment restates its code, whether a
claim is true, whether the right concept is named — is not mechanizable and
stays with the reviewer.
```

- [ ] **Step 2: Add the checklist line**

In `AGENTS.md`, in "Before opening a pull request", after the line `- [ ] No comment restates its code; no `TODO`/`FIXME` was added.`, add:

```markdown
- [ ] `./mvnw checkstyle:check` passes in `services/funds-core` (it also runs
      inside `validate`, so a violation fails the build before any test does).
```

- [ ] **Step 3: Verify the documents describe reality**

Re-read both edits against the committed `checkstyle.xml`. Every rule named in the document must exist in the file, and every rule in the file must be named in the document. A convention document that lists a rule Checkstyle does not have is the exact defect this plan was written to fix.

- [ ] **Step 4: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add docs/conventions/code-comments.md AGENTS.md
git commit -m "Record comment-convention enforcement in the conventions"
```

---

## Final Verification

- [ ] **1. The working tree holds nothing but the intended change**

```bash
cd "$(git rev-parse --show-toplevel)"
git status --porcelain
git diff --stat 3812f00..HEAD
```

Expected: clean status; five files changed — `AGENTS.md`, `docs/conventions/code-comments.md`, `services/funds-core/config/checkstyle/checkstyle.xml`, `services/funds-core/pom.xml`, `ReferenceLedgerModel.java`.

- [ ] **2. No canary artefact survived**

```bash
cd "$(git rev-parse --show-toplevel)"
git log --diff-filter=A --name-only --format= 3812f00..HEAD | sort -u
ls services/funds-core/src/main/resources/db/migration/
```

Expected: the only added file in the range is `services/funds-core/config/checkstyle/checkstyle.xml`; the migration directory holds exactly the eight `V001`-`V006` files, with no `V999`.

- [ ] **3. No production behaviour changed**

```bash
cd "$(git rev-parse --show-toplevel)"
git diff 3812f00..HEAD -- services/funds-core/src/main
```

Expected: empty.

- [ ] **4. The gate is green from a cold start**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw clean
./mvnw validate
```

Expected: `BUILD SUCCESS` with the `check-comment-conventions` execution in the log.

- [ ] **5. The full test gate — human partner's step**

```bash
cd "$(git rev-parse --show-toplevel)/services/funds-core"
./mvnw clean verify
```

Requires Docker for PostgreSQL Testcontainers, which is unreachable from Claude Code sessions on this host. An agent that cannot run this must report it as "not run", never as passed. `MigrationIT` must still pass: this plan adds no migration and edits no migration text, so its `V004` string assertions are unaffected, but the run is the proof.

## Rollback

The wiring is one plugin block. To disable the gate for a single run without touching history, pass `-Dcheckstyle.skip=true`.

To remove it permanently, revert in reverse order the three commits that touch `pom.xml` and `checkstyle.xml`: Task 3's `Extend comment enforcement to funds-core test sources`, Task 2's `Enforce migration headers and the work-item ban`, then Task 1's `Enforce Javadoc conventions on funds-core main sources`. This is why Task 3 commits its two Javadoc blocks separately: those two comments, and Task 4's documentation, are not part of any revert and stay. If Task 4's text was already committed, correct it in the same change — a convention document describing a gate that no longer exists is the defect this plan set out to remove.

## Risks

| Risk | Mitigation |
|---|---|
| maven-checkstyle-plugin 3.6.0 + Checkstyle 14.1.0 is untested in this build (the standalone CLI was used for the baseline) | Task 1 Step 4 catches it in seconds. If they are incompatible, fall back to Checkstyle 13.11.0, which was separately confirmed to parse the same sources. Do not fall back below 13 — the syntax support is the point |
| First build needs the network for ~15 MB of plugin dependencies | One-time; no offline build exists today. Nothing in the plan requires network at run time |
| Quarkus adds generated source roots that Checkstyle would scan | `sourceDirectories` is pinned to `src/main/java` (Task 1 Step 3) |
| `configLocation` silently falling back to `sun_checks.xml` | Task 1 Step 6 would show hundreds of unrelated violations instead of exactly three; the canary distinguishes the two failure modes |
| A future rule change makes the baseline dirty | Every rule in this plan was measured at 0 violations before being added; keep that discipline — measure, then enable |

## Out of Scope

Shell-script headers (the convention requires one and `services/funds-core/scripts/prod-runtime-smoke.sh` has none — it predates the commenting work; that is a separate one-comment change, not a rider on this one), formatting and import rules, `MissingJavadocMethod`, CI wiring (no `.github/workflows` exists in this repository yet), and the retrospective ADR for the comment convention, which waits on `architecture/adr/` from the architecture-documentation plan.

## Appendix: reproducing the baseline

The measurements in "Baseline evidence" were taken with the Checkstyle CLI, without Maven, so they can be reproduced against any checkout:

```bash
curl -L -o /tmp/checkstyle-14.1.0-all.jar \
  https://github.com/checkstyle/checkstyle/releases/download/checkstyle-14.1.0/checkstyle-14.1.0-all.jar
cd "$(git rev-parse --show-toplevel)"
java -jar /tmp/checkstyle-14.1.0-all.jar \
  -c services/funds-core/config/checkstyle/checkstyle.xml \
  services/funds-core/src/main/java \
  services/funds-core/src/main/resources/db/migration \
  services/funds-core/src/test/java
```

Expected after Task 3: `Starting audit... Audit done.` with no violations. This is also the fastest way to check a rule change before wiring it into the POM.
