import os

bind = '0.0.0.0:' + str(int(os.environ.get('PORT', '8080')))
# Receipt cache and concurrency gate belong to one process for this single-user pilot.
workers = 1
worker_class = 'gthread'
threads = 4
timeout = 180
graceful_timeout = 180
keepalive = 5
limit_request_line = 2048
limit_request_fields = 40
limit_request_field_size = 4096
accesslog = None
loglevel = 'critical'
capture_output = False
