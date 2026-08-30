-- Reader (ROADMAP §9 U7): for long text. The second of the productivity half, and the one the Manual and any
-- found book will be read in — so it is written as though the thing in it is worth finishing, not skimming.
--
-- **One pane, two modes**, rather than the list-beside-the-text that Notes uses. A window on a 1x1 monitor is
-- 28 characters wide; give a third of that to an index and prose becomes a column of two-word lines. So the
-- Library *replaces* the text while you are choosing, and gets out of the way once you have chosen.
--
-- **Where you stopped is remembered per document, by line and not by scroll position.** The row a line lands on
-- changes with the window width, so a saved scroll offset restores to the wrong place the moment you resize —
-- which, on a machine where the window manager is one of the things you play with, is most of the time.
--
-- Marks are the same idea with a name on it, kept in the same file. A mark is a *place in a document*, so it
-- survives the document being longer than it was; it is a line number, and nothing pretends that is exact if
-- the file is rewritten underneath it.
local T = win.theme
local KEY = win.KEY
local PATH = "/disk/reader.json"
-- win.KEY carries only the letters the ROM's apps needed (TESTING). F is the raw XT scancode.
local KEY_F = 0x21
-- What counts as something to read. Everything else is still openable by path — this is the list, not the rule.
local EXT = { txt = true, md = true, me = true, doc = true }
local MAXFILES = 300 -- a full disk should not turn opening the Library into a job

-- kernel.top() is our own window at launch, which is the only reliable way to find it: scanning
-- kernel.programs for "main.lua" picks the wrong one when a second CD is also running a main.lua.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

---------------------------------------------------------------------------------------------------- the file
-- pos[path] = the logical line at the top of the view when you last left it
-- marks     = { { path, line, name }, ... }
local prefs = { pos = {}, marks = {} }

