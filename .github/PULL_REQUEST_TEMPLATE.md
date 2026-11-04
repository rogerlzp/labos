# Pull request

Thanks for contributing. LabOS is **pre-alpha**, so small and focused changes are
much easier to review than large ones.

> 中文贡献者请同时参阅 [`docs/提交规范.md`](../docs/提交规范.md) 与
> [`docs/评审规范.md`](../docs/评审规范.md)（评审流程以中文版为准）。

## What this changes

<!-- One or two sentences. What behaviour is different after this PR? -->

## Why

<!-- Why this way and not another. The commit log should say "why", not only "what". -->

## How it was verified

<!-- Be concrete. Prefer a command or a recorded result over a description. -->

- [ ] `scripts/test-all.sh` passes (pure-logic tests, no database required)
- [ ] `npm run typecheck` passes in `webapp/` (front-end changes)
- [ ] A simulator run is attached, if the change touches the device path

## Checklist

- [ ] One thing per PR — unrelated cleanups are split out
- [ ] No credentials, customer data, vendor manuals or internal network addresses
- [ ] Behaviour-changing changes have a note in the decision log
      ([`决策记录.md`](../决策记录.md) / [`docs/en/decision-log.md`](../docs/en/decision-log.md))
- [ ] User-facing text exists in **both** English and Chinese
      (the console switches language at runtime — see
      [`docs/en/i18n-ledger.md`](../docs/en/i18n-ledger.md))
- [ ] Device methods document idempotency, retry-safety and evidence level
- [ ] No claim of real-hardware validation without a reproducible record

## Related

- Issue: <!-- #123 -->
- Design doc: <!-- docs/xxx-设计.md -->
