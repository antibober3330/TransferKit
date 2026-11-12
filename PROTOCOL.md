# TransferKit protocol reference

**Status:** the authenticated Windows-host-mediated Android source→target USB/ADB receive/receipt path is integrated in the session backend and physically smoke-tested with one synthetic item (65 536 bytes) on A04e→Moto. The receive path is not a completed migration: physical desktop Station click-through, destination restore/post-verification/report, and disconnect/recovery acceptance remain incomplete. Wi-Fi TLS/QR is not ready.

## Normative sources

- [`shared/protocol.md`](shared/protocol.md) is the detailed wire and lifecycle specification.
- [`shared/CONTRACTS.md`](shared/CONTRACTS.md) defines category IDs, statuses, reason codes and recovery semantics.
- [`shared/manifest.schema.json`](shared/manifest.schema.json), [`shared/receipt.schema.json`](shared/receipt.schema.json), [`shared/report.schema.json`](shared/report.schema.json) and [`shared/capability.schema.json`](shared/capability.schema.json) are the machine-readable JSON contracts.
- Recovery snapshots use [`shared/checkpoint-state.schema.json`](shared/checkpoint-state.schema.json) and [`shared/resume-plan.schema.json`](shared/resume-plan.schema.json).
- [`shared/protocol-vectors.json`](shared/protocol-vectors.json) contains byte-exact v1 frame examples used by Python and Kotlin tests.

If this overview and a normative schema or `shared/protocol.md` disagree, the schema and detailed protocol file are authoritative.

## Transfer lifecycle

1. Each side gathers device and capability information. The plan distinguishes what the source can read from what the target can restore.
2. The source freezes the chosen inventory in `manifest.json`. Every item has a stable ID, exact byte size and SHA-256 of the transmitted representation.
3. On USB/ADB, the user starts the receiver in its foreground service. Windows provisions a one-time token over the ADB control socket, completes a transfer-bound nonce/HMAC handshake, then sends the manifest; the target validates the manifest before accepting data.
4. The source streams each item in ordered chunks. The target verifies CRC32, writes to private staging, durably commits its checkpoint, then acknowledges the chunk.
5. The target verifies the complete item SHA-256. Receive verification is not a restore result.
6. Restore requires explicit user approval. Each APK install requires a separate system/user confirmation.
7. After all manifest items have terminal receive results, Windows sends `TKFN` and the target returns the final `TKRC` receipt. Restore and destination post-verification are later, separate operations.
8. The report preserves partial failures and never describes a transfer as `OK` just because bytes were received.

## Version 1 frame summary

All integer fields are unsigned big-endian. See `shared/protocol.md` for exact offsets, validation and error handling.

| Frame | Shape | Purpose |
|---|---|---|
| `TKMF` | 4-byte magic, 1-byte version, 4-byte payload length, canonical UTF-8 JSON | Sends the manifest before item bytes. |
| `TKSI` | 4-byte magic, 1-byte version, 16-byte transfer ID | Public source identity sent before auth so the host can make a transfer-bound token offer; contains no manifest or item metadata. |
| `TKCH` | 45-byte header followed by `chunk_size` payload bytes | Carries one ordered item chunk. Header binds transfer ID and short category/item hashes, chunk index/count, size and CRC32. |
| `TKAK` | Fixed 39-byte acknowledgement | Acknowledges a manifest or a chunk and reports a protocol status. |
| `TKSO` / `TKOA` | 53-byte token offer / 22-byte offer acknowledgement | Provisions a random, transfer-bound, five-minute session token over the trusted ADB control forward. |
| `TKHC` / `TKHA` / `TKOK` | 53-byte challenge / 37-byte HMAC proof / 22-byte result | Binds session authentication to the transfer ID and fresh nonce; invalid proofs do not consume the pending token. |
| `TKFN` / `TKRC` | 21-byte finish request / 26-byte status header plus bounded receipt JSON | Requests final classification after all items; `TKRC` returns the schema-validated receipt or a bounded failure status. |

Recommended chunk size is 4 MiB and the maximum is 8 MiB. Empty items use one zero-length chunk. CRC32 detects accidental chunk corruption; the final SHA-256 verifies the complete item. Neither checksum authenticates a peer.

