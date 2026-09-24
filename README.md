# Workday Studio skill for GitHub Copilot

An [Agent Skill](https://docs.github.com/en/copilot) that lets GitHub Copilot (agent mode, Copilot CLI, coding agent) build and extend **Workday Studio** integrations. The same format also works with Claude Code. It writes the two files that define an integration:
- `ws/WSAR-INF/assembly.xml`: the flow,
- `ws/WSAR-INF/assembly-diagram.xml`: the canvas layout.

Then it verifies them with Workday Studio's own schemas, MVEL runtime and diagram reconciler.

The skill is in [`.github/skills/workday-studio-integration/`](.github/skills/workday-studio-integration/SKILL.md). A Polish README is included there.

## What's inside

| Path | Purpose |
|---|---|
| `SKILL.md` | Workflow and rules. Copilot loads it when a Studio task comes up. |
| `references/` | Knowledge loaded on demand: assembly structure, components, steps, MVEL, integration systems, error handling, diagram/swimlane rules, catalog of all built-in cloud elements, StarterKit usage, validation checklist, Outbound REST recipe |
| `templates/` | Skeleton `assembly.xml` / `assembly-diagram.xml` |
| `tools/WdCheck.java` | Offline project check. Details below. |
| `tools/StudioDocs.java` | Workday Studio help pages and element schemas, read on demand from the local Studio installation |
| `tools/SskApi.java` | API catalog (callable local-ins and parameters) of a StarterKit-based project in your workspace |
| `tools/RebrandSsk.java` | Rebrands a copy of a StarterKit template project for a new integration |

`WdCheck` runs six checks:
1. XSD validation,
2. MVEL compilation,
3. diagram references,
4. Studio's reconciler: what the editor will change when the assembly is opened,
5. swimlane conventions,
6. `vm://` calls and parameters, names of launch parameters and attributes, XSLT files.

## Requirements

- A local **Workday Studio** installation (tested with 2026.24). The tools read Studio's own schemas, MVEL runtime, editor classes and help pages from it. They use `--studio <dir>` or `WORKDAY_STUDIO_HOME` when Studio is not in the default location.
- **JDK 17+** on the `PATH`. Every tool is a single file run with `java Tool.java`; there are no other dependencies.
- Put this repository's `.github/skills/` into the root of your Eclipse/Studio workspace and open that folder in VS Code.

```bash
java .github/skills/workday-studio-integration/tools/WdCheck.java <ProjectFolder>
java .github/skills/workday-studio-integration/tools/StudioDocs.java element http-out
```

## Not included

- **No Workday material:** Workday schemas and documentation are read from your own Studio installation at run time and never copied into this repository.
- **No integration projects:** this repository contains no Workday integration projects, StarterKit code or project-derived examples.
- **StarterKit mode** expects a neutral StarterKit template project (`BASE_SSK_Template`) that you prepare in your own workspace. See `references/ssk.md`.

This project is not affiliated with or endorsed by Workday, Inc. Workday and Workday Studio are trademarks of Workday, Inc.
