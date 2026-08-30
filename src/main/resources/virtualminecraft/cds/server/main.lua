-- Server: serves pages to other computers over `net`. Run it, leave it running, and any machine within reach
-- can read what is on this one's disk.
--
-- Not HTTP, on purpose. There is no other browser to be compatible with and no site out there to visit, so the
-- only thing real standards would buy is a parser far bigger than this machine deserves. What is wanted is two
-- computers in a world being useful to each other, and that fits in a hundred lines.
--
-- The protocol, in full:
--   ask:    {p="vw1", op="get",  path="/index"}
--   answer: {p="vw1", op="page", path="/index", status=200, body="..."}
-- One page is one net message, and net messages are capped at 4 KB, so a page is capped too — which is no
-- accident: a page should be a screenful, and the cap enforces what the design wanted anyway.
--
-- **Files (U8, session 21).** A page is a screenful; a *file* is a program someone can run. This is what
-- replaces the dropped Store (ROADMAP §9 U7): software still has to be found on a disk somewhere, but once one
-- player has it, it spreads to everyone else's machines.
--   ask:    {p="vw1", op="stat",  file="hello.lua"}
--   answer: {p="vw1", op="statr", file="hello.lua", size=2310, status=200}
--   ask:    {p="vw1", op="fetch", file="hello.lua", at=0}
--   answer: {p="vw1", op="chunk", file="hello.lua", at=0, data="...", size=2310, last=false, status=200}
-- The 4 KB cap is on the *encoded* message, and escaping is not a fixed multiplier — a file full of quotes
-- encodes far larger than one full of letters — so a chunk is sized by measuring: take a bite, encode it, and
-- shrink until it fits. Chunks are therefore not a fixed size and nothing may assume they are; `at` says where
-- each one belongs and `last` ends the transfer.
local w, h = gfx.size()
local KEY = win.KEY
local KEY_L = 0x26
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local SITE = "/disk/site"
local FILES = "/disk/site/files"
--- Serving at boot (U9/U8, session 21). A machine that only serves while a program happens to be open is not a
--- server: the moment its chunk unloads it freezes, and a thaw brings back the desktop but not the program,
--- because a coroutine cannot be saved to a file. The kernel starts /disk/autostart.lua after every boot, so
--- **copying this program there is what makes a machine answer while nobody is standing next to it.** It is a
--- copy rather than a line pointing at /cd0, so it keeps working when the CD comes out.
local AUTOSTART = "/disk/autostart.lua"
local PROTO = "vw1"
local MAXBODY = 3400          -- the message cap is 4096 encoded; leave room for the envelope and escaping
local MAXMSG = 3900           -- what one encoded message may weigh, measured rather than guessed
local BITE = 2600             -- the first mouthful of a file; shrunk until the encoded message fits

local log, hits, served, refused = {}, {}, 0, 0
local address, myname = "?", "?"
local input, quit = {}, false

local function note(line)
  table.insert(log, 1, line)
  while #log > 14 do table.remove(log) end
  input.redraw = true
end

---------------------------------------------------------------------------------------------------- the site
-- A server with nothing to serve teaches nobody anything, so the first run writes a small site that explains
-- the markup by being written in it.
local DEFAULT_INDEX = [[
# Welcome

This page is being served by a computer
in a Minecraft world.

Edit it with:  edit /disk/site/index.page

* Pages live in /disk/site
* index.page is what you get by default
* a page must fit in one message (4 KB)

=> /about What this is
=> /markup How to write a page
=> /files Files you can copy
]]

local DEFAULT_ABOUT = [[
# About

This is a small web that only exists
between computers in one world. It does
not reach the real internet, and nothing
out there can reach it.

The whole protocol is two messages:
one asks for a path, one answers with a
page.

=> / Back
]]

