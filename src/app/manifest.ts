import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "فیلم‌یاب — شناسایی فیلم از کلیپ اینستاگرام",
    short_name: "فیلم‌یاب",
    description:
      "لینک تیزر یا کلیپ فیلم را از اینستاگرام وارد کنید؛ فیلم‌یاب اسم دقیق فیلم را پیدا می‌کند.",
    start_url: "/",
    display: "standalone",
    orientation: "portrait",
    background_color: "#131009",
    theme_color: "#131009",
    lang: "fa",
    dir: "rtl",
    icons: [
      {
        src: "/icons/icon-192.png",
        sizes: "192x192",
        type: "image/png",
        purpose: "any",
      },
      {
        src: "/icons/icon-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "any",
      },
      {
        src: "/icons/icon-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "maskable",
      },
    ],
  };
}
