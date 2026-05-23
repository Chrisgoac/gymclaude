'use client';

import { SignInButton, Show, UserButton } from '@clerk/nextjs';
import { SyncProvider } from '@/components/sync-provider';

export function AuthHeader() {
  return (
    <header className="mx-auto flex max-w-md items-center justify-between p-4 pb-0">
      <span className="text-sm font-bold">GymLog</span>
      <div className="flex items-center gap-3">
        <SyncProvider />
        <Show when="signed-out">
          <SignInButton mode="modal">
            <button className="text-sm text-primary">Iniciar sesión</button>
          </SignInButton>
        </Show>
        <Show when="signed-in">
          <UserButton />
        </Show>
      </div>
    </header>
  );
}
