import io
import json
import unittest
import threading
from datetime import datetime, timezone
from unittest.mock import patch, MagicMock
from wsgiref.util import setup_testing_defaults
from wsgiref.simple_server import make_server, WSGIRequestHandler
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import bridge
import hosted
import warm_pilot
from test_bridge import JPEG, DRAFT, SETTINGS


class HostedContracts(unittest.TestCase):
    def setUp(self):
        self.app = hosted.Application()
        self.settings = patch.object(bridge, 'config', return_value=SETTINGS.copy()); self.settings.start()
        self.provider = patch.object(bridge, 'call_openai', return_value=DRAFT); self.call = self.provider.start()
        self.addCleanup(self.settings.stop); self.addCleanup(self.provider.stop)

    def request(self, path, body=None, token=SETTINGS['BRIDGE_TOKEN'], **overrides):
        env = {}; setup_testing_defaults(env)
        raw = b'' if body is None else json.dumps(body).encode()
        env.update(REQUEST_METHOD='GET' if body is None else 'POST', PATH_INFO=path,
                   HTTP_AUTHORIZATION='Bearer '+token, CONTENT_TYPE='application/json',
                   CONTENT_LENGTH=str(len(raw)), HTTP_X_SCAN_REQUEST_ID='a'*32)
        env['wsgi.input'] = io.BytesIO(raw); env.update(overrides)
        reply = []
        result = b''.join(self.app(env, lambda status, headers: reply.append((int(status.split()[0]), dict(headers)))))
        return reply[0][0], json.loads(result), reply[0][1]

    def test_health_is_content_free_and_never_calls_provider(self):
        status, body, headers = self.request('/healthz', token='')
        self.assertEqual((200, {'status':'ok'}), (status, body)); self.assertEqual('no-store', headers['Cache-Control']); self.call.assert_not_called()

    def test_pairing_and_setup_require_authentication(self):
        self.assertEqual(401, self.request('/v1/config', token='wrong')[0])
        self.assertEqual(401, self.request('/v1/extract', {'consent':True,'images':[JPEG,JPEG]}, token='wrong')[0]); self.call.assert_not_called()
        self.assertTrue(self.request('/v1/config')[1]['ready']); self.call.assert_not_called()

    def test_one_request_receipt_survives_provider_failure(self):
        payload = {'consent':True,'images':[JPEG,JPEG]}
        self.call.side_effect = bridge.BridgeError(504, 'provider_timeout_or_connection_failed')
        self.assertEqual(504, self.request('/v1/extract', payload)[0]); self.app.last_request = 0
        self.assertEqual(409, self.request('/v1/extract', payload)[0]); self.call.assert_called_once()

    def test_multiple_images_share_one_call(self):
        self.assertEqual((200, DRAFT), self.request('/v1/extract', {'consent':True,'images':[JPEG,JPEG]})[:2]); self.call.assert_called_once_with([JPEG,JPEG],SETTINGS)

    def test_bounds_consent_and_truncated_uploads_block_inference(self):
        for changes in [dict(CONTENT_LENGTH=str(bridge.MAX_BODY+1)),dict(CONTENT_LENGTH='invalid'),dict(CONTENT_LENGTH='0'),dict(CONTENT_TYPE='text/plain'),dict(HTTP_TRANSFER_ENCODING='chunked'),dict(CONTENT_LENGTH='5000')]:
            self.assertIn(self.request('/v1/extract', {'consent':True,'images':[JPEG,JPEG]}, **changes)[0], (400,413))
        self.assertEqual(400,self.request('/v1/extract', {'consent':False,'images':[JPEG,JPEG]})[0]); self.call.assert_not_called()

    def test_concurrency_gate_and_missing_provider_configuration(self):
        self.app.active.acquire()
        try:self.assertEqual(429,self.request('/v1/extract', {'consent':True,'images':[JPEG,JPEG]})[0])
        finally:self.app.active.release()
        with patch.object(bridge,'config',return_value=dict(SETTINGS,OPENAI_API_KEY='')):
            self.assertEqual(503,self.request('/v1/extract', {'consent':True,'images':[JPEG,JPEG]})[0])
        self.call.assert_not_called()

    def test_unexpected_errors_do_not_echo_sensitive_details(self):
        self.call.side_effect=RuntimeError('secret patient text')
        status,body,_=self.request('/v1/extract',{'consent':True,'images':[JPEG,JPEG]})
        self.assertEqual((500,{'error':'reader_failed'}),(status,body))

    def test_real_wsgi_http_health_auth_and_extraction(self):
        class Quiet(WSGIRequestHandler):
            def log_message(self,*args):pass
        server=make_server('127.0.0.1',0,self.app,handler_class=Quiet)
        worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        base='http://127.0.0.1:'+str(server.server_port)
        try:
            with urlopen(base+'/healthz',timeout=2) as response:
                self.assertEqual({'status':'ok'},json.load(response));self.assertEqual('no-store',response.headers['Cache-Control'])
            with self.assertRaises(HTTPError) as caught:urlopen(base+'/v1/config',timeout=2)
            self.assertEqual(401,caught.exception.code);caught.exception.close();self.call.assert_not_called()
            payload=json.dumps({'consent':True,'images':[JPEG,JPEG]}).encode()
            request=Request(base+'/v1/extract',data=payload,headers={'Authorization':'Bearer '+SETTINGS['BRIDGE_TOKEN'],'Content-Type':'application/json','X-Scan-Request-Id':'b'*32})
            with urlopen(request,timeout=2) as response:self.assertEqual(DRAFT,json.load(response))
            self.call.assert_called_once()
        finally:server.shutdown();server.server_close();worker.join()


class WarmupContracts(unittest.TestCase):
    def test_windows_follow_toronto_time_in_winter_and_summer(self):
        for stamp, expected in [('2026-11-26T15:54:00+00:00',False),('2026-11-26T15:55:00+00:00',True),('2026-11-26T17:00:00+00:00',True),('2026-11-26T17:05:00+00:00',False),('2026-11-27T03:55:00+00:00',True),('2026-11-27T05:00:00+00:00',True),('2026-11-27T05:05:00+00:00',False),('2026-07-01T14:55:00+00:00',True),('2026-07-02T02:55:00+00:00',True)]:
            self.assertEqual(expected,warm_pilot.in_window(datetime.fromisoformat(stamp)),stamp)

    def test_outside_window_sends_nothing_and_health_uses_no_secrets(self):
        opener=MagicMock()
        self.assertFalse(warm_pilot.warm('https://pilot.example',datetime(2026,11,26,20,tzinfo=timezone.utc),opener)); opener.open.assert_not_called()
        response=opener.open.return_value.__enter__.return_value;response.status=200;response.read.return_value=b'{"status":"ok"}'
        self.assertTrue(warm_pilot.warm('https://pilot.example',datetime(2026,11,26,16,tzinfo=timezone.utc),opener))
        request=opener.open.call_args[0][0];self.assertEqual('https://pilot.example/healthz',request.full_url);self.assertIsNone(request.data);self.assertFalse(request.has_header('Authorization'))
        for value in ['http://pilot.example','https://pilot.example?token=secret','https://user:secret@pilot.example','https://pilot.example/v1/extract']:
            with self.assertRaises(ValueError):warm_pilot.health_url(value)
