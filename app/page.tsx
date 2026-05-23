import Link from 'next/link';

export default function EntrenarPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Entrenar</h1>
      <p className="text-muted-foreground">El registro de entrenos llega en la Fase 2.</p>
      <Link href="/ejercicios" className="text-primary underline">
        Ver catálogo de ejercicios
      </Link>
    </div>
  );
}
