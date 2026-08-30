#!/usr/bin/env python3
"""vmctui - an interactive console for the VirtualMinecraft bus. Run this INSIDE the VM.

    vmctui                 # full-screen console (curses)
    vmctui --plain         # line-by-line REPL instead (any terminal, even a raw VGA console)
    vmctui --no-events     # do not subscribe to events
    vmctui --port HOST:PORT|/dev/virtio-ports/vmc.bus

Type a component call and press Enter:

    world.getTime                     inventory@north.list
    redstone.setOutput east 15        chat.say "hello from the vm"
    speaker.playNote harp 12          drive@1,0,2.getMedia

Arguments are parsed as JSON when they look like it (numbers, true/false/null, [..], {..})
and as plain strings otherwise; quote anything containing spaces. Commands starting with
a slash drive the console itself - /help, /list, /events, /clear, /reconnect, /quit.

No dependencies beyond the Python standard library; one file on purpose, so it can be
copied into any guest. Fetch it from the host with the dev http server:

    python3 -m http.server 8000        # on the host, in tools/
    curl -o /usr/local/bin/vmctui http://10.0.2.2:8000/bustui.py && chmod +x /usr/local/bin/vmctui
"""
import json
import os
import shlex
import shutil
import socket
import sys
import threading
import time

DEFAULT_PORT = os.environ.get("VMC_BUS", "/dev/virtio-ports/vmc.bus")
SLASH_COMMANDS = ("/help", "/list", "/info", "/events", "/clear", "/reconnect", "/quit")


# ---------------------------------------------------------------------------------------
# Transport: line-delimited JSON-RPC 2.0 over the virtio-serial port (or TCP on Windows)
# ---------------------------------------------------------------------------------------


class BusError(Exception):
    def __init__(self, message, code=None):
        super().__init__(message)
        self.code = code


