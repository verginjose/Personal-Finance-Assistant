# OCR deterministic parser — session notes

**Date:** 2026-11-09
**Scope:** replace the Groq extraction step in `ocr-service` with a local,
deterministic parser, and validate it against the real OCR service and the
bundled 200-receipt dataset.

## Goal

Groq was being used to turn OCR text into a fixed document:

```json
{"vendor":"...","amount":0.0,"transactionType":"Expense",
 "expenseCategory":"...","incomeCategory":null,"currency":"USD",
 "description":"...","date":"YYYY-MM-DD","recurring":false}
```

The request was to do that with a script — no LLM call — using the existing
`POST /ocr` endpoint and the dataset already in the repo.

## What was built

`ocr-service/bench/parse_receipts.py` (stdlib only, no pip installs):

1. Uploads images to `POST /ocr` with `X-Internal-Token`.
2. Parses the returned text into the same document shape Groq produced.
3. Computes the derived `amountInr` / `amountUsd` / `amountDefault` /
   `defaultCurrency` fields the same way `_parse_bill` does (Frankfurter
   rates, with an offline fallback).
4. Records **evidence** per receipt — which line produced the amount, which
   text matched the date, how currency was detected, extracted line items.
5. Emits sanity warnings: `no_amount`, `no_date`, `no_vendor`,
   `amount_out_of_range`, `date_out_of_range`, `currency_defaulted`,
   `no_line_items`.
6. Optionally scores results against a labelled set (`--labels`) per field.

It can also re-parse previously saved `/ocr` responses (`--results`), so the
parser can be tuned without the service running.

## Parsing strategy

| Field | Rule |
|---|---|
| Amount | Total keywords in priority order (`grand total`, `total due`, `amount due`, `balance due`, `take-out total`, `total`), excluding subtotal/tax/tip/change/card/QTY-ITEM lines. Handles label-then-value on the next line and glued text (`TOTAL33.92`). Falls back to the largest 2-decimal amount, which rejects street/card numbers. |
| Date | ISO, month-name (`Apr 25, 2015`, `Jun 26.15`, `Feb23'19`, `Apr 012019`, `Jun 24 20:49:53 2018`) and numeric forms (`04/25/15`, `04-Nov-2018`, `02-082015`). Rejects implausible years (< 1990, > next year). `--date-order auto|mdy|dmy`. |
| Vendor | Brand table first (`McDonald's`, `In-N-Out`, `Popeye's`, `Bella Pasta`, …), then scored header lines. Score = business keyword + multi-word + mostly-uppercase, ties to the line nearest the top. Skips addresses, phones, URLs, store numbers, generic labels (`SETTLED`, `SERVERS`, `Restaurant`, `Your Guest Number Is`). |
| Category | Ordered keyword table matching the `Category` enum; body limited to header + items so marketing footers cannot misclassify. Falls back to common menu vocabulary (`pho`, `samosa`, `ribeye`, `empanada`, …) → `RESTAURANTS`; otherwise `OTHERS`. |
| Currency | `₹`/`Rs` → INR, `€`/`EURO` → EUR, `£` → GBP, explicit ISO codes, Canadian postal / US ZIP hints, then `$`; `--default-currency` (INR by default, USD for this dataset). |
| Type | Income keywords (`salary`, `payroll`, `refund`, …) else Expense; negative amount flips to Income. |
| Recurring | Subscription/rent keywords in the header + items only. |

## Live test

No user was created; the service's own internal credential was used.

```bash
kubectl --kubeconfig ~/.kube/k3s.yaml -n pfa port-forward svc/ocr-service 8100:8100

TOKEN=$(kubectl --kubeconfig ~/.kube/k3s.yaml -n pfa \
  get secret ocr-service-secrets -o jsonpath='{.data.INTERNAL_API_TOKEN}' | base64 -d)

INTERNAL_API_TOKEN="$TOKEN" python3 -I ocr-service/bench/parse_receipts.py \
  --count 200 --first --concurrency 4 --default-currency USD \
  --out /tmp/parsed-200.json
```

### Final result — all 200 receipts

| Metric | Result |
|---|---|
| Uploads succeeded | 200 / 200 |
| Vendor found | 193 / 200 |
| Amount found | 200 / 200 |
| Date found | 172 / 200 (86%) |
| Category | 195 RESTAURANTS, 5 OTHERS, 0 false categories |
| Currency | 194 USD, 4 EUR, 1 CAD, 1 INR |
| Zero-warning receipts | 113 / 200 |
| Amount range | 0.80 – 780.45 (median 34.06) |

Sample parsed documents:

| Receipt | Vendor | Amount | Currency | Date | Category |
|---|---|---|---|---|---|
| 1000 | Green Field | 56.50 | USD | 2016-05-26 | RESTAURANTS |
| 1001 | Grand Lux Cafe | 69.25 | USD | 2015-04-25 | RESTAURANTS |
| 1100 | McDonald's | 26.15 | CAD | 2018-03-10 | RESTAURANTS |
| 1171 | Pop Tates Koruh Hall | 523.00 | USD | 2012-09-12 | RESTAURANTS |
| 1199 | Norma | 128.33 | USD | 2012-05-21 | RESTAURANTS |

### Iterations against real OCR

The synthetic samples passed first; the live text exposed cases they did not.

| Stage | Vendor | Amount | Date | RESTAURANTS | Clean |
|---|---|---|---|---|---|
| First 40 live receipts | 39/40 | 40/40 | 24/40 | 29/40 | 11/40 |
| After date/amount/vendor fixes | 40/40 | 40/40 | 34/40 | 39/40 | 21/40 |
| Final 200-receipt run | 193/200 | 200/200 | 172/200 | 195/200 | 113/200 |

## Fixes driven by real OCR output

