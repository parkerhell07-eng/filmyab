import { NextRequest } from "next/server";
import { db } from "@/lib/db";
import { fetchInstagramPost, parseInstagramUrl } from "@/lib/instagram";
import {
  analyzeVideoUrl,
  analyzeFrames,
  analyzeImageUrl,
  downloadVideo,
  extractFrames,
  buildSearchQueries,
  searchWeb,
  identifyMovie,
  findPosterUrl,
  type Evidence,
  type SceneAnalysis,
  type WebSource,
} from "@/lib/identify";

export const runtime = "nodejs";
export const maxDuration = 300;

interface IdentifyBody {
  url?: string;
  imageBase64?: string;
  note?: string;
}

interface PipelineResult {
  status: "success" | "low_confidence" | "failed";
  movieName: string | null;
  originalName: string | null;
  year: string | null;
  director: string | null;
  genres: string[];
  confidence: number;
  reasoning: string;
  candidates: { name: string; year: string; confidence: number }[];
  posterUrl: string | null;
  sources: { name: string; url: string }[];
  instagramUrl: string | null;
  caption: string | null;
  sceneDescription: string | null;
  sourceMode: "link" | "screenshot";
}

function stripDataUri(b64: string): string {
  const m = b64.match(/^data:image\/[a-zA-Z+]+;base64,(.*)$/s);
  return m ? m[1] : b64;
}

