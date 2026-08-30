#!/usr/bin/env python3
import socket, sys
s = socket.create_connection(('127.0.0.1', 25597), timeout=30)
f = s.makefile('rw', encoding='utf-8', newline='\n')
for cmd in sys.argv[1:]:
    f.write(cmd + '\n'); f.flush()
    print(f'> {cmd}\n  {f.readline().rstrip()}')
