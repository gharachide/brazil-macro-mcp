# brazil-macro-mcp: methodology and guardrails

## Sources

- **SGS** (Sistema Gerenciador de Séries Temporais), Banco Central do Brasil:
  `https://api.bcb.gov.br/dados/serie/bcdata.sgs.{code}/dados`.
- **Focus** (Sistema Expectativas de Mercado), Banco Central do Brasil, OData resource
  `ExpectativasMercadoAnuais`.

Values are returned exactly as published. The server never fills gaps, interpolates or
extrapolates.

## Dating

- Monthly observations are dated on the first day of the reference month: `2026-10-01` is October 2026.
- `SELIC_TARGET` is published for every calendar day. Dates after today repeat the target in
  force until the next Copom meeting; they are not forecasts. `get_latest` returns only values
  dated up to today in Brazil (America/Sao_Paulo).
- The SGS publishes `SELIC_MONTHLY`, `CDI_MONTHLY` and `SELIC_ANNUALIZED` for the month in
  progress as a running total. Such an observation is flagged `"partial": true`; `get_latest`
  leaves it out, and the month in progress can never be compounded.

## Units and valid calculations

| Kind | Series | Unit | Can be compounded |
|---|---|---|---|
| Monthly change of a price index | IPCA, INPC, IGPM | % a.m. | yes |
| Rate accumulated within the month | SELIC_MONTHLY, CDI_MONTHLY | % a.m. | yes |
| Rate per year | SELIC_TARGET, SELIC_ANNUALIZED | % a.a. | no |
| Change over 12 months | IPCA_12M | % em 12 meses | no |
| Exchange rate | USD_BRL | R$/US$ | no |

The server refuses to compound series that cannot be compounded, instead of returning a
plausible but wrong number.

## Calculations

- **Accumulated change**: product of `(1 + r/100)` over every month of the period, minus 1, in
  exact decimal arithmetic with 34 significant digits. Results are rounded to 4 decimal places
  only for display. Example: IPCA monthly values for 2023 compound to 4.6211%, which IBGE
  publishes as 4.62%.
- **Real return**: Fisher relation `(1 + nominal) / (1 + inflation) - 1`, with both series
  compounded over the same months.
- If any month of the period has no official value, the result is `INCOMPLETE_DATA` with the
  missing months. A partial period is never compounded.

## Citations

Every successful result has a `citation` with:

- `citeAs`: a ready-to-quote reference;
- `requests`: each API URL called, when it was retrieved, and whether it came from cache;
- `stale`: `true` when the BCB was unavailable and a cached copy (at most 24 hours old by
  default) was served. Quote the retrieval time in that case.

## Errors

Errors are tool results with `isError: true` and a code from a closed set: `INVALID_ARGUMENT`,
`RANGE_TOO_LARGE`, `NO_DATA`, `INCOMPLETE_DATA`, `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`.
Each one carries guidance. The rule is the same for all of them: do not estimate, interpolate
or recall values from memory in place of the missing data.

## Audit

Each tool call is appended to a JSON Lines file. Every entry holds the SHA-256 of the previous
entry, so any edit, deletion or reordering breaks the chain. Verify it with
`java -jar brazil-macro-mcp.jar --verify-audit <file>`.

This server provides public data for information. It is not investment advice.
