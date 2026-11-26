"""WSGI transport for the single-user pilot. TLS terminates at the hosting provider."""
from collections import OrderedDict
from http import HTTPStatus
import hmac
import json
import threading
import time
import bridge


class Application:
    def __init__(self):
        self.active = threading.BoundedSemaphore(1)
        self.last_request = 0.0
        self.receipts = OrderedDict()

    def __call__(self, environ, start_response):
        try:
            status, value = self.dispatch(environ)
        except bridge.BridgeError as error:
            status, value = error.status, {'error': error.code}
        except (ValueError, TypeError):
            status, value = 400, {'error': 'invalid_request'}
        except Exception:
            # Never send or log raw exceptions, provider replies or image data.
            status, value = 500, {'error': 'reader_failed'}
        body = json.dumps(value).encode()
        start_response(str(status)+' '+HTTPStatus(status).phrase, [
            ('Content-Type', 'application/json'), ('Content-Length', str(len(body))),
            ('Cache-Control', 'no-store'), ('X-Content-Type-Options', 'nosniff')])
        return [body]

    def dispatch(self, env):
        method, path = env.get('REQUEST_METHOD'), env.get('PATH_INFO')
        # Cheap, content-free liveness endpoint for the host and external warm-up scheduler.
        # It never calls OpenAI or returns configuration, tokens or model identifiers.
        if method == 'GET' and path == '/healthz':
            return 200, {'status': 'ok'}
        settings = bridge.config()
        expected = settings.get('BRIDGE_TOKEN', '')
        supplied = env.get('HTTP_AUTHORIZATION', '')
        if len(expected) < 32 or not hmac.compare_digest(supplied.encode(), ('Bearer '+expected).encode()):
            raise bridge.BridgeError(401, 'connection_not_authorized')
        ready = bool(settings.get('OPENAI_API_KEY') and settings.get('OPENAI_MODEL') and settings.get('RETENTION_POLICY') == 'standard')
        if method == 'GET' and path == '/v1/config':
            return 200, {'ready': ready, 'provider': 'OpenAI API', 'retention_notice': bridge.RETENTION,
                         'model': settings.get('OPENAI_MODEL', ''), 'retention_policy': settings.get('RETENTION_POLICY', 'unconfigured')}
        if method != 'POST' or path != '/v1/extract':
            raise bridge.BridgeError(404, 'not_found')
        if not ready:
            raise bridge.BridgeError(503, 'cloud_setup_required')
        if env.get('CONTENT_TYPE', '').split(';')[0] != 'application/json' or env.get('HTTP_TRANSFER_ENCODING'):
            raise bridge.BridgeError(400, 'invalid_request')
        length = int(env.get('CONTENT_LENGTH') or 0)
        if length <= 0 or length > bridge.MAX_BODY:
            raise bridge.BridgeError(413, 'request_too_large')
        if not self.active.acquire(blocking=False):
            raise bridge.BridgeError(429, 'reader_busy')
        try:
            if self.last_request and time.monotonic()-self.last_request < 3:
                raise bridge.BridgeError(429, 'reader_busy')
            raw = env['wsgi.input'].read(length)
            if len(raw) != length:
                raise bridge.BridgeError(400, 'invalid_request')
            return 200, bridge.extract(raw, settings, self, env.get('HTTP_X_SCAN_REQUEST_ID'))
        finally:
            self.active.release()


application = Application()