local DEFAULT_MARKUP = [[
# Writing a page

A page is a text file. Each line starts
with a mark, or with nothing at all.

# is a heading
## is a smaller heading
* is a bullet
> is an indented note
--- is a line across the page

=> /path Label  is a link on this
machine. To point at another machine,
put its name first:

=> othername:/index Their front page

A link starting with file: is a download
rather than a page:

=> file:hello.lua Copy hello.lua

Files live in /disk/site/files and the
/files page lists them by itself.

Anything else is just text.

=> / Back
]]

local DEFAULT_HELLO = [[
-- hello.lua: the smallest program worth sending to somebody.
-- You got this over the little web, which means the machine that
-- served it is somewhere in this world with its own disk and its
-- own cables. Run it with:  start /disk/hello.lua
local w, h = gfx.size()
gfx.clear(16)
gfx.text(8, 8, "hello from another computer", 11, nil, 1)
gfx.text(8, 20, "press any key", 102, nil, 1)
gfx.present()
os.sleep(4000)
return "hello"
]]

local function ensureSite()
  if fs.exists(SITE .. "/index.page") then return end
  pcall(function()
    fs.mkdir(SITE)
    fs.write(SITE .. "/index.page", DEFAULT_INDEX)
    fs.write(SITE .. "/about.page", DEFAULT_ABOUT)
    fs.write(SITE .. "/markup.page", DEFAULT_MARKUP)
    fs.mkdir(FILES)
    fs.write(FILES .. "/hello.lua", DEFAULT_HELLO)
  end)
  note("wrote a starter site to " .. SITE)
end

---------------------------------------------------------------------------------------------------- files
-- The same dull rules as a page path, plus one dot for an extension. Nothing that could climb out of
-- /disk/site/files and start handing the rest of the machine to strangers.
local function fileNamed(name)
  local n = tostring(name or "")
  if not n:match("^[%w_%-]+%.?[%w_%-]*$") or n:find("%.%.") then return nil, "bad name" end
  local path = FILES .. "/" .. n
  if not fs.exists(path) then return nil, "no such file" end
  return path
end

