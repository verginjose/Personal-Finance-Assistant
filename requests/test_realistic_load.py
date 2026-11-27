import argparse
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import threading
import time
import unittest
from run_realistic_test import Scenario,parse_args


class ScenarioTests(unittest.TestCase):
    def test_high_capacity_profiles_are_not_accepted(self):
        for flag,value in [('--users','500'),('--bills-total','10'),('--rps','20')]:
            with self.assertRaises(SystemExit):parse_args(['--out','unused',flag,value])

    def test_first_server_error_stops_without_transport_retries(self):
        requests=[]
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(time.monotonic());self.send_response(503);self.end_headers();self.wfile.write(b'{}')
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            args=argparse.Namespace(rps=1,base_url='http://127.0.0.1:'+str(server.server_port))
            scenario=Scenario(args)
            with self.assertRaises(RuntimeError):scenario.request('read','/anything')
            self.assertTrue(scenario.stop.is_set());self.assertEqual(len(requests),1)
        finally:server.shutdown();server.server_close()

    def test_parser_budget_is_global_and_busy_uploads_are_not_retried(self):
        class Stop:
            def __init__(self):self.waits=[]
            def wait(self,seconds):self.waits.append(seconds);return False
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'receipt.jpg';file.write_bytes(b'\xff\xd8\xff')
            s=Scenario(argparse.Namespace(bills_total=2,receipt=str(file)))
            s.members=[{'token':'fixture','user_id':'fixture'}];s.stop=Stop();calls=[]
            s.request=lambda *a,**kw:(calls.append(kw) or (429,{}))
            s.bills(time.monotonic()+300)
            self.assertEqual(len(calls),2);self.assertEqual(s.stop.waits,[60,60]);self.assertEqual(s.ocr['busy'],2)


if __name__=='__main__':unittest.main()
