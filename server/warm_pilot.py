"""Run on an external scheduler every five minutes; never on the sleeping service itself."""
from datetime import datetime, timezone
import json
import os
from urllib.parse import urlsplit
from urllib.request import Request, build_opener
from zoneinfo import ZoneInfo
from bridge import NoRedirect


def in_window(now):
    local = now.astimezone(ZoneInfo('America/Toronto'))
    minute = local.hour * 60 + local.minute
    return 655 <= minute <= 720 or 1375 <= minute <= 1439 or minute == 0


def health_url(base):
    url = urlsplit(base)
    if url.scheme != 'https' or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ('', '/'):
        raise ValueError('Provide only the HTTPS base address, with no token or query.')
    return base.rstrip('/') + '/healthz'


def warm(base, now=None, opener=None):
    if not in_window(now or datetime.now(timezone.utc)):
        return False
    request = Request(health_url(base), headers={'Accept': 'application/json'})
    with (opener or build_opener(NoRedirect())).open(request, timeout=90) as response:
        value = json.loads(response.read(1024))
        if response.status != 200 or value != {'status': 'ok'}:
            raise ValueError('The pilot health check did not succeed.')
    return True


if __name__ == '__main__':
    try:
        print('Pilot health check completed.' if warm(os.environ['PILOT_BACKEND_URL']) else 'Outside pilot window; no request sent.')
    except Exception:
        # Do not echo URLs, credentials or provider data to scheduler logs.
        raise SystemExit('Pilot health check failed; check deployment and scheduler configuration.')
