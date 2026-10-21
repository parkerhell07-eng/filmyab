/**
 * Instagram post scraper — multi-method chain.
 * Tries (in order): embed/captioned page, public __a=1 JSON, OG meta tags.
 * No authentication required; degrades gracefully when Instagram blocks access.
 */

export type InstagramPostType = "reel" | "post" | "tv" | "unknown";

export interface InstagramPostData {
  shortcode: string;
  type: InstagramPostType;
  caption: string | null;
  hashtags: string[];
  videoUrl: string | null;
  thumbnailUrl: string | null;
  ownerUsername: string | null;
  method: string;
}

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

const IG_APP_ID = "936619743392459";

export function extractHashtags(text: string | null): string[] {
  if (!text) return [];
  const tags = text.match(/#[\p{L}\p{N}_]+/gu) ?? [];
  return [...new Set(tags.map((t) => t.slice(1)).filter((t) => t.length > 1))].slice(0, 20);
}

/** Parse any Instagram post/reel URL into its shortcode. Returns null if not recognizable. */
export function parseInstagramUrl(
  raw: string
): { shortcode: string; type: InstagramPostType } | null {
  if (!raw) return null;
  let s = raw.trim();
  if (!/^https?:\/\//i.test(s)) s = "https://" + s;
  try {
    const u = new URL(s);
    if (
      !/^(www\.)?(instagram\.com|instagr\.am|ig\.me)$/i.test(u.hostname)
    )
      return null;
    const parts = u.pathname.split("/").filter(Boolean);
    // formats: /p/CODE, /reel/CODE, /reels/CODE, /tv/CODE  or  /share/CODE (redirect link)
    for (let i = 0; i < parts.length; i++) {
      const p = parts[i].toLowerCase();
      if (p === "p" || p === "reel" || p === "reels" || p === "tv") {
        const code = parts[i + 1];
        if (code && /^[A-Za-z0-9_-]{5,}$/.test(code)) {
          return {
            shortcode: code,
            type: p === "reel" || p === "reels" ? "reel" : p === "tv" ? "tv" : "post",
          };
        }
      }
    }
    return null;
  } catch {
    return null;
  }
}

async function fetchText(url: string, extraHeaders: Record<string, string> = {}, timeoutMs = 15000) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      signal: ctrl.signal,
      redirect: "follow",
      headers: {
        "User-Agent": BROWSER_UA,
        "Accept-Language": "en-US,en;q=0.9,fa;q=0.8",
        Accept: "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        ...extraHeaders,
      },
    });
    if (!res.ok) return null;
    return await res.text();
  } catch {
    return null;
  } finally {
    clearTimeout(t);
  }
}

