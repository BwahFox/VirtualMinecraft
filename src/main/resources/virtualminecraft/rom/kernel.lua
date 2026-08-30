-- The ROM kernel (ROADMAP §7h §7): libraries, the event loop, programs as coroutines, windows, the desktop,
-- the floor persistence (§2) and the harness hooks. Runs as the machine's main coroutine; every yield to the host
-- goes through here: "wait" (nothing to do), "flip" (a frame is ready / a tick is wanted), "saved" (freeze ack).

---------------------------------------------------------------------------------------------------- libraries
local function romload(path, ...)
  local src = vmc.fs_read(path)
  local fn, err = load(src, "=" .. path:match("[^/]*$"), "t", _G)
  if not fn then error("ROM " .. path .. ": " .. tostring(err), 0) end
  return fn(...)
end
_G.json = romload("/rom/lib/json.lua")
local sys = romload("/rom/lib/sys.lua", json)
_G.bus, _G.state, _G.fs, _G.gfx, _G.os, _G.snd, _G.net = sys.bus, sys.state, sys.fs, sys.gfx, sys.os, sys.snd, sys.net
_G.json = json
_G.win = romload("/rom/lib/win.lua")
_G.shell = romload("/rom/lib/shell.lua")
local win, gfx, fs = _G.win, _G.gfx, _G.fs
local KEY = win.KEY

---------------------------------------------------------------------------------------------------- kernel
local kernel = { windows = {}, programs = {}, apps = {}, order = {}, pointer = { x = 0, y = 0, buttons = 0 }, mods = { ctrl = false, shift = false },
  dirty = true, toast = nil, toastUntil = 0, log = {}, saved = nil, w = 0, h = 0, diskProgs = {}, watched = true,
  MAX_DISK_ICONS = 3 } -- how many programs on disks may take the top of the icon column
_G.kernel = kernel

local function now() return os.clock() end

function kernel.invalidate() kernel.dirty = true end
win.invalidate = kernel.invalidate

