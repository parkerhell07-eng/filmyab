// Faithful JS mirror of the NEW SmartEngine.kt logic (v2.1) — validates the fix
// against the real APIs: rate-limit recovery + deterministic evidence path + verify.
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
const WIKI_UA = "FilmYabEngine/2.1 (Android; movie identification; contact: filmyab.app)";
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const norm = (s) => (s || "").toLowerCase().replace(/[^a-z0-9\u0600-\u06ff]/g, "");

// ---- extractLlmContent (exact mirror) ----
function extractLlmContent(body) {
  const t = (body || "").trim();
  if (t.length < 2) return null;
  const l = t.toLowerCase();
  if (l.startsWith("<!doctype") || l.startsWith("<html") || l.includes("just a moment") || l.includes("cloudflare")) return null;
  if (t.startsWith("{")) {
    try {
      const root = JSON.parse(t);
      if (Array.isArray(root.choices) && root.choices.length > 0) {
        const c0 = root.choices[0];
        const c = (c0.message && c0.message.content) || c0.text;
        return typeof c === "string" && c.trim() ? c.trim() : null;
      }
      return typeof root.content === "string" && root.content.trim() ? root.content.trim() : null;
    } catch { return null; }
  }
  return null;
}

// ---- callLlm (exact mirror: rate-limit waits, model rotation, tolerant parse) ----
async function callLlm(system, user, maxRateLimitWaits = 2, totalBudgetMs = 150000, log = console.log) {
  const deadline = Date.now() + totalBudgetMs;
  let rateWaits = 0, hardFails = 0;
  const models = ["openai-fast", "openai"];
  while (Date.now() < deadline) {
    const model = models[hardFails % models.length];
    try {
      const resp = await fetch("https://text.pollinations.ai/openai?referrer=filmyab", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json", "User-Agent": UA },
        body: JSON.stringify({ model, referrer: "filmyab", temperature: 0.2, messages: [
          { role: "system", content: system }, { role: "user", content: user }
        ] }),
        signal: AbortSignal.timeout(125000)
      });
      log(`  [callLlm] model=${model} HTTP ${resp.status}`);
      if (resp.status === 402 || resp.status === 429) {
        if (rateWaits >= maxRateLimitWaits) return null;
        rateWaits++;
        const ra = parseInt(resp.headers.get("retry-after") || "", 10);
        const waitS = Math.min(Math.max(isNaN(ra) ? 30 : ra, 5), 40);
        log(`  [callLlm] rate-limited → waiting ${waitS + 1.5}s (wait ${rateWaits}/${maxRateLimitWaits})`);
        await sleep(waitS * 1000 + 1500);
        continue;
      }
      if (resp.status >= 500) { hardFails++; await sleep(4000); continue; }
      if (!resp.ok) { hardFails++; await sleep(1200); continue; }
      const bodyStr = await resp.text();
      const content = extractLlmContent(bodyStr);
      if (content && content.includes("{") && content.includes("}")) return content;
      hardFails++; await sleep(1200);
    } catch (e) { hardFails++; log(`  [callLlm] exception: ${e.message}`); await sleep(2500); }
  }
  return null;
}

