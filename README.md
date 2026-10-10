# or1

An experimental agent-native Android phone: the home screen is a chat, and a Claude agent carries out
requests on the device with privileged platform access. The test hardware is a RAVOZ V1 running a non-GMS
Android 13 GSI.

- `app/`: Phase 2, the on-device agent app (Kotlin, Compose). See `app/README.md`.
- `proto/`: Phase 0, a Python prototype that drives the phone over ADB, plus the eval task list.
- `docs/`, `phase1/`: Phase 1 runbook and partition backup script.
- `2026-10-06-handoff.md`: device bring-up notes.

The fonts in `app/agent/src/main/res/font/` are under the SIL Open Font License.

## License

MIT, see `LICENSE`.