local function fileList()
  local out = {}
  local ok, entries = pcall(function() return fs.list(FILES) end)
  if not ok or type(entries) ~= "table" then return out end
  for _, e in ipairs(entries) do
    local name = type(e) == "table" and (e.name or e[1]) or tostring(e)
    local dir = type(e) == "table" and e.dir
    if name and not dir then
      local st = fs.stat(FILES .. "/" .. name)
      out[#out + 1] = { name = tostring(name), size = (st and st.size) or 0 }
    end
  end
  table.sort(out, function(a, b) return a.name < b.name end)
  return out
end

--- The /files page, made from the directory rather than written by hand: a server's downloads should appear
--- because the file is there, not because somebody remembered to edit a page.
local function filesPage()
  local list = fileList()
  local out = { "# Files", "" }
  if #list == 0 then
    out[#out + 1] = "This machine is serving no files."
    out[#out + 1] = ""
    out[#out + 1] = "Put them in /disk/site/files and they"
    out[#out + 1] = "appear here by themselves."
  else
    out[#out + 1] = "Pick one to copy it to your own disk."
    out[#out + 1] = ""
    for _, f in ipairs(list) do
      out[#out + 1] = "=> file:" .. f.name .. " " .. f.name .. "  (" .. f.size .. " B)"
    end
  end
  out[#out + 1] = ""
  out[#out + 1] = "=> / Front page"
  return table.concat(out, "\n") .. "\n"
end

-- Paths are deliberately dull: letters, digits, dash and underscore, one level deep. No "..", no slashes in the
-- middle, nothing that could walk out of /disk/site and start serving the rest of the machine to strangers.
local function pageFor(path)
  local p = tostring(path or "/")
  if p == "" or p == "/" then p = "/index" end
  local name = p:match("^/([%w_%-]+)$")
  if not name then return nil, "bad path" end
  local file = SITE .. "/" .. name .. ".page"
  -- /files is generated unless someone wrote their own files.page, so it is a page like any other and the
  -- browser needs no idea that it is special.
  if name == "files" and not fs.exists(file) then return filesPage() end
  if not fs.exists(file) then return nil, "no such page" end
  local ok, body = pcall(function() return fs.read(file) end)
  if not ok or type(body) ~= "string" then return nil, "could not read it" end
  return body
end

local function reply(to, path, status, body)
  local msg = { p = PROTO, op = "page", path = path, status = status, body = body }
  local ok, err = pcall(bus.call, "net", "send", to, msg)
  if not ok then note("could not answer " .. tostring(to) .. ": " .. tostring(err)) end
  return ok
end

local function answer(to, msg)
  msg.p = PROTO
  local ok, err = pcall(bus.call, "net", "send", to, msg)
  if not ok then note("could not answer " .. tostring(to) .. ": " .. tostring(err)) end
  return ok
end

local function onStat(from, sender, m)
  local who = tostring(sender or from or "?")
  local path, why = fileNamed(m.file)
  if not path then
    note(who .. " stat " .. tostring(m.file) .. "  404 " .. tostring(why))
    answer(from, { op = "statr", file = tostring(m.file or ""), status = 404, why = tostring(why) })
    return
  end
  local st = fs.stat(path)
  local size = (st and st.size) or 0
  note(who .. " stat " .. tostring(m.file) .. "  " .. size .. " B")
  answer(from, { op = "statr", file = tostring(m.file), size = size, status = 200 })
end

--- One chunk, sized by measurement. Escaping is not a fixed multiplier, so the only honest way to fit a message
--- under the cap is to encode it and look.
local function onFetch(from, sender, m)
  local who = tostring(sender or from or "?")
  local path, why = fileNamed(m.file)
  if not path then
    answer(from, { op = "chunk", file = tostring(m.file or ""), status = 404, why = tostring(why) })
    return
  end
  local ok, data = pcall(function() return fs.read(path) end)
  if not ok or type(data) ~= "string" then
    answer(from, { op = "chunk", file = tostring(m.file), status = 500, why = "could not read it" })
    return
  end
  local at = math.max(0, math.floor(tonumber(m.at) or 0))
  if at >= #data then
    answer(from, { op = "chunk", file = tostring(m.file), at = #data, data = "", size = #data, last = true, status = 200 })
    return
  end
  local n = math.min(BITE, #data - at)
  local msg
  while true do
    msg = { p = PROTO, op = "chunk", file = tostring(m.file), at = at, data = data:sub(at + 1, at + n),
            size = #data, last = at + n >= #data, status = 200 }
    if #json.encode(msg) <= MAXMSG or n <= 32 then break end
    n = math.floor(n * 0.6)
  end
  if at == 0 then
    served = served + 1
    hits["file:" .. tostring(m.file)] = (hits["file:" .. tostring(m.file)] or 0) + 1
    note(who .. " gets " .. tostring(m.file) .. "  " .. #data .. " B")
  end
  answer(from, msg)
end

local function onRequest(from, sender, m)
  local path = tostring(m.path or "/")
  local who = tostring(sender or from or "?")
  local body, why = pageFor(path)
  if not body then
    refused = refused + 1
    note(who .. " -> " .. path .. "  404 " .. tostring(why))
    reply(from, path, 404, "# Not found\n\nThere is no page at " .. path .. " here.\n\n=> / Front page\n")
    return
  end
  if #body > MAXBODY then
    refused = refused + 1
    note(who .. " -> " .. path .. "  too big (" .. #body .. " B)")
    reply(from, path, 413, "# Too big\n\nThat page is " .. #body .. " bytes and a page must fit in "
      .. MAXBODY .. ".\n\n=> / Front page\n")
    return
  end
  served = served + 1
  hits[path] = (hits[path] or 0) + 1
  note(who .. " -> " .. path .. "  " .. #body .. " B")
  reply(from, path, 200, body)
end

---------------------------------------------------------------------------------------------------- drawing
local function draw()
  gfx.clear(16)
  gfx.fill(0, 0, w, 12, 0)
  gfx.text(2, 2, "server  " .. myname, 7, nil, 1)
  local right = served .. " served"
  gfx.text(w - #right * 6 - 2, 2, right, 11, nil, 1)

  gfx.text(2, 15, win.fit("address " .. address, w - 4), 6, nil, 1)
  gfx.text(2, 25, win.fit("serving " .. SITE .. "  (+" .. #fileList() .. " files)", w - 4), 6, nil, 1)

  gfx.line(2, 36, w - 3, 36, 102)
  local y = 40
  gfx.text(2, y, "requests", 10, nil, 1)
  y = y + 11
  for _, line in ipairs(log) do
    if y > h - 24 then break end
    gfx.text(2, y, win.fit(line, w - 4), 15, nil, 1)
    y = y + 9
  end
  if #log == 0 then
    gfx.text(2, y, "nothing yet - run the browser on", 102, nil, 1)
    gfx.text(2, y + 9, "another computer and point it here", 102, nil, 1)
  end

  gfx.fill(0, h - 11, w, 11, 0)
  local auto = fs.exists(AUTOSTART) and "A stops serving at boot" or "A serves at boot"
  gfx.text(2, h - 10, win.fit("L pages   " .. auto .. "   Q quits", w - 4), 6, nil, 1)
end

if me then
  -- The whole server is this: answer net_message events. It needs no window focus and no pointer, so it keeps
  -- working while you are using something else on the same machine.
  me.onbus = function(ev)
    if ev.name ~= "net_message" then return end
    local m = ev.message
    if type(m) ~= "table" or m.p ~= PROTO then return end
    -- our own answers come back too when a machine serves itself, so only the asking ops are handled here
    if m.op == "get" then onRequest(ev.from, ev.sender, m)
    elseif m.op == "stat" then onStat(ev.from, ev.sender, m)
    elseif m.op == "fetch" then onFetch(ev.from, ev.sender, m) end
  end
  me.key = function(code, down)
    if not down then return end
    if code == KEY.q or code == KEY.esc then input.quit = true
    elseif code == KEY_L then input.list = true
    elseif code == KEY.a then input.autostart = true end
    input.redraw = true
  end
end

do
  local ok, a = pcall(bus.call, "net", "address")
  address = ok and tostring(a) or "no net component on the bus"
  local ok2, list = pcall(bus.call, "net", "list")
  if ok2 and type(list) == "table" then
    note(#list .. " computer" .. (#list == 1 and "" or "s") .. " within reach")
  else
    note("no net component: nobody can reach this")
  end
  local ok3, info = pcall(os.info)
  myname = (ok3 and info and info.name) and tostring(info.name) or "this computer"
end
ensureSite()
draw()
gfx.present()
while not quit do
  if input.quit then input.quit = nil quit = true end
  if input.autostart then
    input.autostart = nil
    if fs.exists(AUTOSTART) then
      local ok = pcall(function() fs.remove(AUTOSTART) end)
      note(ok and "removed " .. AUTOSTART .. " - this machine no longer serves at boot" or "could not remove it")
    else
      local src = (PROGRAM_DIR or "/cd0") .. "/main.lua"
      local ok, err = pcall(function() fs.copy(src, AUTOSTART) end)
      if ok then
        note("serving at boot: copied to " .. AUTOSTART)
        note("it will start again after a freeze or a reload")
      else
        note("could not install autostart: " .. tostring(err))
      end
    end
    input.redraw = true
  end
  if input.list then
    input.list = nil
    local ok, entries = pcall(function() return fs.list(SITE) end)
    if ok and type(entries) == "table" then
      for _, e in ipairs(entries) do
        local name = type(e) == "table" and (e.name or e[1]) or tostring(e)
        local page = tostring(name):gsub("%.page$", "")
        note("/" .. page .. "   " .. (hits["/" .. page] or 0) .. " hits")
      end
    else
      note("could not list " .. SITE)
    end
  end
  if input.redraw then input.redraw = nil draw() end
  gfx.present()
end
return "Server: " .. served .. " pages served, " .. refused .. " refused"
