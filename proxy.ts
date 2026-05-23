import { clerkMiddleware } from '@clerk/nextjs/server';

export default clerkMiddleware();

export const config = {
  matcher: ['/((?!_next|sw\\.js|manifest\\.webmanifest|icon-.*\\.png|.*\\.[\\w]+$).*)', '/(api|trpc)(.*)'],
};
