-- Notes (ROADMAP §9 U7): a card file. Notes, addresses, recipes, the co-ordinates of the thing you will
-- absolutely remember and will not. The first of the productivity half of the library.
--
-- It is a **window application**, not a full-screen one (PROGRAMMING.md §4), and that is the whole point of it:
-- a note you cannot see while you are doing the thing the note is about is not much of a note. It opens beside
-- the Terminal, keeps its place in the taskbar, and the desktop carries on behind it.
--
-- One card = a title and a body. An address book is just cards whose bodies are addresses, which is why there
-- are no fields: a record keeper that makes you design a schema before you can write down a phone number is a
-- database, and nobody wants a database.
local T = win.theme
local KEY = win.KEY
local PATH = "/disk/notes.json"

-- kernel.top() is our own window at launch, which is the only reliable way to find it: scanning
-- kernel.programs for "main.lua" picks the wrong one when a second CD is also running a main.lua.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

---------------------------------------------------------------------------------------------------- the file
local cards = {}

local function load()
  cards = {}
  if not fs.exists(PATH) then return end
  local ok, t = pcall(function() return json.decode(fs.read(PATH)) end)
  -- a hand-edited or truncated notes.json must not stop the program opening: what survives is what parses
  if not ok or type(t) ~= "table" or type(t.cards) ~= "table" then return end
  for _, c in ipairs(t.cards) do
    if type(c) == "table" and type(c.title) == "string" then
      cards[#cards + 1] = { title = c.title, body = type(c.body) == "string" and c.body or "" }
    end
  end
end

local saveError = nil
local function save()
  local ok, err = pcall(function() fs.write(PATH, json.encode({ cards = cards })) end)
  -- NOT `ok and nil or tostring(err)`: `and nil` collapses and the `or` branch always wins, so a *successful*
  -- save stored the string "nil" and the status line read "nil" ever after. The idiom cannot carry a nil.
  if ok then saveError = nil else saveError = tostring(err) end
  return ok
end

---------------------------------------------------------------------------------------------------- window
local wd = me.window
wd.fullscreen = false
wd.title = "Notes"
wd.w = math.min(math.max(kernel.w - kernel.iconW - 8, 160), 320)
wd.h = math.min(math.max(kernel.h - kernel.taskbarH - 20, 120), 220)
wd.x, wd.y = kernel.iconW + 4, 14
wd.minW, wd.minH = T.fw * 26, T.fh * 10
wd:relayout()

local search = wd:add(win.TextField{ text = "", placeholder = "Find..." })
local list = wd:add(win.List{ items = {} })
local body = wd:add(win.TextArea{ wrap = true })
local status = wd:add(win.Label{ text = "" })
local bh = T.fh + 6
local newBtn = wd:add(win.Button{ text = "New", h = bh })
local renameBtn = wd:add(win.Button{ text = "Rename", h = bh })
local deleteBtn = wd:add(win.Button{ text = "Delete", h = bh })
local saveBtn = wd:add(win.Button{ text = "Save", h = bh })
local buttons = { newBtn, renameBtn, deleteBtn, saveBtn }
wd.buttons, wd.list, wd.body, wd.search = buttons, list, body, search -- the harness

-- The index takes a third of the width, floored at eight characters: below that a title is unreadable and you
-- may as well give the space to the card you are actually writing.
local function indexW(cw) return math.max(T.fw * 8, math.min(math.floor(cw / 3), T.fw * 16)) end
search.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, cw, T.fh + 6 end
list.layout = function(self, cw, ch)
  self.x, self.y, self.w, self.h = 0, T.fh + 8, indexW(cw), ch - (T.fh + 8) - bh - T.fh - 4
end
body.layout = function(self, cw, ch)
  local iw = indexW(cw)
  self.x, self.y, self.w, self.h = iw + 2, T.fh + 8, cw - iw - 2, ch - (T.fh + 8) - bh - T.fh - 4
end
status.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 2, ch - bh - T.fh - 2, cw - 4, T.fh end
for i, b in ipairs(buttons) do
  b.layout = function(self, cw, ch)
    local bw = math.floor((cw - 2 * (#buttons - 1)) / #buttons)
    self.x, self.y, self.w = (i - 1) * (bw + 2), ch - bh, bw
  end
end
wd:relayout()

---------------------------------------------------------------------------------------------------- state
local shown = {}    -- the cards the filter is letting through, as indices into `cards`
local current = nil -- index into `cards`, not into `shown`
local dirty = false

local function setStatus()
  if saveError then status.text = win.fit(saveError, wd.w - 8)
  elseif #cards == 0 then status.text = "No cards yet - press New"
  else
    status.text = #cards .. (#cards == 1 and " card" or " cards")
      .. (#shown ~= #cards and ("  " .. #shown .. " shown") or "")
      .. (dirty and "  *" or "")
  end
  wd:invalidate()
end

--- Write the editor's text back into the card it belongs to. Called before anything that changes which card is
--- on screen, and before the file is written: the TextArea is the only place an edit exists until this runs.
local function commit()
  if current and cards[current] then cards[current].body = body:gettext() end
end

local function refilter()
  local q = (search.text or ""):lower()
  shown = {}
  local labels = {}
  for i, c in ipairs(cards) do
    if q == "" or c.title:lower():find(q, 1, true) or c.body:lower():find(q, 1, true) then
      shown[#shown + 1] = i
      labels[#labels + 1] = c.title
    end
  end
  list.items = labels
  list.selected = nil
  for row, i in ipairs(shown) do if i == current then list.selected = row end end
  setStatus()
end

local function show(index)
  commit()
  current = index
  local c = index and cards[index]
  body:settext(c and c.body or "")
  body.scroll, body.cy, body.cx = 0, 1, 0
  body.readonly = c == nil
  wd.title = c and ("Notes - " .. c.title) or "Notes"
  for row, i in ipairs(shown) do if i == index then list.selected = row end end
  wd:invalidate()
end

local function doSave()
  commit()
  if save() then
    dirty = false
    kernel.notify("Saved " .. #cards .. (#cards == 1 and " card" or " cards"), 2)
  else
    kernel.notify(saveError or "could not save", 5)
  end
  setStatus()
end

---------------------------------------------------------------------------------------------------- actions
newBtn.onclick = function()
  win.prompt("New card", "Title:", "", function(title)
    if not title then return end
    title = title:gsub("^%s+", ""):gsub("%s+$", "")
    if title == "" then return end
    commit()
    cards[#cards + 1] = { title = title, body = "" }
    current = #cards
    search.text = "" -- a new card the filter would hide is a new card you cannot find
    refilter()
    show(current)
    dirty = true
    doSave()
    wd:setfocus(body)
  end)
end

renameBtn.onclick = function()
  local c = current and cards[current]
  if not c then kernel.notify("Select a card first", 3) return end
  win.prompt("Rename", "Title:", c.title, function(title)
    if not title then return end
    title = title:gsub("^%s+", ""):gsub("%s+$", "")
    if title == "" then return end
    c.title = title
    refilter()
    show(current)
    dirty = true
    doSave()
  end)
end

deleteBtn.onclick = function()
  local c = current and cards[current]
  if not c then return end
  win.ask("Delete", "Delete " .. c.title .. "?", { "Delete", "Cancel" }, function(b)
    if b ~= "Delete" then return end
    table.remove(cards, current)
    current = nil
    body:settext("")
    refilter()
    show(shown[1])
    dirty = true
    doSave()
  end)
end

saveBtn.onclick = doSave

list.onselect = function(row) show(shown[row]) end
list.onactivate = function(row) show(shown[row]) wd:setfocus(body) end
search.onchange = function() refilter() end
body.onchange = function() dirty = true setStatus() end

--- Right-click a card: the same four things, where a pointer expects them.
wd.onrightpress = function(_, lx, ly, px, py)
  if not list:contains(lx, ly) then return false end
  local row = list:rowAt(ly)
  if row then show(shown[row]) end
  win.menu(px, py, {
    { text = "New card...", onclick = newBtn.onclick },
    { text = "Rename...", disabled = current == nil, onclick = renameBtn.onclick },
    { text = "Delete...", disabled = current == nil, onclick = deleteBtn.onclick },
    { sep = true },
    { text = "Save", onclick = doSave },
  })
  return true
end

wd.onkey = function(_, code, down, mods)
  if not down then return false end
  if mods.ctrl and code == KEY.s then doSave() return true end
  if mods.ctrl and code == KEY.n then newBtn.onclick() return true end
  return false
end

-- Closing the window is how this program ends, so it is also the last chance to write the file. onclose runs
-- from kernel.close before the window goes, which is early enough for the TextArea still to have the text in it.
wd.onclose = function()
  commit()
  if dirty then save() end
end

---------------------------------------------------------------------------------------------------- go
load()
refilter()
show(shown[1])
if #cards == 0 then
  -- a first card, so an empty machine shows what this is for rather than an empty box
  cards[1] = { title = "Welcome", body =
    "This is a card file.\n\nNew makes a card, Rename retitles it, Delete removes it. "
    .. "Typing in the box at the top searches every card's title and body.\n\n"
    .. "Everything is kept in " .. PATH .. " on this machine's own disk, so it survives a reboot - and it is "
    .. "an ordinary JSON file, so a program you write can read it too." }
  refilter()
  show(1)
  save()
end
wd:setfocus(body)
wd:invalidate()

while not wd.closed do os.sleep(120) end
return "Notes: " .. #cards .. " cards"
