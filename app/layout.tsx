import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { ClerkProvider } from "@clerk/nextjs";
import { BottomNav } from "@/components/bottom-nav";
import { DbProvider } from "@/components/db-provider";
import { AuthHeader } from "@/components/auth-header";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "GymLog",
  description: "App personal de gimnasio: rutinas, pesos y entrenos",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="es"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-screen pb-16 flex flex-col">
        <ClerkProvider>
          <AuthHeader />
          <DbProvider>
            <main className="mx-auto max-w-md p-4">{children}</main>
          </DbProvider>
          <BottomNav />
        </ClerkProvider>
      </body>
    </html>
  );
}
