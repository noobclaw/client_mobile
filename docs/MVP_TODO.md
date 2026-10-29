# Mobile MVP — status

Scope (2026-10-30): only **TikTok 評論區點讚評論** (`tiktok_comment_like`) and **TikTok 定向獲客** (`tiktok_lead_engage`).

Done (code, not yet run on a device):
- App: login, consent, permissions checklist, TikTok binding, task editor for both scripts, schedule (2 slots/day in task tz),
  run now / dry run / stop, floating stop button, heartbeat, log upload, diagnostics, challenge cooldown 24/48/72 h.
- Engine: QuickJS sandbox, signed scripts only (release), target-app allowlist, 30 inputs/min ceiling, per-run caps
  (likes/comments/follows), idempotent charging, dry run (no final taps, no charges, no state/list writes).
- Backend `feat/mobile-mvp` (not merged): `/api/mobile/*`, charge idempotency (only when `idempotency_key` is sent).

Before a public release:
- [ ] Verify every selector on a device (docs/TIKTOK_SELECTORS.md).
- [ ] Confirm quickjs-kt 1.0.15 API (`asyncFunction`, promise result of `evaluate`) in CI.
- [ ] Token → EncryptedSharedPreferences.
- [ ] Generate the signing key, set `SCRIPT_PUBKEY_HEX`, sign both scripts.
- [ ] Real release keystore in CI.
- [ ] Push notifications for `/api/mobile/notify` (stored only for now).