class Bus:
    """Owns the port. A reader thread turns lines into replies (matched by id) and events."""

    def __init__(self, target=DEFAULT_PORT, on_event=None, on_state=None):
        self.target = target
        self.on_event = on_event or (lambda ev: None)
        self.on_state = on_state or (lambda connected, note: None)
        self.fd = None
        self.sock = None
        self._buf = b""
        self._next_id = 1
        self._lock = threading.Lock()
        self._replies = {}
        self._waiters = threading.Condition(self._lock)
        self._stop = False
        self._probing = False
        self.connected = False
        self.subscriptions = []
        self.open()
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()

    # -- connection --------------------------------------------------------------------

    def open(self):
        if ":" in self.target and not os.path.exists(self.target):
            host, _, port = self.target.rpartition(":")
            self.sock = socket.create_connection((host or "127.0.0.1", int(port)), timeout=10)
            self.sock.settimeout(None)
            self.fd = self.sock.fileno()
        else:
            # O_RDWR on the virtio port: one process at a time, no tty layer involved.
            self.fd = os.open(self.target, os.O_RDWR)
        self.connected = True

    def close(self):
        self._stop = True
        try:
            if self.sock is not None:
                self.sock.close()
            elif self.fd is not None:
                os.close(self.fd)
        except OSError:
            pass

    def _write(self, data):
        if self.sock is not None:
            self.sock.sendall(data)
        else:
            os.write(self.fd, data)

    # -- reading -----------------------------------------------------------------------

    def _read_loop(self):
        while not self._stop:
            try:
                chunk = os.read(self.fd, 65536) if self.sock is None else self.sock.recv(65536)
            except OSError as e:
                if self._stop:
                    return
                self._set_state(False, "read failed: %s" % e)
                time.sleep(0.5)
                continue
            if not chunk:
                # The mod closed its end (VM suspended, world unloaded, server restarting).
                # The port itself stays usable, so wait for the host to come back.
                if self.connected:
                    self._set_state(False, "host disconnected - waiting")
                time.sleep(0.5)
                continue
            if not self.connected:
                self._set_state(True, "host back")
                # Host-side subscriptions do not survive a reconnect; restore ours.
                if self.subscriptions:
                    threading.Thread(target=self._resubscribe, daemon=True).start()
            self._buf += chunk
            while b"\n" in self._buf:
                line, self._buf = self._buf.split(b"\n", 1)
                if line.strip():
                    self._dispatch(line)

    def _resubscribe(self):
        try:
            self.call("subscribe", list(self.subscriptions), timeout=10)
        except Exception:
            pass

    def _set_state(self, connected, note):
        self.connected = connected
        try:
            self.on_state(connected, note)
        except Exception:
            pass
        if not connected:
            self._start_probe()

    def _start_probe(self):
        """Nothing arrives while the mod is away, so we cannot notice it coming back by reading alone.
        Park a ping in the port: the write completes and the reply arrives the moment the host reattaches,
        which wakes the reader thread, flips the state and restores our subscriptions."""
        if self._probing or self._stop:
            return
        self._probing = True

        def probe():
            try:
                self.call("ping", timeout=86400)
            except Exception:
                pass
            finally:
                self._probing = False

        threading.Thread(target=probe, daemon=True).start()

    def _dispatch(self, line):
        try:
            msg = json.loads(line)
        except ValueError:
            return
        if msg.get("method") == "event":
            self.on_event(msg.get("params") or {})
            return
        mid = msg.get("id")
        if mid is None:
            return
        with self._waiters:
            self._replies[mid] = msg
            self._waiters.notify_all()

    # -- requests ----------------------------------------------------------------------

    def call(self, method, params=None, timeout=None):
        if timeout is None:
            # A live bus answers within a tick; when the mod is away, say so rather than hanging.
            timeout = 15 if self.connected else 3
        with self._lock:
            mid = self._next_id
            self._next_id += 1
        msg = {"jsonrpc": "2.0", "method": method, "id": mid}
        if params is not None:
            msg["params"] = params
        self._write((json.dumps(msg) + "\n").encode())
        deadline = time.time() + timeout
        with self._waiters:
            while mid not in self._replies:
                left = deadline - time.time()
                if left <= 0:
                    if not self.connected:
                        raise BusError("the bus is disconnected - the computer is suspended, "
                                       "stopped, or its chunk is unloaded (this reconnects by itself)")
                    raise BusError("no reply after %ds (is the computer's chunk loaded?)" % timeout)
                self._waiters.wait(left)
            reply = self._replies.pop(mid)
        if "error" in reply:
            err = reply["error"]
            raise BusError(err.get("message", "error"), err.get("code"))
        return reply.get("result")

    def subscribe(self, names):
        result = self.call("subscribe", names)
        self.subscriptions = names if isinstance(names, list) else [names]
        return result


# ---------------------------------------------------------------------------------------
# Shared command handling (both front ends use this)
# ---------------------------------------------------------------------------------------


def parse_arg(token):
    try:
        return json.loads(token)
    except ValueError:
        return token


def format_result(value, width=80):
    if value is None:
        return "null"
    compact = json.dumps(value, ensure_ascii=False)
    if len(compact) <= max(20, width - 4) and "\n" not in compact:
        return compact
    return json.dumps(value, ensure_ascii=False, indent=2)


def component_label(c):
    return "%s@%s" % (c.get("type", "?"), c.get("location", "?"))


def completions(components, prefix):
    """Component names, then <name>.<method> once a dot is typed."""
    out = []
    if "." in prefix:
        target, _, partial = prefix.rpartition(".")
        for c in components:
            if target in (component_label(c), c.get("type"), c.get("address")):
                for m in c.get("methods", {}):
                    if m.startswith(partial):
                        out.append("%s.%s" % (target, m))
        return sorted(set(out))
    seen = set()
    for c in components:
        for name in (component_label(c), c.get("type", "")):
            if name and name.startswith(prefix) and name not in seen:
                seen.add(name)
                out.append(name)
    for s in SLASH_COMMANDS:
        if s.startswith(prefix):
            out.append(s)
    return sorted(out)


