/**
 * Film identification engine — AI pipeline helpers.
 * Scene analysis (vision on video frames / thumbnail / screenshot),
 * web search, LLM smart-matching, and poster lookup.
 * All functions use z-ai-web-dev-sdk (backend only).
 */

import ZAI from "z-ai-web-dev-sdk";
import { spawn } from "child_process";
import fs from "fs/promises";
import os from "os";
import path from "path";
import { randomUUID } from "crypto";

let cachedZAI: Awaited<ReturnType<typeof ZAI.create>> | null = null;

export async function getZAI() {
  if (!cachedZAI) cachedZAI = await ZAI.create();
  return cachedZAI;
}

/* ------------------------------------------------------------------ */
/* Types                                                               */
/* ------------------------------------------------------------------ */

export interface SceneAnalysis {
  sceneDescription: string | null;
  visibleText: string | null;
}

export interface Evidence {
  caption: string | null;
  hashtags: string[];
  ownerUsername: string | null;
  sceneDescription: string | null;
  visibleText: string | null;
  userNote: string | null;
}

export interface WebSource {
  name: string;
  url: string;
  snippet: string;
  host: string;
}

export interface MovieCandidate {
  name: string;
  year: string;
  confidence: number;
}

export interface MovieMatch {
  found: boolean;
  movieName: string | null;
  originalName: string | null;
  year: string | null;
  director: string | null;
  genres: string[];
  confidence: number;
  reasoning: string;
  candidates: MovieCandidate[];
}

/* ------------------------------------------------------------------ */
/* Video downloading & frame extraction (ffmpeg)                       */
/* ------------------------------------------------------------------ */

async function runCmd(bin: string, args: string[], timeoutMs = 25000): Promise<{ code: number; stdout: string }> {
  return new Promise((resolve, reject) => {
    const proc = spawn(bin, args, { stdio: ["ignore", "pipe", "ignore"] });
    let stdout = "";
    const timer = setTimeout(() => {
      proc.kill("SIGKILL");
      reject(new Error(`${bin} timeout`));
    }, timeoutMs);
    proc.stdout.on("data", (d) => (stdout += d.toString()));
    proc.on("error", (e) => {
      clearTimeout(timer);
      reject(e);
    });
    proc.on("close", (code) => {
      clearTimeout(timer);
      resolve({ code: code ?? 1, stdout });
    });
  });
}

