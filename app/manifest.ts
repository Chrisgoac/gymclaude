import type { MetadataRoute } from 'next';

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: 'GymLog',
    short_name: 'GymLog',
    description: 'App personal de gimnasio: rutinas, pesos y entrenos',
    start_url: '/',
    display: 'standalone',
    background_color: '#d8d3ca',
    theme_color: '#221f1b',
    icons: [
      { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
      { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
    ],
  };
}
