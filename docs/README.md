# Documentation

| Guide | Use it for |
| --- | --- |
| [Project and development](project-map.md) | Architecture, local setup/tests, domain/API/security contracts |
| [Deployment and production](deployment-setup.md) | GitHub settings, VPS/service/sandbox setup, rollout/troubleshooting |
| [Data safety and recovery](operations-runbook.md) | Backup limits and exact restore/import/export/reconciliation procedures |
| [Maintenance](maintenance.md) | Runtime upgrades, scanner scope, legacy encryption |

## Design proposals (not runtime contracts)

- [Template creation improvements](template-creation/README.md) — verified current flow and proposed precision editor/direct Mini App submission; kept separate from the guides above.

Keep guides current/task-oriented. Source/scripts define behavior; CI artifacts and private manifests retain per-run evidence. Avoid phase reports, incident transcripts, commit/test-count timelines and credentials. Historical tracked docs remain in Git history; deleting reports never authorizes deletion of private data/recovery archives.