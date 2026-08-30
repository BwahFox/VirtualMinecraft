-- The machine's libraries over vmc.* (ROADMAP §7h §1f): bus, state, fs, gfx, os. Returns them as a table; the
-- kernel installs them as globals. Everything here is plain Lua a custom OS could load from /rom too.
local json = ...
local v = vmc
local L = {}

---------------------------------------------------------------------------------------------------- bus
local bus = {}
local function sys(payload)
  local r, err = v.call(1, json.encode(payload))
  if r == nil then error(err or "bus error", 0) end
  return json.decode(r)
end
function bus.list() return sys({ op = "list" }) end
--- bus.call(target, method, ...) -> result; raises on error. target = "type", "type@location" or an address.
function bus.call(target, method, ...) return sys({ op = "call", target = target, method = method, args = { ... } }) end
function bus.find(kind) for _, c in ipairs(bus.list()) do if c.type == kind then return c end end end
L.bus = bus

---------------------------------------------------------------------------------------------------- net (computers that can reach each other)
local net = {}
--- net.list() -> { {address, name, location}, ... }: every other computer next to this one, on its cable
--- run, or in range of a wireless modem on both buses (location = a side, an offset, or "wireless")
function net.list() return bus.call("net", "list") end
--- net.send(to, message): `to` is a peer's name or address; message is a string or a table. The peer gets a
--- net_message {from, sender, message} event (a frozen Computer wakes for it).
function net.send(to, message) return bus.call("net", "send", to, message) end
function net.broadcast(message) return bus.call("net", "broadcast", message) end
function net.address() return bus.call("net", "address") end
L.net = net

---------------------------------------------------------------------------------------------------- state (the floor)
local state = {}
function state.save(t) v.call(2, json.encode(t or {})) end
function state.load() local r = v.call(2, "") if r and r ~= "" then local ok, x = pcall(json.decode, r) if ok then return x end end end
L.state = state

---------------------------------------------------------------------------------------------------- fs
local fs = {}
function fs.mounts() return json.decode(v.fs_mounts()) end
function fs.list(path) return json.decode(v.fs_list(path)) end
function fs.stat(path) local r = v.fs_stat(path) return r ~= "null" and json.decode(r) or nil end
function fs.exists(path) return fs.stat(path) ~= nil end
function fs.isdir(path) local st = fs.stat(path) return st ~= nil and st.dir == true end
function fs.read(path) return v.fs_read(path) end
function fs.write(path, data) v.fs_write(path, tostring(data), false) end
function fs.append(path, data) v.fs_write(path, tostring(data), true) end
function fs.mkdir(path) v.fs_mkdir(path) end
function fs.remove(path) v.fs_remove(path) end
function fs.rename(a, b) v.fs_rename(a, b) end
--- What the filesystem accepts as one path segment: letters, digits, dot, underscore and hyphen, up to 64 --
--- and **no spaces** (MachineFiles.NAME). Checking here lets a dialog say the rule instead of a host error
--- turning up as a toast, and it is the reason a copied file is called "notes-copy.txt" and not "notes copy".
fs.NAME_HELP = "letters, digits, . _ - and no spaces"
function fs.validname(name)
  return type(name) == "string" and #name >= 1 and #name <= 64
    and name:match("^[A-Za-z0-9._%-]+$") ~= nil and name ~= "." and name ~= ".."
end

