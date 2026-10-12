import { deepseek } from '@ai-sdk/deepseek';
import { generateObject, jsonSchema, NoObjectGeneratedError, streamText } from 'ai';
import type { JSONSchema7 } from '@ai-sdk/provider';

/** Modelo DeepSeek rápido/barato (V3 chat). Cambiar aquí para usar otro. */
export const MODELO_COACH = 'deepseek-chat';

/** Provider DeepSeek listo para streamText({ model: modeloCoach(), ... }). */
export function modeloCoach() {
  return deepseek(MODELO_COACH);
}

/** true si la API key de DeepSeek está configurada (solo servidor). */
export function deepseekConfigured(): boolean {
  return Boolean(process.env.DEEPSEEK_API_KEY);
}

/** Turno de conversación que se entrega al modelo (texto plano, sin partes ni adjuntos). */
export interface CoachModelMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface CoachStreamInput {
  system: string;
  messages: CoachModelMessage[];
  maxOutputTokens: number;
  /** Aborta la llamada al modelo (timeout del servidor o desconexión del cliente). */
  abortSignal: AbortSignal;
}

/**
 * Proveedor de streaming del Coach v1, inyectable en tests (nunca llaman a DeepSeek). Devuelve los
 * fragmentos de texto en orden; lanza si el proveedor falla o si `abortSignal` se activa.
 */
export interface CoachStreamProvider {
  stream(input: CoachStreamInput): AsyncIterable<string>;
}

/** Proveedor real: DeepSeek vía AI SDK `streamText`, sin reintentos ocultos (el cliente reintenta). */
export const deepseekCoachProvider: CoachStreamProvider = {
  async *stream({ system, messages, maxOutputTokens, abortSignal }) {
    const result = streamText({
      model: modeloCoach(),
      system,
      messages,
      maxOutputTokens,
      abortSignal,
      maxRetries: 0,
      // onError vacío: el por defecto del AI SDK vuelca el error completo a consola (puede incluir
      // la petición). El error llega igualmente como parte 'error' de fullStream y se relanza.
      onError: () => {},
    });
    for await (const part of result.fullStream) {
      if (part.type === 'text-delta') {
        if (part.text) yield part.text;
      } else if (part.type === 'error') {
        throw part.error instanceof Error ? part.error : new Error('Coach provider error');
      } else if (part.type === 'abort') {
        throw abortSignal.reason instanceof Error ? abortSignal.reason : new Error('Coach provider aborted');
      }
    }
  },
};

export interface AiObjectInput {
  system: string;
  prompt: string;
  /** JSON schema of the structured output (e.g. MESO_SCHEMA). */
  schema: object;
  maxOutputTokens: number;
  abortSignal: AbortSignal;
}

/** The model answered, but not with a parseable object of the requested shape. */
export class AiOutputError extends Error {
  constructor() {
    super('The model did not return a valid object');
    this.name = 'AiOutputError';
  }
}

/**
 * Structured-output provider (mesocycles, intelligent reports), injectable in tests (they never call
 * DeepSeek). Resolves the raw object, which the caller validates strictly; throws [AiOutputError] when the
 * model output is not a parseable object, anything else when the provider fails or `abortSignal` fires.
 */
export interface AiObjectProvider {
  generate(input: AiObjectInput): Promise<unknown>;
}

/** Real provider: DeepSeek via AI SDK `generateObject`, no hidden retries (the client retries). */
export const deepseekObjectProvider: AiObjectProvider = {
  async generate({ system, prompt, schema, maxOutputTokens, abortSignal }) {
    try {
      const { object } = await generateObject({
        model: modeloCoach(),
        schema: jsonSchema(schema as JSONSchema7),
        system,
        prompt,
        maxOutputTokens,
        abortSignal,
        maxRetries: 0,
      });
      return object;
    } catch (error) {
      if (NoObjectGeneratedError.isInstance(error)) throw new AiOutputError();
      throw error;
    }
  },
};