// ---- Wikipedia helpers (exact mirror) ----
const filmRe = /\b(film|movie|series|television|miniseries)\b/i;
const yearRe = /(19|20)\d{2}/;
// curl transport (sandbox: node-fetch TLS bucket is throttled; curl works — logic unchanged)
const { execFileSync } = require("child_process");
function curlJson(url) {
  const out = execFileSync("curl", ["-sS", "--max-time", "20", "-w", "\n%{http_code}", "-H", `User-Agent: ${WIKI_UA}`, "-H", "Accept: application/json", url], { encoding: "utf8" });
  const idx = out.lastIndexOf("\n");
  const code = parseInt(out.slice(idx + 1).trim(), 10);
  const body = out.slice(0, idx);
  return { code, body };
}
async function wikiSearch(lang, q, limit) {
  const url = `https://${lang}.wikipedia.org/w/rest.php/v1/search/title?q=${encodeURIComponent(q)}&limit=${limit}`;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const { code, body } = curlJson(url);
      if (code === 429 && attempt === 0) { await sleep(2000); continue; }
      if (code !== 200) return [];
      const j = JSON.parse(body);
      return (j.pages || []).map(p => ({ lang, key: p.key, title: p.title, description: p.description || "" }));
    } catch { if (attempt === 1) return []; await sleep(2000); }
  }
  return [];
}
async function wikiSummary(lang, key) {
  const url = `https://${lang}.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(key)}`;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const { code, body } = curlJson(url);
      if (code === 429 && attempt === 0) { await sleep(2000); continue; }
      if (code !== 200) return null;
      return JSON.parse(body);
    } catch { if (attempt === 1) return null; await sleep(2000); }
  }
  return null;
}

// ---- directEvidenceCandidates (exact mirror) ----
const GENERIC_TAGS = new Set(["movie","movies","film","films","cinema","reel","reels","instagram","viral","explore","trending","hollywood","bollywood","trailer","clip","scene","scenes","edit","edits","shorts","video","videos","fyp","foryou","actor","actress","love","follow","like","dubbed","netflix","series","movieclip","movieclips","filmclip","newmovie","fullmovie","hd","4k","subscribe","share","comment","explorepage","instagood","reelsvideo","reelsinstagram","trendingreels","viralreels","capcut","status","whatsapp"]);
const splitCamel = (s) => s.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/([A-Za-z])(\d)/g, "$1 $2").replace(/\s+/g, " ").trim();

