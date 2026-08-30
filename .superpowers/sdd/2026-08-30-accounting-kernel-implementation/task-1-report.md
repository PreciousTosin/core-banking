# Task 1 report: Bootstrap exact money domain

## Implementation

- Bootstrapped `services/funds-core` as a Quarkus 3.33.3.1 Maven service targeting Java release 25.
- Added the requested Quarkus runtime/test dependencies, Maven 3.9.16 wrapper, and Java/Maven Enforcer rules.
- Added canonical ISO-style three-letter `CurrencyCode` and signed checked-64-bit `Money` minor-unit arithmetic.
- `add`, `subtract`, and `negate` use exact arithmetic and translate overflow to `MonetaryOverflowException` while preserving the arithmetic cause.
- Added application and test configuration plus the specified `MoneyTest`.

## Files

Created all requested Task 1 files under `services/funds-core`, plus a domain-package compatibility facade for `MonetaryOverflowException` because the supplied test source references that type without importing its specified exception subpackage.

## TDD evidence

### RED

Command (before production money types):

```text
./mvnw -Dmaven.repo.local=/tmp/core-banking-m2 -Dtest=MoneyTest test
```

The build failed during test compilation with missing-symbol errors for the money domain types (and, after the domain types were introduced, the supplied test's unqualified `MonetaryOverflowException`). This was expected: the test was written before the production implementation and could not compile until its contract existed.

### GREEN

Focused and full commands both completed successfully after implementation:

```text
./mvnw -Dmaven.repo.local=/tmp/core-banking-m2 -Dtest=MoneyTest test -q
./mvnw -Dmaven.repo.local=/tmp/core-banking-m2 test
```

Final full run: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.

Wrapper check reported Apache Maven 3.9.16 and Java 25.0.2 from the mandated JAVA_HOME. Quarkus emits a known LogManager initialization warning during tests; it does not affect the green result.

## Self-review

- Exact arithmetic is used throughout; no floating point or unchecked wrapping exists.
- Currency mismatch is rejected before arithmetic; currency input is non-null, upper-canonicalized, and exactly three ASCII letters.
- Overflow cases cover `Long.MAX_VALUE + 1` and negating `Long.MIN_VALUE`.
- Scope is limited to the requested service bootstrap and money primitive.
- `git diff --check` passed.

## Concerns

- The task's supplied test omits an import for the exception located at the explicitly requested `domain.exception` path. The added facade keeps that exact test compiling while the thrown type remains assignable to the requested exception type.
- Test startup logs a JBoss LogManager warning; all assertions and the Maven build pass.
