import { requirePrincipal, type ApiPrincipal } from './auth';
import { errorResponse } from './response';

const REQUEST_ID_RE = /^[A-Za-z0-9._-]{8,128}$/;

function requestIdFor(request: Request): string {
  const incoming = request.headers.get('x-request-id');
  return incoming && REQUEST_ID_RE.test(incoming) ? incoming : crypto.randomUUID();
}

export function withApiRoute(
  handler: (request: Request, principal: ApiPrincipal) => Promise<Response>,
): (request: Request) => Promise<Response> {
  return async (request) => {
    const requestId = requestIdFor(request);
    try {
      const principal = await requirePrincipal();
      const response = await handler(request, principal);
      const headers = new Headers(response.headers);
      headers.set('x-request-id', requestId);
      if (!headers.has('content-type')) headers.set('content-type', 'application/json');
      return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
    } catch (error) {
      return errorResponse(error, requestId);
    }
  };
}
