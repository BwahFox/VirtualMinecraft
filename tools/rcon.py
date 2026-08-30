#!/usr/bin/env python3
import socket, struct, sys
def send(sock, req_id, typ, payload):
    data = struct.pack('<ii', req_id, typ) + payload.encode() + b'\x00\x00'
    sock.sendall(struct.pack('<i', len(data)) + data)
def recv(sock):
    raw = sock.recv(4)
    if len(raw) < 4: return None, None
    (length,) = struct.unpack('<i', raw)
    data = b''
    while len(data) < length: data += sock.recv(length - len(data))
    rid, typ = struct.unpack('<ii', data[:8])
    return rid, data[8:-2].decode(errors='replace')
s = socket.create_connection(('127.0.0.1', 25598), timeout=10)
send(s, 1, 3, 'vmctest'); rid, _ = recv(s)
if rid == -1: sys.exit('auth failed')
for cmd in sys.argv[1:]:
    send(s, 2, 2, cmd); rid, out = recv(s)
    print(f'> {cmd}\n{out}')
