# Script signing

The app only runs script packs signed with the NoobClaw Ed25519 key.

- Signed message: `"<script_id>\n<version>\n<sha256hex(script.js)>"` (UTF-8).
- The public key (32 bytes, hex) is baked into the APK from the GitHub secret `SCRIPT_PUBKEY_HEX` (`-PscriptPubKey=...`).
- Release builds refuse to run any script without a pinned key. Debug builds without a key skip verification and log a warning.
- The private key never touches the server. It stays on the release machine.

## Steps (backend repo)

```bash
# once
npx ts-node src/scripts/signMobileScript.ts keygen ~/noobclaw-mobile-key     # prints the public key → GitHub secret SCRIPT_PUBKEY_HEX
# every script change: bump manifest.version first
npx ts-node src/scripts/signMobileScript.ts sign tiktok_comment_like ~/noobclaw-mobile-key/private.pem
npx ts-node src/scripts/signMobileScript.ts sign tiktok_lead_engage  ~/noobclaw-mobile-key/private.pem
git add matrix/mobile_scenarios && git commit
```

`sign` builds `script.js` from `_shared/tiktok_lib.js` + `<id>/src.js` and writes `script.sig`. The server answers
`script_unsigned` until a `.sig` exists.
