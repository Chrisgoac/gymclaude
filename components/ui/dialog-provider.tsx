'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

interface ConfirmOpts {
  titulo: string;
  mensaje?: string;
  confirmar?: string;
  cancelar?: string;
  destructivo?: boolean;
}

interface PromptOpts {
  titulo: string;
  mensaje?: string;
  valorInicial?: string;
  placeholder?: string;
  confirmar?: string;
  cancelar?: string;
}

interface DialogsApi {
  confirm: (opts: ConfirmOpts) => Promise<boolean>;
  prompt: (opts: PromptOpts) => Promise<string | null>;
}

type Estado =
  | { tipo: 'confirm'; opts: ConfirmOpts }
  | { tipo: 'prompt'; opts: PromptOpts }
  | null;

const Ctx = createContext<DialogsApi | null>(null);

export function useDialogs(): DialogsApi {
  const api = useContext(Ctx);
  if (!api) throw new Error('useDialogs debe usarse dentro de <DialogProvider>');
  return api;
}

export function DialogProvider({ children }: { children: React.ReactNode }) {
  const [estado, setEstado] = useState<Estado>(null);
  const [valor, setValor] = useState('');
  const resolver = useRef<((v: boolean | string | null) => void) | null>(null);

  const confirm = useCallback(
    (opts: ConfirmOpts) =>
      new Promise<boolean>((res) => {
        resolver.current = res as (v: boolean | string | null) => void;
        setEstado({ tipo: 'confirm', opts });
      }),
    [],
  );

  const prompt = useCallback(
    (opts: PromptOpts) =>
      new Promise<string | null>((res) => {
        resolver.current = res as (v: boolean | string | null) => void;
        setValor(opts.valorInicial ?? '');
        setEstado({ tipo: 'prompt', opts });
      }),
    [],
  );

  const cerrar = useCallback((resultado: boolean | string | null) => {
    resolver.current?.(resultado);
    resolver.current = null;
    setEstado(null);
  }, []);

  // Cancela el valor por defecto (false / null) al cerrar.
  const cancelar = useCallback(
    () => cerrar(estado?.tipo === 'prompt' ? null : false),
    [cerrar, estado],
  );
  const aceptar = useCallback(
    () => cerrar(estado?.tipo === 'prompt' ? valor : true),
    [cerrar, estado, valor],
  );

  useEffect(() => {
    if (!estado) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') cancelar();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [estado, cancelar]);

  return (
    <Ctx.Provider value={{ confirm, prompt }}>
      {children}
      {estado && (
        <div
          className="fixed inset-0 z-50 grid place-items-center bg-foreground/50 p-4"
          onClick={cancelar}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-label={estado.opts.titulo}
            className="brutal-box brutal-shadow w-full max-w-sm space-y-4 bg-card p-5"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="font-[family-name:var(--font-display)] text-2xl leading-none">
              {estado.opts.titulo}
            </h2>
            {estado.opts.mensaje && (
              <p className="text-sm text-muted-foreground">{estado.opts.mensaje}</p>
            )}
            {estado.tipo === 'prompt' && (
              <Input
                autoFocus
                value={valor}
                placeholder={estado.opts.placeholder}
                onChange={(e) => setValor(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') aceptar();
                }}
              />
            )}
            <div className="flex justify-end gap-2">
              <Button variant="outline" size="sm" onClick={cancelar}>
                {estado.opts.cancelar ?? 'Cancelar'}
              </Button>
              <Button
                size="sm"
                variant={estado.tipo === 'confirm' && estado.opts.destructivo ? 'destructive' : 'default'}
                onClick={aceptar}
              >
                {estado.opts.confirmar ?? 'Aceptar'}
              </Button>
            </div>
          </div>
        </div>
      )}
    </Ctx.Provider>
  );
}
