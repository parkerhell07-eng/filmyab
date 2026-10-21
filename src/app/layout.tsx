import type { Metadata, Viewport } from "next";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";

export const metadata: Metadata = {
  title: "فیلم‌یاب | از کلیپ اینستاگرام تا اسم فیلم",
  description:
    "لینک تیزر یا کلیپ فیلم را از اینستاگرام وارد کنید؛ فیلم‌یاب با تحلیل هوشمند صحنه و جستجوی وب، اسم دقیق فیلم را پیدا می‌کند.",
  keywords: ["شناسایی فیلم", "اسم فیلم", "کلیپ اینستاگرام", "فیلم‌یاب", "movie finder"],
  applicationName: "فیلم‌یاب",
  manifest: "/manifest.webmanifest",
  icons: {
    icon: "/icons/icon-192.png",
    apple: "/icons/icon-192.png",
  },
  appleWebApp: {
    capable: true,
    title: "فیلم‌یاب",
    statusBarStyle: "black-translucent",
  },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  themeColor: "#131009",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="fa" dir="rtl" className="dark">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        {/* eslint-disable-next-line @next/next/no-page-custom-font -- required for Persian font in App Router */}
        <link
          href="https://fonts.googleapis.com/css2?family=Vazirmatn:wght@300;400;500;600;700;800&display=swap"
          rel="stylesheet"
        />
      </head>
      <body className="antialiased bg-background text-foreground min-h-screen">
        {children}
        <Toaster />
      </body>
    </html>
  );
}