def help_lines(components, what=None):
    lines = []
    if what:
        hits = [c for c in components
                if what in (component_label(c), c.get("type"), c.get("address"))]
        if not hits:
            return ["no component '%s' - /list shows what this computer has" % what]
        for c in hits:
            lines.append("%s  (%s)" % (component_label(c), c.get("address", "")))
            for name, doc in c.get("methods", {}).items():
                lines.append("    %s" % doc)
        return lines
    lines.append("Call a component:  <type>[@<location>].<method> [args...]")
    lines.append("  world.getTime            redstone.setOutput east 15")
    lines.append("  inventory@north.list     chat.say \"hello from the vm\"")
    lines.append("Console commands:")
    lines.append("  /list             components on this computer (Tab completes them)")
    lines.append("  /help <type>      every method of that component, with its signature")
    lines.append("  /info             VM name, id, protocol, whether the block is loaded")
    lines.append("  /events on|off    stream world events (redstone, chat, hot-plug, ...)")
    lines.append("  /clear  /reconnect  /quit          (Ctrl-C also quits)")
    lines.append("Up/Down history, Tab completion, PgUp/PgDn scroll back.")
    return lines


def run_command(bus, text, components, width=80):
    """Returns (style, lines, new_components_or_None). style in: result, error, info."""
    text = text.strip()
    if not text:
        return None, [], None
    try:
        parts = shlex.split(text)
    except ValueError as e:
        return "error", ["cannot parse: %s" % e], None
    if not parts:
        return None, [], None
    head, args = parts[0], parts[1:]

    if head in ("/quit", "/exit", "/q"):
        raise SystemExit(0)
    if head in ("/help", "/?", "help"):
        return "info", help_lines(components, args[0] if args else None), None
    if head in ("/clear", "clear"):
        return "clear", [], None
    if head in ("/list", "list") and not args:
        result = bus.call("list")
        lines = ["%-28s %s" % (component_label(c), " ".join(sorted(c.get("methods", {}))))
                 for c in result]
        return "info", lines or ["(no components)"], result
    if head in ("/info", "info"):
        return "result", [format_result(bus.call("info"), width)], None
    if head == "/events":
        if args and args[0] in ("off", "no", "0"):
            bus.call("unsubscribe", ["*"])
            bus.subscriptions = []
            return "info", ["events off"], None
        bus.subscribe(["*"])
        return "info", ["events on (everything this computer emits)"], None
    if head == "/reconnect":
        bus.subscribe(bus.subscriptions or ["*"])
        return "info", ["re-subscribed"], None
    if head.startswith("/"):
        return "error", ["unknown console command '%s' - try /help" % head], None

    # A component call: <target>.<method> [args...], which the mod accepts directly.
    if "." not in head:
        return "info", help_lines(components, head), None
    params = [parse_arg(a) for a in args]
    result = bus.call(head, params or None)
    return "result", format_result(result, width).splitlines() or ["null"], None


def event_line(params):
    name = params.get("name", "event")
    rest = {k: v for k, v in params.items() if k not in ("name", "address")}
    return "%s %s" % (name, json.dumps(rest, ensure_ascii=False))


# ---------------------------------------------------------------------------------------
# Full-screen front end
# ---------------------------------------------------------------------------------------


