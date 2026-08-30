-- Browser: reads pages served by other computers over `net`.
--
-- It opens on the list of machines it can actually reach, because on a small web that list *is* the web — there
-- is no address to type until you know somebody's name, and "what can I see from here" is the first question
-- anyone has. Every peer is a link.
--
-- Pages are the line-based markup written up in the Server's own /markup page: a mark at the start of a line,
-- or nothing at all. No tags, because a tag parser would cost more than the whole rest of this program.
local w, h = gfx.size()
local KEY = win.KEY
local KEY_B, KEY_G, KEY_H = 0x30, 0x22, 0x23
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local PROTO = "vw1"
local BOOKMARKS = "/disk/bookmarks.json"
local TIMEOUT = 5
--- Waking a sleeping machine (U9) is a boot, not a round trip: the chunk loads, the block entity ticks, the
--- machine thaws, the kernel comes up and /disk/autostart.lua runs before the queued message can be delivered.
--- **Measured at 0.5 s** on an idle server (2026-08-29, cold, 500 cables away), so this is insurance for a busy
--- one that has to generate a chunk first -- not the usual case. It is deliberately not much longer than that:
--- when a woken machine answers nothing, the *common* reason is that it has no server running, and making
--- someone wait half a minute to be told so is worse than the wait being short.
local WAKE_TIMEOUT = 10
local MAXFILE = 64 * 1024     -- what this machine will take: the disk is 1 MB and the Lua heap is 4

local HEAD, FOOT = 22, 11
local BACK, TEXT, HEADING, LINK, LINKHOT, RULE, QUOTE = 16, 15, 11, 12, 14, 102, 10

local host, path = nil, "/"          -- nil host = the local "what can I see" page
local body, lines, links = "", {}, {}
local rows = {}                      -- `lines` wrapped to the screen: what is actually drawn and scrolled
local status, waiting, sentAt = "", false, 0
local patience = TIMEOUT              -- how long the current wait is allowed to take
local history, bookmarks = {}, {}
local scroll, hot = 0, nil
local typing, typed = false, ""
local input, quit = {}, false
--- A download in flight (U8). `offer` is a file whose size we know and whose transfer has not been agreed to
--- yet: the size is shown *before* anything is copied, because a machine with a 1 MB disk should never find out
--- how big a file was by running out of room half way through it.
local offer, dl = nil, nil

local function say(s) status = s input.redraw = true end

