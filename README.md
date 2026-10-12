# RegataSimulator

Telegram meme submissions and scheduled publishing, with Spring Boot administration pages/APIs, ImageMagick rendering, SQLite/JsonDB storage and backup/recovery tools.

Start with the [documentation index](docs/README.md) for development, deployment, recovery and maintenance. Contributor/agent safety rules are in [AGENTS.md](AGENTS.md).

**Startup has real side effects:** bot registration and schedules default on. Tests need isolated data and explicitly disabled/mocked external effects. The GitHub environment named `stage` is production.
