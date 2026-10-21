import { NextResponse } from "next/server";
import { db } from "@/lib/db";

function safeParse<T>(json: string | null, fallback: T): T {
  if (!json) return fallback;
  try {
    return JSON.parse(json) as T;
  } catch {
    return fallback;
  }
}

export async function GET() {
  try {
    const rows = await db.identification.findMany({
      orderBy: { createdAt: "desc" },
      take: 30,
    });
    const items = rows.map((r) => ({
      id: r.id,
      instagramUrl: r.instagramUrl,
      status: r.status,
      movieName: r.movieName,
      originalName: r.originalName,
      year: r.year,
      confidence: r.confidence,
      posterUrl: r.posterUrl,
      source: r.source,
      genres: safeParse<string[]>(r.genres, []),
      createdAt: r.createdAt,
    }));
    return NextResponse.json({ items });
  } catch {
    return NextResponse.json({ items: [] });
  }
}

export async function DELETE() {
  try {
    await db.identification.deleteMany({});
    return NextResponse.json({ ok: true });
  } catch {
    return NextResponse.json({ ok: false }, { status: 500 });
  }
}
