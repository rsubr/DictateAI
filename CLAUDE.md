# VoiceClean (Android) — agent entry point

On-device dictation app: whisper.cpp (speech-to-text) + llama.cpp (text cleanup/rewrite). English only. Target: Galaxy S, Snapdragon 8/7 (arm64-v8a).

**Before doing anything, read in this order:**
1. `docs/STATE.md`   — current status, what's done, what's next, known issues (UPDATE THIS when you finish work)
2. `docs/PLAN.md`    — architecture, milestones, task breakdown
3. `docs/DECISIONS.md` — decisions already made (do not relitigate)

Rules for every agent/session:
- Update `docs/STATE.md` (task checkboxes, "Last session", "Known issues", "Next steps") before finishing.
- Log any new architectural decision in `docs/DECISIONS.md`.
- Build commands live in `docs/BUILD.md`; keep them accurate.
- Stay within your assigned module/files to avoid merge conflicts with parallel agents.
