# Security Policy

## Reporting a vulnerability

Do not report exploitable vulnerabilities in public GitHub issues or discussions.

Use GitHub's private vulnerability reporting / Security Advisories for this repository. If private reporting is not enabled, contact the repository owner through a private channel and request a secure reporting path before sharing technical details.

Include:
- Affected Aegis version or commit.
- A concise description of the security impact.
- Reproduction steps and the smallest safe proof of concept.
- Any relevant configuration or environment assumptions.

Do not include production source code, credentials, personal data, customer database dumps or other secrets in a report.

## Security boundaries

Aegis is designed to be offline-first and deterministic. It must not transmit source code or project metadata to external AI/model providers. Discovery must not execute uploaded application code, SQL, build plugins or scripts. JDBC metadata discovery uses metadata APIs only; the JDBC read-only flag is a driver hint, so production database accounts must independently be restricted to metadata/catalog read privileges.

Generated transformations and SQL plans require explicit human review. Hash validation is not a substitute for compilation, integration, security, performance or data-reconciliation testing.