/** Download a video (Instagram CDN or any public URL) into a temp dir. */
export async function downloadVideo(
  url: string,
  maxBytes = 80 * 1024 * 1024,
  timeoutMs = 30000
): Promise<string | null> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "filmyab-"));
  const filePath = path.join(dir, "video.mp4");
  try {
    const res = await fetch(url, {
      signal: ctrl.signal,
      headers: {
        "User-Agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        Referer: "https://www.instagram.com/",
      },
    });
    if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
    const buf = Buffer.from(await res.arrayBuffer());
    if (buf.length < 10 * 1024) throw new Error("video too small");
    if (buf.length > maxBytes) throw new Error("video too large");
    await fs.writeFile(filePath, buf);
    return filePath;
  } catch {
    await fs.rm(dir, { recursive: true, force: true }).catch(() => {});
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/** Extract up to 3 representative frames from a video using ffmpeg. */
export async function extractFrames(videoPath: string): Promise<Buffer[]> {
  const dir = path.dirname(videoPath);
  let duration: number | null = null;
  try {
    const { code, stdout } = await runCmd(
      "ffprobe",
      ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", videoPath],
      15000
    );
    if (code === 0) {
      const d = parseFloat(stdout.trim().split("\n")[0]);
      if (Number.isFinite(d) && d > 0) duration = d;
    }
  } catch {
    /* ffprobe unavailable */
  }

  const times = duration
    ? [duration * 0.15, duration * 0.45, duration * 0.75]
    : [1, 4, 8];

  const frames: Buffer[] = [];
  for (let i = 0; i < times.length; i++) {
    const out = path.join(dir, `frame-${i}.jpg`);
    try {
      const { code } = await runCmd(
        "ffmpeg",
        [
          "-y",
          "-ss",
          times[i].toFixed(2),
          "-i",
          videoPath,
          "-frames:v",
          "1",
          "-vf",
          "scale='min(540,iw)':-2",
          "-q:v",
          "4",
          out,
        ],
        20000
      );
      if (code === 0) {
        const buf = await fs.readFile(out).catch(() => null);
        if (buf && buf.length > 2000) frames.push(buf);
      }
    } catch {
      /* skip frame */
    }
  }

  await fs.rm(path.dirname(videoPath), { recursive: true, force: true }).catch(() => {});
  return frames.slice(0, 3);
}

/* ------------------------------------------------------------------ */
/* Vision analysis                                                     */
/* ------------------------------------------------------------------ */

const SCENE_PROMPT = `You are a film-forensics analyst. These are frames (or one video) from a movie/TV clip posted on Instagram.
Your goal is to extract every clue that could help identify the EXACT film or series this clip belongs to.

Analyze carefully and reply with ONLY a JSON object (no markdown):
{
  "scene_description": "Detailed description in English: setting, era/period look, visual style/color grading, characters' appearance/clothing/age, notable actors if recognizable, actions happening, production quality (cinematic vs amateur), possible country/language of production",
  "visible_text": "Any text visible in the frames: movie titles, credits, subtitles, watermarks, channel logos, filenames, interface text — reproduce them EXACTLY. Write 'none' if nothing readable."
}

Be specific and concrete (e.g. 'three men in 1940s military uniforms in a muddy trench' instead of 'some people').`;

function extractJson(text: string): Record<string, unknown> | null {
  const cleaned = text.replace(/```(?:json)?/gi, "").trim();
  const start = cleaned.indexOf("{");
  const end = cleaned.lastIndexOf("}");
  if (start === -1 || end === -1 || end <= start) return null;
  try {
    return JSON.parse(cleaned.slice(start, end + 1));
  } catch {
    return null;
  }
}

function parseSceneReply(reply: string): SceneAnalysis {
  const json = extractJson(reply);
  if (json && typeof json.scene_description === "string") {
    return {
      sceneDescription: json.scene_description.slice(0, 2000),
      visibleText:
        typeof json.visible_text === "string" && json.visible_text.toLowerCase() !== "none"
          ? json.visible_text.slice(0, 1000)
          : null,
    };
  }
  // fallback: treat whole reply as description
  return reply.trim().length > 0
    ? { sceneDescription: reply.trim().slice(0, 2000), visibleText: null }
    : { sceneDescription: null, visibleText: null };
}

/** Analyze the clip video by direct URL (video_url content type). */
export async function analyzeVideoUrl(videoUrl: string): Promise<SceneAnalysis | null> {
  try {
    const zai = await getZAI();
    const response = await zai.chat.completions.createVision({
      model: "glm-4.6v",
      messages: [
        { role: "assistant", content: "Output only text, no markdown." },
        {
          role: "user",
          content: [
            { type: "text", text: SCENE_PROMPT },
            { type: "video_url", video_url: { url: videoUrl } },
          ],
        },
      ],
      thinking: { type: "disabled" },
    });
    const reply = response?.choices?.[0]?.message?.content;
    if (!reply) return null;
    return parseSceneReply(reply);
  } catch {
    return null;
  }
}

/** Analyze extracted frames (base64 buffers) or a screenshot. */
export async function analyzeFrames(frames: Buffer[]): Promise<SceneAnalysis | null> {
  if (frames.length === 0) return null;
  try {
    const zai = await getZAI();
    const response = await zai.chat.completions.createVision({
      model: "glm-4.6v",
      messages: [
        { role: "assistant", content: "Output only text, no markdown." },
        {
          role: "user",
          content: [
            { type: "text", text: SCENE_PROMPT },
            ...frames.map((b) => ({
              type: "image_url" as const,
              image_url: { url: `data:image/jpeg;base64,${b.toString("base64")}` },
            })),
          ],
        },
      ],
      thinking: { type: "disabled" },
    });
    const reply = response?.choices?.[0]?.message?.content;
    if (!reply) return null;
    return parseSceneReply(reply);
  } catch {
    return null;
  }
}

/** Analyze a remote image (thumbnail) by URL. */
export async function analyzeImageUrl(imageUrl: string): Promise<SceneAnalysis | null> {
  try {
    const zai = await getZAI();
    const response = await zai.chat.completions.createVision({
      model: "glm-4.6v",
      messages: [
        { role: "assistant", content: "Output only text, no markdown." },
        {
          role: "user",
          content: [
            { type: "text", text: SCENE_PROMPT },
            { type: "image_url", image_url: { url: imageUrl } },
          ],
        },
      ],
      thinking: { type: "disabled" },
    });
    const reply = response?.choices?.[0]?.message?.content;
    if (!reply) return null;
    return parseSceneReply(reply);
  } catch {
    return null;
  }
}

/* ------------------------------------------------------------------ */
/* Web search                                                          */
/* ------------------------------------------------------------------ */

function cleanQuery(text: string, maxLen = 110): string {
  return text
    .replace(/#[\p{L}\p{N}_]+/gu, " ")
    .replace(/@[\w.]+/g, " ")
    .replace(/https?:\/\/\S+/g, " ")
    .replace(/[\p{Emoji_Presentation}\u200c]/gu, " ")
    .replace(/[^\p{L}\p{N}\s؟?]/gu, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, maxLen);
}

/** Build 2-3 targeted search queries from collected evidence. */
export function buildSearchQueries(evidence: Evidence): string[] {
  const queries: string[] = [];
  const cap = evidence.caption ? cleanQuery(evidence.caption) : "";
  if (cap.length > 12) queries.push(`${cap} movie`);

  const tags = evidence.hashtags.slice(0, 6).join(" ");
  if (tags) queries.push(`${tags} movie clip scene`);

  const scene = evidence.sceneDescription ? cleanQuery(evidence.sceneDescription, 90) : "";
  if (scene.length > 20) queries.push(`film scene: ${scene}`);

  if (queries.length === 0 && evidence.visibleText) {
    const vt = cleanQuery(evidence.visibleText, 80);
    if (vt.length > 6) queries.push(vt);
  }
  return [...new Set(queries)].slice(0, 3);
}

export async function searchWeb(queries: string[], num = 8): Promise<WebSource[]> {
  const zai = await getZAI();
  const all: WebSource[] = [];
  const seen = new Set<string>();
  for (const query of queries) {
    try {
      const results = (await zai.functions.invoke("web_search", {
        query,
        num,
      })) as Array<{
        url: string;
        name: string;
        snippet?: string;
        host_name?: string;
      }>;
      for (const r of results ?? []) {
        if (!r?.url || seen.has(r.url)) continue;
        seen.add(r.url);
        all.push({
          name: (r.name ?? "").slice(0, 160),
          url: r.url,
          snippet: (r.snippet ?? "").slice(0, 400),
          host: r.host_name ?? new URL(r.url).hostname,
        });
      }
    } catch {
      /* continue with next query */
    }
  }
  return all.slice(0, 18);
}

/* ------------------------------------------------------------------ */
/* LLM smart match                                                     */
/* ------------------------------------------------------------------ */

const MATCH_SYSTEM = `You are a film identification expert with encyclopedic knowledge of world cinema (Hollywood, European, Asian, Iranian, Turkish, Indian, Korean, Russian and more).
You receive evidence collected from an Instagram post that contains a clip/teaser of a movie or TV series: the post caption, hashtags, an AI analysis of the scene, text visible in frames, web search results, and an optional note from the user.

Your job: identify the EXACT movie or series the clip comes from.

Rules:
- Weigh ALL evidence together. Captions/hashtags are often in Persian or other languages; treat them as strong hints. Scene analysis gives visual clues (actors, era, style, on-screen text).
- If visible text contains a title or credits, it is the strongest signal.
- Reply with ONLY a valid JSON object (no markdown fences), exactly this shape:
{
  "found": true/false,
  "movieName": "Persian title if you know it, otherwise the original title; null if not found",
  "originalName": "Original title (e.g. 'Inception') or null",
  "year": "release year as string or null",
  "director": "director or null",
  "genres": ["genre1", "genre2"],
  "confidence": 0.0 to 1.0,
  "reasoning": "2-3 sentences IN PERSIAN (فارسی) explaining the identification and which evidence supports it",
  "candidates": [{"name": "alternative title", "year": "year", "confidence": 0.0}]
}
- candidates: up to 3 alternatives ordered by plausibility (always include some, even when found=true).
- Set found=true only when confidence >= 0.45. If evidence is too weak, found=false and put best guesses in candidates.
- TV series are allowed: originalName = series name.

** Evidence **`;

export async function identifyMovie(
  evidence: Evidence,
  sources: WebSource[]
): Promise<MovieMatch> {
  const evidenceBlock = JSON.stringify(
      {
        user_note: evidence.userNote ?? null,
        instagram_caption: evidence.caption,
        hashtags: evidence.hashtags,
        uploader: evidence.ownerUsername,
        ai_scene_analysis: evidence.sceneDescription,
        ai_visible_text: evidence.visibleText,
        web_search_results: sources.slice(0, 12).map((s) => ({
          title: s.name,
          host: s.host,
          snippet: s.snippet,
          url: s.url,
        })),
      },
      null,
      1
    );

  try {
    const zai = await getZAI();
    const completion = await zai.chat.completions.create({
      messages: [
        { role: "assistant", content: MATCH_SYSTEM },
        { role: "user", content: evidenceBlock },
      ],
      thinking: { type: "enabled" },
    });

    const reply: string = completion?.choices?.[0]?.message?.content ?? "";
    const json = extractJson(reply);
    if (!json) {
      return {
        found: false,
        movieName: null,
        originalName: null,
        year: null,
        director: null,
        genres: [],
        confidence: 0,
        reasoning: reply.trim().slice(0, 600) || "پاسخ هوش مصنوعی قابل تفسیر نبود.",
        candidates: [],
      };
    }

    const conf = Math.max(0, Math.min(1, Number(json.confidence) || 0));
    const candidates = Array.isArray(json.candidates)
      ? json.candidates
          .slice(0, 3)
          .map((c: Record<string, unknown>) => ({
            name: String(c?.name ?? "").slice(0, 120),
            year: String(c?.year ?? "").slice(0, 12),
            confidence: Math.max(0, Math.min(1, Number(c?.confidence) || 0)),
          }))
          .filter((c: MovieCandidate) => c.name.length > 0)
      : [];

    return {
      found: Boolean(json.found) && conf >= 0.35,
      movieName: json.movieName ? String(json.movieName).slice(0, 150) : null,
      originalName: json.originalName ? String(json.originalName).slice(0, 150) : null,
      year: json.year ? String(json.year).slice(0, 12) : null,
      director: json.director ? String(json.director).slice(0, 120) : null,
      genres: Array.isArray(json.genres)
        ? json.genres.slice(0, 5).map((g: unknown) => String(g).slice(0, 40))
        : [],
      confidence: conf,
      reasoning: String(json.reasoning ?? "").slice(0, 900),
      candidates,
    };
  } catch (err) {
    return {
      found: false,
      movieName: null,
      originalName: null,
      year: null,
      director: null,
      genres: [],
      confidence: 0,
      reasoning:
        "خطا در مرحله تحلیل نهایی هوش مصنوعی: " +
        (err instanceof Error ? err.message : "نامشخص"),
      candidates: [],
    };
  }
}

/* ------------------------------------------------------------------ */
/* Poster lookup                                                       */
/* ------------------------------------------------------------------ */

export async function findPosterUrl(
  originalName: string,
  year?: string | null
): Promise<string | null> {
  try {
    const zai = await getZAI();
    const query = `${originalName}${year ? ` ${year}` : ""} movie poster`;
    const res = await zai.images.search.create({ query, count: 8, rank: true });
    const items = res?.results ?? [];
    for (const item of items) {
      const w = parseInt(item?.original_width ?? "0", 10) || 0;
      const h = parseInt(item?.original_height ?? "0", 10) || 0;
      if (w >= 200 && h >= 200 && item?.original_url) return item.original_url;
    }
    return items[0]?.original_url ?? null;
  } catch {
    return null;
  }
}

/** Small helper used by the SSE route to keep ids unique for temp artifacts. */
export function newId(): string {
  return randomUUID().replace(/-/g, "").slice(0, 16);
}
