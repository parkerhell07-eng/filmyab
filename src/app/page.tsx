"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { useToast } from "@/hooks/use-toast";
import { AnimatePresence, motion } from "framer-motion";
import { formatDistanceToNow } from "date-fns";
import { faIR as faLocale } from "date-fns/locale";
import {
  AlertTriangle,
  BrainCircuit,
  CheckCircle2,
  Clapperboard,
  ExternalLink,
  Film,
  Globe,
  History,
  ImageUp,
  Link2,
  Loader2,
  MinusCircle,
  ClipboardPaste,
  RotateCcw,
  ScanSearch,
  Search,
  Sparkles,
  Smartphone,
  Trash2,
  XCircle,
} from "lucide-react";

/* ------------------------------------------------------------------ */
/* Types                                                               */
/* ------------------------------------------------------------------ */

type StepKey = "fetch" | "analyze" | "search" | "match" | "poster";
type StepState = "pending" | "active" | "done" | "skip" | "error";

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

interface HistoryItem {
  id: string;
  instagramUrl: string | null;
  status: string;
  movieName: string | null;
  originalName: string | null;
  year: string | null;
  confidence: number | null;
  posterUrl: string | null;
  source: string | null;
  genres: string[];
  createdAt: string;
}

type Phase = "idle" | "running" | "result" | "error";

const STEP_META: Record<
  StepKey,
  { label: string; hint: string; icon: React.ElementType }
> = {
  fetch: {
    label: "خواندن پست اینستاگرام",
    hint: "دریافت توضیحات، هشتگ‌ها و ویدیو",
    icon: Link2,
  },
  analyze: {
    label: "تحلیل صحنه با هوش مصنوعی",
    hint: "بررسی فریم‌ها، بازیگران و متن داخل تصویر",
    icon: ScanSearch,
  },
  search: {
    label: "جستجوی هوشمند در وب",
    hint: "غبارگیری از صدها منبع سینمایی",
    icon: Globe,
  },
  match: {
    label: "تطبیق و تشخیص فیلم",
    hint: "ترکیب همه سرنخ‌ها توسط هوش مصنوعی",
    icon: BrainCircuit,
  },
  poster: {
    label: "یافتن پوستر فیلم",
    hint: "گرفتن تصویر رسمی فیلم",
    icon: Film,
  },
};

const STEP_ORDER: StepKey[] = ["fetch", "analyze", "search", "match", "poster"];

/* ------------------------------------------------------------------ */
/* Small building blocks                                               */
/* ------------------------------------------------------------------ */

function PosterImage({
  src,
  alt,
  className,
}: {
  src: string | null;
  alt: string;
  className?: string;
}) {
  const [failedFor, setFailedFor] = useState<string | null>(null);
  if (!src || failedFor === src) {
    return (
      <div
        aria-label={alt}
        className={`flex items-center justify-center bg-gradient-to-br from-zinc-800 to-zinc-900 text-amber-500/60 ${className ?? ""}`}
      >
        <Film className="h-8 w-8" aria-hidden />
      </div>
    );
  }
  return (
    <img
      src={src}
      alt={alt}
      className={`object-cover ${className ?? ""}`}
      onError={() => setFailedFor(src)}
      loading="lazy"
    />
  );
}

function confidenceColor(conf: number): string {
  if (conf >= 0.75) return "bg-emerald-500";
  if (conf >= 0.45) return "bg-amber-500";
  return "bg-rose-500";
}

function ConfidenceBar({ value }: { value: number }) {
  const pct = Math.round(value * 100);
  return (
    <div>
      <div className="mb-1.5 flex items-center justify-between text-xs">
        <span className="text-muted-foreground">میزان اطمینان</span>
        <span className="font-bold">{pct}٪</span>
      </div>
      <div
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        className="h-2 w-full overflow-hidden rounded-full bg-muted"
      >
        <motion.div
          initial={{ width: 0 }}
          animate={{ width: `${pct}%` }}
          transition={{ duration: 0.9, ease: "easeOut" }}
          className={`h-full rounded-full ${confidenceColor(value)}`}
        />
      </div>
    </div>
  );
}