--- fs.copy(src, dst): a file, or a whole directory tree. There is no host-side copy, so this reads and writes,
--- which is also why it works across mounts -- copying a game's directory off a CD onto the internal disk is
--- the reason it exists.
function fs.copy(src, dst)
  local st = fs.stat(src)
  if not st then error(src .. ": no such file", 0) end
  if not st.dir then fs.write(dst, fs.read(src)) return end
  if dst == src or dst:sub(1, #src + 1) == src .. "/" then error("cannot copy a directory into itself", 0) end
  fs.mkdir(dst)
  for _, e in ipairs(fs.list(src)) do fs.copy(fs.join(src, e.name), fs.join(dst, e.name)) end
end
function fs.format(mount) v.fs_format(mount) end
function fs.burn(src, cd) v.fs_burn(src, cd) end
function fs.dirname(path) return path:match("^(.*)/[^/]*$") or "/" end
function fs.basename(path) return path:match("([^/]*)$") end
function fs.join(dir, name) if dir:sub(-1) == "/" then return dir .. name end return dir .. "/" .. name end
--- Loads and runs a Lua file with the ROM's globals: fs.run("/fd0/hello.lua", ...)
function fs.run(path, ...)
  local fn, err = load(fs.read(path), "=" .. path, "t", setmetatable({}, { __index = _G, __newindex = _G }))
  if not fn then error(err, 0) end
  return fn(...)
end
L.fs = fs

---------------------------------------------------------------------------------------------------- gfx
local gfx = {}
function gfx.size() return v.gfx_size() end
function gfx.clear(c) v.gfx_clear(c or 0) end
function gfx.pixel(x, y, c) v.gfx_pixel(x, y, c) end
function gfx.get(x, y) return v.gfx_get(x, y) end
function gfx.line(x0, y0, x1, y1, c) v.gfx_line(x0, y0, x1, y1, c) end
function gfx.rect(x, y, w, h, c) v.gfx_rect(x, y, w, h, c) end
function gfx.fill(x, y, w, h, c) v.gfx_fill(x, y, w, h, c) end
function gfx.circle(cx, cy, r, c) v.gfx_circle(cx, cy, r, c, false) end
function gfx.disc(cx, cy, r, c) v.gfx_circle(cx, cy, r, c, true) end
--- gfx.text(x, y, s [, fg [, bg [, font]]]) -> width; bg nil = transparent; font 0 = 8x16, 1 = 6x8
function gfx.text(x, y, s, fg, bg, font) return v.gfx_text(x, y, tostring(s), fg or 7, bg == nil and -1 or bg, font or 0) end
function gfx.blit(x, y, w, h, data, key, stride) v.gfx_blit(x, y, w, h, data, key == nil and -1 or key, stride or w) end
function gfx.read(x, y, w, h) return v.gfx_read(x, y, w, h) end
--- The hardware cursor: drawn by the client over the picture, so moving it costs no redraw (U1.3).
function gfx.cursor(x, y, visible) v.gfx_cursor(x, y, visible ~= false) end
function gfx.cursorshape(w, h, hotx, hoty, data, key) v.gfx_cursor_shape(w, h, hotx or 0, hoty or 0, data, key == nil and -1 or key) end
function gfx.copy(sx, sy, w, h, dx, dy) v.gfx_copy(sx, sy, w, h, dx, dy) end
function gfx.clip(x, y, w, h) v.gfx_clip(x or 0, y or 0, w or 0, h or 0) end
function gfx.palette(i, rgb) return v.gfx_palette(i, rgb) end
--- Ends the frame: yields to the host until the next flush. Only the kernel's coroutine may call it directly;
--- programs yield "flip" and the kernel passes it up.
function gfx.present() coroutine.yield("flip") end
--- Sprites (Paint writes them): "VMCSPR w h\n" followed by two hex digits per pixel, palette indices.
function gfx.savesprite(path, w, h, data)
  local hex = {}
  for i = 1, #data do hex[i] = string.format("%02x", data:byte(i)) end
  fs.write(path, "VMCSPR " .. w .. " " .. h .. "\n" .. table.concat(hex))
end
--- gfx.loadsprite(path) -> { w = , h = , data = } for gfx.sprite / gfx.blit
function gfx.loadsprite(path)
  local s = fs.read(path)
  local w, h, body = s:match("^VMCSPR (%d+) (%d+)\n(.*)$")
  if not w then error("not a sprite: " .. path, 0) end
  w, h = tonumber(w), tonumber(h)
  local bytes = {}
  for i = 1, w * h do bytes[i] = string.char(tonumber(body:sub(i * 2 - 1, i * 2), 16) or 0) end
  return { w = w, h = h, data = table.concat(bytes) }
end
--- gfx.sprite(x, y, spr [, key]): draws a loaded sprite; key = the transparent colour (default none)
function gfx.sprite(x, y, spr, key) gfx.blit(x, y, spr.w, spr.h, spr.data, key) end
function gfx.fontw(font) return (font == 1) and 6 or 8 end
function gfx.fonth(font) return (font == 1) and 8 or 16 end
--- The test card from S2.
function gfx.demo(frames)
  local w, h = gfx.size()
  if w == 0 then return "no monitor" end
  for f = 1, frames or 60 do
    gfx.clear(0)
    for i = 0, 15 do gfx.fill(math.floor(i * w / 16), 0, math.ceil(w / 16), math.floor(h / 4), i) end
    for x = 0, w - 1 do gfx.line(x, math.floor(h / 4), x, math.floor(h / 2), 16 + (x + f * 3) % 216) end
    gfx.disc(math.floor(w / 2), math.floor(h * 3 / 4), math.floor(h / 6), 8 + f % 8)
    gfx.circle(math.floor(w / 2), math.floor(h * 3 / 4), math.floor(h / 6) + 2, 7)
    gfx.text(8, math.floor(h / 2) + 8, "VirtualMinecraft Computer  " .. w .. "x" .. h .. "  frame " .. f, 7, 0)
    gfx.text(8, math.floor(h / 2) + 28, "8x16 font: The quick brown fox jumps over the lazy dog", 11, -1)
    gfx.text(8, math.floor(h / 2) + 48, "6x8 font: 0123456789 !\"#$%&'()*+,-./:;<=>?@[]^_`{|}~", 10, -1, 1)
    gfx.present()
  end
  return "done"
end
L.gfx = gfx

---------------------------------------------------------------------------------------------------- snd (§5)
-- Four synth channels (1-4) and two sample channels (5-6). Waves: snd.SQUARE, TRIANGLE, SAW, SINE, NOISE.
local snd = { SQUARE = 0, TRIANGLE = 1, SAW = 2, SINE = 3, NOISE = 4 }
--- snd.channel(ch, wave, freq, vol, attack, decay, sustain, release, duty): starts a note (times in seconds).
function snd.channel(ch, wave, freq, vol, a, d, s, r, duty) v.snd_channel(ch, wave or 0, freq, vol or 1, a or 0, d or 0, s == nil and 1 or s, r or 0.05, duty or 0.5) end
--- snd.off(ch): lets the note go (the release stage).
function snd.off(ch) v.snd_note_off(ch) end
--- snd.slide(ch, freq, seconds): glides the pitch.
function snd.slide(ch, freq, seconds) v.snd_slide(ch, freq, seconds or 0.1) end
--- snd.sample(id, data, rate): loads 8-bit unsigned PCM (a string, ≤ 64 KB) under a number.
function snd.sample(id, data, rate) v.snd_sample_load(id, data, rate or 22050) end
--- snd.play(ch, id, vol, loop): plays a loaded sample on channel 5 or 6.
function snd.play(ch, id, vol, loop) v.snd_sample_play(ch, id, vol or 1, loop or false) end
--- snd.stop([ch]): silence one channel, or everything.
function snd.stop(ch) v.snd_stop(ch) end
--- snd.master([vol]) -> vol
function snd.master(vol) return v.snd_master(vol) end
local NOTES = { C = 0, ["C#"] = 1, Db = 1, D = 2, ["D#"] = 3, Eb = 3, E = 4, F = 5, ["F#"] = 6, Gb = 6, G = 7, ["G#"] = 8, Ab = 8, A = 9, ["A#"] = 10, Bb = 10, B = 11 }
--- snd.note("A4") -> 440; snd.note(69) -> 440 (MIDI number)
function snd.note(n)
  if type(n) == "number" then return 440 * 2 ^ ((n - 69) / 12) end
  local name, octave = tostring(n):match("^([A-Ga-g][#b]?)(%-?%d+)$")
  if not name then return nil end
  local semi = NOTES[name:sub(1, 1):upper() .. name:sub(2)]
  if not semi then return nil end
  return 440 * 2 ^ ((semi + 12 * (tonumber(octave) + 1) - 69) / 12)
end
--- snd.beep([freq [, seconds [, wave]]]): a quick tone on channel 1 (returns at once; the envelope ends it).
function snd.beep(freq, seconds, wave) snd.channel(1, wave or 0, freq or 880, 0.6, 0.005, seconds or 0.15, 0, 0.05) end

-- Songs: what the Music app saves in /disk/songs/<name>.json. A song is patterns plus an order:
--
--   { bpm, steps,                       -- steps *per pattern*
--     wave = { 4 wave numbers },
--     patterns = { { notes = { [step] = {4 MIDI numbers} }, vol = { [step] = {4 volumes 1-9} } }, ... },
--     order   = { 1, 1, 2, 3 },         -- which pattern plays when
--     notes   = <a copy of the first pattern's notes> }
--
-- **`notes` is the compatibility mirror.** Music wrote a single 16-step `notes` table until session 21, and
-- programs (including /rom/examples/song.lua) read `song.notes[step][channel]` directly. Normalising *into*
-- that shape rather than away from it means every old song still plays and every old program still works: a
-- one-pattern song is exactly what it always was, and a many-pattern song at least hands the old reader its
-- first pattern instead of nothing.
--- snd.normalisesong(song) -> song: fills in the defaults and the patterns/order pair, in place.
function snd.normalisesong(song)
  if type(song) ~= "table" then error("not a song", 0) end
  song.bpm = tonumber(song.bpm) or 120
  song.wave = song.wave or { 0, 1, 2, 3 }
  if type(song.patterns) ~= "table" or #song.patterns == 0 then
    if type(song.notes) ~= "table" then error("not a song", 0) end
    song.patterns = { { notes = song.notes, vol = song.vol } }
  end
  for _, p in ipairs(song.patterns) do
    p.notes = p.notes or {}
    p.vol = p.vol or {}
  end
  if type(song.order) ~= "table" or #song.order == 0 then song.order = { 1 } end
  song.steps = tonumber(song.steps) or #song.patterns[1].notes
  if song.steps < 1 then song.steps = 16 end
  song.notes = song.patterns[song.order[1]] and song.patterns[song.order[1]].notes or song.patterns[1].notes
  return song
end
--- snd.loadsong(path) -> song: reads and checks one of Music's files.
function snd.loadsong(path)
  local ok, song = pcall(function() return snd.normalisesong(json.decode(fs.read(path))) end)
  if not ok then error(tostring(path) .. " is not a song", 0) end
  return song
end
--- snd.playsong(song|path [, opts]) -> handle: plays it in the background (a kernel program) and returns at
--- once; handle:stop() ends it. opts.loop = false plays it once, opts.onstep(step, orderIndex, pattern) is
--- called on every step, opts.alive() may return false to end it (Music passes "is my window still open"),
--- opts.name labels the program.
function snd.playsong(song, opts)
  if type(song) == "string" then song = snd.loadsong(song) else snd.normalisesong(song) end
  opts = opts or {}
  if not (kernel and kernel.spawn) then error("snd.playsong needs the kernel: call it from a program", 0) end
  local h = { song = song, step = 0, order = 1, stopped = false }
  h.stop = function()
    h.stopped = true
    if h.program then kernel.kill(h.program) h.program = nil end
    for c = 1, 4 do snd.off(c) end
  end
  h.program = kernel.spawn(opts.name or "song", function()
    repeat
      for oi = 1, #song.order do
        local pat = song.patterns[song.order[oi]] or song.patterns[1]
        h.order = oi
        for s = 1, song.steps do
          if h.stopped or (opts.alive and not opts.alive()) then h.stop() return end
          h.step = s
          for c = 1, 4 do
            local n = pat.notes[s] and pat.notes[s][c] or 0
            if n and n > 0 then
              -- volume is 1-9 in the file and 9 is what every song written before it had, so the default keeps
              -- the old loudness exactly
              local vv = (pat.vol[s] and pat.vol[s][c]) or 9
              snd.channel(c, song.wave[c] or 0, snd.note(n), 0.5 * (vv / 9), 0.005, 0.1, 0.6, 0.08)
            else
              snd.off(c)
            end
          end
          if opts.onstep then opts.onstep(s, oi, song.order[oi]) end
          os.sleep(30000 / song.bpm) -- a step is an eighth note
        end
      end
    until h.stopped or opts.loop == false
    h.stop()
  end)
  return h
end
L.snd = snd

---------------------------------------------------------------------------------------------------- os
local os = {}
function os.clock() return v.clock(0) / 1e9 end      -- seconds, monotonic, since this machine booted
function os.time() return v.clock(1) end               -- world ticks
--- The **world's** milliseconds since 1970 (ROADMAP §9 U10(b), [name]: "day 0, time 0 would be equal to January
--- 1st, 1970 at 6:00AM minecraft time"). A Minecraft day is a day, so the world's clock runs 72x real time and
--- this is nothing to do with the host's -- a world that has run a thousand days is in 1972.
function os.epoch() return v.clock(2) end
--- The host's wall clock in milliseconds. The one thing os.epoch() cannot tell you is how much *real* time went
--- by (across a freeze, say), so this stays available; almost nothing should want it.
function os.realtime() return v.clock(3) end

---------------------------------------------------------------------------------------------------- the calendar
-- Days <-> y/m/d without a single table lookup or leap-year special case: Howard Hinnant's civil-calendar
-- algorithms, which are exact for every year Lua's numbers can hold and are four lines each.
local function civilFromDays(z)
  z = z + 719468
  local era = math.floor(z / 146097)
  local doe = z - era * 146097
  local yoe = math.floor((doe - math.floor(doe / 1460) + math.floor(doe / 36524) - math.floor(doe / 146096)) / 365)
  local y = yoe + era * 400
  local doy = doe - (365 * yoe + math.floor(yoe / 4) - math.floor(yoe / 100))
  local mp = math.floor((5 * doy + 2) / 153)
  local d = doy - math.floor((153 * mp + 2) / 5) + 1
  local m = mp < 10 and mp + 3 or mp - 9
  return (m <= 2 and y + 1 or y), m, d
end

--- The day number (0 = 1970-01-01) of a date. The inverse of civilFromDays; a Calendar needs both.
function os.daysfromdate(y, m, d)
  local yy = m <= 2 and y - 1 or y
  local era = math.floor(yy / 400)
  local yoe = yy - era * 400
  local mp = (m + 9) % 12
  local doy = math.floor((153 * mp + 2) / 5) + d - 1
  local doe = yoe * 365 + math.floor(yoe / 4) - math.floor(yoe / 100) + doy
  return era * 146097 + doe - 719468
end

os.MONTHS = { "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December" }
os.DAYS = { "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" }

--- The world's date broken up, from an epoch in milliseconds (default: now). Lua's os.date("*t") fields, plus
--- `day` (the Minecraft day number) and `epoch`. wday is 1 = Sunday, yday is 1 on the 1st of January.
function os.datetable(epochMs)
  local ms = math.floor(tonumber(epochMs) or os.epoch())
  local days = math.floor(ms / 86400000)
  local rem = ms - days * 86400000
  local y, m, d = civilFromDays(days)
  return {
    year = y, month = m, day = d,
    hour = math.floor(rem / 3600000), min = math.floor(rem / 60000) % 60, sec = math.floor(rem / 1000) % 60,
    wday = (days + 4) % 7 + 1,               -- 1970-01-01 was a Thursday
    yday = days - os.daysfromdate(y, 1, 1) + 1,
    isdst = false,
    epoch = ms, worldday = math.floor(os.time() / 24000),
  }
end

--- os.date() with no arguments is the taskbar's: "HH:MM" and the Minecraft day number, as it always was.
--- os.date(fmt [, epochMs]) formats the world's date -- "%Y-%m-%d %H:%M" and friends, or "*t" for the table.
--- Supported: %Y %y %m %d %e %H %I %M %S %p %j %A %a %B %b %F %T %D %n %% (anything else is left alone).
function os.date(fmt, epochMs)
  if fmt == nil then
    local t = os.time() % 24000
    local hours = math.floor((t / 1000 + 6) % 24)
    local minutes = math.floor((t % 1000) / 1000 * 60)
    return string.format("%02d:%02d", hours, minutes), math.floor(os.time() / 24000)
  end
  local t = os.datetable(epochMs)
  if fmt == "*t" or fmt == "!*t" then return t end
  local h12 = t.hour % 12
  if h12 == 0 then h12 = 12 end
  local map = {
    Y = string.format("%04d", t.year), y = string.format("%02d", t.year % 100),
    m = string.format("%02d", t.month), d = string.format("%02d", t.day),
    e = string.format("%2d", t.day),
    H = string.format("%02d", t.hour), I = string.format("%02d", h12),
    M = string.format("%02d", t.min), S = string.format("%02d", t.sec),
    p = t.hour < 12 and "AM" or "PM", j = string.format("%03d", t.yday),
    A = os.DAYS[t.wday], a = os.DAYS[t.wday]:sub(1, 3),
    B = os.MONTHS[t.month], b = os.MONTHS[t.month]:sub(1, 3),
    F = string.format("%04d-%02d-%02d", t.year, t.month, t.day),
    T = string.format("%02d:%02d:%02d", t.hour, t.min, t.sec),
    D = string.format("%02d/%02d/%02d", t.month, t.day, t.year % 100),
    n = "\n", ["%"] = "%",
  }
  return (tostring(fmt):gsub("%%(.)", function(c) return map[c] or ("%" .. c) end))
end

--- How many days there are in a month, which a Calendar needs to draw a grid.
function os.monthdays(y, m)
  return os.daysfromdate(m == 12 and y + 1 or y, m == 12 and 1 or m + 1, 1) - os.daysfromdate(y, m, 1)
end
--- Sleep inside a program: yields to the kernel, which resumes the program after ms milliseconds.
function os.sleep(ms) coroutine.yield("sleep", ms or 0) end
function os.reboot() v.call(4, "reboot") end
function os.shutdown() v.call(4, "shutdown") end
function os.label(name) if name then v.call(4, "label:" .. tostring(name)) end return v.name() end
--- The case and its parts (ROADMAP §9 U3b): tier, tierName, mem (MB), cpu (%), maxw/maxh, colours, disk (KB),
--- synth/samples (voices), desktop (what it boots into), desktopMode (0 auto, 1 desktop, 2 shell).
function os.info()
  local ok, t = pcall(json.decode, v.info and v.info() or "{}")
  if not ok or type(t) ~= "table" then t = {} end
  t.tier = t.tier or 2
  t.tierName = t.tierName or "Computer"
  if t.desktop == nil then t.desktop = t.tier > 1 end
  return t
end
--- What the machine boots into: "auto" (the tier decides), "desktop" or "shell". Takes effect at the next boot.
function os.desktop(mode) v.call(4, "desktop:" .. tostring(mode or "auto")) end
L.os = os

return L
