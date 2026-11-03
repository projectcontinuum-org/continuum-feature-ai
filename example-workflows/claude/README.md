# Claude AI Agent example workflows

Five workflows that use the **Claude AI Agent** node against public APIs. Each one replaces a repetitive lookup-and-judge task with one agent run per table row. All five need no API keys except the Claude token.

## Setup

1. Create a **GENERIC** credential named `anthropic` with a key `token` holding your Anthropic API key. If you use another name, or another backend (Ollama, OpenRouter, ...), change the node's **Credential**, **Backend** and **Model** fields.
2. Import a `.cwf` file into the workbench and run it. Edit the Create Table node to use your own data.

The agent only gets `curl`, `jq` and a few text tools (a Bash allow list), runs in an empty working directory, and cannot see the worker's secrets. `Max Budget` limits spend per row; the defaults are 0.25 to 1.00 USD.

| File | Task | Input table | Output / routing |
|------|------|-------------|------------------|
| `01-AdverseEventTriage.cwf` | FDA adverse event triage (openFDA): totals, serious and fatal reports, reactions that grew in the last 12 months compared with the 12 before | 4 drugs, date windows computed with `.now` | Splitter on `emerging_signal_count >= 1` |
| `02-LiteratureScreening.cwf` | Title/abstract screening of PubMed records against inclusion criteria, with a supporting quote | 8 PMIDs (5 on topic, 3 off) | Splitter at `relevance >= 80` (include); the rest goes through a filter `relevance > 25` (human review) |
| `03-ClinicalTrialMonitor.cwf` | New phase 2/3 trials per indication on ClinicalTrials.gov in the last 90 days | 4 indications | Splitter on `trial_count >= 1` |
| `04-DependencyCveScan.cwf` | Dependency scan: downloads a lockfile, queries OSV, counts advisories by severity | 3 public sample projects (npm, PyPI, Go) | Splitter on `critical_count >= 1` |
| `05-CompoundNameResolver.cwf` | Vendor compound names to PubChem structures, with a confidence; ambiguous or unknown names are held back | 8 names, including a trade name, a salt, an ambiguous name and one that does not exist | Splitter on `confidence >= 0.5`, then RDKit canonical SMILES and descriptors |

Workflow 5 needs a worker that provides the RDKit nodes (`continuum-feature-cheminformatics`) in addition to this module's worker.

## Notes

- Each agent returns typed columns through an **Output Schema** with **Flatten Structured Output**, so downstream nodes can use numeric columns directly. Nested values (lists of trials, reactions) stay in `agent_structured_output`.
- **On Error** is set to continue: a failed row gets `agent_is_error = true` and an `agent_error` message instead of stopping the run. Failed rows have empty result columns, so the splitters send them to the `low` output.
- Prompts tell the agent to use only facts from the API responses and to treat response text as data. Still review results before acting on them, especially for safety and compliance decisions.
- Data is live: counts and sample inputs (for example the PMIDs) change over time.