function StepRow({
  step,
  state,
  detail,
}: {
  step: StepKey;
  state: StepState;
  detail?: string;
}) {
  const meta = STEP_META[step];
  const Icon = meta.icon;
  return (
    <li className="flex items-start gap-3">
      <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center">
        {state === "pending" && (
          <div className="h-6 w-6 rounded-full border-2 border-muted" aria-hidden />
        )}
        {state === "active" && (
          <Loader2 className="h-6 w-6 animate-spin text-amber-400" aria-hidden />
        )}
        {state === "done" && (
          <CheckCircle2 className="h-6 w-6 text-emerald-500" aria-hidden />
        )}
        {state === "skip" && (
          <MinusCircle className="h-6 w-6 text-muted-foreground/60" aria-hidden />
        )}
        {state === "error" && <XCircle className="h-6 w-6 text-rose-500" aria-hidden />}
      </div>
      <div className="min-w-0 flex-1">
        <p
          className={`text-sm font-semibold ${
            state === "pending"
              ? "text-muted-foreground/60"
              : state === "active"
                ? "text-amber-300"
                : state === "error"
                  ? "text-rose-400"
                  : "text-foreground"
          }`}
        >
          {meta.label}
          {state === "skip" && " (رد شد)"}
        </p>
        <p className="mt-0.5 truncate text-xs text-muted-foreground">
          {detail && state !== "pending" ? detail : meta.hint}
        </p>
      </div>
      <Icon
        className={`mt-1 h-4 w-4 shrink-0 ${
          state === "pending" ? "text-muted-foreground/40" : "text-amber-500/70"
        }`}
        aria-hidden
      />
    </li>
  );
}

function initialSteps(): Record<StepKey, { state: StepState; detail?: string }> {
  return {
    fetch: { state: "pending" },
    analyze: { state: "pending" },
    search: { state: "pending" },
    match: { state: "pending" },
    poster: { state: "pending" },
  };
}

/* ------------------------------------------------------------------ */
/* Main page                                                           */
/* ------------------------------------------------------------------ */