local function loadPrefs()
  if not fs.exists(PATH) then return end
  local ok, t = pcall(function() return json.decode(fs.read(PATH)) end)
  -- a truncated or hand-edited reader.json must not stop the program opening: what survives is what parses
  if not ok or type(t) ~= "table" then return end
  if type(t.pos) == "table" then
    for k, v in pairs(t.pos) do
      if type(k) == "string" and type(v) == "number" then prefs.pos[k] = math.floor(v) end
    end
  end
  if type(t.marks) == "table" then
    for _, m in ipairs(t.marks) do
      if type(m) == "table" and type(m.path) == "string" and type(m.line) == "number" then
        prefs.marks[#prefs.marks + 1] = { path = m.path, line = math.floor(m.line), name = tostring(m.name or m.path) }
      end
    end
  end
end

local prefsError = nil
local function savePrefs()
  local ok, err = pcall(function() fs.write(PATH, json.encode(prefs)) end)
  -- NOT `ok and nil or tostring(err)`: `and nil` collapses and the `or` branch always wins, so a *successful*
  -- write would store the string "nil" (the bug session 18 found twice).
  if ok then prefsError = nil else prefsError = tostring(err) end
  return ok
end

---------------------------------------------------------------------------------------------------- window
local wd = me.window
wd.fullscreen = false
wd.title = "Reader"
wd.w = math.min(math.max(kernel.w - kernel.iconW - 8, 170), 340)
wd.h = math.min(math.max(kernel.h - kernel.taskbarH - 20, 120), 230)
wd.x, wd.y = kernel.iconW + 4, 14
wd.minW, wd.minH = T.fw * 22, T.fh * 9
wd:relayout()

local list = wd:add(win.List{ items = {} })
local area = wd:add(win.TextArea{ wrap = true, readonly = true })
local status = wd:add(win.Label{ text = "" })
local bh = T.fh + 6
-- The four buttons mean different things in the two modes, so they are relabelled rather than swapped out:
-- a row of buttons that moves under the pointer between one screen and the next is its own small cruelty.
local b1 = wd:add(win.Button{ text = "Open", h = bh })
local b2 = wd:add(win.Button{ text = "Marks", h = bh })
local b3 = wd:add(win.Button{ text = "Find", h = bh })
local b4 = wd:add(win.Button{ text = "Close", h = bh })
local buttons = { b1, b2, b3, b4 }
wd.buttons, wd.list, wd.area, wd.status = buttons, list, area, status -- the harness

local function bodyH(ch) return ch - bh - T.fh - 4 end
list.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, cw, bodyH(ch) end
area.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, cw, bodyH(ch) end
status.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 2, ch - bh - T.fh - 2, cw - 4, T.fh end
for i, b in ipairs(buttons) do
  b.layout = function(self, cw, ch)
    local bw = math.floor((cw - 2 * (#buttons - 1)) / #buttons)
    self.x, self.y, self.w = (i - 1) * (bw + 2), ch - bh, bw
  end
end
wd:relayout()

---------------------------------------------------------------------------------------------------- state
local mode = "library"    -- "library" | "reading"
local libTab = "docs"     -- "docs" | "marks"
local docs = {}           -- { { path, size }, ... } found by scan()
local rows = {}           -- what the list is showing, parallel to list.items
local path = nil          -- the open document
local lines = 0           -- its logical line count
local find = { text = "", row = nil }

---------------------------------------------------------------------------------------------------- scanning
--- Every readable document on every mount, two directories deep. Deep enough to find `/cd0/docs/manual.txt`
--- and shallow enough that a disk full of a program's data files does not bury the list.
local function scan()
  docs = {}
  local seen = 0
  local function walk(dir, depth)
    if seen >= MAXFILES or depth > 2 then return end
    local ok, entries = pcall(function() return fs.list(dir) end)
    if not ok or type(entries) ~= "table" then return end
    table.sort(entries, function(a, b) return a.name < b.name end)
    for _, e in ipairs(entries) do
      if seen >= MAXFILES then return end
      local p = (dir == "/" and "" or dir) .. "/" .. e.name
      if e.dir then
        walk(p, depth + 1)
      else
        local ext = e.name:match("%.([^.]+)$")
        -- `program.txt` at a mount's root is the CD's label card — the name and description the disk item shows.
        -- It is a text file, but it is not a document, and listing it puts the machine's plumbing in the library.
        if ext and EXT[ext:lower()] and not (depth == 1 and e.name == "program.txt") then
          seen = seen + 1
          docs[#docs + 1] = { path = p, size = e.size or 0 }
        end
      end
    end
  end
  local ok, ms = pcall(function() return fs.mounts() end)
  if ok and type(ms) == "table" then
    for _, m in ipairs(ms) do walk("/" .. m.name, 1) end
  end
end

---------------------------------------------------------------------------------------------------- reading
--- The visual row a logical line starts on, so a remembered line can be put back at the top of the view.
local function rowOfLine(li)
  local vis = area:visual()
  for i, r in ipairs(vis) do if r.li >= li then return i end end
  return #vis
end

--- The logical line at the top of the view: what gets remembered.
local function lineAtTop()
  local vis = area:visual()
  local r = vis[(area.scroll or 0) + 1]
  return r and r.li or 1
end

local function maxScroll()
  return math.max(0, #area:visual() - area:rows())
end

local function setStatus()
  if prefsError then status.text = win.fit(prefsError, wd.w - 8) return end
  if mode == "library" then
    if libTab == "marks" then
      status.text = #prefs.marks == 0 and "No marks yet - open a document and press Mark"
        or (#prefs.marks .. (#prefs.marks == 1 and " mark" or " marks"))
    else
      status.text = #docs == 0 and "Nothing to read on this machine"
        or (#docs .. (#docs == 1 and " document" or " documents"))
    end
    return
  end
  local top = lineAtTop()
  local pct = lines <= 1 and 100 or math.floor((top - 1) * 100 / math.max(1, lines - 1))
  -- The percentage is of the *top* line, so it reads 0% on the first screen and only says 100% at the end.
  status.text = win.fit(fs.basename(path or "") .. "  line " .. top .. "/" .. lines .. "  " .. pct .. "%", wd.w - 8)
end

local function scrollTo(row)
  area.scroll = math.max(0, math.min(row, maxScroll()))
  setStatus()
  wd:invalidate()
end

--- Write down where the view is, and put it on the disk. Saving here rather than only on close is the point of
--- the feature: a machine whose chunk unloads, or whose power goes, must not be the thing that loses your place.
--- It is called when the document changes or the Library opens, never per scroll, so it is a handful of small
--- writes a session.
local function remember()
  if path and mode == "reading" then
    local line = lineAtTop()
    if prefs.pos[path] ~= line then
      prefs.pos[path] = line
      savePrefs()
    end
  end
end

---------------------------------------------------------------------------------------------------- modes
local function relabel()
  if mode == "library" then
    b1.text = "Open"
    b2.text = libTab == "docs" and "Marks" or "Docs"
    b3.text = "Rescan"
    b4.text = "Close"
    b3.disabled = libTab == "marks"
  else
    -- "Back", not "Library": four buttons across a window on a 1x1 monitor leaves about five characters each,
    -- and "Libra.." is not a word.
    b1.text = "Back"
    b2.text = "Mark"
    b3.text = "Find"
    b4.text = "Close"
    b3.disabled = false
  end
  wd:invalidate()
end

local function showLibrary()
  remember()
  mode = "library"
  list.hidden = false
  area.hidden = true
  rows = {}
  local labels = {}
  if libTab == "marks" then
    for i, m in ipairs(prefs.marks) do
      rows[i] = m
      labels[i] = m.name .. "  (" .. fs.basename(m.path) .. ":" .. m.line .. ")"
    end
  else
    for i, d in ipairs(docs) do
      rows[i] = d
      labels[i] = d.path .. "  " .. (d.size < 1024 and (d.size .. "b") or (math.floor(d.size / 1024) .. "k"))
    end
  end
  if #labels == 0 then labels[1] = libTab == "marks" and "(no marks)" or "(nothing found)" rows[1] = nil end
  list.items = labels
  -- Come back to the Library and the document you were just in is the one under the cursor, so Enter is "carry
  -- on reading" rather than "find your place in the list again".
  list.selected = nil
  for i, r in ipairs(rows) do if r.path == path then list.selected = i break end end
  list.scroll = 0
  wd.title = "Reader"
  relabel()
  setStatus()
  -- Whatever got us here — Esc, a button, the tab toggle — the list is what the keyboard should be talking to.
  -- Pressing a button leaves the focus on the button, and then Down and Enter quietly do nothing.
  wd:setfocus(list)
  wd:invalidate()
end

--- Open a document and put the view back where it was left (or at `atLine`, for a mark).
local function open(p, atLine)
  local ok, body = pcall(function() return fs.read(p) end)
  if not ok then
    win.info("Reader", { "Could not read", p, tostring(body) })
    return false
  end
  remember()
  path = p
  area:settext(body)
  lines = #(area.lines or { "" })
  mode = "reading"
  list.hidden = true
  area.hidden = false
  find.row = nil
  wd.title = "Reader - " .. fs.basename(p)
  relabel()
  scrollTo(rowOfLine(atLine or prefs.pos[p] or 1) - 1)
  wd:setfocus(area)
  return true
end

---------------------------------------------------------------------------------------------------- find
local function findFrom(startRow)
  local q = find.text:lower()
  if q == "" then return nil end
  local vis = area:visual()
  -- Searched by *visual* row rather than by logical line so that "the next one" means the next one you can see
  -- coming; a match is wherever the wrapped text shows it. A phrase broken across a wrap is the price, and it
  -- is the same price every wrapped reader pays.
  for i = startRow, #vis do
    if vis[i].text:lower():find(q, 1, true) then return i end
  end
  for i = 1, math.min(startRow - 1, #vis) do
    if vis[i].text:lower():find(q, 1, true) then return i end
  end
  return nil
end

local function findNext()
  if find.text == "" then return end
  -- After a hit, the next one starts below it; a fresh search starts at the top of the view, which must include
  -- the row you are looking at — a search that skips the line under your eyes reads as a search that missed.
  local from = find.row and (find.row + 1) or ((area.scroll or 0) + 1)
  local hit = findFrom(from)
  if not hit then
    kernel.notify("Not found: " .. find.text, 3)
    return
  end
  find.row = hit
  scrollTo(hit - 1)
  status.text = win.fit("Found '" .. find.text .. "' at row " .. hit .. " - F3 for the next", wd.w - 8)
  wd:invalidate()
end

local function doFind()
  win.prompt("Find", "Text:", find.text, function(text)
    if not text or text == "" then return end
    find.text = text
    find.row = nil -- from the top of the view, not from the last hit in a document you have since left
    findNext()
  end)
end

---------------------------------------------------------------------------------------------------- marks
local function addMark()
  if not path then return end
  local line = lineAtTop()
  -- The default name is the first few words where you are standing, which is nearly always the right name and
  -- saves the one thing that stops people bookmarking anything: having to think of a title.
  local text = (area.lines and area.lines[line] or ""):gsub("^%s+", "")
  local default = text ~= "" and win.fit(text, 22 * T.fw):gsub("%s+$", "") or (fs.basename(path) .. " " .. line)
  win.prompt("Mark", "Name:", default, function(name)
    if not name then return end
    name = name:gsub("^%s+", ""):gsub("%s+$", "")
    if name == "" then return end
    for _, m in ipairs(prefs.marks) do
      if m.path == path and m.line == line then m.name = name savePrefs() kernel.notify("Mark moved", 2) return end
    end
    prefs.marks[#prefs.marks + 1] = { path = path, line = line, name = name }
    savePrefs()
    kernel.notify("Marked: " .. name, 2)
    setStatus()
  end)
end

local function deleteMark(i)
  local m = prefs.marks[i]
  if not m then return end
  win.ask("Delete mark", "Delete " .. m.name .. "?", { "Delete", "Cancel" }, function(b)
    if b ~= "Delete" then return end
    table.remove(prefs.marks, i)
    savePrefs()
    showLibrary()
  end)
end

---------------------------------------------------------------------------------------------------- actions
local function openByPath()
  win.prompt("Open", "Path:", path or "/disk/", function(p)
    if not p or p == "" then return end
    if not fs.exists(p) then kernel.notify("No such file: " .. p, 4) return end
    if fs.isdir(p) then kernel.notify(p .. " is a directory", 4) return end
    open(p)
  end)
end

b1.onclick = function()
  if mode == "library" then openByPath() else showLibrary() end
end

b2.onclick = function()
  if mode == "library" then
    libTab = libTab == "docs" and "marks" or "docs"
    showLibrary()
  else
    addMark()
  end
end

b3.onclick = function()
  if mode == "library" then
    if libTab == "docs" then scan() showLibrary() kernel.notify("Found " .. #docs .. " documents", 2) end
  else
    doFind()
  end
end

b4.onclick = function() kernel.close(wd) end

list.onactivate = function(row)
  local r = rows[row]
  if not r then return end
  if libTab == "marks" then
    if open(r.path, r.line) then find.row = nil end
  else
    open(r.path)
  end
end
list.onselect = function() setStatus() end

--- A reader scrolls; it does not have a cursor. The base TextArea navigates by moving one, which is right for
--- an editor and wrong here — Home and End would move the caret inside a line you cannot see and leave the view
--- exactly where it was. Overriding `key` on this one instance is the whole fix.
function area:key(code, down, mods)
  if not down then return false end
  local page = math.max(1, self:rows() - 1) -- one line of overlap, so a paragraph is never cut in half by a page
  if code == KEY.up then scrollTo((self.scroll or 0) - 1)
  elseif code == KEY.down then scrollTo((self.scroll or 0) + 1)
  elseif code == KEY.pgup or code == KEY.left then scrollTo((self.scroll or 0) - page)
  elseif code == KEY.pgdn or code == KEY.right or code == KEY.space then scrollTo((self.scroll or 0) + page)
  elseif code == KEY.home then scrollTo(0)
  elseif code == KEY["end"] then scrollTo(maxScroll())
  else return false end
  return true
end

--- The wheel is the other way the view moves, and the status line counts lines, so it has to hear about it.
local basewheel = area.wheel
function area:wheel(dy)
  local r = basewheel(self, dy)
  setStatus()
  return r
end

--- Right-click a document, a mark or the page.
wd.onrightpress = function(_, lx, ly, px, py)
  if mode == "library" then
    if not list:contains(lx, ly) then return false end
    local row = list:rowAt(ly)
    if row then list.selected = row end
    local r = row and rows[row]
    win.menu(px, py, {
      { text = "Open", disabled = r == nil, onclick = function() if r then list.onactivate(row) end end },
      { text = libTab == "docs" and "Marks" or "Documents", onclick = b2.onclick },
      { sep = true },
      { text = "Delete mark...", disabled = not (libTab == "marks" and r), onclick = function() deleteMark(row) end },
      { text = "Open path...", onclick = openByPath },
    })
    return true
  end
  win.menu(px, py, {
    { text = "Mark this place...", onclick = addMark },
    { text = "Find...", onclick = doFind },
    { text = "Find next", disabled = find.text == "", onclick = findNext },
    { sep = true },
    { text = "Back to the Library", onclick = showLibrary },
  })
  return true
end

wd.onkey = function(_, code, down, mods)
  if not down then return false end
  if code == KEY.esc and mode == "reading" then showLibrary() return true end
  if code == KEY.f3 then findNext() return true end
  if mods.ctrl and code == KEY_F then doFind() return true end
  if mods.ctrl and code == KEY.d and mode == "reading" then addMark() return true end
  if mods.ctrl and code == KEY.o then openByPath() return true end
  -- The list has focus in the Library, so these only arrive when nothing else wanted them.
  if mode == "reading" then return area:key(code, down, mods) end
  return false
end

wd.onclose = function()
  remember()
  savePrefs()
end

---------------------------------------------------------------------------------------------------- go
loadPrefs()
scan()

-- First run on this machine — no reader.json yet — writes one document to /disk, which is both the instructions
-- and the demonstration: the Library finds it on the very next scan. Keyed on reader.json rather than on the
-- library being empty, because a machine holding nothing but CDs would otherwise never get it, and that is
-- exactly the machine whose owner has not read this yet.
if not fs.exists(PATH) and not fs.exists("/disk/readme.txt") then
  local ok = pcall(function()
    fs.write("/disk/readme.txt",
      "About the Reader\n\n"
      .. "This is where long text is read on this machine. It finds every .txt and .md file on every disk, "
      .. "wraps it to the window, and remembers the line you stopped on - so closing it and coming back a week "
      .. "later puts you where you were.\n\n"
      .. "Keys\n\n"
      .. "PgUp / PgDn - a screen\n"
      .. "Up / Down - a line\n"
      .. "Home / End - start, end\n"
      .. "Esc - back to the Library\n"
      .. "Ctrl+F - find, F3 for next\n"
      .. "Ctrl+D - mark this place\n"
      .. "Ctrl+O - open any path\n\n"
      .. "Marks\n\n"
      .. "A mark is a named line in a document. Press Mark, or Ctrl+D, and the name it offers you is the line "
      .. "you are standing on - which is usually the right name. The Marks tab in the Library lists them all, "
      .. "and right-clicking one deletes it.\n\n"
      .. "Everything the Reader remembers lives in /disk/reader.json on this machine's own disk. It is ordinary "
      .. "JSON, so a program you write can read it too - and if you delete it, nothing is lost but the places.\n")
  end)
  if ok then scan() end
end

showLibrary()
wd:setfocus(list)
wd:invalidate()

while not wd.closed do os.sleep(120) end
remember()
savePrefs()
return "Reader: " .. #docs .. " documents, " .. #prefs.marks .. " marks"
