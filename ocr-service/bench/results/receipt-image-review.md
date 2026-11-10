# Receipt image review — 2026-11-10

Ran the live `/ocr` endpoint on 200 repository JPG receipts and two public annotated images. All returned text. Parsed text locally with the candidate parser; no transactions or callbacks were submitted. Changes are not deployed.

Repository processing results: `receipt-image-review.json`. This is processing coverage, not labelled accuracy. Public OCR outputs and provenance: `../tests/fixtures/public_receipts.json`.

Fixed missing recognition of the Malaysian RM currency marker, including Amount(RM) headings and RM12.50. Explicit ISO currency codes retain precedence. The public Malaysian crop changes from default INR to MYR. Added public-output and synthetic boundary regression tests.

Remaining limitations: the annotated Singapore image prints 39.90 but OCR misreads an item as 59.60 and the parser selects it. The Malaysian crop contains corrupted digits and an incomplete header, so its total is not reliable. Annotation overlays, rotation and low resolution need a separate image-preprocessing evaluation; this change does not claim to solve them.

Validation: 37 service tests (4 skips), 19 benchmark tests; all executed tests pass.

Public source: https://github.com/recycletechno/ICDAR-2019-SROIE/tree/main/img