---------------------------------------------------------------------------------------------------- parsing
-- One pass, line by line. Each line becomes {kind, text, target} and links also go in `links` so they can be
-- numbered for the keyboard and hit-tested for the pointer.
--- Parsed lines are *logical*; what is drawn is `rows`, one per line of screen ([name], session 18: "we also
--- need text wrap in the browser"). Everything used to go through `win.fit`, which cuts a long line off with a
--- `~` — fine for a status bar, useless for a page, since the whole point of a page is prose. Wrapping happens
--- once here rather than in `draw`, so scrolling, hit-testing and the scrollbar all count the same rows.
---
--- A continuation row keeps its line's kind (so a wrapped link is still a link, and still clickable along its
--- whole length) and is indented under the marker rather than back to the margin: a bullet whose second line
--- starts under the dash reads as a new bullet.
local function layout()
  rows = {}
  local cols = math.max(4, math.floor((w - 4) / 6))
  for _, l in ipairs(lines) do
    if l.kind == "rule" then
      rows[#rows + 1] = { kind = "rule" }
    elseif l.kind == "link" then
      local prefix = "[" .. l.link .. "] "
      local pad = string.rep(" ", #prefix)
      local first = true
      for _, t in ipairs(win.wrap(l.text, cols - #prefix)) do
        rows[#rows + 1] = { kind = "link", text = t, link = l.link, prefix = first and prefix or pad, first = first }
        first = false
      end
    elseif l.kind == "bullet" then
      local first = true
      for _, t in ipairs(win.wrap(l.text, cols - 2)) do
        rows[#rows + 1] = { kind = "bullet", text = t, prefix = first and "- " or "  " }
        first = false
      end
    elseif l.kind == "quote" then
      for _, t in ipairs(win.wrap(l.text, math.max(4, math.floor((w - 12) / 6)))) do
        rows[#rows + 1] = { kind = "quote", text = t }
      end
    else
      for _, t in ipairs(win.wrap(l.text, cols)) do
        rows[#rows + 1] = { kind = l.kind, text = t }
      end
    end
  end
end

local function parse(src)
  lines, links = {}, {}
  for raw in (tostring(src) .. "\n"):gmatch("([^\n]*)\n") do
    local line = raw:gsub("%s+$", "")
    local kind, text, target = "text", line, nil
    if line:sub(1, 3) == "===" or line:sub(1, 3) == "---" then
      kind, text = "rule", ""
    elseif line:sub(1, 3) == "=> " then
      local dest, label = line:sub(4):match("^(%S+)%s*(.*)$")
      if dest then
        kind, target = "link", dest
        text = (label ~= "" and label) or dest
        links[#links + 1] = { target = dest, label = text, line = #lines + 1 }
      end
    elseif line:sub(1, 2) == "##" then
      kind, text = "h2", line:sub(3):gsub("^%s+", "")
    elseif line:sub(1, 1) == "#" then
      kind, text = "h1", line:sub(2):gsub("^%s+", "")
    elseif line:sub(1, 2) == "* " then
      kind, text = "bullet", line:sub(3)
    elseif line:sub(1, 2) == "> " then
      kind, text = "quote", line:sub(3)
    end
    lines[#lines + 1] = { kind = kind, text = text, target = target, link = #links }
  end
  layout()
  scroll, hot = 0, nil
end

---------------------------------------------------------------------------------------------------- the wire
local function peers()
  local ok, list = pcall(bus.call, "net", "list")
  if not ok or type(list) ~= "table" then return nil end
  return list
end

-- The front page: everything within reach, as links. Built locally, never fetched.
local function localPage()
  local out = { "# Nearby computers", "" }
  local list = peers()
  if not list then
    out[#out + 1] = "This computer has no net component,"
    out[#out + 1] = "so it cannot reach anything."
    out[#out + 1] = ""
    out[#out + 1] = "Put a modem on it, or run a cable"
    out[#out + 1] = "to another computer."
  elseif #list == 0 then
    out[#out + 1] = "Nothing within reach."
    out[#out + 1] = ""
    out[#out + 1] = "A cable between two computers, or a"
    out[#out + 1] = "modem on each, and they find each"
    out[#out + 1] = "other."
  else
    -- **Link by address, label by name.** Every machine is called "computer" until somebody labels it, so a
    -- link built from the name pointed at whichever one came first in the list -- fine with two machines,
    -- silently wrong with three (2026-08-29: a browser timed out while the server it meant to ask had just
    -- answered somebody else). The address is a UUID and unique; the reader never sees it, only the label.
    local seenName = {}
    for _, peer in ipairs(list) do
      if not peer.note then
        local n = tostring(peer.name or "?")
        seenName[n] = (seenName[n] or 0) + 1
      end
    end
    for _, peer in ipairs(list) do
      if not peer.note then
        local name = tostring(peer.name or peer.address or "?")
        local addr = tostring(peer.address or name)
        -- when two machines share a name, show enough of the address to tell them apart
        local label = name
        if (seenName[name] or 0) > 1 then label = name .. " " .. addr:sub(1, 4) end
        if peer.loaded == false then label = label .. " (asleep)" end
        out[#out + 1] = "=> " .. addr .. ":/ " .. label .. "  (" .. tostring(peer.location or "?") .. ")"
      end
    end
  end
  if #bookmarks > 0 then
    out[#out + 1] = ""
    out[#out + 1] = "## Bookmarks"
    for _, b in ipairs(bookmarks) do
      out[#out + 1] = "=> " .. b .. " " .. b
    end
  end
  out[#out + 1] = ""
  out[#out + 1] = "G types an address, H explains the"
  out[#out + 1] = "keys."
  return table.concat(out, "\n")
end

local function showLocal()
  host, path = nil, "/"
  parse(localPage())
  say("nearby")
end

---------------------------------------------------------------------------------------------------- files
local function human(n)
  if n < 1024 then return n .. " B" end
  return string.format("%.1f KB", n / 1024)
end

--- Is this peer asleep? `net.list` says so per peer since U9; anything we cannot find, we treat as awake, so a
--- machine reached some other way is never given the long timeout by accident.
local function asleep(who)
  local list = peers()
  if not list then return false end
  for _, p in ipairs(list) do
    if not p.note and (tostring(p.address) == who or tostring(p.name) == who) then
      return p.loaded == false, tostring(p.name or who)
    end
  end
  return false
end

--- How long to wait, and what to say while waiting. Kept in one place because three call sites ask.
local function beginWait(who, what)
  local sleeping, name = asleep(who)
  waiting, sentAt = true, os.clock()
  patience = sleeping and WAKE_TIMEOUT or TIMEOUT
  if sleeping then
    say("waking " .. (name or who) .. " - this takes a moment")
  else
    say(what)
  end
end

local function askStat(name)
  if not host then say("no machine to ask") return end
  local ok, err = pcall(bus.call, "net", "send", host, { p = PROTO, op = "stat", file = name })
  if not ok then say("could not ask: " .. tostring(err)) return end
  beginWait(host, "asking about " .. name)
end

local function askChunk()
  if not dl then return end
  local ok, err = pcall(bus.call, "net", "send", dl.host, { p = PROTO, op = "fetch", file = dl.name, at = dl.at })
  if not ok then
    say("transfer failed: " .. tostring(err))
    dl = nil
    return
  end
  -- mid-transfer the machine is awake by definition, so the short timeout is the right one here
  waiting, sentAt, patience = true, os.clock(), TIMEOUT
end

local function startDownload()
  if not offer then return end
  dl = { host = host, name = offer.name, size = offer.size, at = 0, parts = {} }
  offer = nil
  askChunk()
end

--- Written once, at the end, rather than appended chunk by chunk: a half-written file that looks like a program
--- is worse than no file, and 64 KB of Lua string is cheaper than 30 partial writes to a quota'd disk.
local function finishDownload()
  local data = table.concat(dl.parts)
  local target = "/disk/" .. dl.name
  local ok, err = pcall(function() fs.write(target, data) end)
  if ok then
    say("saved " .. target .. "  (" .. human(#data) .. ")")
    parse("# Saved\n\n" .. target .. "\n" .. human(#data) .. "\n\n"
      .. (dl.name:match("%.lua$") and ("Run it with:\nstart " .. target .. "\n\n") or "")
      .. "=> /files More files\n=> / Front page\n")
  else
    say("could not save: " .. tostring(err))
    parse("# Could not save\n\n" .. tostring(err) .. "\n\n=> /files Back to the files\n")
  end
  dl = nil
end

local function request(toHost, toPath)
  local ok, err = pcall(bus.call, "net", "send", toHost, { p = PROTO, op = "get", path = toPath })
  if not ok then
    parse("# Could not ask\n\n" .. tostring(err) .. "\n\n=> nearby: Back to what is nearby\n")
    say("failed")
    return
  end
  beginWait(toHost, "asking " .. toHost .. " for " .. toPath)
end

-- An address is "name", "name:/path", or "/path" (meaning: on the machine we are already reading).
local function go(dest, remember)
  if dest == "nearby:" or dest == "nearby" then showLocal() return end
  if dest:sub(1, 5) == "file:" then
    -- a download, not a page: ask how big it is and let the reader decide
    askStat(dest:sub(6))
    return
  end
  local hostPart, pathPart = dest:match("^([^:/][^:]*):(/.*)$")
  if not hostPart then
    if dest:sub(1, 1) == "/" then
      hostPart, pathPart = host, dest
    else
      hostPart, pathPart = dest, "/"
    end
  end
  if not hostPart then say("no machine to ask - pick one from nearby") return end
  if remember ~= false then history[#history + 1] = { host = host, path = path } end
  host, path = hostPart, pathPart or "/"
  request(host, path)
end

local function back()
  local prev = table.remove(history)
  if not prev then showLocal() return end
  if not prev.host then showLocal() return end
  host, path = prev.host, prev.path
  request(host, path)
end

local function saveBookmarks()
  pcall(function() fs.write(BOOKMARKS, json.encode(bookmarks)) end)
end

local function loadBookmarks()
  if not fs.exists(BOOKMARKS) then return end
  local ok, t = pcall(function() return json.decode(fs.read(BOOKMARKS)) end)
  if ok and type(t) == "table" then bookmarks = t end
end

---------------------------------------------------------------------------------------------------- drawing
local function rowY(i) return HEAD + 2 + (i - 1 - scroll) * 9 end
local function visibleRows() return math.floor((h - HEAD - FOOT - 4) / 9) end

local function draw()
  gfx.clear(BACK)
  gfx.fill(0, 0, w, HEAD, 0)
  local where = host and (host .. path) or "nearby"
  gfx.text(2, 2, win.fit(typing and ("go: " .. typed .. "_") or where, w - 4), typing and 14 or 7, nil, 1)
  gfx.text(2, 12, win.fit(status, w - 4), waiting and 10 or 6, nil, 1)
  gfx.line(0, HEAD - 1, w, HEAD - 1, 102)

  local shownRows = visibleRows()
  for i = 1 + scroll, math.min(#rows, scroll + shownRows) do
    local l = rows[i]
    local y = rowY(i)
    if l.kind == "rule" then
      gfx.line(4, y + 4, w - 5, y + 4, RULE)
    elseif l.kind == "h1" then
      gfx.text(2, y, l.text, HEADING, nil, 1)
      gfx.line(2, y + 8, 2 + #l.text * 6, y + 8, HEADING)
    elseif l.kind == "h2" then
      gfx.text(2, y, l.text, HEADING, nil, 1)
    elseif l.kind == "bullet" then
      gfx.text(2, y, l.prefix .. l.text, TEXT, nil, 1)
    elseif l.kind == "quote" then
      gfx.text(8, y, l.text, QUOTE, nil, 1)
    elseif l.kind == "link" then
      local col = (hot == l.link) and LINKHOT or LINK
      gfx.text(2, y, l.prefix .. l.text, col, nil, 1)
      gfx.line(2 + #l.prefix * 6, y + 8, 2 + (#l.prefix + #l.text) * 6, y + 8, col)
    else
      gfx.text(2, y, l.text, TEXT, nil, 1)
    end
  end
  if #rows > shownRows then
    local frac = scroll / math.max(1, #rows - shownRows)
    local barY = HEAD + math.floor(frac * (h - HEAD - FOOT - 10))
    gfx.fill(w - 3, barY, 2, 10, 102)
  end

  -- A transfer takes the footer over: while a file is coming in, how far it has got is the only thing anyone
  -- wants the bottom of the screen to say.
  if dl then
    local total = math.max(1, dl.size or 1)
    local frac = math.min(1, dl.at / total)
    gfx.fill(0, h - FOOT, w, FOOT, 0)
    local barW = w - 4
    gfx.rect(2, h - FOOT + 2, barW, 7, 102)
    gfx.fill(3, h - FOOT + 3, math.max(0, math.floor((barW - 2) * frac)), 5, 11)
    local label = dl.name .. "  " .. human(dl.at) .. " / " .. human(dl.size or 0)
    gfx.text(4, h - FOOT + 2, win.fit(label, w - 8), 0, nil, 1)
  else
    gfx.fill(0, h - FOOT, w, FOOT, 0)
    local foot
    if offer then
      foot = offer.name .. "  " .. human(offer.size) .. "   Enter copies it, Esc leaves it"
    elseif typing then
      foot = "type an address, Enter goes, Esc cancels"
    else
      foot = "click a link  Bksp back  G go  B mark  H keys  Q quit"
    end
    gfx.text(2, h - FOOT + 1, win.fit(foot, w - 4), offer and 14 or 6, nil, 1)
  end
end

local HELP = [[
# Keys

* click a link, or press its number
* Backspace goes back
* G types an address
* B bookmarks this page
* R asks again
* arrows and page keys scroll
* Q quits

## Files

A link starting with file: is a copy, not
a page. Following one shows how big it is;
Enter copies it to /disk, Esc leaves it.
A .lua file is then a program you can run
with:  start /disk/name.lua

## Addresses

> name          that machine's front page
> name:/page    one page on it
> /page         a page on this machine

=> nearby: What is nearby
]]

---------------------------------------------------------------------------------------------------- input
local function linkAt(px, py)
  for i = 1 + scroll, math.min(#rows, scroll + visibleRows()) do
    local l = rows[i]
    if l.kind == "link" then
      local y = rowY(i)
      if py >= y and py < y + 9 then return l.link end
    end
  end
end

if me then
  me.window.cursor = true
  kernel.showCursor(true)
  me.pointer = function(px, py, b, pressed)
    local n = linkAt(px, py)
    if n ~= hot then hot = n input.redraw = true end
    if pressed and n and links[n] then input.follow = links[n].target end
  end
  me.char = function(cp)
    if not typing then return end
    local c = string.char(cp)
    if c:match("[%w%./:_%-]") then typed = typed .. c input.redraw = true end
  end
  me.key = function(code, down)
    if not down then return end
    if typing then
      if code == KEY.enter or code == KEY.kpenter then
        typing = false
        if typed ~= "" then input.follow = typed end
        typed = ""
      elseif code == KEY.esc then typing = false typed = ""
      elseif code == KEY.backspace then typed = typed:sub(1, -2) end
      input.redraw = true
      return
    end
    -- An offer or a transfer owns Enter and Esc while it is up: Esc must stop the copy, not quit the browser
    -- out from under it.
    if offer or dl then
      if code == KEY.enter or code == KEY.kpenter then input.accept = true return end
      if code == KEY.esc or code == KEY.q then input.cancel = true return end
    end
    if code == KEY.q or code == KEY.esc then input.quit = true
    elseif code == KEY.backspace then input.back = true
    elseif code == KEY_G then typing, typed = true, ""
    elseif code == KEY_B then input.bookmark = true
    elseif code == KEY.r then input.reload = true
    elseif code == KEY_H then parse(HELP) say("keys")
    elseif code == KEY.up then scroll = math.max(0, scroll - 1)
    elseif code == KEY.down then scroll = math.min(math.max(0, #rows - visibleRows()), scroll + 1)
    elseif code == KEY.pgup then scroll = math.max(0, scroll - visibleRows())
    elseif code == KEY.pgdn then scroll = math.min(math.max(0, #rows - visibleRows()), scroll + visibleRows())
    else
      -- A digit follows the link with that number, so the whole thing works without a pointer.
      local c = code - 0x01
      local digits = { [0x02] = 1, [0x03] = 2, [0x04] = 3, [0x05] = 4, [0x06] = 5,
                       [0x07] = 6, [0x08] = 7, [0x09] = 8, [0x0a] = 9 }
      local n = digits[code]
      if n and links[n] then input.follow = links[n].target end
    end
    input.redraw = true
  end
  me.onbus = function(ev)
    if ev.name ~= "net_message" then return end
    local m = ev.message
    if type(m) ~= "table" or m.p ~= PROTO then return end
    if m.op == "page" then
      waiting = false
      body = tostring(m.body or "")
      parse(body)
      local code = tonumber(m.status) or 0
      say(code == 200 and (#body .. " bytes from " .. tostring(host)) or ("status " .. code))
    elseif m.op == "statr" then
      waiting = false
      if tonumber(m.status) ~= 200 then
        say(tostring(m.file) .. ": " .. tostring(m.why or "refused"))
        return
      end
      local size = tonumber(m.size) or 0
      if size > MAXFILE then
        offer = nil
        say(tostring(m.file) .. " is " .. human(size) .. " - too big for this machine")
        return
      end
      offer = { name = tostring(m.file), size = size }
      say(offer.name .. "  " .. human(size) .. "   Enter to copy, Esc to leave it")
    elseif m.op == "chunk" then
      waiting = false
      if not dl or tostring(m.file) ~= dl.name then return end
      if tonumber(m.status) ~= 200 then
        say("transfer failed: " .. tostring(m.why or m.status))
        dl = nil
        return
      end
      local piece = tostring(m.data or "")
      -- `at` is authoritative: a chunk that does not start where we are is a reply out of order, and the honest
      -- thing is to stop rather than to write a file with a hole in it.
      if (tonumber(m.at) or -1) ~= dl.at then
        say("transfer out of step; stopped")
        dl = nil
        return
      end
      dl.parts[#dl.parts + 1] = piece
      dl.at = dl.at + #piece
      dl.size = tonumber(m.size) or dl.size
      if m.last or dl.at >= (dl.size or 0) then
        finishDownload()
      else
        askChunk()
      end
      input.redraw = true
    end
  end
end

loadBookmarks()
showLocal()
draw()
gfx.present()
while not quit do
  if input.quit then input.quit = nil quit = true end
  if input.follow then local d = input.follow input.follow = nil go(d) end
  if input.back then input.back = nil back() end
  if input.reload then input.reload = nil if host then request(host, path) else showLocal() end end
  if input.bookmark then
    input.bookmark = nil
    if host then
      local a = host .. path
      local seen = false
      for _, b in ipairs(bookmarks) do if b == a then seen = true end end
      if not seen then bookmarks[#bookmarks + 1] = a saveBookmarks() say("bookmarked " .. a)
      else say("already bookmarked") end
    else say("nothing to bookmark here") end
  end
  if input.accept then
    input.accept = nil
    if offer then startDownload() end
  end
  if input.cancel then
    input.cancel = nil
    if offer then offer = nil say("left it") end
    if dl then dl = nil say("transfer stopped") end
  end
  -- A machine that is switched off, or out of range, simply never answers. Say so rather than hanging.
  if waiting and os.clock() - sentAt > patience then
    waiting = false
    if dl then
      -- Part of a file is not a file. Say how far it got and throw it away.
      say("transfer stalled at " .. human(dl.at) .. " of " .. human(dl.size or 0))
      dl = nil
    else
      local why = patience >= WAKE_TIMEOUT
        and ("It was asleep and was woken, but nothing\nanswered. A machine only serves while a\nserver is running on it - and a thawed\nmachine comes back with its desktop but\nno programs, so it needs the Server\ninstalled with its A key (autostart).\n")
        or "It may be switched off, out of range,\nor not running the server.\n"
      parse("# No answer\n\n" .. tostring(host) .. " did not answer in " .. math.floor(patience) .. " seconds.\n\n"
        .. why .. "\n=> nearby: What is nearby\n")
      say("timed out")
    end
  end
  if input.redraw then input.redraw = nil draw() end
  gfx.present()
end
return "Browser: " .. (host and (host .. path) or "nearby")