function decodeEntities(s: string): string {
  return s
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'")
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .replace(/&#x2F;/gi, "/");
}

function stripTags(html: string): string {
  return decodeEntities(html.replace(/<br\s*\/?>/gi, "\n").replace(/<[^>]+>/g, " "))
    .replace(/[ \t]+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function unescapeJsonString(s: string): string {
  try {
    return JSON.parse(`"${s}"`);
  } catch {
    return s.replace(/\\u0026/g, "&").replace(/\\\//g, "/").replace(/\\"/g, '"');
  }
}

function cleanCaption(text: string | null | undefined): string | null {
  if (!text) return null;
  let t = text.trim();
  // og:title style: `username on Instagram: "caption..."` or `username (@user) • Instagram photos`
  t = t.replace(/^[\w.\-]+ on Instagram:\s*/i, "");
  t = t.replace(/^Instagram video by\s+/i, "");
  t = t.replace(/\s*•\s*See Instagram (photos and videos|video[^)]*)?.*$/i, "");
  t = t.replace(/[\u2026]? See more on Instagram.*$/i, "");
  t = t.replace(/^"(.*)"$/s, "$1");
  t = t.trim();
  return t.length > 0 ? t : null;
}

/* ---------------- Method 1: embed/captioned page ---------------- */

async function fetchViaEmbed(shortcode: string): Promise<Partial<InstagramPostData> | null> {
  const html = await fetchText(
    `https://www.instagram.com/p/${shortcode}/embed/captioned/`,
    { Referer: "https://www.instagram.com/" }
  );
  if (!html || html.length < 500) return null;

  const out: Partial<InstagramPostData> = { method: "embed" };

  const videoMatch =
    html.match(/"video_url":"((?:[^"\\]|\\.)*)"/) ||
    html.match(/"playback_url":"((?:[^"\\]|\\.)*)"/);
  if (videoMatch) out.videoUrl = unescapeJsonString(videoMatch[1]);

  const imgMatch =
    html.match(/"display_url":"((?:[^"\\]|\\.)*)"/) ||
    html.match(/class="EmbeddedMediaImage"[^>]+src="([^"]+)"/);
  if (imgMatch) out.thumbnailUrl = unescapeJsonString(imgMatch[1]);

  const userMatch = html.match(/class="UsernameText">([^<]+)</);
  if (userMatch) out.ownerUsername = userMatch[1].trim();

  // caption attempt 1: contextJSON (double-escaped)
  const ctxMatch = html.match(/"contextJSON":"((?:[^"\\]|\\.)*)"/);
  if (ctxMatch) {
    try {
      const ctx = JSON.parse(`"${ctxMatch[1]}"`); // first unescape
      const inner = JSON.parse(ctx); // actual object
      const node =
        inner?.graphql?.shortcode_media ??
        inner?.shortcode_media ??
        inner?.data?.shortcode_media ??
        inner;
      const capEdge = node?.edge_media_to_caption?.edges?.[0]?.node?.text;
      const cap = capEdge ?? node?.caption?.text ?? null;
      if (cap) out.caption = cleanCaption(cap);
    } catch {
      /* ignore */
    }
  }

  // caption attempt 2: rendered Caption block
  if (!out.caption) {
    const capHtml = html.match(/<div class="Caption"[^>]*>([\s\S]*?)<\/div>/);
    if (capHtml) {
      let txt = stripTags(capHtml[1]);
      // remove leading creator username line
      txt = txt.replace(/^\S+[\s\S]{0,2}?\n/, (m) => (m.includes("\n") ? "" : m));
      out.caption = cleanCaption(txt);
    }
  }

  // caption attempt 3: edge_media_to_caption directly in page JSON
  if (!out.caption) {
    const m = html.match(
      /"edge_media_to_caption":\{"edges":\[\{"node":\{"text":"((?:[^"\\]|\\.)*)"/
    );
    if (m) out.caption = cleanCaption(unescapeJsonString(m[1]));
  }

  out.hashtags = extractHashtags(out.caption);
  if (!out.caption && !out.videoUrl && !out.thumbnailUrl) return null;
  return out;
}

/* ---------------- Method 2: public JSON endpoint ---------------- */

async function fetchViaApi(shortcode: string): Promise<Partial<InstagramPostData> | null> {
  const body = await fetchText(
    `https://www.instagram.com/p/${shortcode}/?__a=1&__d=dis`,
    { "X-IG-App-ID": IG_APP_ID, Accept: "application/json" }
  );
  if (!body) return null;
  try {
    const json = JSON.parse(body);
    const item =
      json?.items?.[0] ??
      json?.graphql?.shortcode_media ??
      json?.data?.shortcode_media ??
      null;
    if (!item) return null;

    const captionText =
      item?.caption?.text ??
      item?.edge_media_to_caption?.edges?.[0]?.node?.text ??
      null;
    const videoUrl =
      item?.video_url ??
      item?.video_versions?.[0]?.url ??
      item?.playback_url ??
      null;
    const thumbnailUrl =
      item?.image_versions2?.candidates?.[0]?.url ??
      item?.display_url ??
      item?.thumbnail_src ??
      null;

    const out: Partial<InstagramPostData> = {
      method: "api",
      caption: cleanCaption(captionText),
      videoUrl: videoUrl ?? null,
      thumbnailUrl: thumbnailUrl ?? null,
      ownerUsername: item?.owner?.username ?? item?.user?.username ?? null,
    };
    out.hashtags = extractHashtags(out.caption);
    if (!out.caption && !out.videoUrl && !out.thumbnailUrl) return null;
    return out;
  } catch {
    return null;
  }
}

/* ---------------- Method 3: OG meta tags ---------------- */

