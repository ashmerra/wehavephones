# Contributing

Keep changes focused and use the project helpers in `scripts/` to build and
run the relevant checks. Include a brief description of the behavior change
and the checks performed in the commit or pull request.

## Documentation and investigation notes

`docs/` contains durable public documentation for using, building and
maintaining WeHavePhones. Update it when the product's behavior, architecture,
compatibility or build procedure changes. Public driver catalogs and license
provenance belong in the repository.

Internal research, investigations, benchmark reports, device trial reports,
overnight results, session logs, handoffs, raw captures and scratch notes must
stay local. Save them under the gitignored `out/investigations/<topic>/`.
Do not copy investigation timelines or test diaries into public guides.

An instruction to investigate, test, document findings, commit or push does
not authorize publishing these notes. Publishing a specific investigation
document requires an explicit request naming that document.

Before committing, inspect the staged paths and diff. Stage explicit paths
and leave internal notes out. Never force-add ignored reports or weaken the
ignore rules to publish them.