def run_curses(bus, want_events):
    import curses

    def app(stdscr):
        curses.curs_set(1)
        stdscr.timeout(100)
        colours = {}
        if curses.has_colors():
            curses.start_color()
            try:
                curses.use_default_colors()
                bg = -1
            except curses.error:
                bg = curses.COLOR_BLACK
            for i, (name, fg) in enumerate((("prompt", curses.COLOR_CYAN),
                                            ("result", curses.COLOR_GREEN),
                                            ("error", curses.COLOR_RED),
                                            ("event", curses.COLOR_YELLOW),
                                            ("info", curses.COLOR_WHITE),
                                            ("bar", curses.COLOR_BLUE)), start=1):
                curses.init_pair(i, fg, bg)
                colours[name] = curses.color_pair(i)
        attr = lambda style: colours.get(style, 0)

        transcript = []          # (style, text)
        pending = []             # events from the reader thread
        pending_lock = threading.Lock()
        state = {"connected": True, "note": "", "scroll": 0}

        def on_event(params):
            with pending_lock:
                pending.append(event_line(params))

        def on_state(connected, note):
            state["connected"] = connected
            state["note"] = note
            with pending_lock:
                pending.append(None if connected else "")  # force a redraw

        bus.on_event = on_event
        bus.on_state = on_state

        def add(style, text):
            for line in (text.splitlines() or [""]):
                transcript.append((style, line))
            del transcript[:-2000]

        components = []
        try:
            info = bus.call("info", timeout=5)
            components = bus.call("list", timeout=5)
            add("info", "connected to '%s' (protocol %s, block loaded: %s)"
                % (info.get("name"), info.get("protocol"), info.get("loaded")))
            add("info", "components: " + ", ".join(component_label(c) for c in components))
        except Exception as e:
            add("error", "%s" % e)
        if want_events:
            try:
                bus.subscribe(["*"])
                add("info", "events on - try a lever next to the computer, or say something in chat")
            except Exception as e:
                add("error", "subscribe failed: %s" % e)
        add("info", "/help for commands, Tab completes, Ctrl-C quits")

        buf, cursor, history, hpos = "", 0, [], None

        def wrapped(width):
            out = []
            for style, line in transcript:
                if not line:
                    out.append((style, ""))
                while line:
                    out.append((style, line[:width]))
                    line = line[width:]
            return out

        while True:
            with pending_lock:
                while pending:
                    item = pending.pop(0)
                    if item:
                        add("event", "* " + item)
            h, w = stdscr.getmaxyx()
            body_h = max(1, h - 2)
            lines = wrapped(w)
            max_scroll = max(0, len(lines) - body_h)
            state["scroll"] = min(state["scroll"], max_scroll)
            top = max_scroll - state["scroll"]

            stdscr.erase()
            head = " vmc bus %s%s" % ("connected" if state["connected"] else "DISCONNECTED",
                                      (" - " + state["note"]) if state["note"] else "")
            stdscr.addnstr(0, 0, head.ljust(w), w,
                           attr("bar") | (curses.A_REVERSE if not state["connected"] else curses.A_BOLD))
            for row, (style, line) in enumerate(lines[top:top + body_h]):
                try:
                    stdscr.addnstr(1 + row, 0, line, w, attr(style))
                except curses.error:
                    pass
            prompt = "vmc> "
            try:
                stdscr.addnstr(h - 1, 0, prompt, w, attr("prompt") | curses.A_BOLD)
                stdscr.addnstr(h - 1, len(prompt), buf, max(0, w - len(prompt) - 1))
                stdscr.move(h - 1, min(w - 1, len(prompt) + cursor))
            except curses.error:
                pass
            stdscr.refresh()

            try:
                ch = stdscr.getch()
            except KeyboardInterrupt:
                return
            if ch == -1:
                continue
            if ch in (curses.KEY_RESIZE,):
                continue
            if ch in (4,):                                     # Ctrl-D
                return
            if ch in (10, 13, curses.KEY_ENTER):
                line = buf
                buf, cursor, hpos = "", 0, None
                if not line.strip():
                    continue
                history.append(line)
                add("prompt", "> " + line)
                state["scroll"] = 0
                try:
                    style, out, newcomp = run_command(bus, line, components, w)
                except SystemExit:
                    return
                except BusError as e:
                    style, out, newcomp = "error", ["! %s%s" % (e, " (code %s)" % e.code if e.code else "")], None
                except Exception as e:                          # noqa: BLE001 - a test tool must not die
                    style, out, newcomp = "error", ["! %s: %s" % (type(e).__name__, e)], None
                if style == "clear":
                    del transcript[:]
                    continue
                if newcomp is not None:
                    components = newcomp
                for o in out:
                    add(style or "info", o)
            elif ch in (curses.KEY_BACKSPACE, 127, 8):
                if cursor:
                    buf = buf[:cursor - 1] + buf[cursor:]
                    cursor -= 1
            elif ch == curses.KEY_DC:
                buf = buf[:cursor] + buf[cursor + 1:]
            elif ch == curses.KEY_LEFT:
                cursor = max(0, cursor - 1)
            elif ch == curses.KEY_RIGHT:
                cursor = min(len(buf), cursor + 1)
            elif ch in (curses.KEY_HOME, 1):
                cursor = 0
            elif ch in (curses.KEY_END, 5):
                cursor = len(buf)
            elif ch == 21:                                      # Ctrl-U
                buf, cursor = "", 0
            elif ch == curses.KEY_UP:
                if history:
                    hpos = len(history) - 1 if hpos is None else max(0, hpos - 1)
                    buf = history[hpos]
                    cursor = len(buf)
            elif ch == curses.KEY_DOWN:
                if hpos is not None:
                    hpos += 1
                    if hpos >= len(history):
                        hpos, buf = None, ""
                    else:
                        buf = history[hpos]
                    cursor = len(buf)
            elif ch == curses.KEY_PPAGE:
                state["scroll"] = min(max_scroll, state["scroll"] + body_h)
            elif ch == curses.KEY_NPAGE:
                state["scroll"] = max(0, state["scroll"] - body_h)
            elif ch == 9:                                       # Tab
                word = buf[:cursor].split(" ")[-1]
                matches = completions(components, word)
                common = os.path.commonprefix(matches) if matches else ""
                if len(common) > len(word):                     # extend as far as it is unambiguous
                    tail = common[len(word):]
                    buf = buf[:cursor] + tail + buf[cursor:]
                    cursor += len(tail)
                elif len(matches) > 1:
                    add("info", "  ".join(matches[:20]))
            elif 32 <= ch < 256:
                buf = buf[:cursor] + chr(ch) + buf[cursor:]
                cursor += 1

    import curses as _curses
    _curses.wrapper(app)


