# TransferKit report and receipt format

This document explains the JSON report, the target receipt and the CSV export. The JSON Schemas under [`shared/`](shared/) and the models in `transferkit/contracts.py` are normative.

## Keep three facts separate

- **Manifest:** what the source selected and the exact bytes/hashes it intended to send.
- **Receipt:** what the target received and verified at the receive stage. It is a final, immutable receive result, not proof that restore succeeded.
- **Transfer report:** what happened across planning, transfer, verification, restore and post-verification. It carries the receipt counts/failures forward and adds destination outcomes.

Thus `items_verified` in a receipt does not mean `restored_count` in a report. A report can show successful receipt and failed restore. Re-running destination checks must not rewrite the historical receipt.

## `report.json`

The document follows [`shared/report.schema.json`](shared/report.schema.json), rejects unknown properties, and contains:

| Field | Meaning |
|---|---|
| `version` | Report contract version, currently `1.0`. |
| `transfer_id` | UUID identifying this transfer. |
| `started_at`, `finished_at` | RFC 3339 UTC timestamps ending in `Z`. |
| `mode` | `quick`, `full` or `professional`. |
| `source_device`, `target_device` | Manufacturer, model, Android SDK and TransferKit-safe fingerprint. |
| `categories` | One result per reported category. |
| `summary` | Counts of total categories by plan status and count of integrity-failed categories. |
| `decisions` | Optional-compatible list of user/space decisions with reason code and affected item IDs. |

Every category result includes:

- `category_id` and its contract `tier`;
- `source_count`, `manifest_count`, `transferred_count`, `verified_count`, `restored_count`;
- `plan_status`, `status`, `reason`, and zero or more `failures`.

The counters describe different stages. A later stage cannot exceed the previous stage: manifest count cannot exceed source count, transferred cannot exceed manifest, verified cannot exceed transferred, and restored cannot exceed verified.

### `plan_status` and `status` are different

`plan_status` is the preflight decision: `TRANSFERABLE`, `PARTIAL` or `UNAVAILABLE`.

`status` is the observed result: `OK`, `PARTIAL`, `UNAVAILABLE`, `FAILED` or `FAILED_INTEGRITY`. `OK` requires the operation to finish and the destination result to pass its post-verification. Receiving and hashing staging bytes alone are not enough. A `SHA256_MISMATCH` failure requires `FAILED_INTEGRITY`, and that status requires a matching mismatch failure.

Each failure contains `item_id`, `stage`, `reason`, and an optional user-readable `display_message`. The machine-readable reason comes from [`shared/CONTRACTS.md`](shared/CONTRACTS.md); user text is kept separate from that stable code.

### Small valid report example

```json
{
  "version": "1.0",
  "transfer_id": "00000000-0000-4000-8000-000000000001",
  "started_at": "2026-11-12T12:00:00Z",
  "finished_at": "2026-11-12T12:01:00Z",
  "mode": "quick",
  "source_device": {
    "manufacturer": "Samsung",
    "model": "SM-G988B",
    "sdk": 33,
    "fingerprint": "example-source-installation-fingerprint"
  },
  "target_device": {
    "manufacturer": "Samsung",
    "model": "SM-M315F",
    "sdk": 31,
    "fingerprint": "example-target-installation-fingerprint"
  },
  "categories": [
    {
      "category_id": "photos",
      "tier": "A",
      "source_count": 1,
      "manifest_count": 1,
      "transferred_count": 1,
      "verified_count": 1,
      "restored_count": 1,
      "plan_status": "TRANSFERABLE",
      "status": "OK",
      "reason": "OK",
      "failures": []
    }
  ],
  "summary": {
    "total_categories": 1,
    "transferable": 1,
    "partial": 0,
    "unavailable": 0,
    "failed_integrity": 0
  },
  "decisions": []
}
```

Example fingerprints are placeholders, not device identifiers. Reports must not contain file payloads, contact contents, passwords, pairing tokens or unnecessary hardware identifiers.

## `receipt.json`

The receipt conforms to [`shared/receipt.schema.json`](shared/receipt.schema.json) and contains `version`, `transfer_id`, `received_at`, and per-category counters/failures. It is final:

```text
items_verified + items_failed == items_received
items_failed == number of unique failure records
```

Every failed item has exactly one failure record with its item ID, receive/verification stage and reason. Items that never reached the target are not counted as received or failed in the receipt; the report/decision list records omitted items separately. Intermediate states use durable checkpoints and acknowledgements, not a partial final receipt.

```json
{
  "version": "1.0",
  "transfer_id": "00000000-0000-4000-8000-000000000001",
  "received_at": "2026-11-12T12:00:30Z",
  "categories": {
    "photos": {
      "items_received": 1,
      "items_verified": 1,
      "items_failed": 0,
      "failures": []
    }
  }
}
```

## CSV export

`report.csv` is a flat, multi-record representation encoded as UTF-8 with BOM. Its first row is the header. `record_type` identifies:

- `summary`: category totals;
- `category`: plan and observed result counters;
- `failure`: one row per item failure, including stage and reason;
- `decision`: user choice and a compact JSON array of affected item IDs.

Columns not applicable to a row are blank. CSV is for spreadsheet/reporting use; JSON plus its schema is the canonical machine-readable report. Both exporters can receive a receipt and run consistency checks for transfer ID, counts and matching failure records before writing.

## Canonical serialization and validation

Canonical Python JSON is compact UTF-8 without BOM, uses sorted object keys, preserves Unicode, disallows NaN/Infinity, and emits UTC timestamps with `Z`. The report exporter validates the JSON Schema and Pydantic semantic rules before output. See `tests/test_contracts.py` and `tests/test_report.py` for regression coverage.
