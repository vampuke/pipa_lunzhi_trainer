import { ApiError, errorResponse } from './http';
import { route } from './routes';
import type { Env } from './types';

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const requestId = crypto.randomUUID();
    let response: Response;
    try {
      response = await route(request, env);
    } catch (error: unknown) {
      if (error instanceof ApiError) {
        response = errorResponse(error);
      } else {
        // Do not log bodies, URLs, headers, exception messages or SQL bindings:
        // any of those can contain credentials. Correlate only by server UUID.
        console.error(JSON.stringify({ event: 'request_failed', requestId }));
        response = errorResponse(new ApiError(500, 'internal_error', 'An internal error occurred.'));
      }
    }
    response.headers.set('X-Request-Id', requestId);
    response.headers.set('Cache-Control', 'no-store');
    return response;
  },
} satisfies ExportedHandler<Env>;
