export function calcularDimensiones(w: number, h: number, max: number): { width: number; height: number } {
  const lado = Math.max(w, h);
  if (lado <= max) return { width: w, height: h };
  const escala = max / lado;
  return { width: Math.round(w * escala), height: Math.round(h * escala) };
}

/** Redimensiona a máx 1024px el lado mayor y exporta JPEG ~0.8. Devuelve un Blob. */
export async function compressImage(file: File, max = 1024, calidad = 0.8): Promise<Blob> {
  const bitmap = await createImageBitmap(file);
  const { width, height } = calcularDimensiones(bitmap.width, bitmap.height, max);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('No se pudo crear el contexto de canvas');
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close?.();
  return await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('No se pudo comprimir la imagen'))),
      'image/jpeg',
      calidad,
    );
  });
}