async function fetchViaOg(shortcode: string): Promise<Partial<InstagramPostData> | null> {
  const html = await fetchText(`https://www.instagram.com/p/${shortcode}/`);
  if (!html) return null;

  const meta = (prop: string): string | null => {
    const re = new RegExp(
      `<meta[^>]+(?:property|name)=["']${prop}["'][^>]+content=["']([^"']*)["']`,
      "i"
    );
    const re2 = new RegExp(
      `<meta[^>]+content=["']([^"']*)["'][^>]+(?:property|name)=["']${prop}["']`,
      "i"
    );
    const m = html.match(re) ?? html.match(re2);
    return m ? decodeEntities(m[1]) : null;
  };

  const title = meta("og:title");
  const description = meta("og:description");
  const caption =
    cleanCaption(title) ??
    cleanCaption(description?.replace(/^\d+\s*(likes?,?\s*|comments?,?\s*)?/i, ""));

  const out: Partial<InstagramPostData> = {
    method: "og",
    caption,
    videoUrl: meta("og:video:secure_url") ?? meta("og:video:url") ?? meta("og:video"),
    thumbnailUrl: meta("og:image"),
  };
  const userMatch = title?.match(/^([\w.\-]+) on Instagram/i);
  if (userMatch) out.ownerUsername = userMatch[1];
  out.hashtags = extractHashtags(out.caption);
  if (!out.caption && !out.videoUrl && !out.thumbnailUrl) return null;
  return out;
}

function mergeParts(parts: (Partial<InstagramPostData> | null)[]): InstagramPostData {
  const merged: InstagramPostData = {
    shortcode: "",
    type: "unknown",
    caption: null,
    hashtags: [],
    videoUrl: null,
    thumbnailUrl: null,
    ownerUsername: null,
    method: "none",
  };
  const methods: string[] = [];
  for (const p of parts) {
    if (!p) continue;
    if (p.caption && !merged.caption) merged.caption = p.caption;
    if (p.videoUrl && !merged.videoUrl) merged.videoUrl = p.videoUrl;
    if (p.thumbnailUrl && !merged.thumbnailUrl) merged.thumbnailUrl = p.thumbnailUrl;
    if (p.ownerUsername && !merged.ownerUsername) merged.ownerUsername = p.ownerUsername;
    if (p.method) methods.push(p.method);
  }
  merged.hashtags = extractHashtags(merged.caption);
  merged.method = methods.join("+") || "none";
  return merged;
}

/** Resolve a /share/ redirect link to its canonical URL, following at most 3 hops. */
async function resolveShareLink(url: string): Promise<string | null> {
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 12000);
    try {
      const res = await fetch(url, {
        signal: ctrl.signal,
        redirect: "follow",
        headers: { "User-Agent": BROWSER_UA },
      });
      const finalUrl = res.url;
      await res.arrayBuffer().catch(() => null);
      return finalUrl;
    } finally {
      clearTimeout(t);
    }
  } catch {
    return null;
  }
}

/** Main entry: fetch an Instagram post by URL (reel/post/tv/share links supported). */
export async function fetchInstagramPost(
  rawUrl: string
): Promise<InstagramPostData | null> {
  let url = rawUrl.trim();
  if (!/^https?:\/\//i.test(url)) url = "https://" + url;

  let parsed = parseInstagramUrl(url);

  // share links redirect to canonical post URLs
  if (!parsed && /instagram\.com\/share\//i.test(url)) {
    const finalUrl = await resolveShareLink(url);
    if (finalUrl) parsed = parseInstagramUrl(finalUrl);
  }
  if (!parsed) return null;

  const results = await Promise.allSettled([
    fetchViaEmbed(parsed.shortcode),
    fetchViaApi(parsed.shortcode),
    fetchViaOg(parsed.shortcode),
  ]);

  const parts = results.map((r) => (r.status === "fulfilled" ? r.value : null));
  const hasAny = parts.some((p) => p && (p.caption || p.videoUrl || p.thumbnailUrl));
  if (!hasAny) return null;

  const merged = mergeParts(parts);
  merged.shortcode = parsed.shortcode;
  merged.type = parsed.type;
  return merged;
}