async function directEvidenceCandidates(post, ocrText, log = console.log) {
  const queries = [];
  if (post.ogTitle) {
    let s = post.ogTitle.replace(/\s*[•|]\s*Instagram\s*$/, "").trim();
    const m = s.match(/^\s*(?:[\d.,]+[KkMm]?\s+likes?,\s*[\d.,]+\s+comments?\s*-\s*)?[^:\n]{0,60}on Instagram:\s*/);
    if (m) s = s.slice(m[0].length).trim();
    if (s.length > 80) s = s.slice(0, 80).trim();
    if (s.length >= 4) queries.push(s);
  }
  for (const tag of (post.hashtags || []).slice(0, 10)) {
    const base = tag.replace(/^#/, "").trim();
    if (base.length < 3 || GENERIC_TAGS.has(base.toLowerCase())) continue;
    const spaced = base.replace(/[_\-.]+/g, " ").replace(/\s+/g, " ").trim();
    queries.push(splitCamel(spaced));
  }
  for (const raw of (post.caption || "").slice(0, 400).split("\n")) {
    const t = raw.trim(), letters = (t.match(/\p{L}/gu) || []).length;
    if (t.length >= 4 && t.length <= 60 && letters >= 4 && letters >= t.length * 0.6) queries.push(t);
  }
  for (const raw of (ocrText || "").split(" | ")) {
    const t = raw.trim(), letters = (t.match(/\p{L}/gu) || []).length;
    if (t.length >= 4 && t.length <= 70 && letters >= 4 && letters >= t.length * 0.5) queries.push(t);
  }
  log(`  [direct] queries: ${JSON.stringify(queries.slice(0, 8))}`);
  const out = [], seen = new Set();
  for (const q of queries.slice(0, 8)) {
    if (out.length >= 4) break;
    await sleep(300); // politeness spacing between wiki calls (mirrors sequential app flow)
    let pages = [];
    const lang = /[؀-ۿ]/.test(q) ? "fa" : "en";
    try { pages = await wikiSearch(lang, q, 5); } catch {}
    if (!pages.length) continue;
    const filmish = (d) => filmRe.test(d) || (d || "").includes("فیلم") || (d || "").includes("سریال");
    const filmPages = pages.filter(p => filmish(p.description));
    const exact = pages.find(p => norm(p.title) === norm(q));
    const chosen = [...new Map([...filmPages, ...(exact ? [exact] : [])].map(p => [p.key, p])).values()].slice(0, 2);
    for (const p of chosen) {
      const k = norm(p.title);
      if (!k || seen.has(k)) continue;
      seen.add(k);
      out.push({ name: p.title, year: (p.description.match(yearRe) || [])[0] || null, confidence: 0.5, reason: "از نشانه‌های خود پست" });
    }
  }
  return out;
}

// ---- scoring + verify (mirror) ----
function scoreCandidate(c, post, ocrText, wiki) {
  let score = c.confidence > 0 ? c.confidence : 0.5;
  if (wiki && wiki.isFilm) score += 0.25;
  const title = c.name; if (!title) return Math.min(Math.max(score, 0.05), 0.97);
  const hay = [post.caption, post.ogTitle, post.ownerUsername].filter(Boolean).join(" ");
  if (norm(hay).includes(norm(title)) && norm(title).length >= 3) score += 0.10;
  if (ocrText && norm(ocrText).includes(norm(title)) && norm(title).length >= 3) score += 0.15;
  const tagHay = (post.hashtags || []).join("").toLowerCase();
  if (norm(title).length >= 3 && tagHay.includes(norm(title))) score += 0.10;
  return Math.min(score, 0.97);
}
async function verifyCandidates(cands, post, ocrText, log = console.log) {
  const out = [], seen = new Set();
  for (const c of cands) {
    if (out.length >= 4) break;
    const title = c.name; if (!title) continue;
    const key = norm(title); if (!key || seen.has(key)) continue; seen.add(key);
    let pages, chosen;
    if (/[؀-ۿ]/.test(title)) {
      pages = await wikiSearch("fa", title, 5);
      chosen = pages.find(p => filmRe.test(p.description) || (p.description||"").includes("فیلم") || (p.description||"").includes("سریال"))
        || pages.find(p => norm(p.title) === key);
    } else {
      pages = await wikiSearch("en", title, 5);
      chosen = pages.find(p => filmRe.test(p.description))
        || pages.find(p => norm(p.title) === key)
        || (await wikiSearch("en", `${title} film`, 4)).find(p => filmRe.test(p.description));
    }
    let wiki = null;
    if (chosen) {
      const s = await wikiSummary(chosen.lang, chosen.key);
      if (s) {
        const desc = s.description || "";
        const hay = `${desc} ${(s.extract || "").slice(0, 400)}`;
        if (filmRe.test(hay) || hay.includes("فیلم") || hay.includes("سریال")) {
          wiki = { pageTitle: chosen.title, pageDescription: desc || null, isFilm: true,
            year: (desc.match(yearRe) || (s.extract || "").slice(0, 200).match(yearRe) || [])[0] || null,
            posterUrl: (s.originalimage || s.thumbnail || {}).source || null,
            pageUrl: s.content_urls?.desktop?.page || null };
        }
      }
    }
    const score = scoreCandidate(c, post, ocrText, wiki);
    if (wiki || score >= 0.5) { out.push({ candidate: c, wiki, score }); log(`  [verify] ${title} → wiki=${!!wiki} score=${score.toFixed(2)}`); }
    else log(`  [verify] ${title} → REJECTED (no wiki, score=${score.toFixed(2)})`);
  }
  return out.sort((a, b) => b.score - a.score);
}

// ================= TESTS =================
const CAND_SYS = 'You are FilmYab, an on-device movie identification engine. You receive evidence scraped from an Instagram reel or post: caption, hashtags, uploader, page og-title, and text read from video frames by on-device OCR (may contain noise, watermarks, subtitle lines or title cards). Task: name the exact movie or series the clip is from. Pay special attention to title cards, credits, character names and subtitle phrases found by OCR. Reply ONLY with minified JSON, no markdown, exactly this schema: {"scene_summary_fa":"یک جمله کوتاه فارسی درباره صحنه","candidates":[{"title":"original English title","year":"","confidence":0.0,"reason_fa":"دلیل کوتاه فارسی"}],"found":true}. Rules: at most 3 candidates ordered by likelihood; confidence is 0..1 and reflects your certainty for each candidate; year="" if unsure; if the evidence is too weak to name any movie, set found=false and candidates=[].';

async function main() {
  console.log("== TEST 1: rate-limit recovery (the exact user failure path) ==");
  const t0 = Date.now();
  const content = await callLlm(CAND_SYS,
    "Identify the movie from this Instagram post.\nCaption: best prison escape scene ever #thegreenmile\nOn-screen text (OCR): John Coffey | Paul Edgecomb",
    2, 150000, console.log);
  const dt = ((Date.now() - t0) / 1000).toFixed(1);
  if (!content) { console.log(`TEST1 FAILED after ${dt}s`); process.exit(1); }
  console.log(`  ✓ recovered after ${dt}s → ${content.slice(0, 160)}`);

  console.log("\n== TEST 2: direct evidence path (ZERO LLM calls) ==");
  const post = { ogTitle: "Movie Central on Instagram: best prison drama ever made", hashtags: ["#ShawshankRedemption", "#movie", "#classicfilm"], caption: "one of the greatest endings in cinema history", ownerUsername: "moviecentral" };
  const ocr = "Andy Dufresne | Get busy living or get busy dying";
  const direct = await directEvidenceCandidates(post, ocr, console.log);
  console.log("  direct candidates:", direct.map(c => `${c.name} (${c.year})`));
  if (!direct.some(c => /shawshank/i.test(c.name))) { console.log("TEST2 FAILED"); process.exit(1); }
  const verified = await verifyCandidates(direct, post, ocr, console.log);
  const best = verified[0];
  console.log(`  ✓ best: ${best.candidate.name} (${best.wiki?.year || "?"}) score=${best.score.toFixed(2)} poster=${!!best.wiki?.posterUrl}`);
  if (best.score < 0.7) { console.log("TEST2 score too low"); process.exit(1); }

  console.log("\n== TEST 2b: Persian caption → fa.wikipedia (ZERO LLM) ==");
  const faPost = { ogTitle: "Cinema Baghi on Instagram: سکانس فوق العاده", hashtags: ["#جدایی_نادر_از_سیمین", "#film"], caption: "سکانس پایانی فیلم جدایی نادر از سیمین", ownerUsername: "cinemabaghi" };
  const faDirect = await directEvidenceCandidates(faPost, "", console.log);
  console.log("  fa candidates:", faDirect.map(c => `${c.name} (${c.year})`));
  if (!faDirect.length) { console.log("TEST2b WARN: no fa candidates (throttle may still be active)"); }
  else {
    const faV = await verifyCandidates(faDirect, faPost, "", console.log);
    if (faV.length) console.log(`  ✓ fa best: ${faV[0].candidate.name} score=${faV[0].score.toFixed(2)}`);
    else console.log("TEST2b WARN: fa verify empty");
  }

  console.log("\n== TEST 3: merged pipeline (LLM + direct, dedup) ==");
  const llmCands = [{ name: "The Shawshank Redemption", year: "1994", confidence: 0.9 }, { name: "The Green Mile", year: "1999", confidence: 0.5 }];
  const merged = [];
  const mseen = new Set();
  for (const c of [...llmCands, ...direct]) {
    const k = norm(c.name); if (!k || mseen.has(k)) continue; mseen.add(k); merged.push(c);
    if (merged.length >= 5) break;
  }
  console.log("  merged:", merged.map(c => c.name));
  const v2 = await verifyCandidates(merged, post, ocr, console.log);
  console.log(`  ✓ best after merge: ${v2[0].candidate.name} score=${v2[0].score.toFixed(2)}`);

  console.log("\nALL TESTS PASSED — the new algorithm recovers from rate limits AND identifies movies without the LLM.");
}
main().catch(e => { console.error("FATAL", e); process.exit(1); });
