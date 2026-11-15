# Transformation Workbench: Safety Contract

## Current Java transformation

The initial Jakarta migration is intentionally limited to import declarations and three explicit package prefixes:

| Source prefix | Target prefix |
|---|---|
| `javax.persistence` | `jakarta.persistence` |
| `javax.validation` | `jakarta.validation` |
| `javax.servlet` | `jakarta.servlet` |

The implementation uses the JDK compiler tree API to locate import declarations and source ranges. It does not use regular-expression replacement over the complete source file. Comments, string literals, unrelated imports and executable code are left unchanged.

## Preconditions and output safety

- The source must parse without Java syntax errors.
- The input and output paths must differ.
- The output file must not already exist.
- No source file is overwritten.
- The transformation is deterministic and reports every import mapping.
- A successful syntax parse is not proof of type correctness, framework compatibility or runtime behavior.
- The tool does not resolve symbols, inspect dependency versions, rewrite XML/properties/YAML, or modify reflective class names.

## Required human validation

Before accepting generated output:
1. Review the source diff.
2. Confirm that the selected Jakarta API version is compatible with the target framework and runtime.
3. Update dependency coordinates and configuration separately where required.
4. Compile the complete project.
5. Run unit, integration and contract tests.
6. Validate persistence mappings, servlet filters, validation behavior and serialization boundaries.

## Future expansion gates

New rules must be narrow, versioned, deterministic and backed by positive and negative fixtures. Rules that require symbol resolution must not be enabled until the project classpath and dependency graph can be resolved reliably. SQL/DDL transformations must use parser-backed source ranges, preserve comments/strings, emit diagnostics for unsupported constructs, and provide a reversible migration plan. No transformation may claim semantic equivalence without executable evidence.
