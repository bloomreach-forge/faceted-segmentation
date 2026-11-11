# Expression Grammar

A rule is stored as one plain string in the section's `facetseg:faceted_segmentation` property.
The builder writes it, and the component parses it on every render. You only need this page if
something else reads or writes rules, such as a migration script.

```
Signed In IN [Signed In]
  AND (
    Size IN [Large]
      OR Day of the Week IN [Saturday, Sunday]
  )
```

## The parts

| Part | What it is | Example |
|---|---|---|
| Facet | The value list's display name. | `Day of the Week` |
| Value | The item's label. | `Saturday` |
| `IN` | The visitor has at least one of the values. | `Size IN [Small, Medium]` |
| `NOT IN` | The visitor has none of the values. | `Size NOT IN [Small]` |
| `AND`, `OR` | Join conditions and groups. | `Size IN [Small] OR Signed In IN [Signed In]` |
| `NOT` | Negates a group. | `NOT (Size IN [Small] AND Signed In IN [Signed In])` |

- `NOT` binds tightest, then `AND`, then `OR`. Use brackets for anything else.
- Keywords are uppercase. Names and values are matched exactly, including case.
- `NOT` in front of a single condition flips its operator: `NOT (Size IN [Small])` is stored as
  `Size NOT IN [Small]`. This matters under [`strict`](rendering.html#absentFacetPolicy).
- Renaming a value list or an item breaks the rules that use the old name. They still parse, but
  never match.

## Rules for your value lists

- No label may contain a comma, `[` or `]`.
- No display name may contain `AND`, `OR`, `NOT` or `IN` as an uppercase word.

The site logs a warning for any value list that breaks these.

## Working with rules in Java

The `common` jar, which comes with the `hst` jar, has the parser, formatter and evaluator the
component uses:

| Class | Does |
|---|---|
| `ExpressionParser.parse(String)` | Parses a rule. Throws `ExpressionParseException` if it's malformed. |
| `ExpressionFormatter.format(ExpressionNode)` | Writes a rule in the builder's layout. |
| `ExpressionEvaluator.evaluate(ExpressionNode, Audience, EvaluateOptions)` | Checks a rule against a visitor. |
| `Expressions` | Builds rules in code. |

Build rules with `Expressions` and write them with `ExpressionFormatter`, rather than joining
strings. The result always parses and looks the same as a rule from the builder.