function kernel.say(text)
  text = tostring(text)
  kernel.log[#kernel.log + 1] = text
  if #kernel.log > 200 then table.remove(kernel.log, 1) end
  if kernel.onlog then kernel.onlog(text) end
  vmc.log(1, text)
end
_G.print = function(...)
  local parts = {}
  for i = 1, select("#", ...) do parts[#parts + 1] = tostring((select(i, ...))) end
  kernel.say(table.concat(parts, "\t"))
end

function kernel.notify(text, seconds)
  kernel.toast = tostring(text)
  kernel.toastUntil = now() + (seconds or 3)
  kernel.invalidate()
end

--- Screen metrics: font by width, taskbar height, icon grid.
function kernel.layout()
  local w, h = gfx.size()
  kernel.w, kernel.h = w, h
  kernel.closeMenus() -- a menu is placed against the screen it opened on; there is nothing to re-lay-out
  -- The font is a preference since 2026-08-28 ([name]: text on a monitor is hard to read, and the player should
  -- choose between screen space and letter size). "auto" is the old rule -- 6x8 below 512 px, 8x16 from there --
  -- "large" and "small" force one; Settings > Desktop cycles it and it lives in /disk/desktop.json.
  local big = w >= 512
  if kernel.textSize == "large" then big = true elseif kernel.textSize == "small" then big = false end
  win.setfont(big and 0 or 1)
  local T = win.theme
  kernel.taskbarH = T.fh + 6
  kernel.iconW = math.max(T.fh * 3 + 8, T.fw * 9) + 10 -- the icon column stays clear of windows
  if kernel.console then
    -- the shell owns the screen (a Basic Computer, or Settings said so): no icons, no taskbar, every window
    -- fills the screen -- the stock apps still open, they just come up full size, one at a time
    kernel.taskbarH, kernel.iconW = 0, 0
    for _, wd in ipairs(kernel.windows) do
      wd.x, wd.y, wd.w, wd.h = 0, 0, w, h
      wd.maximized = nil
      wd:relayout()
    end
    kernel.invalidate()
    return
  end
  if w == 0 or h == 0 then -- no monitor: nothing to lay out, and every size would come out nonsense
    kernel.invalidate()
    return
  end
  local deskH = h - kernel.taskbarH
  for _, wd in ipairs(kernel.windows) do
    if wd.fullscreen then
      wd.x, wd.y, wd.w, wd.h = 0, 0, w, h
    else
      local minW = math.min(wd.minW or win.MIN_W(), w)
      local minH = math.min(wd.minH or win.MIN_H(), deskH)
      -- a window that was sized while the machine had no screen (or against a much bigger one) comes back as
      -- a sliver; give it the default size and place instead of a clamped stub
      if wd.needsFit or wd.w < minW or wd.h < minH then
        wd.needsFit = nil
        wd.w, wd.h = math.min(w - kernel.iconW - 8, 300), math.min(deskH - 10, 220)
        wd.x, wd.y = kernel.iconW + 4, 6
      end
      wd.w = math.max(minW, math.min(wd.w, w))
      wd.h = math.max(minH, math.min(wd.h, deskH))
      wd.x = math.max(0, math.min(wd.x, w - 16))
      wd.y = math.max(0, math.min(wd.y, deskH - win.TITLE_H()))
    end
    wd:relayout()
  end
  kernel.invalidate()
end

---------------------------------------------------------------------------------------------------- windows
function kernel.show(wd)
  kernel.windows[#kernel.windows + 1] = wd
  if wd.fullscreen then wd.x, wd.y, wd.w, wd.h = 0, 0, kernel.w, kernel.h end
  wd:relayout()
  kernel.focus(wd)
  return wd
end

function kernel.focus(wd)
  for i, x in ipairs(kernel.windows) do
    if x == wd then table.remove(kernel.windows, i) break end
  end
  kernel.windows[#kernel.windows + 1] = wd
  wd.minimized = nil -- focusing a window is how you get it back: the taskbar button, Alt-Tab, the app opening again
  kernel.invalidate()
end

--- The window input goes to. A minimised window is still in the list -- the taskbar draws it, the desktop saves
--- it, the app behind it keeps running -- it is simply not on screen, so it must never be the one that gets a key.
function kernel.top()
  for i = #kernel.windows, 1, -1 do
    if not kernel.windows[i].minimized then return kernel.windows[i] end
  end
  return nil
end

--- The window that *looks* active. A menu is on top and takes the keys, but the window you were using must not
--- grey out just because you opened one, so the title bars and the taskbar ask this instead of kernel.top().
function kernel.active()
  for i = #kernel.windows, 1, -1 do
    local wd = kernel.windows[i]
    if not wd.minimized and not wd.popup then return wd end
  end
  return nil
end

--- Out of the way, into the taskbar (U6). It goes to the *bottom* of the z-order as well as being hidden, so
--- that un-minimising it later does not put it back over something you have been using since.
function kernel.minimize(wd)
  if wd.minimized or wd.popup then return end
  wd.minimized = true
  for i, x in ipairs(kernel.windows) do
    if x == wd then table.remove(kernel.windows, i) break end
  end
  table.insert(kernel.windows, 1, wd)
  kernel.invalidate()
end

--- Every open menu, deepest last. A menu is a transient: anything that redraws the world underneath it -- a
--- screen change, a program taking the screen, a shutdown -- takes it away rather than leaving it floating.
function kernel.menus()
  local out = {}
  for _, wd in ipairs(kernel.windows) do if wd.popup then out[#out + 1] = wd end end
  return out
end

function kernel.closeMenus()
  for i = #kernel.windows, 1, -1 do
    local wd = kernel.windows[i]
    if wd.popup then wd.child = nil kernel.close(wd) end
  end
end

--- Double-click on a title bar: fill the whole desktop, icon column included ([name], session 12: "screen space
--- is precious" -- on a 1x1 monitor the icons are a quarter of the width); again: back to where it was (U1.5).
--- The taskbar stays: it is how you reach the other windows and the Apps list while one window owns the screen.
function kernel.maximize(wd)
  if wd.fullscreen then return end
  if wd.maximized then
    local r = wd.maximized
    wd.x, wd.y, wd.w, wd.h = r.x, r.y, r.w, r.h
    wd.maximized = nil
  else
    wd.maximized = { x = wd.x, y = wd.y, w = wd.w, h = wd.h }
    wd.x, wd.y, wd.w, wd.h = 0, 0, kernel.w, kernel.h - kernel.taskbarH
  end
  wd:relayout()
  kernel.invalidate()
end

function kernel.close(wd)
  for i, x in ipairs(kernel.windows) do
    if x == wd then
      table.remove(kernel.windows, i)
      wd.closed = true
      if wd.onclose then pcall(wd.onclose, wd) end
      if wd.program then kernel.kill(wd.program) end
      kernel.invalidate()
      -- the shell is the console's floor: closing the last window brings it back
      if kernel.console and #kernel.windows == 0 and not kernel.halting then kernel.open("terminal") end
      return
    end
  end
end

---------------------------------------------------------------------------------------------------- programs
-- A program is a coroutine with a window. It runs until it returns; yields: "flip" (frame ready), "sleep", ms,
-- "wait" (event-driven, done for now). Errors kill the program and show a dialog; the machine survives.
function kernel.spawn(name, fn, wd)
  local p = { name = name, co = coroutine.create(fn), window = wd, wake = 0, alive = true }
  if wd then wd.program = p end
  kernel.programs[#kernel.programs + 1] = p
  kernel.resume(p)
  return p
end

function kernel.kill(p)
  p.alive = false
  for i, x in ipairs(kernel.programs) do if x == p then table.remove(kernel.programs, i) break end end
end

function kernel.resume(p, ...)
  if not p.alive or coroutine.status(p.co) == "dead" then kernel.kill(p) return end
  local ok, a, b = coroutine.resume(p.co, ...)
  if not ok then
    kernel.kill(p)
    kernel.say("program '" .. p.name .. "' failed: " .. tostring(a))
    win.ask("Program error", win.fit(tostring(a), kernel.w - 24), { "OK" })
    return
  end
  if coroutine.status(p.co) == "dead" then
    kernel.kill(p)
    if p.window and p.window.fullscreen then kernel.close(p.window) end
    return
  end
  if a == "flip" then p.wake = now() + kernel.frameTime() kernel.dirty = true -- the next frame is due one frame from now
  elseif a == "sleep" then p.wake = now() + (tonumber(b) or 0) / 1000
  else p.waiting = true end
end

--- Seconds between a presenting program's frames: the host says (vmc.frame_ms: 1000/computerMaxFps while somebody
--- watches, 50 otherwise); a harness without it gets 20 fps.
function kernel.frameTime() return (vmc.frame_ms and vmc.frame_ms() or 50) / 1000 end

--- The 256 palette entries as a table, and setting them back. A full-screen program may load a palette of its
--- own (every ported game does), and when it leaves, the desktop must not be left wearing it: runfile takes a
--- copy before the program starts and puts it back when its window closes, whichever way the program ended.
function kernel.palette()
  local p = {}
  for i = 0, 255 do p[i] = gfx.palette(i) end
  return p
end

function kernel.setpalette(p)
  if type(p) ~= "table" then return end
  for i = 0, 255 do if p[i] then gfx.palette(i, p[i]) end end
  kernel.dirty = true
end

--- Run a Lua file as a full-screen program (the desktop's "Run"). Arguments reach it as `...`, so a program
--- can be told which file to open (`examples song tune`), the way `run` hands arguments to a plain .lua file.
--- What a program on a disk asks for, from the lines after the name in its program.txt: `mem=16` (MB) and
--- `screen=512x512`. Nil when there is no program.txt or it asks for nothing.
function kernel.requirements(dir)
  local okt, txt = pcall(fs.read, fs.join(dir, "program.txt"))
  if not okt or type(txt) ~= "string" then return nil end
  local req = {}
  for line in txt:gmatch("[^\r\n]+") do
    local k, v = line:match("^%s*(%w+)%s*=%s*(%S+)")
    if k == "mem" then req.mem = tonumber(v)
    elseif k == "screen" then local w, h = v:match("^(%d+)x(%d+)$") req.w, req.h = tonumber(w), tonumber(h) end
  end
  return next(req) and req or nil
end

--- Why this machine cannot run a program with those requirements, in a sentence -- or nil. The case is what is
--- compared (the screen cap, the memory budget), so the answer does not change with the monitor of the moment.
function kernel.unmet(req)
  if not req then return nil end
  local _, cap = vmc.mem()
  if req.mem and cap < req.mem * 1024 * 1024 then
    return string.format("Needs %d MB (this computer has %d)", req.mem, math.floor(cap / 1024 / 1024 + 0.5))
  end
  local info = kernel.info or {}
  if req.w and req.h and info.maxw and info.maxh and (info.maxw < req.w or info.maxh < req.h) then
    return string.format("Needs a %dx%d screen (this case shows up to %dx%d)", req.w, req.h, info.maxw, info.maxh)
  end
  return nil
end

--- Run a program full-screen. `restored` is what `kernel.save` kept for it last time (ROADMAP §9 U12) and is
--- handed back through the `program` table in the program's own environment; nothing else passes it.
function kernel.runfile(path, args, restored)
  local why = kernel.unmet(kernel.requirements(fs.dirname(path)))
  if why then kernel.notify(why, 5) kernel.say(why) return end
  local src
  local ok, err = pcall(function() src = fs.read(path) end)
  if not ok then kernel.notify(tostring(err)) return end
  local env = setmetatable({}, { __index = _G, __newindex = _G })
  rawset(env, "PROGRAM_DIR", fs.dirname(path)) -- a program finds its own files here
  -- §9 U12: state across a freeze. A program that wants to come back where it left off sets `program.save` (and
  -- optionally `program.version`); the kernel calls that function when the machine is frozen and hands what it
  -- returns back through `program.restore()` on the way in. It is *data*, not a call stack, so it survives a mod
  -- update -- and the version is the program's own, so a program that has changed shape refuses its old state
  -- instead of unpacking it into fields that have moved.
  local held = restored
  local prog = { path = path, dir = fs.dirname(path), args = args, version = 0 }
  --- The saved state, or nil: when there is none, when it was already taken, or when it was written by a
  --- version this program does not claim to speak. Call it *after* setting program.version.
  function prog.restore()
    local h = held
    held = nil
    if not h then return nil end
    if (h.version or 0) ~= (prog.version or 0) then
      kernel.say(fs.basename(path) .. ": saved state is version " .. tostring(h.version or 0)
        .. ", this program speaks " .. tostring(prog.version or 0) .. " -- starting fresh")
      return nil
    end
    return h.state
  end
  rawset(env, "program", prog)
  local fn, cerr = load(src, "=" .. fs.basename(path), "t", env)
  if not fn then kernel.notify(tostring(cerr), 5) return end
  kernel.addRecent(path) -- the start menu's Documents list is "what this machine has been used for"
  local wd = win.Window.new{ title = fs.basename(path), fullscreen = true }
  wd.savedPalette = kernel.palette()
  wd.onclose = function(self) kernel.setpalette(self.savedPalette) end
  wd.programFile = prog -- what kernel.save asks when the machine freezes
  kernel.show(wd)
  kernel.spawn(fs.basename(path), function()
    gfx.clear(0)
    local r = fn(table.unpack(args or {}))
    if r ~= nil then kernel.say(tostring(r)) end
  end, wd)
  return wd
end

---------------------------------------------------------------------------------------------------- apps
function kernel.register(app) kernel.apps[app.id] = app kernel.order[#kernel.order + 1] = app.id end
function kernel.open(id, args)
  local app = kernel.apps[id]
  if not app then return nil end
  local ok, wd = pcall(app.open, args or {})
  if not ok then kernel.say("app " .. id .. " failed: " .. tostring(wd)) return nil end
  if wd then
    wd.app = id
    kernel.show(wd)
    if kernel.console and not wd.fullscreen then wd.x, wd.y, wd.w, wd.h = 0, 0, kernel.w, kernel.h wd:relayout() end
  end
  return wd
end
function kernel.find(id) for _, wd in ipairs(kernel.windows) do if wd.app == id then return wd end end end

for _, id in ipairs({ "terminal", "files", "edit", "manual", "settings", "calendar", "inventory", "redstone", "world", "network", "music", "paint", "snake", "breakout" }) do
  local ok, err = pcall(function() kernel.register(romload("/rom/apps/" .. id .. ".lua")) end)
  if not ok then vmc.log(3, "app " .. id .. ": " .. tostring(err)) end
end
kernel.register{ id = "demo", name = "Demo", icon = "*", open = function()
  local wd = win.Window.new{ title = "Demo", fullscreen = true }
  kernel.show(wd)
  kernel.spawn("demo", function() gfx.demo(200) end, wd)
  return nil
end }

--- Programs on disk (§7): a directory with main.lua on any mount but /rom; program.txt's first line names it.
function kernel.diskPrograms()
  local out = {}
  local ok = pcall(function()
    for _, m in ipairs(fs.mounts()) do
      if m.name ~= "rom" then
        local root = "/" .. m.name
        local function add(dir, fallback)
          if not fs.exists(fs.join(dir, "main.lua")) then return end
          local name = fallback
          local okt, txt = pcall(fs.read, fs.join(dir, "program.txt"))
          if okt and type(txt) == "string" then
            local first = txt:match("^[^\r\n]*")
            if first and first ~= "" then name = first end
          end
          out[#out + 1] = { name = name, path = fs.join(dir, "main.lua"), mount = m.name, unmet = kernel.unmet(kernel.requirements(dir)) }
        end
        add(root, m.label or m.name) -- a CD that *is* the program (main.lua at its root)
        for _, e in ipairs(fs.list(root)) do
          if e.dir then add(fs.join(root, e.name), e.name) end
        end
      end
    end
  end)
  return out
end

--- The cached answer, for the desktop's icon column: fs.mounts + a listing per mount is too much to do on every
--- frame, so it is taken at boot and whenever a disk goes in or out (which is exactly when it can change).
function kernel.refreshDisks()
  local ok, progs = pcall(kernel.diskPrograms)
  kernel.diskProgs = ok and progs or {}
  kernel.dirty = true
  return kernel.diskProgs
end

-- The launcher: every app plus the programs on disk, from the taskbar's Apps button.
kernel.register{ id = "launcher", name = "Apps", icon = "^", hidden = true, open = function()
  local T = win.theme
  local wd = win.Window.new{ title = "Programs", x = 4, y = kernel.h - kernel.taskbarH - 4 - math.min(kernel.h - kernel.taskbarH - 8, 150), w = math.min(kernel.w - 8, 180), h = math.min(kernel.h - kernel.taskbarH - 8, 150) }
  local list = wd:add(win.List{ items = {} })
  local open = wd:add(win.Button{ text = "Open", h = T.fh + 6 })
  local entries = {}
  list.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, cw, ch - T.fh - 8 end
  open.layout = function(self, cw, ch) self.x, self.y, self.w = 0, ch - T.fh - 6, cw end
  wd:relayout()
  local labels = {}
  for _, id in ipairs(kernel.order) do
    local app = kernel.apps[id]
    if not app.hidden then entries[#entries + 1] = { app = id } labels[#labels + 1] = app.name end
  end
  for _, prog in ipairs(kernel.diskPrograms()) do
    entries[#entries + 1] = { path = prog.path }
    labels[#labels + 1] = prog.name .. "  (/" .. prog.mount .. ")" .. (prog.unmet and ("  - " .. prog.unmet) or "")
  end
  list.items = labels
  -- size the window to its entries (a program on a CD must not hide below the fold), up to the desktop's height
  wd.h = math.min(kernel.h - kernel.taskbarH - 8, win.TITLE_H() + #labels * list:rowh() + T.fh + 8 + 6)
  wd.y = kernel.h - kernel.taskbarH - 4 - wd.h
  wd:relayout()
  list.selected = 1
  local function launch(i)
    local e = entries[i]
    if not e then return end
    kernel.close(wd)
    if e.app then
      local existing = kernel.find(e.app)
      if existing then kernel.focus(existing) else kernel.open(e.app) end
    else kernel.runfile(e.path) end
  end
  list.onactivate = function(i) launch(i) end
  open.onclick = function() launch(list.selected) end
  wd:setfocus(list)
  return wd
end }

---------------------------------------------------------------------------------------------------- menus (U6)
-- The start menu and the right-click menus. All four are built the same way, from `win.menu`; what differs is
-- only what is under the pointer when you ask.

-- Recent documents, for the start menu's Documents list. A path joins it when the desktop opens a document or
-- runs a program from one. It is a *preference*, not session state: it lives on the machine's disk beside the
-- wallpaper, so it outlives a reboot the way the Documents menu on a real 95 desktop did.
kernel.recent = {}
kernel.MAX_RECENT = 8

function kernel.addRecent(path)
  if type(path) ~= "string" or path == "" or path:sub(1, 5) == "/rom/" then return end
  for i, p in ipairs(kernel.recent) do if p == path then table.remove(kernel.recent, i) break end end
  table.insert(kernel.recent, 1, path)
  while #kernel.recent > kernel.MAX_RECENT do table.remove(kernel.recent) end
  kernel.savePrefs()
end

--- Open a document the way the desktop would: a .lua file runs, anything else opens in Edit.
function kernel.openDocument(path)
  if not fs.exists(path) then
    kernel.notify("Gone: " .. path, 3)
    for i, p in ipairs(kernel.recent) do if p == path then table.remove(kernel.recent, i) kernel.savePrefs() break end end
    return
  end
  if path:match("%.lua$") then kernel.runfile(path) -- which records it
  else kernel.addRecent(path) kernel.open("edit", { path = path }) end
end

--- Can this path be written to? The mount says; a CD and the ROM say no, and the desktop must not offer to
--- delete something off a disk that will only refuse.
function kernel.writable(path)
  local name = tostring(path):match("^/([^/]+)")
  if not name then return false end
  for _, m in ipairs(fs.mounts()) do
    if m.name == name then return not m.readOnly end
  end
  return false
end

--- What this machine is (U6): the one place that says out loud what os.info(), vmc.mem() and the mounts know.
function kernel.about()
  local T = win.theme
  local info = os.info()
  local used, cap = vmc.mem()
  local sw, sh = gfx.size()
  local up = math.floor(os.clock())
  local mounts = {}
  for _, m in ipairs(fs.mounts()) do mounts[#mounts + 1] = "/" .. m.name end
  local rows = {
    { "Name", os.label() },
    { "Case", string.format("%s (tier %d)", info.tierName or "Computer", info.tier or 2) },
    { "CPU", (info.cpu or 25) .. "% of a core" },
    { "Memory", string.format("%d / %d KB", math.floor(used / 1024), math.floor(cap / 1024)) },
    { "Screen", string.format("%dx%d, %d colours", sw, sh, info.colours or 256) },
    { "Disk", (info.disk or 0) .. " KB" },
    { "Sound", string.format("%d synth, %d sample", info.synth or 4, info.samples or 2) },
    { "Uptime", up < 90 and (up .. " s") or (math.floor(up / 60) .. " min") },
    { "Mounts", table.concat(mounts, " ") },
  }
  local labelW = T.fw * 8
  local valueW = 0
  for _, r in ipairs(rows) do valueW = math.max(valueW, win.textw(r[2])) end
  local w = math.min(kernel.w - 8, math.max(T.fw * 22, labelW + valueW + 14))
  local h = win.TITLE_H() + #rows * (T.fh + 2) + T.fh + 18
  local wd = win.Window.new{ title = "About this computer", modal = true, resizable = false,
    x = math.floor((kernel.w - w) / 2), y = math.max(0, math.floor((kernel.h - kernel.taskbarH - h) / 2)) }
  wd.w, wd.h, wd.needsFit = w, h, nil
  for i, r in ipairs(rows) do
    local y = 3 + (i - 1) * (T.fh + 2)
    wd:add(win.Label{ x = 3, y = y, w = labelW, text = r[1], fg = T.disabled })
    wd:add(win.Label{ x = 3 + labelW, y = y, w = w - labelW - 10, text = r[2] })
  end
  wd:add(win.Button{ x = math.floor((w - T.fw * 8) / 2), y = h - win.TITLE_H() - T.fh - 10, w = T.fw * 8, h = T.fh + 6,
    text = "OK", onclick = function() kernel.close(wd) end })
  kernel.show(wd)
  return wd
end

--- Everything you can start, as menu items: the programs on the disks first (a CD you just put in is the thing
--- you came for), then the apps.
local function programItems()
  local items = {}
  for _, prog in ipairs(kernel.diskPrograms()) do
    local path = prog.path
    items[#items + 1] = { text = prog.name .. " (/" .. prog.mount .. ")", disabled = prog.unmet ~= nil,
      onclick = function() kernel.openDocument(path) end }
  end
  if #items > 0 then items[#items + 1] = { sep = true } end
  for _, id in ipairs(kernel.order) do
    local app = kernel.apps[id]
    if not app.hidden then
      items[#items + 1] = { text = app.name, onclick = function()
        local existing = kernel.find(id)
        if existing then kernel.focus(existing) else kernel.open(id) end
      end }
    end
  end
  return items
end

local function recentItems()
  local items = {}
  for _, path in ipairs(kernel.recent) do
    local p = path
    items[#items + 1] = { text = fs.basename(p), onclick = function() kernel.openDocument(p) end }
  end
  if #items > 0 then
    items[#items + 1] = { sep = true }
    items[#items + 1] = { text = "Clear", onclick = function() kernel.recent = {} kernel.savePrefs() end }
  end
  return items
end

--- The start menu, above the Apps button.
function kernel.startMenu()
  local b = kernel.appsButton
  -- the *desktop's* bottom edge, not the button's top: win.menu flips a menu that will not fit downward, and
  -- passing the button's y would leave the flipped menu hanging two pixels over the taskbar
  return win.menu(b and b.x or 0, kernel.h - kernel.taskbarH, {
    { text = "Programs", submenu = programItems },
    { text = "Documents", submenu = recentItems },
    { sep = true },
    { text = "Settings", onclick = function()
      local existing = kernel.find("settings")
      if existing then kernel.focus(existing) else kernel.open("settings") end
    end },
    { text = "About this computer", onclick = kernel.about },
    { sep = true },
    { text = "Shut down...", onclick = function()
      win.ask("Power", "Shut down this computer?", { "Shut down", "Reboot", "Cancel" }, function(b2)
        if b2 == "Shut down" then kernel.save() os.shutdown() elseif b2 == "Reboot" then kernel.save() os.reboot() end
      end)
    end },
  })
end

--- Right-click on the desktop itself.
function kernel.desktopMenu(px, py)
  return win.menu(px, py, {
    { text = "New file...", onclick = function()
      win.prompt("New file", "Name:", "untitled.lua", function(name)
        if not name or name == "" then return end
        if not fs.validname(name) then kernel.notify("Names take " .. fs.NAME_HELP, 5) return end
        local path = "/disk/" .. name
        if fs.exists(path) then kernel.notify(name .. " already exists", 3) return end
        local ok, err = pcall(fs.write, path, "")
        if ok then kernel.openDocument(path) else kernel.notify(tostring(err), 4) end
      end)
    end },
    { text = "New folder...", onclick = function()
      win.prompt("New folder", "Name:", "folder", function(name)
        if not name or name == "" then return end
        if not fs.validname(name) then kernel.notify("Names take " .. fs.NAME_HELP, 5) return end
        local ok, err = pcall(fs.mkdir, "/disk/" .. name)
        if ok then kernel.notify("Made /disk/" .. name, 2) else kernel.notify(tostring(err), 4) end
      end)
    end },
    { text = "Refresh", onclick = function() kernel.refreshDisks() kernel.invalidate() end },
    { sep = true },
    { text = "Wallpaper...", onclick = function()
      local existing = kernel.find("settings")
      if existing then kernel.focus(existing) else kernel.open("settings") end
    end },
    { text = "Properties", onclick = kernel.about },
  })
end

--- Right-click on a desktop icon. An app icon is a shortcut and has nothing to delete; a program on a writable
--- disk does, and deleting it takes the whole program directory, not the main.lua the icon points at.
function kernel.iconMenu(ic, px, py)
  local items = {}
  items[#items + 1] = { text = "Open", onclick = function()
    if ic.path then kernel.openDocument(ic.path)
    else
      local existing = kernel.find(ic.id)
      if existing then kernel.focus(existing) else kernel.open(ic.id) end
    end
  end }
  items[#items + 1] = { sep = true }
  if ic.path then
    local dir = fs.dirname(ic.path)
    if kernel.writable(ic.path) then
      items[#items + 1] = { text = "Delete...", onclick = function()
        win.ask("Delete", "Delete " .. fs.basename(dir) .. "?", { "Delete", "Cancel" }, function(b)
          if b ~= "Delete" then return end
          local ok, err = pcall(fs.remove, dir)
          if ok then kernel.refreshDisks() kernel.notify("Deleted " .. fs.basename(dir), 2)
          else kernel.notify(tostring(err), 4) end
        end)
      end }
    end
    items[#items + 1] = { text = "Properties", onclick = function()
      local st = fs.stat(ic.path) or {}
      local req = kernel.requirements(dir)
      local lines = { ic.path, (st.size or 0) .. " bytes" }
      if req then lines[#lines + 1] = "asks for " .. (req.mem and (req.mem .. " MB ") or "") .. (req.w and (req.w .. "x" .. req.h) or "") end
      lines[#lines + 1] = kernel.unmet(req) or "runs on this computer"
      win.info("Properties", lines)
    end }
  else
    local app = kernel.apps[ic.id] or {}
    items[#items + 1] = { text = "Properties", onclick = function()
      win.info("Properties", { app.name or ic.id, "Built in", "/rom/apps/" .. tostring(ic.id) .. ".lua" })
    end }
  end
  return win.menu(px, py, items)
end

--- Right-click on a title bar or a taskbar button: the window's own menu.
function kernel.windowMenu(wd, px, py)
  return win.menu(px, py, {
    { text = wd.minimized and "Restore" or "Minimise", disabled = wd.fullscreen == true,
      onclick = function() if wd.minimized then kernel.focus(wd) else kernel.minimize(wd) end end },
    { text = wd.maximized and "Restore size" or "Maximise", disabled = wd.fullscreen == true or wd.resizable == false,
      onclick = function() kernel.focus(wd) kernel.maximize(wd) end },
    { sep = true },
    { text = "Close", onclick = function() kernel.close(wd) end },
  })
end

---------------------------------------------------------------------------------------------------- drawing
-- The arrow: X = outline (black), . = fill (white), space = transparent. 9 wide, 14 high, hot spot top-left. It is
-- a **hardware cursor** (U1.3): sent to the client once as a sprite and drawn there at the pointer, so a hover
-- costs the machine nothing but a position; a full-screen program hides it unless its window says cursor = true.
local CURSOR = { "X", "XX", "X.X", "X..X", "X...X", "X....X", "X.....X", "X......X", "X...XXXXX", "X..X.X", "X.X X.X", "XX  X.X", "X    X.X", "      XX" }

local function cursorSprite()
  local w, h = 9, #CURSOR
  local rows = {}
  for _, s in ipairs(CURSOR) do
    local r = {}
    for col = 1, w do
      local c = s:sub(col, col)
      r[col] = string.char(c == "X" and 0 or c == "." and 7 or 255)
    end
    rows[#rows + 1] = table.concat(r)
  end
  return w, h, table.concat(rows)
end

function kernel.showCursor(show)
  if kernel.cursorShown ~= show then
    kernel.cursorShown = show
    gfx.cursor(kernel.pointer.x, kernel.pointer.y, show)
  end
end

-- ---------------------------------------------------------------------------------------------- wallpaper
-- The desktop background. Patterns are *drawn*, not loaded: a handful of primitives costs no asset files, no
-- licence questions and no memory, and it looks right at 256x256 and on a 1024x768 wall alike. A picture you
-- painted yourself is the one exception, and Paint already writes those.
kernel.wallpapers = { "plain", "grid", "stars", "weave", "bricks", "bands" }

-- Window accent: the title bar and the selection highlight, which are the two places the desktop shows a colour
-- of its own. Each entry carries its own text colour, because contrast is not guessable from the index -- white
-- on the yellow one is unreadable and black on the blue one is worse.
kernel.accents = {
  { name = "blue",   colour = 12, text = 7 },
  { name = "green",  colour = 11, text = 0 },
  { name = "red",    colour = 8,  text = 15 },
  { name = "amber",  colour = 10, text = 0 },
  { name = "violet", colour = 9,  text = 15 },
  { name = "teal",   colour = 14, text = 0 },
  { name = "slate",  colour = 5,  text = 15 },
  { name = "ash",    colour = 102, text = 15 },
}
kernel.accent = 1

-- Preferences live on the machine's OWN DISK, not in the desktop blob. The blob (state/kernel.dat, syscall 2)
-- is session state: a reboot deletes it on purpose so the machine comes up clean, which is right for window
-- positions and wrong for a wallpaper. A wallpaper is a preference and must outlive a reboot.
kernel.prefsPath = "/disk/desktop.json"

function kernel.savePrefs()
  pcall(function()
    fs.write(kernel.prefsPath, json.encode({ wallpaper = kernel.wallpaper, accent = kernel.accent,
      saver = kernel.saver, recent = kernel.recent, volume = kernel.volume, text = kernel.textSize }))
  end)
end

function kernel.loadPrefs()
  if not fs.exists(kernel.prefsPath) then return end
  local ok, t = pcall(function() return json.decode(fs.read(kernel.prefsPath)) end)
  if not ok or type(t) ~= "table" then return end
  if type(t.wallpaper) == "table" then kernel.wallpaper = t.wallpaper end
  if t.text == "large" or t.text == "small" then
    kernel.textSize = t.text
    kernel.layout() -- the font is chosen in layout, and layout already ran before the disk was mounted
  end
  if tonumber(t.accent) then kernel.setAccent(tonumber(t.accent)) end
  -- everything below is checked rather than trusted: desktop.json is an ordinary file on an ordinary disk, and
  -- a hand-edited one must not be able to stop the machine booting
  if type(t.saver) == "table" then
    for _, k in ipairs(kernel.savers) do if t.saver.kind == k then kernel.saver.kind = k end end
    local secs = tonumber(t.saver.timeout)
    if secs and secs >= 0 then kernel.saver.timeout = math.min(secs, 3600) end
  end
  if type(t.recent) == "table" then
    kernel.recent = {}
    for _, path in ipairs(t.recent) do
      if type(path) == "string" and #kernel.recent < kernel.MAX_RECENT then kernel.recent[#kernel.recent + 1] = path end
    end
  end
  local vol = tonumber(t.volume)
  if vol then kernel.setVolume(vol) end
end

--- Master volume as a preference (U6's Sound page). The chip takes 0..1; the desktop remembers it, because a
--- machine that came back from a reboot at full volume after you turned it down is a machine you turn off.
kernel.volume = 1
function kernel.setVolume(v)
  kernel.volume = math.max(0, math.min(1, tonumber(v) or 1))
  pcall(snd.master, kernel.volume)
  return kernel.volume
end

function kernel.setAccent(i)
  local a = kernel.accents[i]
  if not a then return end
  kernel.accent = i
  local T = win.theme
  T.title, T.titleText = a.colour, a.text
  T.sel, T.selText = a.colour, a.text
  kernel.dirty = true
end
-- Default to plain: the desktop looks exactly as it always did until somebody picks a pattern. "grid" was the
-- first default and it draws frameDark lines on the desk colour, which are so close together that the feature
-- looks broken rather than subtle.
kernel.wallpaper = { kind = "plain" }

-- A fixed-seed generator, so the stars are in the same place on every redraw. Seeding from the clock would make
-- the sky twitch every time a window moved.
local function seeded(seed)
  return function()
    seed = (seed * 1103515245 + 12345) % 2147483648
    return seed / 2147483648
  end
end

function kernel.drawWallpaper()
  local T = win.theme
  local wp = kernel.wallpaper or {}
  local w, h = kernel.w, kernel.h
  local base = tonumber(wp.colour) or T.desk
  local accent = tonumber(wp.accent) or T.frameDark
  gfx.clear(base)
  local kind = wp.kind or "plain"

  if kind == "sprite" and wp.sprite then
    local ok, spr = pcall(gfx.loadsprite, "/disk/sprites/" .. tostring(wp.sprite) .. ".spr")
    if ok and type(spr) == "table" and (spr.w or 0) > 0 and (spr.h or 0) > 0 then
      for y = 0, h - 1, spr.h do
        for x = 0, w - 1, spr.w do gfx.sprite(x, y, spr, 0) end
      end
    end
    return
  end

  if kind == "grid" then
    for x = 0, w, 16 do gfx.line(x, 0, x, h, accent) end
    for y = 0, h, 16 do gfx.line(0, y, w, y, accent) end
  elseif kind == "stars" then
    local rnd = seeded(20260827)
    for _ = 1, math.max(20, math.floor(w * h / 700)) do
      local x, y = math.floor(rnd() * w), math.floor(rnd() * h)
      gfx.pixel(x, y, rnd() > 0.8 and 255 or accent)
    end
  elseif kind == "weave" then
    for i = -h, w, 10 do
      gfx.line(i, 0, i + h, h, accent)
      gfx.line(i + h, 0, i, h, accent)
    end
  elseif kind == "bricks" then
    local bw, bh = 32, 12
    local row = 0
    for y = 0, h, bh do
      gfx.line(0, y, w, y, accent)
      local off = (row % 2 == 0) and 0 or math.floor(bw / 2)
      for x = off, w, bw do gfx.line(x, y, x, math.min(h, y + bh), accent) end
      row = row + 1
    end
  elseif kind == "bands" then
    -- The palette's 6x6x6 cube is index 16 + 36r + 6g + b, so a sky costs eight fills and no gradient code.
    local n = 8
    local bandH = math.ceil(h / n)
    for i = 0, n - 1 do
      local t = i / (n - 1)
      local r = math.floor(t * 2)
      local g = math.floor(t * 3)
      local b = math.floor(5 - t * 2)
      gfx.fill(0, i * bandH, w, bandH, 16 + 36 * r + 6 * g + b)
    end
  end
end

-- ---------------------------------------------------------------------------------------------- screensaver
-- 95's own, and it lands on machinery that already exists (§7g, the idle framebuffer park). Two rules keep it
-- from being a tax on every computer in the world:
--   * it runs only while somebody is **watching**. An unwatched machine has to go still so the host can park
--     its framebuffer, and a saver animating into an empty room would hold every idle computer awake for ever.
--   * it runs only over the desktop, never over a full-screen program, which is drawing its own frames.
-- Waking is a *dismissal*: the click or key that brings the desktop back is spent doing that and does not also
-- press whatever happened to be under the pointer.
kernel.savers = { "blank", "stars", "lines" }
kernel.saver = { kind = "stars", timeout = 300 } -- seconds of no input; 0 = never
kernel.saverOn = false
kernel.lastInput = 0

function kernel.wake()
  kernel.lastInput = now()
  if not kernel.saverOn then return false end
  kernel.saverOn, kernel.saverState = false, nil
  kernel.dirty = true
  return true
end

--- Start it now (the idle timeout, and Settings' Preview button).
function kernel.startSaver()
  kernel.saverOn, kernel.saverState, kernel.saverNext = true, nil, 0
  kernel.clockRect = nil -- or the next minute would paint the taskbar clock across it
  kernel.lastInput = now()
  kernel.dirty = true
end

--- Turn the saver on when the desktop has been idle long enough, and pace its frames while it is on. Called
--- from the loop whenever there is nothing else to do.
function kernel.tickSaver()
  local s = kernel.saver or {}
  if kernel.saverOn then
    if not kernel.watched then kernel.wake() return end -- somebody walked away: go still and let the host park
    if s.kind ~= "blank" and now() >= (kernel.saverNext or 0) then
      kernel.saverNext = now() + kernel.frameTime()
      kernel.dirty = true
    end
    return
  end
  if (s.timeout or 0) <= 0 or not kernel.watched or kernel.console then return end
  local top = kernel.top()
  if top and top.fullscreen then return end
  if now() - kernel.lastInput < s.timeout then return end
  kernel.startSaver()
end

function kernel.drawSaver()
  local w, h = kernel.w, kernel.h
  local kind = (kernel.saver or {}).kind or "blank"
  gfx.clear(0)
  if kind == "blank" then return end
  local st = kernel.saverState
  if kind == "stars" then
    -- a flight through a star field: each star is a point in a unit box that walks toward the eye and is
    -- respawned at the back when it passes it. Brightness is depth, which is the whole trick.
    local function born() return { x = math.random() * 2 - 1, y = math.random() * 2 - 1, z = 0.6 + math.random() * 0.4 } end
    if not st then
      st = { stars = {} }
      for i = 1, math.max(40, math.floor(w * h / 700)) do
        st.stars[i] = born()
        st.stars[i].z = math.random() * 0.9 + 0.1 -- the first frame is a field, not a wall arriving together
      end
      kernel.saverState = st
    end
    local cx, cy = w / 2, h / 2
    for i, p in ipairs(st.stars) do
      p.z = p.z - 0.012
      local sx = math.floor(cx + p.x / p.z * cx)
      local sy = math.floor(cy + p.y / p.z * cy)
      -- respawn as soon as it leaves the screen, not only when it reaches the eye: most of a unit box projects
      -- off the edges, and waiting for z to run out leaves the sky nearly empty
      if p.z <= 0.05 or sx < 0 or sy < 0 or sx >= w or sy >= h then
        st.stars[i] = born()
      else
        local shade = p.z > 0.55 and 5 or p.z > 0.28 and 7 or 15
        gfx.pixel(sx, sy, shade)
        if p.z < 0.2 then gfx.pixel(math.min(w - 1, sx + 1), sy, shade) end
      end
    end
  elseif kind == "lines" then
    -- a quadrilateral bouncing off the edges, with the last few frames still on screen behind it
    if not st then
      st = { pts = {}, trail = {} }
      for i = 1, 4 do
        st.pts[i] = { x = math.random(w - 1), y = math.random(h - 1),
          dx = (math.random() < 0.5 and -1 or 1) * (1 + math.random() * 2),
          dy = (math.random() < 0.5 and -1 or 1) * (1 + math.random() * 2) }
      end
      kernel.saverState = st
    end
    local frame = {}
    for i, p in ipairs(st.pts) do
      p.x, p.y = p.x + p.dx, p.y + p.dy
      if p.x < 0 then p.x, p.dx = 0, -p.dx elseif p.x > w - 1 then p.x, p.dx = w - 1, -p.dx end
      if p.y < 0 then p.y, p.dy = 0, -p.dy elseif p.y > h - 1 then p.y, p.dy = h - 1, -p.dy end
      frame[i] = { x = math.floor(p.x), y = math.floor(p.y) }
    end
    table.insert(st.trail, 1, frame)
    while #st.trail > 6 do table.remove(st.trail) end
    local shades = { 15, 12, 9, 11, 5, 8 }
    for age = #st.trail, 1, -1 do
      local f = st.trail[age]
      local c = shades[age] or 5
      for i = 1, 4 do
        local a, b = f[i], f[i % 4 + 1]
        gfx.line(a.x, a.y, b.x, b.y, c)
      end
    end
  end
end

--- The two colours desktop text has to be drawn in: the ink, and the shadow under it.
--- An icon label lands on the **wallpaper**, not on a panel, so it cannot take its colour from the window
--- accent: that one is picked to read against a title bar, and every accent whose text colour is black made the
--- labels black on a dark wallpaper -- invisible ([name], session 18). White over a black shadow, or black over a
--- white one, reads on a flat colour, on a pattern and on a painted picture alike.
function kernel.deskInk()
  local wp = kernel.wallpaper or {}
  local ok, rgb = pcall(gfx.palette, tonumber(wp.colour) or win.theme.desk)
  if not ok or type(rgb) ~= "number" then return 15, 0 end
  local r, g, b = math.floor(rgb / 65536) % 256, math.floor(rgb / 256) % 256, rgb % 256
  if (r * 299 + g * 587 + b * 114) / 1000 >= 140 then return 0, 15 end
  return 15, 0
end

function kernel.draw()
  local T = win.theme
  local w, h = kernel.w, kernel.h
  if kernel.saverOn then
    kernel.showCursor(false)
    kernel.drawSaver()
    return
  end
  local top = kernel.top()
  if top and top.fullscreen then
    top:draw(true)
    kernel.clockRect = nil
    kernel.showCursor(top.cursor == true)
    return
  end
  kernel.showCursor(true)
  if kernel.console then
    -- the shell's screen: windows only (the terminal is borderless and fills it), plus the toast
    gfx.clear(0)
    kernel.icons, kernel.taskButtons, kernel.appsButton, kernel.powerButton, kernel.clockRect = {}, {}, nil, nil, nil
    for i, wd in ipairs(kernel.windows) do wd:draw(i == #kernel.windows) end
    if kernel.toast and now() < kernel.toastUntil then
      -- at the top: the bottom line is the shell's prompt
      local tw = win.textw(kernel.toast) + 8
      gfx.fill(w - tw - 2, 2, tw, T.fh + 4, 0)
      gfx.text(w - tw + 2, 4, kernel.toast, 10, nil, T.font)
    end
    return
  end
  kernel.drawWallpaper()
  -- desktop icons: a grid down the left
  local cell = T.fh * 3 + 8
  local iw = math.max(cell, T.fw * 9)
  kernel.icons = {}
  local maxIcons = math.max(1, math.floor((h - kernel.taskbarH - 6) / cell))
  local shown = 0
  local ink, shadow = kernel.deskInk()
  local function icon(entry, glyph, name, colour)
    shown = shown + 1
    local ix, iy = 6, 6 + (shown - 1) * cell
    entry.x, entry.y, entry.w, entry.h = ix, iy, iw, cell - 2
    kernel.icons[shown] = entry
    win.bevel(ix + math.floor((iw - T.fh * 2) / 2), iy, T.fh * 2, T.fh * 2, T.frame, T.frameLight, T.frameDark)
    gfx.text(ix + math.floor((iw - T.fw) / 2), iy + math.floor(T.fh / 2), glyph, colour, nil, T.font)
    local label = win.fit(name, iw)
    local lx, ly = ix + math.floor((iw - win.textw(label)) / 2), iy + T.fh * 2 + 2
    gfx.text(lx + 1, ly + 1, label, shadow, nil, T.font)
    gfx.text(lx, ly, label, ink, nil, T.font)
  end
  -- a program on a disk gets an icon at the top of the column: put a CD in and its game is simply *there*
  for i, prog in ipairs(kernel.diskProgs or {}) do
    if i > kernel.MAX_DISK_ICONS or shown >= maxIcons then break end
    icon({ path = prog.path }, prog.name:sub(1, 1):upper(), prog.name, T.ok or T.accent)
  end
  for _, id in ipairs(kernel.order) do
    local app = kernel.apps[id]
    if not app.hidden and shown < maxIcons then
      icon({ id = id }, app.icon or app.name:sub(1, 1), app.name, T.accent)
    end
  end
  local act = kernel.active()
  for _, wd in ipairs(kernel.windows) do
    if not wd.minimized then wd:draw(wd == act) end
  end
  -- taskbar
  local ty = h - kernel.taskbarH
  win.bevel(0, ty, w, kernel.taskbarH, T.frame, T.frameLight, T.frameDark)
  local x = 3
  kernel.taskButtons = {}
  local clock = os.date()
  local clockW = win.textw(clock) + 6
  local pw = T.fw * 2 + 4
  local aw = T.fw * 4 + 6
  win.bevel(x, ty + 2, aw, kernel.taskbarH - 4, T.sel, T.frameLight, T.frameDark)
  gfx.text(x + 3, ty + 3, "Apps", T.selText, nil, T.font)
  kernel.appsButton = { x = x, y = ty + 2, w = aw, h = kernel.taskbarH - 4 }
  x = x + aw + 3
  -- a menu is not a task: it has no button, and it must not shrink everybody else's
  local tasks = {}
  for _, wd in ipairs(kernel.windows) do if not wd.popup then tasks[#tasks + 1] = wd end end
  for _, wd in ipairs(tasks) do
    local bw = math.min(T.fw * 10, math.floor((w - clockW - pw - aw - 15) / math.max(1, #tasks)))
    if x + bw > w - clockW - pw - 6 then break end
    local active = wd == act
    win.bevel(x, ty + 2, bw, kernel.taskbarH - 4, active and T.frameDark or T.button, active and T.frameDark or T.frameLight, active and T.frameLight or T.frameDark)
    -- a minimised window's button keeps its name but goes quiet: it is the only thing on screen that says the
    -- window still exists, so it has to read as "here, but not open"
    local fg = active and T.frameLight or (wd.minimized and T.disabled or T.buttonText)
    gfx.text(x + 3, ty + 3, win.fit(wd.title, bw - 6), fg, nil, T.font)
    kernel.taskButtons[#kernel.taskButtons + 1] = { x = x, y = ty + 2, w = bw, h = kernel.taskbarH - 4, window = wd }
    x = x + bw + 3
  end
  gfx.text(w - clockW - pw - 2, ty + 3, clock, T.text, nil, T.font)
  kernel.clockRect = { x = w - clockW - pw - 2, y = ty + 3, w = win.textw(clock), h = T.fh }
  kernel.clockText = clock
  -- power button
  win.bevel(w - pw - 2, ty + 2, pw, kernel.taskbarH - 4, T.warn, T.frameLight, T.frameDark)
  gfx.text(w - pw - 2 + math.floor((pw - T.fw) / 2), ty + 3, "O", T.titleText, nil, T.font)
  kernel.powerButton = { x = w - pw - 2, y = ty + 2, w = pw, h = kernel.taskbarH - 4 }
  -- toast
  if kernel.toast and now() < kernel.toastUntil then
    local tw = win.textw(kernel.toast) + 8
    gfx.fill(math.floor((w - tw) / 2), ty - T.fh - 8, tw, T.fh + 4, 0)
    gfx.text(math.floor((w - tw) / 2) + 4, ty - T.fh - 6, kernel.toast, 10, nil, T.font)
  end
end

--- The taskbar clock, once an in-game minute: redraw just its rectangle when nothing covers it (a full draw
--- otherwise). Called by the loop when it wakes; the wake itself comes from the timed wait below.
function kernel.tickClock()
  local r = kernel.clockRect
  if not r or kernel.dirty or not kernel.watched then return end
  local clock = os.date()
  if clock == kernel.clockText then return end
  local covered = false
  for _, wd in ipairs(kernel.windows) do
    if wd.x < r.x + r.w and wd.x + wd.w > r.x and wd.y < r.y + r.h and wd.y + wd.h > r.y then covered = true end
  end
  if covered then kernel.dirty = true return end
  gfx.text(r.x, r.y, clock, win.theme.text, win.theme.frame, win.theme.font) -- one primitive = one dirty rectangle
  kernel.clockText = clock
end

--- How long the kernel may sleep with nothing to do: until the clock's next in-game minute (1000/60 ticks =
--- 833 ms) while the desktop shows, or forever (nil = until an event) under a full-screen program.
function kernel.waitDelay()
  if kernel.saverOn then
    -- the saver draws itself; "blank" has nothing to animate, so it costs one frame and then nothing
    if (kernel.saver or {}).kind == "blank" then return nil end
    return math.max(1, math.ceil(((kernel.saverNext or 0) - now()) * 1000))
  end
  -- nobody is looking: sleep until something happens rather than waking for a clock no one can read
  if not kernel.clockRect or not kernel.watched then return nil end
  local perMinute = 1000 / 60
  local t = os.time() % 24000
  local nextMinute = (math.floor(t / perMinute) + 1) * perMinute
  return math.floor((nextMinute - t) * 50) + 30
end

---------------------------------------------------------------------------------------------------- input
local drag = nil
local resize = nil
local function inRect(r, x, y) return x >= r.x and y >= r.y and x < r.x + r.w and y < r.y + r.h end

local function pointerEvent(ev)
  local px, py = ev.x or 0, ev.y or 0
  local buttons = ev.buttons or 0
  local was = kernel.pointer.buttons
  kernel.pointer.x, kernel.pointer.y, kernel.pointer.buttons = px, py, buttons
  gfx.cursor(px, py, kernel.cursorShown ~= false)
  if kernel.wake() then return end -- the screensaver eats the input that dismissed it
  if buttons ~= 0 or was ~= 0 then kernel.invalidate() end -- a press, release or drag redraws; a hover moves only the cursor
  local pressed = buttons % 2 == 1 and was % 2 == 0
  local released = buttons % 2 == 0 and was % 2 == 1
  -- the right button, RFB bit 3 (LuaComputerBlockEntity passes the mask through). Nothing but the menus wants
  -- it, and a program that takes the screen gets the raw mask anyway.
  local rpressed = math.floor(buttons / 4) % 2 == 1 and math.floor(was / 4) % 2 == 0
  local top = kernel.top()
  if top and top.fullscreen then
    if top.onpointer then top.onpointer(top, px, py, buttons, pressed, released) end
    if top.program and top.program.pointer then top.program.pointer(px, py, buttons, pressed, released) end
    return
  end
  if drag then
    if buttons % 2 == 1 then
      drag.window.x = px - drag.dx
      drag.window.y = math.max(0, py - drag.dy)
    else drag = nil end
    return
  end
  if resize then
    if buttons % 2 == 1 then
      local wd = resize.window
      wd.maximized = nil
      local minW, minH = wd.minW or win.MIN_W(), wd.minH or win.MIN_H()
      local w = math.max(minW, math.min(px - wd.x + resize.dx, kernel.w - wd.x))
      local h = math.max(minH, math.min(py - wd.y + resize.dy, kernel.h - kernel.taskbarH - wd.y))
      if w ~= wd.w or h ~= wd.h then wd.w, wd.h = w, h wd:relayout() end
    else resize = nil end
    return
  end
  -- a menu follows the pointer with no button held; it is the only thing on the desktop that does, which is
  -- why a bare move costs nothing anywhere else
  local menus = kernel.menus()
  if #menus > 0 and buttons == 0 and not released then
    for i = #menus, 1, -1 do
      if menus[i]:hit(px, py) then menus[i]:hover(px, py) return end
    end
    return
  end
  if (pressed or rpressed) and #menus > 0 then
    -- a click anywhere else closes the menu and stops there, the way it does in the desktop this is copying:
    -- you dismiss a menu first and click the thing second
    local inside = false
    for _, m in ipairs(menus) do if m:hit(px, py) then inside = true end end
    if not inside then kernel.closeMenus() return end
  end
  if rpressed then
    for _, tb in ipairs(kernel.taskButtons or {}) do
      if inRect(tb, px, py) then kernel.windowMenu(tb.window, px, py - 2) return end
    end
    if kernel.appsButton and inRect(kernel.appsButton, px, py) then kernel.startMenu() return end
    for i = #kernel.windows, 1, -1 do
      local wd = kernel.windows[i]
      if not wd.minimized and wd:hit(px, py) then
        if wd:titleHit(px, py) then kernel.windowMenu(wd, px, py)
        else
          if wd ~= top then kernel.focus(wd) end
          wd:rightpress(px, py) -- an app with a context menu of its own (Files); nothing happens otherwise
        end
        return
      end
      if wd.modal then return end
    end
    for _, ic in ipairs(kernel.icons or {}) do
      if inRect(ic, px, py) then kernel.iconMenu(ic, px, py) return end
    end
    if py < kernel.h - kernel.taskbarH then kernel.desktopMenu(px, py) end
    return
  end
  if pressed then
    -- taskbar / power / icons first
    if kernel.powerButton and inRect(kernel.powerButton, px, py) then
      win.ask("Power", "Shut down this computer?", { "Shut down", "Reboot", "Cancel" }, function(b)
        if b == "Shut down" then kernel.save() os.shutdown() elseif b == "Reboot" then kernel.save() os.reboot() end
      end)
      return
    end
    if kernel.appsButton and inRect(kernel.appsButton, px, py) then
      kernel.startMenu()
      return
    end
    for _, tb in ipairs(kernel.taskButtons or {}) do
      -- the 95 rule: the button of the window you are already in minimises it, so one button does both
      if inRect(tb, px, py) then
        if tb.window == kernel.active() and not tb.window.minimized then kernel.minimize(tb.window)
        else kernel.focus(tb.window) end
        return
      end
    end
    for i = #kernel.windows, 1, -1 do
      local wd = kernel.windows[i]
      if not wd.minimized and wd:hit(px, py) then
        if wd ~= top then kernel.focus(wd) end
        local box = wd:titleButton(px, py)
        if box == "close" then kernel.close(wd)
        elseif box == "max" then kernel.maximize(wd)
        elseif box == "min" then kernel.minimize(wd)
        elseif wd:titleHit(px, py) then
          if now() - (wd.titlePressAt or -1) < 0.4 then wd.titlePressAt = nil kernel.maximize(wd)
          else wd.titlePressAt = now() drag = { window = wd, dx = px - wd.x, dy = py - wd.y } end
        elseif wd:gripHit(px, py) then resize = { window = wd, dx = wd.x + wd.w - px, dy = wd.y + wd.h - py }
        else wd:press(px, py, 1) end
        return
      end
      if wd.modal then return end
    end
    for _, ic in ipairs(kernel.icons or {}) do
      if inRect(ic, px, py) then
        if ic.path then kernel.openDocument(ic.path)
        else
          local existing = kernel.find(ic.id)
          if existing then kernel.focus(existing) else kernel.open(ic.id) end
        end
        return
      end
    end
  elseif released then
    if top and top.pressed then top:release(px, py, 1) end
  elseif buttons % 2 == 1 and top then
    top:drag(px, py)
  end
end

local function keyEvent(ev)
  local code, down = ev.code, ev.down
  local waking = kernel.wake()
  if code == KEY.ctrl then kernel.mods.ctrl = down return end
  if code == KEY.lshift or code == KEY.rshift then kernel.mods.shift = down return end
  if waking then return end
  local top = kernel.top()
  if top and top.program and top.program.key then top.program.key(code, down, kernel.mods) end
  if top and top:key(code, down, kernel.mods) then return end
  if down and kernel.mods.ctrl and code == KEY.q and top and not top.modal then kernel.close(top) end
end

---------------------------------------------------------------------------------------------------- persistence (the floor, §2)
--- The most a single program may keep across a freeze. The host caps the whole blob at 256 KB and a save that
--- goes over is a save that fails, which would cost the desktop its windows too -- so one greedy program is
--- refused here, loudly, rather than taking everything else with it.
kernel.MAX_PROGRAM_STATE = 32 * 1024

function kernel.save()
  local saved = { windows = {}, programs = {}, log = {} }
  kernel.closeMenus()
  for _, wd in ipairs(kernel.windows) do
    if wd.app and not wd.fullscreen then
      local app = kernel.apps[wd.app]
      local st = app and app.save and app.save(wd) or nil
      saved.windows[#saved.windows + 1] = { app = wd.app, x = wd.x, y = wd.y, w = wd.w, h = wd.h,
        minimized = wd.minimized or nil, state = st }
    elseif wd.programFile and wd.programFile.save then
      -- §9 U12: a running program that says how to keep itself. Its own error is its own problem: the machine
      -- is being frozen and everything else in this loop still has to be written.
      local prog = wd.programFile
      local ok, st = pcall(prog.save)
      if not ok then
        kernel.say("save failed in " .. fs.basename(prog.path) .. ": " .. tostring(st))
      elseif st ~= nil then
        local eok, blob = pcall(json.encode, st)
        if not eok then
          kernel.say("save in " .. fs.basename(prog.path) .. " returned something unsaveable: " .. tostring(blob))
        elseif #blob > kernel.MAX_PROGRAM_STATE then
          kernel.say(fs.basename(prog.path) .. " wanted to keep " .. math.floor(#blob / 1024)
            .. " KB, the limit is " .. math.floor(kernel.MAX_PROGRAM_STATE / 1024) .. " KB: dropped")
        else
          saved.programs[#saved.programs + 1] = { path = prog.path, args = prog.args, version = prog.version or 0, state = st }
        end
      end
    end
  end
  for i = math.max(1, #kernel.log - 30), #kernel.log do saved.log[#saved.log + 1] = kernel.log[i] end
  local ok, err = pcall(state.save, saved)
  if not ok then
    -- over the host's 256 KB, or something in a window's state that will not encode. Losing the desktop is bad;
    -- losing the desktop *silently* is worse, and a machine that cannot save at all must still shut down.
    kernel.say("could not save the desktop: " .. tostring(err))
  end
end

--- Which program paths came back from a freeze, so autostart does not start a second copy of itself (§9 U12).
kernel.restoredPrograms = {}

local function restore()
  local saved = state.load()
  if not saved then return false end
  for _, l in ipairs(saved.log or {}) do kernel.log[#kernel.log + 1] = l end
  for _, s in ipairs(saved.windows or {}) do
    local wd = kernel.open(s.app, { restore = s.state })
    if wd then
      wd.x, wd.y, wd.w, wd.h = s.x, s.y, s.w, s.h
      wd:relayout()
      if s.minimized then kernel.minimize(wd) end
    end
  end
  -- §9 U12: and the full-screen programs that said how to keep themselves. After the windows, so a restored
  -- program is on top exactly as it was, and only if its file is still there -- a CD can be ejected while the
  -- machine is frozen, and a missing file must be a line in the log, not a dialog on a machine waking up.
  for _, s in ipairs(saved.programs or {}) do
    if type(s.path) == "string" and fs.exists(s.path) then
      kernel.restoredPrograms[s.path] = true
      local ok, err = pcall(kernel.runfile, s.path, s.args, s)
      if ok then kernel.say("restored " .. s.path) else kernel.say("could not restore " .. s.path .. ": " .. tostring(err)) end
    else
      kernel.say("cannot restore " .. tostring(s.path) .. ": it is gone")
    end
  end
  -- the geometry comes back verbatim, so it may be from another screen -- or from a boot with no monitor at
  -- all, where it went negative. Lay the desktop out again over the restored windows, never under them.
  kernel.layout()
  return #(saved.windows or {}) > 0 or #(saved.programs or {}) > 0
end

---------------------------------------------------------------------------------------------------- events
local handlers = {}
handlers.pointer = pointerEvent
handlers.scancode = keyEvent
handlers.char = function(ev)
  if kernel.wake() then return end
  local top = kernel.top()
  if top and top.program and top.program.char then top.program.char(ev.cp) end
  if top then top:char(ev.cp) end
end
handlers.wheel = function(ev)
  if kernel.wake() then return end
  local top = kernel.top()
  if top then top:wheel(ev.dy or 0, ev.x or kernel.pointer.x, ev.y or kernel.pointer.y) end
end
handlers.paste = function(ev)
  if kernel.wake() then return end
  local top = kernel.top()
  if top and type(ev.text) == "string" then top:paste(ev.text) end
end
handlers.screen = function(ev) kernel.layout() end
handlers.save = function(ev) kernel.save() kernel.saved = true end
-- Thawed. frozen_for_ticks is the longer of the world's clock and the wall clock, so a machine that slept
-- through a server stop is told how long it really was, not the zero the world's clock would report.
handlers.resume = function(ev)
  local t = tonumber(ev.frozen_for_ticks) or 0
  local how = t < 1200 and (math.floor(t / 20) .. " s")
    or t < 72000 and (math.floor(t / 1200) .. " min")
    or (math.floor(t / 72000) .. " h")
  kernel.notify("Resumed after " .. how .. (ev.reason == "stop" and " (server was down)" or ""), 4)
end
handlers.exec = function(ev)
  if type(ev.code) == "string" then
    local t = kernel.find("terminal") or kernel.open("terminal")
    if t and t.exec then t.exec(ev.code) end
  end
end
handlers.shell = function(ev) -- a shell line for the Terminal (the harness and /vmc computer event … shell {"line":…})
  if type(ev.line) == "string" then
    local t = kernel.find("terminal") or kernel.open("terminal")
    if t and t.shell then t.shell(ev.line) end
  end
end
handlers.key = function(ev) end -- the old keysym path: ignored, the view sends scancodes + chars

local function busEvent(ev)
  for _, wd in ipairs(kernel.windows) do if wd.onbus then pcall(wd.onbus, wd, ev) end end
  for _, p in ipairs(kernel.programs) do if p.onbus then pcall(p.onbus, ev) end end
end

-- A disk went in or out: the icon column and the launcher follow it, and the toast says what is on it, so
-- "insert a CD and its game appears" is literally what happens (ROADMAP §9 U3, program distribution).
local function diskEvent(ev)
  local before = {}
  for _, p in ipairs(kernel.diskProgs or {}) do before[p.path] = true end
  kernel.refreshDisks()
  if ev.name == "disk_inserted" then
    local fresh = {}
    for _, p in ipairs(kernel.diskProgs) do if not before[p.path] then fresh[#fresh + 1] = p.name end end
    local what = ev.description or ev.kind or "Disk"
    if #fresh > 0 then kernel.notify(what .. ": " .. table.concat(fresh, ", ", 1, math.min(3, #fresh)), 5)
    else kernel.notify(what .. " inserted", 3) end
  else
    kernel.notify("Disk ejected", 2)
  end
  busEvent(ev)
end
handlers.disk_inserted = diskEvent
handlers.disk_ejected = diskEvent

-- Somebody started or stopped looking at one of our monitors. While unwatched the desktop stops repainting its
-- clock, so the host can hand the framebuffer back; the first viewer gets a full repaint.
handlers.viewers = function(ev)
  local watched = (tonumber(ev.n) or 0) > 0
  if watched ~= kernel.watched then
    kernel.watched = watched
    if watched then
      kernel.dirty = true
      -- arriving at a machine is not the same as having sat in front of it doing nothing: you get the desktop,
      -- and the screensaver only after you have stood there for the whole timeout
      kernel.lastInput = now()
    end
  end
  busEvent(ev) -- a program may want to pause itself while nobody can see it
end

-- Redstone sleep asked the machine to stop (the wake threshold fell back below itself): save and shut down
-- cleanly. The host pulls the plug 5 s later if we do not, so there is time for a toast and a state write.
handlers.power = function(ev)
  kernel.notify("Powering off (" .. tostring(ev.reason or "host") .. ")", 3)
  kernel.draw()
  busEvent(ev)
  kernel.save()
  os.shutdown()
end

---------------------------------------------------------------------------------------------------- boot
-- the case (ROADMAP §9 U3b): a Basic Computer boots into the shell unless Settings says desktop; os.info() has the rest
kernel.info = os.info()
kernel.textSize = "auto"
kernel.console = not kernel.info.desktop
kernel.layout()
kernel.pointer.x, kernel.pointer.y = math.floor(kernel.w / 2), math.floor(kernel.h / 2)
do
  local cw, ch, cdata = cursorSprite()
  gfx.cursorshape(cw, ch, 0, 0, cdata, 255)
  kernel.cursorShown = true
  gfx.cursor(kernel.pointer.x, kernel.pointer.y, true)
end
kernel.refreshDisks()
kernel.lastInput = now() -- or a machine whose first event is a viewer would be five minutes idle from cold
kernel.loadPrefs() -- after refreshDisks: /disk has to be mounted before we can read it
local restored = restore()
if not restored then kernel.open("terminal") end
if kernel.console and not kernel.find("terminal") then kernel.open("terminal") end
kernel.notify("Welcome to " .. os.label(), 4)
if not restored then kernel.say("kernel up: " .. kernel.w .. "x" .. kernel.h) end

--- **Autostart** (U9/U8, session 21). `kernel.save` keeps *windows*, because a window is a position and a
--- little state; it cannot keep a running program, because a program is a coroutine and a coroutine cannot be
--- written to a file. That was fine until a machine was expected to serve something while nobody was standing
--- next to it: U9 wakes a frozen machine and delivers the message, and the machine came back with a tidy
--- desktop and nothing listening.
---
--- So a machine may say what it runs at boot, the way every real server does. `/disk/autostart.lua` is started
--- after the desktop is restored -- on a cold boot, after a freeze, and after a chunk reload alike, because all
--- three end up here. It is one file and one line, and it is the difference between "a computer that answers"
--- and "a computer that answered until you walked away".
-- §9 U12 composes with it rather than replacing it: if autostart's own program came back from the freeze with
-- its state, starting it again would be a second copy of the same thing.
if fs.exists("/disk/autostart.lua") and not kernel.restoredPrograms["/disk/autostart.lua"] then
  local ok, err = pcall(function() return kernel.runfile("/disk/autostart.lua") end)
  if ok then
    kernel.say("autostart: /disk/autostart.lua")
  else
    kernel.notify("autostart failed: " .. tostring(err), 6)
  end
end

-- One turn of the kernel: an event if there is one, else due programs, the clock, a frame or a wait. Run under
-- pcall so an error raised *into* the kernel — the host's asynchronous "not enough memory", which lands at whatever
-- safe point comes next, often the kernel's own yield — costs a notification, not the desktop.
local function iteration()
  local e = vmc.event_next()
  if e then
    local ok, ev = pcall(json.decode, e)
    if ok and type(ev) == "table" then
      local h = handlers[ev.name]
      local hok, err = pcall(h or busEvent, ev)
      if not hok then kernel.say("event " .. tostring(ev.name) .. ": " .. tostring(err)) end
      if kernel.saved then kernel.saved = nil coroutine.yield("saved") end
    end
  else
    -- no events: run programs that are due, draw if anything changed (the host sends the frame at once), then
    -- wait — until the earliest program wake, the toast's end or the clock's next minute, or for an event
    local t = now()
    local nextWake = math.huge
    for _, p in ipairs({ table.unpack(kernel.programs) }) do
      if p.alive then
        if p.wake > 0 and t >= p.wake then p.wake = 0 kernel.resume(p) end
        if p.wake > 0 then nextWake = math.min(nextWake, p.wake) end
      end
    end
    if kernel.toast then
      if t < kernel.toastUntil then nextWake = math.min(nextWake, kernel.toastUntil) else kernel.toast = nil kernel.dirty = true end
    end
    kernel.tickSaver()
    kernel.tickClock()
    if kernel.dirty then
      kernel.dirty = false
      local ok, err = pcall(kernel.draw)
      if not ok then vmc.log(3, "draw: " .. tostring(err)) end
      coroutine.yield("flip")
    else
      local delay = kernel.waitDelay()
      if nextWake < math.huge then
        local d = math.max(1, math.ceil((nextWake - now()) * 1000))
        delay = delay and math.min(delay, d) or d
      end
      coroutine.yield("wait", delay) -- an event wakes it sooner; nil = until an event
    end
  end
end

while true do
  local ok, err = pcall(iteration)
  if not ok then
    err = tostring(err)
    vmc.log(3, "kernel: " .. err)
    kernel.notify(err:find("not enough memory", 1, true) and "Not enough memory - close a program" or ("kernel: " .. err), 6)
    kernel.dirty = true
  end
end