# ---------------------------------------------------------------------------------------
# Plain line-by-line front end (raw consoles, or --plain)
# ---------------------------------------------------------------------------------------


def run_plain(bus, want_events):
    out_lock = threading.Lock()

    def emit(text):
        with out_lock:
            sys.stdout.write("\r" + text + "\n")
            sys.stdout.flush()

    bus.on_event = lambda params: emit("* " + event_line(params))
    bus.on_state = lambda connected, note: emit("! " + note)

    components = []
    try:
        info = bus.call("info", timeout=5)
        components = bus.call("list", timeout=5)
        emit("connected to '%s' (protocol %s, loaded: %s)"
             % (info.get("name"), info.get("protocol"), info.get("loaded")))
        emit("components: " + ", ".join(component_label(c) for c in components))
    except Exception as e:
        emit("! %s" % e)
    if want_events:
        try:
            bus.subscribe(["*"])
            emit("events on")
        except Exception as e:
            emit("! subscribe failed: %s" % e)
    emit("/help for commands, /quit to leave")

    try:
        import readline

        def complete(text, state):
            matches = completions(components, text)
            return matches[state] if state < len(matches) else None

        readline.set_completer(complete)
        readline.set_completer_delims(" \t")
        readline.parse_and_bind("tab: complete")
    except Exception:
        pass

    while True:
        try:
            line = input("vmc> ")
        except (EOFError, KeyboardInterrupt):
            print()
            return
        try:
            width = shutil.get_terminal_size((80, 24)).columns
            style, out, newcomp = run_command(bus, line, components, width)
        except SystemExit:
            return
        except BusError as e:
            style, out, newcomp = "error", ["! %s%s" % (e, " (code %s)" % e.code if e.code else "")], None
        except Exception as e:                                  # noqa: BLE001
            style, out, newcomp = "error", ["! %s: %s" % (type(e).__name__, e)], None
        if style == "clear":
            os.system("clear")
            continue
        if newcomp is not None:
            components = newcomp
        for o in out:
            emit(o)


def main(argv):
    if "-h" in argv or "--help" in argv:
        print(__doc__)
        return 0
    plain = "--plain" in argv
    want_events = "--no-events" not in argv
    target = DEFAULT_PORT
    if "--port" in argv:
        target = argv[argv.index("--port") + 1]

    try:
        bus = Bus(target)
    except OSError as e:
        sys.stderr.write("cannot open %s: %s\n" % (target, e))
        if getattr(e, "errno", None) == 16:  # EBUSY
            sys.stderr.write("another program already has the bus open - only one at a time.\n")
        else:
            sys.stderr.write("is this running inside a VirtualMinecraft VM? "
                             "(set VMC_BUS or pass --port)\n")
        return 1

    try:
        if plain:
            run_plain(bus, want_events)
        else:
            try:
                run_curses(bus, want_events)
            except ImportError:
                sys.stderr.write("no curses here, falling back to the plain console\n")
                run_plain(bus, want_events)
    finally:
        bus.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