- Dates glued to neighbouring text or times:
  `5/26/201612:53:10PM`, `16:4304/25/15`, `04-Nov-20189:07`,
  `30-Jun-201810:36`, `02-082015`.
- Month forms: `Jun 26.15`, `Feb23'19`, `Date:Apr 012019`,
  `SUN JUN 24 20:49:53 2018` (time between day and year).
- `MART` was matching `Mar` and producing `1814-03-01`.
- Amounts:
  - item name and price on separate lines (`Coffee` / `2.95`);
  - glued total `TOTAL33.92`;
  - card suffix `Acctxxxxxxx2276` and item code `100027` chosen as totals;
  - street number `14727.5` chosen by the fallback.
- Vendor:
  - `>SETTLED<`, `SERVERS`, `Restaurant`, `Your Guest Number Is`,
    `2 House Vodka`, `House`, website lines;
  - brand variants `Mc Donald's`, `IN-N-OUT`, `POPEYE'S`, `Yogurt land`;
  - `E1` → `El` OCR repair, trailing store numbers (`Denny's #8129`).
- False categories:
  - footer “Now streaming on NETFLIX!” made Commander's Palace an OTT
    subscription;
  - `Hammocks Trading Company` → stocks;
  - item `Sprouts` → groceries;
  - `Cabernet` → cab, `BALDI` → Aldi;
  - `% of sale`/menu text → books/courses.
- Category fallback widened with menu vocabulary
  (`pho`, `samosa`, `caldo`, `ribeye`, `empanada`, `falafel`, `bbq`, …).

## Known limitations

- 28 receipts have no readable date in the OCR text (e.g. `ov-15-06`,
  `Ju116'17`). They are reported as `no_date`, not guessed.
- 7 receipts produced no vendor line (OCR header is address/garbage only).
- 5 receipts remain `OTHERS` because neither the vendor nor the items name a
  cuisine.
- Currency is only *detected* on 135/200; the rest fall back to
  `--default-currency`. The evidence shows `currency_source=default`, so this
  is visible rather than silent.
- Heuristic by nature. Its value over Groq is that every field carries its
  evidence, the output is reproducible, and it costs nothing per receipt.

## Files changed

- `ocr-service/bench/parse_receipts.py` — new parser.
- `ocr-service/bench/README.md` — usage section for the parser.

No service code was changed; the Groq path in `app/main.py` is untouched.


## Follow-up tuning and vector category fallback — 2026-11-09

User requested further deterministic tuning, then explicitly requested both
parser improvements and vector-search categorization.

### Implemented

- Total labels now handle `TOTAL33.92`, `Tota1`, `TOTAI`, `TOTL`, clocks before
  labels, percentages, amounts before labels, split `TOTAL / DUE`, later service
  charges/tips, and cash tender confusion. Neighboring arithmetic helps resolve
  interleaved subtotal/tax/total OCR. Negative totals become Income with positive
  document and converted amounts.
- Dates recover glued year/time forms (`Jun281705:36P`, `Nov301406:35PM`,
  `08/09/19428PM`), ISO dates touching clocks, and restricted OCR month repairs
  (`Ju1` → `Jul`, `0ct` → `Oct`, `Apri1` → `April`). Original matched text remains
  in evidence. Two previously incorrect dates were also corrected.
- Vendor filtering repairs McDona ld variants, skips pickup/date/table labels,
  and recovers `Argonaut Diner` from a digit-glued header. Vendor coverage alone
  remains an unreliable accuracy metric; some other false header choices remain.
- Glued state/ZIP hints such as `TX78521` now count as detected USD.
- `bench/category_search.py`: local vector cosine search; default **sparse
  TF-IDF (lexical)**, optional local SentenceTransformer **semantic** embeddings.
  No vector database is required for this small corpus. Rules take precedence;
  only Expense/OTHERS falls back. Low scores or narrow cross-category margins
  abstain. Evidence records neighbors, acceptance, scores and index SHA-256.
- `--category-examples`, `--category-model`, `--category-threshold`,
  `--category-margin`; starter reviewed category descriptions in
  `bench/category_examples.json`. IDs matching the current receipt filename are
  excluded to avoid direct self-matches. Split training/evaluation vendors when
  measuring accuracy. The optional semantic backend is implemented but was not
  runtime-tested: sentence-transformers and model weights are not installed.

### Validation

A fresh live `/ocr` run succeeded for 200/200; raw text is in
`/tmp/parser-baseline-200.json`. Tuned hybrid replay of identical text is in
`/tmp/parser-hybrid-200.json` (conversion disabled to avoid rate drift).

| Coverage metric | Before | After |
|---|---:|---:|
| Amount found | 200 | 200 |
| Date found | 172 | 184 |
| Vendor found | 193 | 193 |
| Currency detected (rather than defaulted) | 135 | 141 |
| Warning-free receipts | 113 | 123 |
| Total warnings | 107 | 89 |

28 amount values changed after OCR-text review, e.g. receipt 1148: 1.19 → 13.07,
1150: 5.96 → 80.45, 1181: 9.00 → 439.86. These are targeted fixes, not an
image-labelled all-field accuracy score. Category distribution stays 195
RESTAURANTS / 5 OTHERS: the sparse index safely abstains on the five unknowns.
No measured category accuracy improvement is claimed; a mixed-category labelled
holdout and a pinned semantic model are needed to assess that.

`python3 -I -m unittest discover -s ocr-service/bench/tests -v` runs 12 tests,
including 48 selected real OCR fixtures, refund signs, ambiguous dates, vector
acceptance/abstention, competing labels, rule precedence and self-exclusion.
`bench/tests/fixtures/tuning_report.json` preserves the before/after summaries.

The production Groq path remains untouched; this work is in the benchmark parser.
