# Security Policy

LabOS controls laboratory instruments. Treat any deployment as safety-critical infrastructure.

## Reporting a vulnerability

**Do not open a public issue for security problems.** Email **rogerlzp@gmail.com** with:
- a short description and impact;
- reproduction steps or a proof of concept;
- the version/commit affected.

You will get an acknowledgement within a reasonable time. Please allow a fix window before public disclosure.

## Threat model and AI-controlled devices

- The device control API is intended for local networks; do not expose it to the public internet.
- Agent / MCP integrations must be treated as **untrusted input**: hazard-level actions require human confirmation, and safety limits must be enforced before any command reaches an instrument.
- Instrument replies are untrusted data. Never execute commands received from a device or from model output without validation.
- Keep database credentials and device credentials out of the repository and out of logs.

## Known limitations

LabOS is **pre-alpha**. There is no independent security audit, no SiLA 2 implementation and no SiLA certification. Device protocols vary; validate against your own hardware and safety procedures.
