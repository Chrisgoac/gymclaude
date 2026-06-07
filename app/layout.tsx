import type { Metadata } from "next";
import { Anton, Archivo, Space_Mono } from "next/font/google";
import "./globals.css";
import { ClerkProvider } from "@clerk/nextjs";
import { BottomNav } from "@/components/bottom-nav";
import { DbProvider } from "@/components/db-provider";
import { AuthHeader } from "@/components/auth-header";
import { DialogProvider } from "@/components/ui/dialog-provider";

// Display ultra-condensado para titulares y números grandes (peso, reps, 1RM).
const fontDisplay = Anton({
  weight: "400",
  variable: "--font-display",
  subsets: ["latin"],
  display: "swap",
});

// Grotesca neutra y robusta para el cuerpo.
const fontBody = Archivo({
  variable: "--font-body",
  subsets: ["latin"],
  display: "swap",
});

// Monospace para lecturas de "máquina": fechas, etiquetas, estado de sync.
const fontMono = Space_Mono({
  weight: ["400", "700"],
  variable: "--font-mono",
  subsets: ["latin"],
  display: "swap",
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
      className={`${fontDisplay.variable} ${fontBody.variable} ${fontMono.variable} h-full antialiased`}
    >
      <body className="min-h-screen pb-24 flex flex-col">
        <ClerkProvider>
          <DialogProvider>
            <AuthHeader />
            <DbProvider>
              <main className="mx-auto w-full max-w-md p-4">{children}</main>
            </DbProvider>
            <BottomNav />
          </DialogProvider>
        </ClerkProvider>
        {/* Grano de película sutil sobre toda la UI — textura industrial. */}
        <div className="grain" aria-hidden="true" />
      </body>
    </html>
  );
}