export async function POST(req: NextRequest) {
  let body: IdentifyBody = {};
  try {
    body = await req.json();
  } catch {
    /* empty body */
  }

  const url = (body.url ?? "").trim();
  const imageBase64 = stripDataUri((body.imageBase64 ?? "").trim());
  const note = (body.note ?? "").trim().slice(0, 300) || null;
  const mode: "link" | "screenshot" | null = url
    ? "link"
    : imageBase64.length > 100
      ? "screenshot"
      : null;

  const encoder = new TextEncoder();

  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      let closed = false;
      const send = (obj: unknown) => {
        if (closed) return;
        try {
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(obj)}\n\n`));
        } catch {
          closed = true;
        }
      };

      let scene: SceneAnalysis | null = null;
      let caption: string | null = null;
      let hashtags: string[] = [];
      let uploader: string | null = null;
      let effectiveUrl: string | null = null;

      try {
        if (!mode) {
          send({
            type: "error",
            message: "لطفاً لینک اینستاگرام یا اسکرین‌شات را وارد کنید.",
          });
          return;
        }

        /* ---------- Steps 1 & 2: fetch post + analyze visuals ---------- */

        if (mode === "link") {
          send({ step: "fetch", status: "start" });
          if (!parseInstagramUrl(url) && !/instagram\.com\/share\//i.test(url)) {
            send({
              step: "fetch",
              status: "error",
              detail: "لینک واردشده یک پست یا ریلز اینستاگرام نیست.",
            });
            send({
              type: "error",
              message:
                "این یک لینک معتبر پست/ریلز اینستاگرام نیست. لینک را از دکمه «کپی لینک» خود اینستاگرام بگیرید.",
            });
            return;
          }

          const post = await fetchInstagramPost(url);
          if (!post) {
            send({
              step: "fetch",
              status: "error",
              detail: "اینستاگرام اجازه خواندن این پست را نداد.",
            });
            send({
              type: "error",
              hint: "screenshot",
              message:
                "اینستاگرام دسترسی به این پست را مسدود کرده (پست خصوصی یا محدودیت دسترسی). می‌توانید از حالت «جستجو با اسکرین‌شات» استفاده کنید.",
            });
            await db.identification
              .create({
                data: { instagramUrl: url, note, status: "failed", source: "link" },
              })
              .catch(() => {});
            return;
          }

          caption = post.caption;
          hashtags = post.hashtags;
          uploader = post.ownerUsername;
          effectiveUrl = url;

          send({
            step: "fetch",
            status: "done",
            data: {
              caption: caption ? caption.slice(0, 220) : null,
              hashtags: hashtags.slice(0, 8),
              hasVideo: Boolean(post.videoUrl),
              hasImage: Boolean(post.thumbnailUrl),
              uploader,
              method: post.method,
            },
          });

          send({ step: "analyze", status: "start" });
          if (post.videoUrl) scene = await analyzeVideoUrl(post.videoUrl);
          if (!scene && post.videoUrl) {
            const videoPath = await downloadVideo(post.videoUrl);
            if (videoPath) {
              const frames = await extractFrames(videoPath);
              if (frames.length > 0) scene = await analyzeFrames(frames);
            }
          }
          if (!scene && post.thumbnailUrl) scene = await analyzeImageUrl(post.thumbnailUrl);

          send({
            step: "analyze",
            status: scene?.sceneDescription ? "done" : "skip",
            data: scene?.sceneDescription
              ? { sceneDescription: scene.sceneDescription.slice(0, 300) }
              : undefined,
          });
        } else {
          /* ---------- Screenshot mode ---------- */
          send({ step: "fetch", status: "skip" });
          send({ step: "analyze", status: "start" });
          const buf = Buffer.from(imageBase64, "base64");
          if (buf.length < 1000) {
            send({ type: "error", message: "فایل تصویر نامعتبر است." });
            return;
          }
          scene = await analyzeFrames([buf]);
          send({
            step: "analyze",
            status: scene?.sceneDescription ? "done" : "skip",
            data: scene?.sceneDescription
              ? { sceneDescription: scene.sceneDescription.slice(0, 300) }
              : undefined,
          });
        }

        /* ---------- Step 3: web search ---------- */

        const evidence: Evidence = {
          caption,
          hashtags,
          ownerUsername: uploader,
          sceneDescription: scene?.sceneDescription ?? null,
          visibleText: scene?.visibleText ?? null,
          userNote: note,
        };

        send({ step: "search", status: "start" });
        const queries = buildSearchQueries(evidence);
        const sources: WebSource[] =
          queries.length > 0 ? await searchWeb(queries) : [];
        send({
          step: "search",
          status: sources.length > 0 ? "done" : "skip",
          data: { queries, count: sources.length },
        });

        /* ---------- Step 4: smart match ---------- */

        send({ step: "match", status: "start" });
        const match = await identifyMovie(evidence, sources);
        send({
          step: "match",
          status: "done",
          data: {
            found: match.found,
            confidence: match.confidence,
            title: match.originalName ?? match.movieName,
          },
        });

        /* ---------- Step 5: poster ---------- */

        let posterUrl: string | null = null;
        if (match.found && match.originalName) {
          send({ step: "poster", status: "start" });
          posterUrl = await findPosterUrl(match.originalName, match.year);
          send({ step: "poster", status: posterUrl ? "done" : "skip" });
        } else {
          send({ step: "poster", status: "skip" });
        }

        /* ---------- Compose & persist ---------- */

        const status: PipelineResult["status"] =
          match.found && match.confidence >= 0.45
            ? "success"
            : match.found || match.candidates.length > 0
              ? "low_confidence"
              : "failed";

        const result: PipelineResult = {
          status,
          movieName: match.movieName,
          originalName: match.originalName,
          year: match.year,
          director: match.director,
          genres: match.genres,
          confidence: match.confidence,
          reasoning: match.reasoning,
          candidates: match.candidates,
          posterUrl,
          sources: sources.slice(0, 8).map((s) => ({ name: s.name, url: s.url })),
          instagramUrl: effectiveUrl,
          caption: caption ? caption.slice(0, 500) : null,
          sceneDescription: scene?.sceneDescription ?? null,
          sourceMode: mode,
        };

        await db.identification
          .create({
            data: {
              instagramUrl: result.instagramUrl,
              note,
              status: result.status,
              movieName: result.movieName,
              originalName: result.originalName,
              year: result.year,
              director: result.director,
              genres: JSON.stringify(result.genres),
              confidence: result.confidence,
              reasoning: result.reasoning,
              posterUrl: result.posterUrl,
              sources: JSON.stringify(result.sources),
              candidates: JSON.stringify(result.candidates),
              caption: result.caption,
              sceneDescription: result.sceneDescription,
              source: mode,
            },
          })
          .catch(() => {});

        send({ type: "result", result });
      } catch (err) {
        send({
          type: "error",
          message:
            "خطای غیرمنتظره در پردازش. لطفاً دوباره تلاش کنید." +
            (err instanceof Error ? ` (${err.message.slice(0, 120)})` : ""),
        });
      } finally {
        closed = true;
        try {
          controller.close();
        } catch {
          /* already closed */
        }
      }
    },
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    },
  });
}
