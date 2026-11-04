# Contributing

Thanks for your interest. LabOS is **pre-alpha**; small, focused changes are easiest to review.

## Ground rules

1. Read [docs/en/contribution-guidelines.md](docs/en/contribution-guidelines.md) (English) and [docs/提交规范.md](docs/提交规范.md) (Chinese).
2. **License:** Apache-2.0. By contributing you agree that your contribution is licensed under the same terms.
3. **No secrets, no internal data:** never commit credentials, private network addresses, customer data, vendor manuals you do not have rights to redistribute, or code you do not have the rights to publish.
4. **Device work:** follow [docs/en/devices/](docs/en/devices/) and [docs/设备/](docs/设备/). Annotate each method with idempotency / retry-safety / evidence level. Do not claim real-hardware validation without a reproducible record.
5. **Tests:** `scripts/test-all.sh` must stay green; pure-logic tests must run without a database.
6. **Behaviour-changing changes** need cross-family review (see ROADMAP §7) and a note in the decision log.

## Pull requests

- One topic per PR; include the motivation and the verification you ran.
- Keep the kernel free of framework dependencies; protocol adapters belong in separate processes.
- Update English and Chinese docs when behaviour changes.

Questions: open an issue or email rogerlzp@gmail.com.