## Durable receive and resume

The receiving implementation writes an item chunk into app-private staging, syncs the bytes, commits the SQLite checkpoint, and only then returns an ACK. An identical retransmission can be acknowledged idempotently; a different payload for an already accepted index is rejected. The target's checkpoint is authoritative.

The detailed specification separates v1 chunk framing from the v2 space-preflight/streaming extension. A peer must not silently interpret an unknown control frame as a chunk. Recovery binds the transfer ID to the manifest digest, protocol version and source/target installation identities. A successful receive receipt is final and classifies every received item as verified or failed; intermediate progress belongs in ACKs/checkpoints.

## Transport boundary

- **Windows Station + USB/ADB:** Python `run_authenticated_usb_adb_transfer` opens explicit source/target ADB forwards, authenticates the Android source and target, relays manifest/chunks and forwards an ACK only after a matching target ACK; `TransferHistory` stores checkpoints and the final receipt. Android source/receiver foreground servers close their accept loops and sockets on stop. On Samsung A04e/API 33 → Motorola moto g06/API 35, a generated 65 536-byte/16-chunk item completed with schema-valid receipt and independent source/target SHA-256 match; test APK and item were removed. This proves a narrow synthetic receive path, not restore of user data. Desktop Station click-through, restore/post-verification/report, and disconnect recovery remain incomplete. This is Windows-host mediated, not direct ADB between phones.
- **Wi-Fi/TLS:** TLS 1.3, one-time pairing and certificate pinning are the target profile. Current code has pairing/provider scaffolding, not a complete TLS client/server, QR flow or reconnect path.
- **Android-to-Android USB:** not a guaranteed profile; it depends on OTG/host role and tethering support.
- **Wi-Fi Direct/Aware and acoustic bootstrap:** research proposals only; neither is implemented or required. Sound is considered only for short discovery/bootstrap, never file bytes or sole peer authentication.
- **Backup Relay:** Python archive APIs exist. Optional `TKAE` v1 protects the ZIP using AES-256-GCM and a password-derived key; UI/password workflow is not integrated. Without the optional wrapper, ZIP is plaintext.

Do not attach `ReceiverStreamHandler` to an unauthenticated socket. `TKMF`/`TKCH` framing alone does not authenticate the sender. Never provision the token through shell command arguments or intent extras; use the dedicated ADB control socket. An ADB receive receipt does not mean `OK` restore status.

The detailed D-043 handshake and byte layouts are in [`shared/protocol.md`](shared/protocol.md); shared vectors are in [`shared/protocol-vectors.json`](shared/protocol-vectors.json).

## Validation and error behavior

Reject invalid versions, malformed IDs, incorrect frame sizes, unknown categories, duplicate/ambiguous item hashes, out-of-order chunks, CRC mismatches, writes that fail, and full-item SHA mismatches. Integrity failure is terminal for that attempt and remains `FAILED_INTEGRITY`; it cannot be downgraded to `PARTIAL` or `OK`.

The Python and Kotlin codecs test shared v1 vectors including `TKSI`. Android instrumentation passed 21/21 on each of Samsung S20 Ultra/API 33, Samsung M31/API 31, Samsung A04e/API 33, and Motorola moto g06/API 35; these include Recovery Keystore identity, SQLite v1/v2→v3 migration, exact durable chunk metadata and failed-commit ACK behavior. Android unit tests passed 40/40, and the Python full suite passed 121/121. The authenticated synthetic receive/receipt smoke passed on A04e→Moto. Temporary packages were removed from A04e/Moto; existing S20/M31 installs were preserved, and all four Wi-Fi ADB serials remained online. These checks do not establish restore acceptance, Recovery handshake/resume, or cable-disconnect recovery. Current implementation limits are tracked in [`docs/project/ARCHITECTURE_AND_LIMITS.md`](docs/project/ARCHITECTURE_AND_LIMITS.md), [`docs/project/RELEASE_READINESS.md`](docs/project/RELEASE_READINESS.md) and [`WORK_CHECKLIST.md`](WORK_CHECKLIST.md).
