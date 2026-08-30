"""Tiny expect-style driver for a QEMU serial console exposed as a unix socket (-serial unix:PATH,server=on,wait=off)."""
import re
import socket
import sys
import time


class Console:
    def __init__(self, path, log):
        self.buf = ""
        self.log = open(log, "ab")
        for _ in range(60):
            try:
                self.s = socket.socket(socket.AF_UNIX)
                self.s.connect(path)
                break
            except OSError:
                time.sleep(1)
        else:
            raise SystemExit("serial socket never appeared: " + path)
        self.s.settimeout(0.5)

    def _read(self):
        try:
            d = self.s.recv(65536)
        except socket.timeout:
            return False
        if not d:
            raise SystemExit("serial closed (QEMU exited?)")
        self.log.write(d)
        self.log.flush()
        self.buf += d.decode("utf-8", "replace")
        self.buf = re.sub(r"\x1b\[[0-9;?]*[A-Za-z]", "", self.buf)  # drop ANSI sequences (ash asks for the cursor position after the prompt)
        return True

    def expect(self, pattern, timeout=120):
        """Waits until the regex matches the accumulated output; returns the match and drops everything before it."""
        rx = re.compile(pattern, re.S)
        end = time.time() + timeout
        while time.time() < end:
            m = rx.search(self.buf)
            if m:
                self.buf = self.buf[m.end():]
                return m
            self._read()
        tail = self.buf[-800:]
        raise SystemExit(f"timeout waiting for {pattern!r}; last output:\n{tail}")

    def send(self, text):
        self.s.sendall(text.encode())

    def sendline(self, line=""):
        self.send(line + "\n")

    def run(self, cmd, prompt=r"[#$] $", timeout=600):
        """Runs a shell command and waits for the prompt again; returns the output it produced.

        expect() consumes everything up to and including the match, so the output has to be taken
        from the match itself — self.buf afterwards is whatever arrived *after* the prompt, i.e.
        almost always nothing. Returning that instead is a silent-failure machine: every check of
        the form `if "OK" not in out` passes vacuously.
        """
        self.buf = ""
        self.sendline(cmd)
        m = self.expect(prompt, timeout)
        return m.string[:m.start()]

    def drain(self, seconds=1.0):
        end = time.time() + seconds
        while time.time() < end:
            self._read()
        out, self.buf = self.buf, ""
        return out


def wait_prompt(c, timeout=300):
    return c.expect(r"[#$] $", timeout)


if __name__ == "__main__":
    # quick manual use: python3 sconsole.py <sock> 'command'
    c = Console(sys.argv[1], "/dev/null")
    print(c.run(sys.argv[2]))
