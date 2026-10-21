# Project Worklog — فیلم‌یاب (FilmYab)

Instagram clip → Movie name identifier. Mobile-first Persian RTL web app (PWA-installable on Android),
built with Next.js 16 + Prisma + z-ai-web-dev-sdk (vision / web_search / page_reader / image search).

Pipeline: Instagram URL → scrape post (caption/hashtags/video/thumbnail) → AI scene analysis (frames via ffmpeg + vision)
→ web search → LLM smart-match → movie name + confidence + poster → SQLite history.

---
Task ID: 1
Agent: main (Z.ai Code)
Task: Project setup — worklog init, DB schema

Work Log:
- Inspected project scaffold (Next.js 16, shadcn/ui complete, z-ai-web-dev-sdk 0.0.18, ffmpeg available)
- Read SDK typings: confirmed `functions.invoke('web_search'|'page_reader')`, `chat.completions.createVision` (video_url/image_url), `images.search.create`
- Initialized this worklog
- Added `Identification` model to prisma/schema.prisma (history of identifications)

Stage Summary:
- Schema ready to push; API contract designed for SSE streaming pipeline

---
Task ID: 5-b
Agent: general-purpose (icon generator)
Task: Generate PWA icons for FilmYab

Work Log:
- Read worklog.md and skills/image-generation/SKILL.md (CLI usage: `z-ai image -p "..." -o "..." -s 1024x1024`)
- Verified tooling: z-ai CLI at /usr/local/bin/z-ai, bun at /usr/local/bin/bun, sharp 0.34.5 installed
- Created /home/z/my-project/public/icons/ directory
- Generated the app icon via z-ai CLI with the specified prompt (clapperboard + magnifying glass, dark charcoal #141210 bg, amber-gold #f5b544 tones, flat minimal, no text) at 1024x1024 — success on first attempt (no fallback needed)
- CLI output was actually JPEG data with .png extension; re-encoded icon-1024.png to true PNG via sharp (1024x1024, ~838 KB)
- Wrote a small bun script using sharp to resize the 1024 source to icon-512.png (512x512) and icon-192.png (192x192), PNG compression level 9; temp script deleted afterwards
- Verified all 3 files with ls -la, `file`, and sharp metadata: all valid PNGs, nonzero size

Stage Summary:
- PWA icon set produced (real AI generation, no fallback):
  - /home/z/my-project/public/icons/icon-1024.png (1024x1024 PNG, 838,188 bytes)
  - /home/z/my-project/public/icons/icon-512.png (512x512 PNG, 266,239 bytes)
  - /home/z/my-project/public/icons/icon-192.png (192x192 PNG, 45,020 bytes)
- Ready to be referenced in next.config / app manifest icons array (left for main agent)

---
Task ID: 2 → 5-a (main implementation)
Agent: main (Z.ai Code)
Task: Full implementation of FilmYab — backend pipeline, API routes, Persian RTL UI, PWA, E2E verification

Work Log:
- src/lib/instagram.ts: multi-method IG scraper (embed/captioned HTML, __a=1 JSON with X-IG-App-ID, OG meta tags), /share/ redirect resolver, shortcode parser, caption/hashtag extraction
- src/lib/identify.ts: AI engine — ffmpeg frame extraction (3 frames @ 15/45/75%), vision analysis via glm-4.6v (video_url → frames → thumbnail fallbacks), web_search queries builder, LLM smart-match with thinking mode + robust JSON extraction, poster lookup via images.search
- src/app/api/identify/route.ts: SSE streaming pipeline (fetch → analyze → search → match → poster), link + screenshot modes, DB persistence, Persian error messages with screenshot hint
- src/app/api/history/route.ts: GET last 30 / DELETE all
- layout: fa/rtl, Vazirmatn via Google Fonts, PWA metadata + viewport themeColor
- globals.css: warm dark cinematic theme (amber/gold accent, oklch), glass cards, custom scrollbar
- src/app/manifest.ts: PWA manifest (standalone, portrait, fa/rtl icons)
- src/app/page.tsx: full UI — input + paste button, optional hint field, animated 5-step pipeline panel (SSE events), result card (poster, names, confidence bar, reasoning, candidates, sources collapsible), screenshot upload fallback, history list (max-h-96 scroll, fa relative dates), sticky footer
- Fixed: date-fns v4 locale is faIR (not fa) — was breaking compile
- Fixed: react-hooks/set-state-in-effect in PosterImage (failedFor pattern)

Verification (agent-browser E2E):
- Page renders on mobile (390x844) & desktop; RTL Persian cinematic dark theme OK
- Invalid link → validation toast OK
- Real IG link → IG blocked datacenter IP → graceful error panel + screenshot hint + failed row in history (verified DB row via /api/history)
- Screenshot E2E (AI-generated cinematic still): full pipeline in 22.3s → correctly identified "Ad Astra (2019)" at 90% confidence, poster found, candidates (The Martian/Moon/Interstellar), 7 sources, reasoning in Persian
- History shows both entries; reset button works; lint clean; no console errors after fix

Stage Summary:
- App fully functional; link mode works when IG is reachable (sandbox IP is blocked by IG — degrade path verified); screenshot mode fully verified end-to-end
- Key artifacts: prisma schema (Identification), 2 lib modules, 2 API routes, manifest, themed page