export default function Home() {
  const { toast } = useToast();

  const [url, setUrl] = useState("");
  const [note, setNote] = useState("");
  const [phase, setPhase] = useState<Phase>("idle");
  const [steps, setSteps] = useState(initialSteps);
  const [result, setResult] = useState<PipelineResult | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [suggestScreenshot, setSuggestScreenshot] = useState(false);
  const [history, setHistory] = useState<HistoryItem[]>([]);
  const [sourcesOpen, setSourcesOpen] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const stepsRef = useRef<HTMLDivElement>(null);
  const runningRef = useRef(false);

  const loadHistory = useCallback(async () => {
    try {
      const res = await fetch("/api/history");
      const data = (await res.json()) as { items: HistoryItem[] };
      setHistory(data.items ?? []);
    } catch {
      /* history is non-critical */
    }
  }, []);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  const resetUI = useCallback(() => {
    setSteps(initialSteps());
    setResult(null);
    setErrorMsg(null);
    setSuggestScreenshot(false);
  }, []);

  const handleEvent = useCallback((evt: Record<string, unknown>) => {
    if (typeof evt.step === "string" && STEP_ORDER.includes(evt.step as StepKey)) {
      const key = evt.step as StepKey;
      const status = evt.status as StepState;
      const data = evt.data as Record<string, unknown> | undefined;
      let detail: string | undefined;
      if (key === "fetch") {
        if (status === "done" && data) {
          const bits: string[] = [];
          if (data.caption) bits.push("کپشن پیدا شد");
          if (data.hasVideo) bits.push("ویدیو پیدا شد");
          if (data.uploader) bits.push(`@${String(data.uploader)}`);
          detail = bits.length > 0 ? bits.join(" • ") : "متادیتای پست دریافت شد";
        } else if (status === "error" && typeof evt.detail === "string") {
          detail = evt.detail;
        } else if (status === "skip") {
          detail = "حالت اسکرین‌شات";
        }
      }
      if (key === "search" && status === "done" && data) {
        detail = `${data.count} نتیجه مرتبط پیدا شد`;
      }
      if (key === "match" && status === "done" && data) {
        detail =
          data.found && data.title
            ? `کاندید اصلی: ${String(data.title)}`
            : "بررسی گزینه‌های احتمالی";
      }
      if (key === "analyze" && status === "done" && data?.sceneDescription) {
        detail = "صحنه تحلیل شد";
      }
      setSteps((prev) => ({ ...prev, [key]: { state: status, detail } }));
    } else if (evt.type === "result") {
      setResult(evt.result as PipelineResult);
      setPhase("result");
    } else if (evt.type === "error") {
      setErrorMsg(
        (evt.message as string) ?? "خطای نامشخصی رخ داد. لطفاً دوباره تلاش کنید."
      );
      setSuggestScreenshot(evt.hint === "screenshot");
      setPhase("error");
    }
  }, []);

  const runIdentify = useCallback(
    async (payload: { url?: string; imageBase64?: string }) => {
      if (runningRef.current) return;
      runningRef.current = true;
      resetUI();
      setPhase("running");
      setTimeout(
        () => stepsRef.current?.scrollIntoView({ behavior: "smooth", block: "start" }),
        120
      );
      try {
        const res = await fetch("/api/identify", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ ...payload, note: note || undefined }),
        });
        if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        let finished = false;

        while (!finished) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const parts = buffer.split("\n\n");
          buffer = parts.pop() ?? "";
          for (const part of parts) {
            const dataLine = part
              .split("\n")
              .find((l) => l.startsWith("data:"));
            if (!dataLine) continue;
            try {
              const evt = JSON.parse(dataLine.slice(5).trim());
              handleEvent(evt);
              if (evt.type === "result" || evt.type === "error") finished = true;
            } catch {
              /* ignore malformed chunk */
            }
          }
        }
      } catch {
        setErrorMsg("ارتباط با سرور برقرار نشد. اتصال اینترنت را بررسی کنید.");
        setPhase("error");
      } finally {
        runningRef.current = false;
        loadHistory();
      }
    },
    [handleEvent, loadHistory, note, resetUI]
  );

  const onSubmit = useCallback(
    (e?: React.FormEvent) => {
      e?.preventDefault();
      const trimmed = url.trim();
      if (!trimmed) {
        toast({
          title: "لینک خالی است",
          description: "لینک ریلز یا پست اینستاگرام را وارد کنید.",
          variant: "destructive",
        });
        return;
      }
      if (!/instagram\.com|instagr\.am/i.test(trimmed)) {
        toast({
          title: "لینک نامعتبر",
          description: "این آدرس یک لینک اینستاگرام به نظر نمی‌رسد.",
          variant: "destructive",
        });
        return;
      }
      runIdentify({ url: trimmed });
    },
    [runIdentify, toast, url]
  );

  const pasteFromClipboard = useCallback(async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text) {
        setUrl(text.trim());
        toast({ title: "لینک چسبانده شد ✓" });
      }
    } catch {
      toast({
        title: "دسترسی به کلیپ‌بورد ممکن نشد",
        description: "لطفاً روی کادر لینک نگه دارید و Paste را بزنید.",
        variant: "destructive",
      });
    }
  }, [toast]);

  const onFileSelected = useCallback(
    (file: File | null | undefined) => {
      if (!file) return;
      if (!file.type.startsWith("image/")) {
        toast({
          title: "فایل تصویر نیست",
          description: "یک اسکرین‌شات یا تصویر انتخاب کنید.",
          variant: "destructive",
        });
        return;
      }
      if (file.size > 8 * 1024 * 1024) {
        toast({
          title: "حجم تصویر زیاد است",
          description: "حداکثر حجم مجاز ۸ مگابایت است.",
          variant: "destructive",
        });
        return;
      }
      const reader = new FileReader();
      reader.onload = () => {
        const base64 = String(reader.result).split(",")[1];
        if (base64) runIdentify({ imageBase64: base64 });
      };
      reader.readAsDataURL(file);
    },
    [runIdentify, toast]
  );

  const clearHistory = useCallback(async () => {
    if (!window.confirm("کل تاریخچه جستجو پاک شود؟")) return;
    try {
      await fetch("/api/history", { method: "DELETE" });
      setHistory([]);
      toast({ title: "تاریخچه پاک شد" });
    } catch {
      toast({ title: "پاک کردن تاریخچه ناموفق بود", variant: "destructive" });
    }
  }, [toast]);

  const resetAll = useCallback(() => {
    setPhase("idle");
    resetUI();
    setUrl("");
    setNote("");
  }, [resetUI]);

  const isRunning = phase === "running";
  const canSubmit = !isRunning;

  /* ---------------------------------------------------------------- */

  return (
    <div className="flex min-h-screen flex-col">
      {/* ---------- Header ---------- */}
      <header className="sticky top-0 z-20 border-b border-border/60 glass-card">
        <div className="mx-auto flex w-full max-w-lg items-center gap-3 px-4 py-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-amber-400 to-orange-600 shadow-lg shadow-amber-500/20">
            <Clapperboard className="h-5 w-5 text-zinc-950" aria-hidden />
          </div>
          <div className="flex-1">
            <h1 className="text-lg font-extrabold leading-tight text-gold-gradient">
              فیلم‌یاب
            </h1>
            <p className="text-[11px] leading-none text-muted-foreground">
              از کلیپ اینستاگرام تا اسم فیلم
            </p>
          </div>
          <Badge variant="outline" className="border-amber-500/40 text-amber-400">
            <Sparkles className="ml-1 h-3 w-3" aria-hidden />
            هوش مصنوعی
          </Badge>
        </div>
      </header>

      <main className="mx-auto w-full max-w-lg flex-1 px-4 pb-10 pt-5">
        {/* ---------- Intro ---------- */}
        <motion.section
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4 }}
          className="mb-5 text-center"
        >
          <h2 className="text-base font-bold leading-7">
            اسم آن فیلم را فراموش کرده‌ای؟
          </h2>
          <p className="mt-1 text-sm leading-6 text-muted-foreground">
            لینک تیزر یا کلیپی از فیلم را که در اینستاگرام دیدی بچسبان؛ ما صحنه را
            تحلیل می‌کنیم و اسم دقیق فیلم را پیدا می‌کنیم.
          </p>
        </motion.section>

        {/* ---------- Input card ---------- */}
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.45, delay: 0.05 }}
        >
          <Card className="glass-card border-border/80 shadow-xl shadow-black/30">
            <CardContent className="p-4">
              <form onSubmit={onSubmit} aria-label="فرم شناسایی فیلم">
                <label htmlFor="ig-url" className="mb-1.5 block text-xs font-semibold text-muted-foreground">
                  لینک پست یا ریلز اینستاگرام
                </label>
                <div className="flex gap-2">
                  <div className="relative flex-1">
                    <Link2
                      className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
                      aria-hidden
                    />
                    <Input
                      id="ig-url"
                      dir="ltr"
                      inputMode="url"
                      autoComplete="off"
                      spellCheck={false}
                      placeholder="https://www.instagram.com/reel/..."
                      className="pr-9 text-left text-sm"
                      value={url}
                      onChange={(e) => setUrl(e.target.value)}
                      disabled={isRunning}
                    />
                  </div>
                  <Button
                    type="button"
                    variant="secondary"
                    size="icon"
                    className="h-10 w-10 shrink-0"
                    onClick={pasteFromClipboard}
                    disabled={isRunning}
                    aria-label="چسباندن لینک از کلیپ‌بورد"
                    title="چسباندن از کلیپ‌بورد"
                  >
                    <ClipboardPaste className="h-4 w-4" aria-hidden />
                  </Button>
                </div>

                <label htmlFor="ig-note" className="mb-1.5 mt-3 block text-xs font-semibold text-muted-foreground">
                  راهنما (اختیاری) — هر چیزی که درباره فیلم می‌دانی
                </label>
                <Input
                  id="ig-note"
                  placeholder="مثلاً: فیلم ایرانی بود، یک صحنه تعقیب و گریز داشت..."
                  className="text-sm"
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                  maxLength={300}
                  disabled={isRunning}
                />

                <Button
                  type="submit"
                  disabled={!canSubmit}
                  className="mt-4 h-12 w-full bg-gradient-to-l from-amber-500 via-amber-400 to-orange-500 text-base font-extrabold text-zinc-950 shadow-lg shadow-amber-500/25 transition-transform active:scale-[0.98] hover:brightness-110 disabled:opacity-60"
                >
                  {isRunning ? (
                    <>
                      <Loader2 className="ml-2 h-5 w-5 animate-spin" aria-hidden />
                      در حال جستجو...
                    </>
                  ) : (
                    <>
                      <Search className="ml-2 h-5 w-5" aria-hidden />
                      شناسایی فیلم
                    </>
                  )}
                </Button>
              </form>

              <div className="my-3 flex items-center gap-3 text-[11px] text-muted-foreground">
                <Separator className="flex-1" />
                یا
                <Separator className="flex-1" />
              </div>

              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                className="hidden"
                onChange={(e) => {
                  onFileSelected(e.target.files?.[0]);
                  e.currentTarget.value = "";
                }}
              />
              <Button
                type="button"
                variant="outline"
                className="h-11 w-full border-dashed border-amber-500/40 text-sm font-semibold text-amber-300 hover:border-amber-500/70 hover:bg-amber-500/10"
                disabled={isRunning}
                onClick={() => fileInputRef.current?.click()}
              >
                <ImageUp className="ml-2 h-4 w-4" aria-hidden />
                جستجو با اسکرین‌شات از کلیپ
              </Button>
              <p className="mt-2 text-center text-[11px] leading-5 text-muted-foreground">
                اگر اینستاگرام لینک را مسدود کرد، اسکرین‌شات همان کلیپ را آپلود کن —
                نتیجه یکسان است.
              </p>
            </CardContent>
          </Card>
        </motion.div>

        {/* ---------- Pipeline steps (running) ---------- */}
        <AnimatePresence>
          {isRunning && (
            <motion.div
              ref={stepsRef}
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.35 }}
              className="mt-4"
              aria-live="polite"
              role="status"
            >
              <Card className="glass-card border-amber-500/20">
                <CardContent className="p-4">
                  <ul className="space-y-4">
                    {STEP_ORDER.map((key) => (
                      <StepRow
                        key={key}
                        step={key}
                        state={steps[key].state}
                        detail={steps[key].detail}
                      />
                    ))}
                  </ul>
                  <div className="mt-4 flex items-center justify-center gap-2 text-xs text-muted-foreground">
                    <Sparkles className="h-3.5 w-3.5 animate-pulse text-amber-400" aria-hidden />
                    <span>الگوریتم فیلم‌یاب در حال کار است؛ چند لحظه صبر کنید…</span>
                  </div>
                </CardContent>
              </Card>
            </motion.div>
          )}
        </AnimatePresence>

        {/* ---------- Error panel ---------- */}
        <AnimatePresence>
          {phase === "error" && (
            <motion.div
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.35 }}
              className="mt-4"
              role="alert"
            >
              <Card className="border-rose-500/30 bg-rose-500/5">
                <CardContent className="p-4">
                  <div className="flex items-start gap-3">
                    <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-rose-400" aria-hidden />
                    <div className="flex-1">
                      <p className="text-sm font-bold text-rose-300">شناسایی ناموفق بود</p>
                      <p className="mt-1 text-sm leading-6 text-muted-foreground">{errorMsg}</p>
                      <div className="mt-3 flex flex-wrap gap-2">
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() => (url.trim() ? runIdentify({ url }) : fileInputRef.current?.click())}
                        >
                          <RotateCcw className="ml-1.5 h-3.5 w-3.5" aria-hidden />
                          تلاش دوباره
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          className="border-amber-500/40 text-amber-300"
                          onClick={() => fileInputRef.current?.click()}
                        >
                          <ImageUp className="ml-1.5 h-3.5 w-3.5" aria-hidden />
                          جستجو با اسکرین‌شات
                        </Button>
                      </div>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </motion.div>
          )}
        </AnimatePresence>

        {/* ---------- Result panel ---------- */}
        <AnimatePresence>
          {phase === "result" && result && (
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.45 }}
              className="mt-4"
            >
              <Card className="glass-card border-amber-500/25 shadow-2xl shadow-amber-950/20">
                <CardContent className="p-4">
                  {result.status === "failed" ? (
                    /* ----- not found ----- */
                    <div className="text-center">
                      <div className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-amber-500/10">
                        <Film className="h-7 w-7 text-amber-400" aria-hidden />
                      </div>
                      <h3 className="text-base font-extrabold">
                        با اطمینان کافی پیدا نشد
                      </h3>
                      <p className="mx-auto mt-2 max-w-sm text-sm leading-6 text-muted-foreground">
                        {result.reasoning ||
                          "سرنخ‌ها کافی نبودند. اگر گزینه‌های زیر به فیلم نزدیک نیستند، با اسکرین‌شات واضح‌تر دوباره امتحان کن."}
                      </p>
                      {result.candidates.length > 0 && (
                        <div className="mt-4 text-right">
                          <p className="mb-2 text-xs font-bold text-muted-foreground">
                            نزدیک‌ترین حدس‌ها:
                          </p>
                          <div className="flex flex-wrap gap-2">
                            {result.candidates.map((c, i) => (
                              <Badge
                                key={i}
                                variant="secondary"
                                className="bg-secondary/80 px-3 py-1.5"
                                dir="ltr"
                              >
                                {c.name}
                                {c.year ? ` (${c.year})` : ""}
                              </Badge>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  ) : (
                    /* ----- found (success / low confidence) ----- */
                    <>
                      <div className="mb-3 flex items-center justify-between">
                        {result.status === "success" ? (
                          <Badge className="bg-emerald-500/15 text-emerald-400 hover:bg-emerald-500/20">
                            <CheckCircle2 className="ml-1 h-3.5 w-3.5" aria-hidden />
                            فیلم شناسایی شد
                          </Badge>
                        ) : (
                          <Badge className="bg-amber-500/15 text-amber-400 hover:bg-amber-500/20">
                            <AlertTriangle className="ml-1 h-3.5 w-3.5" aria-hidden />
                            حدس قوی
                          </Badge>
                        )}
                        <span className="text-xs text-muted-foreground">
                          {result.sourceMode === "screenshot" ? "بر اساس اسکرین‌شات" : "بر اساس لینک پست"}
                        </span>
                      </div>

                      <div className="flex gap-4">
                        <PosterImage
                          src={result.posterUrl}
                          alt={`پوستر ${result.movieName ?? result.originalName ?? "فیلم"}`}
                          className="h-36 w-24 shrink-0 rounded-xl border border-border/70 shadow-lg"
                        />
                        <div className="min-w-0 flex-1">
                          <h3 className="text-xl font-extrabold leading-8 text-gold-gradient">
                            {result.movieName ?? result.originalName}
                          </h3>
                          {result.originalName && result.movieName !== result.originalName && (
                            <p dir="ltr" className="truncate text-sm font-semibold text-foreground/85">
                              {result.originalName}
                              {result.year ? ` (${result.year})` : ""}
                            </p>
                          )}
                          {result.director && (
                            <p className="mt-1 text-xs text-muted-foreground">
                              کارگردان: <span className="font-semibold text-foreground/80">{result.director}</span>
                            </p>
                          )}
                          {result.genres.length > 0 && (
                            <div className="mt-2 flex flex-wrap gap-1.5">
                              {result.genres.map((g, i) => (
                                <Badge key={i} variant="outline" className="text-[11px] text-muted-foreground">
                                  {g}
                                </Badge>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>

                      <div className="mt-4">
                        <ConfidenceBar value={result.confidence} />
                      </div>

                      {result.reasoning && (
                        <div className="mt-4 rounded-xl border border-border/60 bg-background/40 p-3">
                          <p className="mb-1 flex items-center gap-1.5 text-xs font-bold text-amber-400">
                            <BrainCircuit className="h-3.5 w-3.5" aria-hidden />
                            استدلال هوش مصنوعی
                          </p>
                          <p className="text-[13px] leading-7 text-muted-foreground">{result.reasoning}</p>
                          {result.sceneDescription && (
                            <p className="mt-2 border-t border-border/50 pt-2 text-[11px] leading-6 text-muted-foreground/80">
                              آنچه در صحنه دیدیم: {result.sceneDescription}
                            </p>
                          )}
                        </div>
                      )}

                      {result.candidates.length > 0 && (
                        <div className="mt-4">
                          <p className="mb-2 text-xs font-bold text-muted-foreground">
                            گزینه‌های احتمالی دیگر:
                          </p>
                          <div className="flex flex-wrap gap-2">
                            {result.candidates.map((c, i) => (
                              <Badge
                                key={i}
                                variant="secondary"
                                className="bg-secondary/80 px-3 py-1.5"
                                dir="ltr"
                              >
                                {c.name}
                                {c.year ? ` (${c.year})` : ""}
                              </Badge>
                            ))}
                          </div>
                        </div>
                      )}

                      {result.sources.length > 0 && (
                        <Collapsible open={sourcesOpen} onOpenChange={setSourcesOpen} className="mt-4">
                          <CollapsibleTrigger className="flex w-full items-center justify-between rounded-lg border border-border/60 px-3 py-2 text-xs font-bold text-muted-foreground transition-colors hover:bg-secondary/40">
                            <span className="flex items-center gap-1.5">
                              <Globe className="h-3.5 w-3.5" aria-hidden />
                              منابع جستجو ({result.sources.length})
                            </span>
                            <span>{sourcesOpen ? "−" : "+"}</span>
                          </CollapsibleTrigger>
                          <CollapsibleContent>
                            <ul className="mt-2 max-h-44 space-y-1 overflow-y-auto nice-scroll pr-1">
                              {result.sources.map((s, i) => (
                                <li key={i}>
                                  <a
                                    href={s.url}
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    dir="ltr"
                                    className="flex items-center gap-1.5 rounded-md px-2 py-1.5 text-left text-[11px] text-muted-foreground transition-colors hover:bg-secondary/40 hover:text-foreground"
                                  >
                                    <ExternalLink className="h-3 w-3 shrink-0" aria-hidden />
                                    <span className="truncate">{s.name || s.url}</span>
                                  </a>
                                </li>
                              ))}
                            </ul>
                          </CollapsibleContent>
                        </Collapsible>
                      )}
                    </>
                  )}

                  <div className="mt-4 flex flex-wrap gap-2">
                    <Button size="sm" variant="secondary" onClick={resetAll}>
                      <RotateCcw className="ml-1.5 h-3.5 w-3.5" aria-hidden />
                      شناسایی جدید
                    </Button>
                    {result.instagramUrl && (
                      <Button size="sm" variant="ghost" asChild>
                        <a href={result.instagramUrl} target="_blank" rel="noopener noreferrer">
                          <ExternalLink className="ml-1.5 h-3.5 w-3.5" aria-hidden />
                          پست اصلی
                        </a>
                      </Button>
                    )}
                  </div>
                </CardContent>
              </Card>
            </motion.div>
          )}
        </AnimatePresence>

        {/* ---------- History ---------- */}
        <motion.section
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.45, delay: 0.1 }}
          className="mt-6"
          aria-label="تاریخچه جستجوها"
        >
          <div className="mb-2 flex items-center justify-between px-1">
            <h3 className="flex items-center gap-1.5 text-sm font-bold text-muted-foreground">
              <History className="h-4 w-4" aria-hidden />
              تاریخچه جستجوها
            </h3>
            {history.length > 0 && (
              <Button
                size="sm"
                variant="ghost"
                className="h-7 px-2 text-xs text-muted-foreground hover:text-rose-400"
                onClick={clearHistory}
              >
                <Trash2 className="ml-1 h-3.5 w-3.5" aria-hidden />
                پاک کردن
              </Button>
            )}
          </div>

          {history.length === 0 ? (
            <Card className="border-dashed">
              <CardContent className="p-5 text-center text-xs text-muted-foreground">
                هنوز جستجویی انجام نداده‌ای. اولین لینک را امتحان کن!
              </CardContent>
            </Card>
          ) : (
            <div className="max-h-96 space-y-2 overflow-y-auto nice-scroll pb-1 pl-1">
              {history.map((item) => {
                const conf = item.confidence ?? 0;
                return (
                  <Card key={item.id} className="border-border/60 py-0">
                    <CardContent className="flex items-center gap-3 p-3">
                      <PosterImage
                        src={item.posterUrl}
                        alt={item.movieName ?? "نتیجه جستجو"}
                        className="h-16 w-11 shrink-0 rounded-lg border border-border/60"
                      />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-bold">
                          {item.movieName ?? item.originalName ?? "شناسایی نشد"}
                        </p>
                        <p dir="ltr" className="truncate text-[11px] text-muted-foreground">
                          {[item.originalName, item.year].filter(Boolean).join(" • ")}
                        </p>
                        <p className="mt-0.5 text-[10px] text-muted-foreground/70">
                          {formatDistanceToNow(new Date(item.createdAt), {
                            addSuffix: true,
                            locale: faLocale,
                          })}
                          {item.source === "screenshot" ? " • اسکرین‌شات" : ""}
                        </p>
                      </div>
                      <div className="flex shrink-0 flex-col items-end gap-1">
                        {item.status === "failed" ? (
                          <Badge variant="outline" className="text-[10px] text-muted-foreground">
                            ناموفق
                          </Badge>
                        ) : (
                          <Badge
                            variant="outline"
                            className={`text-[10px] ${
                              conf >= 0.75
                                ? "border-emerald-500/40 text-emerald-400"
                                : conf >= 0.45
                                  ? "border-amber-500/40 text-amber-400"
                                  : "border-rose-500/40 text-rose-400"
                            }`}
                          >
                            {Math.round(conf * 100)}٪
                          </Badge>
                        )}
                        {item.instagramUrl && (
                          <a
                            href={item.instagramUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-muted-foreground transition-colors hover:text-amber-400"
                            aria-label="مشاهده پست اصلی"
                          >
                            <ExternalLink className="h-3.5 w-3.5" aria-hidden />
                          </a>
                        )}
                      </div>
                    </CardContent>
                  </Card>
                );
              })}
            </div>
          )}
        </motion.section>
      </main>

      {/* ---------- Footer (sticky bottom) ---------- */}
      <footer className="mt-auto border-t border-border/60 glass-card pb-[env(safe-area-inset-bottom)]">
        <div className="mx-auto w-full max-w-lg px-4 py-4 text-center">
          <a
            href="https://github.com/parkerhell07-eng/filmyab/releases/latest/download/FilmYab-v2.0-debug.apk"
            target="_blank"
            rel="noopener noreferrer"
            className="mb-3 flex items-center justify-center gap-2 rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-2.5 text-xs font-semibold text-amber-400 transition-colors hover:bg-amber-500/20"
          >
            <Smartphone className="size-4" aria-hidden />
            دانلود اپ اندروید (APK) — نسخه ۲٫۰ با موتور هوشمند بدون سرور
          </a>
          <p className="text-[11px] leading-5 text-muted-foreground">
            فیلم‌یاب نسخه ۲٫۰ — موتور هوشمند روی دستگاه: متادیتای پست، فریم‌ها، OCR، تحلیل هوشمند و ویکی‌پدیا؛ بدون هیچ تنظیماتی
          </p>
          <p className="mt-1 text-[10px] leading-5 text-muted-foreground/70">
            نسخه وب: منوی کروم ← <span dir="ltr">Add to Home Screen</span> ·{" "}
            <a
              href="https://github.com/parkerhell07-eng/filmyab"
              target="_blank"
              rel="noopener noreferrer"
              className="underline underline-offset-2 hover:text-foreground"
            >
              سورس‌کد در گیت‌هاب
            </a>
          </p>
        </div>
      </footer>
    </div>
  );
}
