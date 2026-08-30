-- The window system and widget toolkit (ROADMAP §7h §7): windows with a title bar, and widgets that draw with gfx
-- and take pointer/keyboard events. Pointer-first with big targets (the VR rule); keyboard shortcuts where they
-- help. Coordinates inside a window are relative to its client area. The kernel owns z-order and focus.
local gfx = gfx
local win = {}

win.theme = { font = 1, fw = 6, fh = 8, desk = 1, frame = 6, frameDark = 5, frameLight = 7, title = 12, titleText = 7,
  titleInactive = 5, text = 0, panel = 7, field = 7, button = 6, buttonText = 0, accent = 9, sel = 12, selText = 7, border = 0,
  disabled = 5, ok = 11, warn = 8 }
local T = win.theme

-- scancodes (XT set 1, what the client sends)
win.KEY = { esc = 0x01, backspace = 0x0e, tab = 0x0f, enter = 0x1c, kpenter = 0x9c, ctrl = 0x1d, lshift = 0x2a, rshift = 0x36,
  f1 = 0x3b, f2 = 0x3c, f3 = 0x3d, f4 = 0x3e, f5 = 0x3f, home = 0xc7, up = 0xc8, pgup = 0xc9, left = 0xcb, right = 0xcd,
  ["end"] = 0xcf, down = 0xd0, pgdn = 0xd1, delete = 0xd3, s = 0x1f, q = 0x10, c = 0x2e, v = 0x2f, a = 0x1e, n = 0x31, o = 0x18,
  w = 0x11, d = 0x20, p = 0x19, r = 0x13, x = 0x2d, z = 0x2c, space = 0x39 }
local KEY = win.KEY

--- Encode a codepoint as UTF-8 (Cobalt has no utf8 library); the fonts draw Latin-1, so high codepoints show as '?'.
function win.utf8char(cp)
  if cp < 0x80 then return string.char(cp)
  elseif cp < 0x100 then return string.char(cp)
  else return "?" end
end

function win.setfont(font)
  T.font = font
  T.fw, T.fh = gfx.fontw(font), gfx.fonth(font)
end

local function text(x, y, s, fg, bg) return gfx.text(x, y, s, fg, bg, T.font) end
win.textw = function(s) return #tostring(s) * T.fw end
win.text = text

--- Cut a string to fit w pixels, with a trailing '~' if cut.
local function fit(s, w)
  s = tostring(s)
  local n = math.floor(w / T.fw)
  if #s <= n or n < 1 then return s end
  return s:sub(1, math.max(1, n - 1)) .. "~"
end
win.fit = fit

local function bevel(x, y, w, h, fill, light, dark)
  gfx.fill(x, y, w, h, fill)
  gfx.line(x, y, x + w - 1, y, light) gfx.line(x, y, x, y + h - 1, light)
  gfx.line(x, y + h - 1, x + w - 1, y + h - 1, dark) gfx.line(x + w - 1, y, x + w - 1, y + h - 1, dark)
end
win.bevel = bevel

---------------------------------------------------------------------------------------------------- widgets
local Widget = {}
Widget.__index = Widget
function Widget.new(cls, o)
  o = o or {}
  o.x, o.y, o.w, o.h = o.x or 0, o.y or 0, o.w or 10, o.h or T.fh + 4
  return setmetatable(o, cls)
end
function Widget:contains(lx, ly) return lx >= self.x and ly >= self.y and lx < self.x + self.w and ly < self.y + self.h end
function Widget:draw(ox, oy) end
function Widget:press(lx, ly, button) end
function Widget:release(lx, ly, button) end
function Widget:drag(lx, ly) end
function Widget:key(code, down, mods) return false end
function Widget:char(cp) return false end
function Widget:wheel(dy) return false end
function Widget:paste(s) return false end
function Widget:invalidate() if self.window then self.window:invalidate() end end

local function subclass(name)
  local c = setmetatable({}, { __index = Widget })
  c.__index = c
  c.__name = name
  win[name] = setmetatable(c, { __index = Widget, __call = function(cls, o) return Widget.new(cls, o) end })
  return c
end

-- Label ----------------------------------------------------------------------------------------------
local Label = subclass("Label")
function Label:draw(ox, oy)
  local s = self.text or ""
  if self.center then
    text(ox + self.x + math.floor((self.w - win.textw(s)) / 2), oy + self.y, fit(s, self.w), self.fg or T.text, self.bg)
  else
    text(ox + self.x, oy + self.y, fit(s, self.w), self.fg or T.text, self.bg)
  end
end

-- Button ---------------------------------------------------------------------------------------------
local Button = subclass("Button")
function Button:draw(ox, oy)
  local x, y = ox + self.x, oy + self.y
  local down = self.down
  -- `active` is the tab/toolbar look: the button that is currently the answer wears the accent rather than
  -- being drawn pushed in, because a pushed-in button also means "your finger is on it right now"
  local fill = self.disabled and T.frame or (down and T.frameDark or (self.active and T.sel or T.button))
  bevel(x, y, self.w, self.h, fill, down and T.frameDark or T.frameLight, down and T.frameLight or T.frameDark)
  local s = fit(self.text or "", self.w - 4)
  local fg = self.disabled and T.disabled or (self.active and not down and T.selText or (self.fg or T.buttonText))
  text(x + math.floor((self.w - win.textw(s)) / 2) + (down and 1 or 0), y + math.floor((self.h - T.fh) / 2) + (down and 1 or 0), s, fg)
end
function Button:press(lx, ly, button)
  if self.disabled then return end
  self.down = true
  self:invalidate()
end
function Button:release(lx, ly, button)
  if not self.down then return end
  self.down = false
  self:invalidate()
  if self:contains(lx, ly) and self.onclick then self.onclick(self) end
end

-- Toggle ---------------------------------------------------------------------------------------------
local Toggle = subclass("Toggle")
function Toggle:draw(ox, oy)
  local x, y = ox + self.x, oy + self.y
  local box = T.fh + 2
  bevel(x, y, box, box, T.field, T.frameDark, T.frameLight)
  if self.on then gfx.fill(x + 2, y + 2, box - 4, box - 4, T.accent) end
  text(x + box + T.fw, y + 1, fit(self.text or "", self.w - box - T.fw), T.text)
end
function Toggle:release(lx, ly, button)
  if self:contains(lx, ly) then
    self.on = not self.on
    self:invalidate()
    if self.onchange then self.onchange(self.on, self) end
  end
end

-- List -----------------------------------------------------------------------------------------------
local List = subclass("List")
function List:rowh() return T.fh + 2 end
function List:rows() return math.max(1, math.floor((self.h - 2) / self:rowh())) end
function List:draw(ox, oy)
  local x, y = ox + self.x, oy + self.y
  gfx.fill(x, y, self.w, self.h, T.field)
  gfx.rect(x, y, self.w, self.h, T.frameDark)
  local items = self.items or {}
  local rh, rows = self:rowh(), self:rows()
  self.scroll = math.max(0, math.min(self.scroll or 0, math.max(0, #items - rows)))
  for i = 1, rows do
    local idx = self.scroll + i
    local it = items[idx]
    if not it then break end
    local ry = y + 1 + (i - 1) * rh
    local sel = idx == self.selected
    if sel then gfx.fill(x + 1, ry, self.w - 2, rh, self.focused and T.sel or T.frameDark) end
    local label = type(it) == "table" and (it.label or it.name) or it
    text(x + 3, ry + 1, fit(label, self.w - 6), sel and T.selText or T.text)
  end
  if #items > rows then
    -- scrollbar
    local sh = math.max(4, math.floor((self.h - 2) * rows / #items))
    local sy = y + 1 + math.floor((self.h - 2 - sh) * self.scroll / math.max(1, #items - rows))
    gfx.fill(x + self.w - 4, y + 1, 3, self.h - 2, T.frame)
    gfx.fill(x + self.w - 4, sy, 3, sh, T.frameDark)
  end
end
--- Which row is at this local y, or nil past the last one. A right-click asks before it opens a menu, so the
--- menu is about the row you pointed at rather than the row that happened to be selected.
function List:rowAt(ly)
  local idx = (self.scroll or 0) + math.floor((ly - self.y - 1) / self:rowh()) + 1
  if (self.items or {})[idx] then return idx end
  return nil
end

function List:press(lx, ly, button)
  local items = self.items or {}
  local idx = (self.scroll or 0) + math.floor((ly - self.y - 1) / self:rowh()) + 1
  if items[idx] then
    local now = os.clock()
    local double = self.selected == idx and self.lastClick and now - self.lastClick < 0.6
    self.lastClick = now
    self.selected = idx
    self:invalidate()
    if double then
      if self.onactivate then self.onactivate(idx, items[idx], self) end
    elseif self.onselect then self.onselect(idx, items[idx], self) end
  end
end
-- dy is +1 for a wheel *up* (the host sends the RFB wheel buttons; LuaComputerBlockEntity turns 0x08 into +1),
-- and scrolling up means showing earlier lines, so the offset goes down. Getting this backwards is the classic
-- inverted-scroll bug; both scrollable widgets subtract.
function List:wheel(dy)
  local items = self.items or {}
  self.scroll = math.max(0, math.min((self.scroll or 0) - dy * 3, math.max(0, #items - self:rows())))
  self:invalidate()
  return true
end
function List:key(code, down, mods)
  if not down then return false end
  local items = self.items or {}
  if code == KEY.up then self.selected = math.max(1, (self.selected or 1) - 1)
  elseif code == KEY.down then self.selected = math.min(#items, (self.selected or 0) + 1)
  elseif code == KEY.enter or code == KEY.kpenter then
    if self.selected and items[self.selected] and self.onactivate then self.onactivate(self.selected, items[self.selected], self) end
    return true
  else return false end
  if self.selected then
    local rows = self:rows()
    if self.selected - 1 < (self.scroll or 0) then self.scroll = self.selected - 1 end
    if self.selected > (self.scroll or 0) + rows then self.scroll = self.selected - rows end
    if self.onselect then self.onselect(self.selected, items[self.selected], self) end
  end
  self:invalidate()
  return true
end

-- TextField ------------------------------------------------------------------------------------------
local TextField = subclass("TextField")
function TextField:draw(ox, oy)
  local x, y = ox + self.x, oy + self.y
  bevel(x, y, self.w, self.h, T.field, T.frameDark, T.frameLight)
  local s = self.text or ""
  local cols = math.floor((self.w - 6) / T.fw)
  local cur = self.cursor or #s
  local from = math.max(0, cur - cols + 1)
  local shown = s:sub(from + 1, from + cols)
  local ty = y + math.floor((self.h - T.fh) / 2)
  if s == "" and self.placeholder and not self.focused then
    -- what the box is for, in grey, until you use it. An empty box at the top of a window says nothing about
    -- itself, and there is rarely room beside it for a label.
    text(x + 3, ty, fit(self.placeholder, self.w - 6), T.disabled)
  else
    text(x + 3, ty, shown, T.text)
  end
  if self.focused then
    local cx = x + 3 + (cur - from) * T.fw
    gfx.fill(cx, y + 2, 1, self.h - 4, T.text)
  end
end
function TextField:press(lx, ly)
  local s = self.text or ""
  self.cursor = math.max(0, math.min(#s, math.floor((lx - self.x - 3) / T.fw)))
  self:invalidate()
end
function TextField:char(cp)
  if cp < 32 then return false end -- Tab, Enter and friends are keys, not text
  local s = self.text or ""
  local ch = win.utf8char(cp)
  local cur = self.cursor or #s
  self.text = s:sub(1, cur) .. ch .. s:sub(cur + 1)
  self.cursor = cur + #ch
  if self.onchange then self.onchange(self.text, self) end
  self:invalidate()
  return true
end
function TextField:key(code, down, mods)
  if not down then return false end
  local s = self.text or ""
  local cur = self.cursor or #s
  if code == KEY.backspace then
    if cur > 0 then self.text = s:sub(1, cur - 1) .. s:sub(cur + 1) self.cursor = cur - 1 end
  elseif code == KEY.delete then self.text = s:sub(1, cur) .. s:sub(cur + 2)
  elseif code == KEY.left then self.cursor = math.max(0, cur - 1)
  elseif code == KEY.right then self.cursor = math.min(#s, cur + 1)
  elseif code == KEY.home then self.cursor = 0
  elseif code == KEY["end"] then self.cursor = #s
  elseif code == KEY.enter or code == KEY.kpenter then
    if self.onenter then self.onenter(self.text or "", self) end
    return true
  else return false end
  if self.onchange and code ~= KEY.left and code ~= KEY.right then self.onchange(self.text, self) end
  self:invalidate()
  return true
end
function TextField:paste(str)
  local s = self.text or ""
  local cur = self.cursor or #s
  local line = str:gsub("[\r\n].*", "")
  self.text = s:sub(1, cur) .. line .. s:sub(cur + 1)
  self.cursor = cur + #line
  if self.onchange then self.onchange(self.text, self) end
  self:invalidate()
  return true
end

-- TextArea: a small editor (lines, cursor, scroll, paste) -------------------------------------------
local TextArea = subclass("TextArea")
function TextArea:init()
  self.lines = self.lines or { "" }
  self.colors = self.colors or {}
  self.cx, self.cy = self.cx or 0, self.cy or 1
  self.scroll = self.scroll or 0
  self.hscroll = 0
end
function TextArea:settext(s)
  self.lines = {}
  self.colors = {}
  self:touch()
  for l in (tostring(s) .. "\n"):gmatch("([^\n]*)\n") do self.lines[#self.lines + 1] = l end
  if #self.lines == 0 then self.lines = { "" } end
  self.cx, self.cy, self.scroll = 0, 1, 0
  self:invalidate()
end
function TextArea:gettext() return table.concat(self.lines or { "" }, "\n") end
-- Appends whole lines: each call starts a new line (an empty area is filled in place). `fg` colours those lines.
function TextArea:append(s, fg)
  self:init()
  self:touch()
  -- An area that is appended to is a console, and a console follows its last line. This used to be inferred
  -- from `readonly`, which was wrong for the other kind of read-only area — one you *read*. There, every arrow
  -- and Page key ran through scrollToCursor and snapped the view to the end of the document, so the Reader
  -- could not page at all. Appending opts in; nothing else does.
  if self.follow == nil then self.follow = true end
  local first = #self.lines == 1 and self.lines[1] == ""
  for l in (tostring(s) .. "\n"):gmatch("([^\n]*)\n") do
    if first then self.lines[1] = l first = false
    else self.lines[#self.lines + 1] = l end
    self.colors[#self.lines] = fg or false
  end
  if self.maxlines and #self.lines > self.maxlines then
    local drop = #self.lines - self.maxlines
    for i = 1, drop do table.remove(self.lines, 1) table.remove(self.colors, 1) end
  end
  self.cy = #self.lines
  self.cx = #self.lines[self.cy]
  self:scrollToCursor()
  self:invalidate()
end
function TextArea:rows() return math.max(1, math.floor((self.h - 4) / T.fh)) end
function TextArea:cols() return math.max(1, math.floor((self.w - 6) / T.fw)) end

-- Word wrap (`wrap = true`; the Terminal's output is wrapped, and so is Edit once you press its Wrap button).
-- One logical line becomes one or more *visual rows*, broken after the last space that fits, or hard at the
-- edge for a word longer than the width. `scroll` counts visual rows while wrapping, so a wrapped line cannot
-- be half-scrolled off the top and the scrollbar means what it looks like it means.
--
-- Each row carries `off`, where it starts inside its logical line. That is what lets a *wrapped* area still be
-- edited: the cursor lives at (cy, cx) in the text, and off is the only thing needed to turn that into a place
-- on the screen and back again.
local function wraprow(l, cols)
  local out, p, n = {}, 1, #l
  while p <= n do
    if n - p + 1 <= cols then out[#out + 1] = { text = l:sub(p), off = p - 1 } return out end
    local chunk = l:sub(p, p + cols - 1)
    local sp = chunk:match("^.*()%s")
    if sp and sp > 1 then
      out[#out + 1] = { text = l:sub(p, p + sp - 2), off = p - 1 }
      p = p + sp
      while l:sub(p, p) == " " do p = p + 1 end
    else
      out[#out + 1] = { text = chunk, off = p - 1 }
      p = p + cols
    end
  end
  if #out == 0 then out[1] = { text = "", off = 0 } end
  return out
end

--- Wrap a string to `cols` characters at spaces, hard-breaking a word too long to fit. Returns plain strings,
--- one per visual row, and always at least one — an empty string wraps to one empty row, which is the blank
--- line a page asked for. TextArea gets this through `visual()`; a **full-screen** program showing prose (the
--- browser, the reader) has no widget to hang it on and wants it directly.
function win.wrap(s, cols)
  local out = {}
  for _, r in ipairs(wraprow(tostring(s), math.max(1, math.floor(cols)))) do out[#out + 1] = r.text end
  return out
end

--- The visual rows: `{ text, li, off }` per row (`li` = the logical line it came from, `off` = where the row
--- starts inside it). Cached until the text or the width changes. Without `wrap` it is one row per line with
--- off = 0, so every caller can use it unconditionally.
function TextArea:visual()
  self:init()
  local cols = self:cols()
  local key = (self.gen or 0) .. ":" .. cols .. ":" .. tostring(self.wrap)
  if self.vkey == key and self.vrows then return self.vrows end
  local out = {}
  if self.wrap then
    for i, l in ipairs(self.lines) do
      if l == "" then out[#out + 1] = { text = "", li = i, off = 0 }
      else for _, r in ipairs(wraprow(l, cols)) do out[#out + 1] = { text = r.text, li = i, off = r.off } end end
    end
  else
    for i, l in ipairs(self.lines) do out[#out + 1] = { text = l, li = i, off = 0 } end
  end
  if #out == 0 then out[1] = { text = "", li = 1, off = 0 } end
  self.vrows, self.vkey = out, key
  return out
end

--- The text changed: the wrap cache is stale.
function TextArea:touch() self.gen = (self.gen or 0) + 1 end

--- Where the cursor is on screen: the visual row it falls in, and how far along that row. The last row of a
--- logical line owns the position one past its end, which is where the cursor sits after typing to the margin.
function TextArea:cursorAt()
  local vis = self:visual()
  local last = nil
  for i, r in ipairs(vis) do
    if r.li == self.cy then
      if self.cx >= r.off and self.cx <= r.off + #r.text then return i, self.cx - r.off end
      last = i
    elseif last then break end
  end
  if last then return last, #vis[last].text end
  return 1, 0
end

function TextArea:scrollToCursor()
  local rows, cols = self:rows(), self:cols()
  if self.wrap then
    if self.follow then -- a console follows its last line rather than a cursor (set by append)
      self.scroll = math.max(0, #self:visual() - rows)
      return
    end
    local r = self:cursorAt()
    if r - 1 < self.scroll then self.scroll = r - 1 end
    if r > self.scroll + rows then self.scroll = r - rows end
    self.hscroll = 0 -- there is nothing off to the side when every line is wrapped
    return
  end
  if self.cy - 1 < self.scroll then self.scroll = self.cy - 1 end
  if self.cy > self.scroll + rows then self.scroll = self.cy - rows end
  if self.cx < self.hscroll then self.hscroll = self.cx end
  if self.cx >= self.hscroll + cols then self.hscroll = self.cx - cols + 1 end
end
function TextArea:draw(ox, oy)
  self:init()
  local x, y = ox + self.x, oy + self.y
  gfx.fill(x, y, self.w, self.h, self.bg or T.field)
  gfx.rect(x, y, self.w, self.h, T.frameDark)
  local rows, cols = self:rows(), self:cols()
  local vis = self:visual()
  self.scroll = math.max(0, math.min(self.scroll or 0, math.max(0, #vis - rows)))
  for i = 1, rows do
    local r = vis[self.scroll + i]
    if not r then break end
    local shown = self.wrap and r.text or r.text:sub(self.hscroll + 1, self.hscroll + cols)
    local c = self.colors and self.colors[r.li]
    text(x + 3, y + 2 + (i - 1) * T.fh, shown, c or self.fg or T.text)
  end
  if self.focused and not self.readonly then
    local cr, cc = self:cursorAt()
    local row = cr - self.scroll
    if not self.wrap then row, cc = self.cy - self.scroll, self.cx - self.hscroll end
    if row >= 1 and row <= rows and cc >= 0 and cc <= cols then
      gfx.fill(x + 3 + cc * T.fw, y + 2 + (row - 1) * T.fh, 1, T.fh, T.text)
    end
  end
  -- the scrollbar: a wrapped editor has no other way to say "there is more below", and Edit's whole complaint
  -- was not being able to tell
  if #vis > rows then
    local sh = math.max(4, math.floor((self.h - 2) * rows / #vis))
    local sy = y + 1 + math.floor((self.h - 2 - sh) * self.scroll / math.max(1, #vis - rows))
    gfx.fill(x + self.w - 4, y + 1, 3, self.h - 2, T.frame)
    gfx.fill(x + self.w - 4, sy, 3, sh, T.frameDark)
  end
end
function TextArea:press(lx, ly)
  self:init()
  local vis = self:visual()
  local r = math.max(1, math.min(#vis, self.scroll + math.floor((ly - self.y - 2) / T.fh) + 1))
  local col = math.max(0, math.floor((lx - self.x - 3 + T.fw / 2) / T.fw))
  self.cy = math.max(1, math.min(#self.lines, vis[r] and vis[r].li or r))
  -- the click lands inside the *row*, so a wrapped row's offset is what turns a column into a text position
  local off = (vis[r] and vis[r].off or 0) + (self.wrap and 0 or self.hscroll)
  self.cx = math.max(0, math.min(#self.lines[self.cy], off + col))
  self:invalidate()
end
function TextArea:wheel(dy)
  self:init()
  self.scroll = math.max(0, math.min(math.max(0, #self:visual() - self:rows()), self.scroll - dy * 3))
  self:invalidate()
  return true
end
function TextArea:changed() self:touch() if self.onchange then self.onchange(self) end end
function TextArea:char(cp)
  self:init()
  if self.readonly then return false end
  local ch = win.utf8char(cp)
  local l = self.lines[self.cy]
  self.lines[self.cy] = l:sub(1, self.cx) .. ch .. l:sub(self.cx + 1)
  self.cx = self.cx + #ch
  self:scrollToCursor() self:changed() self:invalidate()
  return true
end
function TextArea:paste(s)
  self:init()
  if self.readonly then return false end
  for l in (s:gsub("\r", "") .. "\n"):gmatch("([^\n]*)\n") do
    if l ~= "" then
      local cur = self.lines[self.cy]
      self.lines[self.cy] = cur:sub(1, self.cx) .. l .. cur:sub(self.cx + 1)
      self.cx = self.cx + #l
    end
    self:newline()
  end
  -- paste ends with a newline we do not want
  if self.cy > 1 and self.lines[self.cy] == "" then table.remove(self.lines, self.cy) self.cy = self.cy - 1 self.cx = #self.lines[self.cy] end
  self:scrollToCursor() self:changed() self:invalidate()
  return true
end
function TextArea:newline()
  local l = self.lines[self.cy]
  local rest = l:sub(self.cx + 1)
  self.lines[self.cy] = l:sub(1, self.cx)
  table.insert(self.lines, self.cy + 1, rest)
  self.cy = self.cy + 1
  self.cx = 0
end
--- Up and down by one row *as drawn*. Wrapped, a paragraph is many rows, and stepping a whole paragraph at a
--- time is the thing that makes a wrapped editor feel broken.
function TextArea:moveRow(delta)
  local vis = self:visual()
  local r, c = self:cursorAt()
  r = math.max(1, math.min(#vis, r + delta))
  self.cy = vis[r].li
  self.cx = math.min(vis[r].off + c, #self.lines[self.cy])
end

function TextArea:key(code, down, mods)
  self:init()
  if not down then return false end
  local l = self.lines[self.cy]
  if code == KEY.up then if self.wrap then self:moveRow(-1) else self.cy = math.max(1, self.cy - 1) end
  elseif code == KEY.down then if self.wrap then self:moveRow(1) else self.cy = math.min(#self.lines, self.cy + 1) end
  elseif code == KEY.pgup then if self.wrap then self:moveRow(-self:rows()) else self.cy = math.max(1, self.cy - self:rows()) end
  elseif code == KEY.pgdn then if self.wrap then self:moveRow(self:rows()) else self.cy = math.min(#self.lines, self.cy + self:rows()) end
  elseif code == KEY.left then
    if self.cx > 0 then self.cx = self.cx - 1 elseif self.cy > 1 then self.cy = self.cy - 1 self.cx = #self.lines[self.cy] end
  elseif code == KEY.right then
    if self.cx < #l then self.cx = self.cx + 1 elseif self.cy < #self.lines then self.cy = self.cy + 1 self.cx = 0 end
  elseif code == KEY.home then self.cx = 0
  elseif code == KEY["end"] then self.cx = #l
  elseif self.readonly then return false
  elseif code == KEY.enter or code == KEY.kpenter then self:newline() self:changed()
  elseif code == KEY.backspace then
    if self.cx > 0 then self.lines[self.cy] = l:sub(1, self.cx - 1) .. l:sub(self.cx + 1) self.cx = self.cx - 1
    elseif self.cy > 1 then
      local prev = self.lines[self.cy - 1]
      self.cx = #prev
      self.lines[self.cy - 1] = prev .. l
      table.remove(self.lines, self.cy)
      self.cy = self.cy - 1
    end
    self:changed()
  elseif code == KEY.delete then
    if self.cx < #l then self.lines[self.cy] = l:sub(1, self.cx) .. l:sub(self.cx + 2)
    elseif self.cy < #self.lines then self.lines[self.cy] = l .. self.lines[self.cy + 1] table.remove(self.lines, self.cy + 1) end
    self:changed()
  elseif code == KEY.tab then
    self.lines[self.cy] = l:sub(1, self.cx) .. "  " .. l:sub(self.cx + 1) self.cx = self.cx + 2 self:changed()
  else return false end
  self.cx = math.min(self.cx, #self.lines[self.cy])
  self:scrollToCursor()
  self:invalidate()
  return true
end

---------------------------------------------------------------------------------------------------- Window
local Window = {}
Window.__index = Window
win.Window = Window
win.TITLE_H = function() return T.fh + 4 end
--- The resize grip in the bottom-right corner, and the smallest window a drag can make (apps may set minW/minH).
win.GRIP = function() return T.fh + 2 end
win.MIN_W = function() return T.fw * 12 end
win.MIN_H = function() return win.TITLE_H() + T.fh * 2 + 4 end

function Window.new(o)
  o = o or {}
  o.widgets = {}
  o.x, o.y = o.x or 8, o.y or 8
  -- never smaller than a window can be dragged to. Apps size themselves from the screen ("as wide as the
  -- desktop, up to 300"), and a machine with no monitor yet has a 0x0 screen, so that arithmetic goes negative:
  -- a window with a negative size draws its title and a stray frame line and nothing else. Clamp at the source.
  local reqW, reqH = o.w or 160, o.h or 120
  o.w, o.h = math.max(win.MIN_W(), reqW), math.max(win.MIN_H(), reqH)
  if reqW < win.MIN_W() or reqH < win.MIN_H() then
    o.needsFit = true -- sized against a screen that was not there: kernel.layout gives it a real size later
  end
  o.title = o.title or "Window"
  return setmetatable(o, Window)
end
function Window:client()
  if self.fullscreen or self.borderless then return self.x, self.y, self.w, self.h end
  local th = win.TITLE_H()
  return self.x + 1, self.y + th, self.w - 2, self.h - th - 1
end
function Window:add(widget)
  widget.window = self
  self.widgets[#self.widgets + 1] = widget
  if widget.layout then local _, _, cw, ch = self:client() widget:layout(cw, ch) end
  return widget
end
function Window:relayout()
  local _, _, cw, ch = self:client()
  for _, w in ipairs(self.widgets) do if w.layout then w:layout(cw, ch) end end
  self:invalidate()
end
function Window:invalidate() if win.invalidate then win.invalidate() end end
function Window:setfocus(widget)
  if self.focus == widget then return end
  if self.focus then self.focus.focused = false end
  self.focus = widget
  if widget then widget.focused = true end
  self:invalidate()
end
function Window:hit(px, py) return px >= self.x and py >= self.y and px < self.x + self.w and py < self.y + self.h end
function Window:titleHit(px, py) return not self.fullscreen and not self.borderless and self:hit(px, py) and py < self.y + win.TITLE_H() end
--- How many title-bar boxes this window has: three (minimise, maximise, close) for an ordinary window, one for
--- a dialog -- there is nowhere sensible to minimise a modal question to, and nothing to gain by maximising it.
function Window:titleButtons() return (self.resizable ~= false and not self.modal and not self.fullscreen) and 3 or 1 end

--- Which title-bar box is under the pointer: "close", "max", "min", or nil. Close stays hard against the right
--- edge whatever the count, so every click that used to close a window still does.
function Window:titleButton(px, py)
  if not self:titleHit(px, py) then return nil end
  local th = win.TITLE_H()
  local from = self.x + self.w - th - 1
  local names = { "close", "max", "min" }
  for i = 1, self:titleButtons() do
    if px >= from and px < from + th then return names[i] end
    from = from - th
  end
  return nil
end

function Window:closeHit(px, py) return self:titleButton(px, py) == "close" end
function Window:gripHit(px, py)
  if self.fullscreen or self.borderless or self.resizable == false then return false end
  local g = win.GRIP()
  return self:hit(px, py) and px >= self.x + self.w - g and py >= self.y + self.h - g
end
function Window:draw(active)
  if self.fullscreen then
    if self.ondraw then self.ondraw(self) end
    return
  end
  local th = win.TITLE_H()
  if not self.borderless then
    bevel(self.x, self.y, self.w, self.h, T.frame, T.frameLight, T.frameDark)
    gfx.fill(self.x + 1, self.y + 1, self.w - 2, th - 1, active and T.title or T.titleInactive)
    local nb = self:titleButtons()
    text(self.x + 4, self.y + 2, fit(self.title, self.w - th * nb - 8), T.titleText)
    -- the title-bar boxes, right to left: close, maximise (a box, or two boxes once it is maximised), minimise
    local glyph = { "x", self.maximized and "\"" or "[", "_" }
    for i = 1, nb do
      local bx = self.x + self.w - th * i - 1
      bevel(bx, self.y + 1, th, th - 1, T.button, T.frameLight, T.frameDark)
      text(bx + math.floor((th - T.fw) / 2), self.y + 2, glyph[i], T.buttonText)
    end
  end
  local cx, cy, cw, ch = self:client()
  gfx.fill(cx, cy, cw, ch, self.bg or T.panel)
  gfx.clip(cx, cy, cw, ch)
  if self.ondraw then self.ondraw(self, cx, cy, cw, ch) end
  for _, w in ipairs(self.widgets) do if not w.hidden then w:draw(cx, cy) end end
  gfx.clip()
  if self.resizable ~= false and not self.borderless then
    -- the grip: three diagonal lines in the corner, over whatever the client area put there
    local x1, y1 = self.x + self.w - 2, self.y + self.h - 2
    for i = 2, win.GRIP() - 2, 3 do gfx.line(x1 - i, y1, x1, y1 - i, T.frameDark) end
  end
end
--- A pointer moving with no button down. Only menus care, so only menus implement `hover` on a widget; every
--- other window ignores it and costs nothing (the kernel does not even redraw for a bare move).
function Window:hover(px, py)
  local w, lx, ly = self:widgetAt(px, py)
  if w and w.hover then w:hover(lx, ly) end
end

function Window:widgetAt(px, py)
  local cx, cy = self:client()
  local lx, ly = px - cx, py - cy
  for i = #self.widgets, 1, -1 do
    local w = self.widgets[i]
    if w:contains(lx, ly) and not w.hidden then return w, lx, ly end
  end
  return nil, lx, ly
end
function Window:press(px, py, button)
  local w, lx, ly = self:widgetAt(px, py)
  if w and (w.press ~= Widget.press or w.release ~= Widget.release or w.char ~= Widget.char or w.key ~= Widget.key) then
    self:setfocus(w)
  end
  self.pressed = w
  if w then w:press(lx, ly, button) end
  if self.onpress then self.onpress(self, lx, ly, button) end
end
--- The right button inside a window's client area. Nothing handles it by default, so an app opts in with
--- `onrightpress(self, lx, ly, px, py)` and every other window behaves exactly as it did. Both coordinate pairs
--- are passed because the app decides *what* from the local ones and places the menu with the screen ones.
function Window:rightpress(px, py)
  local w, lx, ly = self:widgetAt(px, py)
  if w and w.rightpress and w:rightpress(lx, ly) then return true end
  if self.onrightpress then return self.onrightpress(self, lx, ly, px, py) end
  return false
end

function Window:release(px, py, button)
  local cx, cy = self:client()
  local w = self.pressed
  self.pressed = nil
  if w then w:release(px - cx, py - cy, button) end
end
function Window:drag(px, py)
  local cx, cy = self:client()
  if self.pressed then self.pressed:drag(px - cx, py - cy) end
end
function Window:key(code, down, mods)
  if self.focus and self.focus:key(code, down, mods) then return true end
  if self.onkey then return self.onkey(self, code, down, mods) end
  return false
end
function Window:char(cp)
  if self.focus and self.focus:char(cp) then return true end
  if self.onchar then return self.onchar(self, cp) end
  return false
end
function Window:wheel(dy, px, py)
  local w = self:widgetAt(px, py)
  if w and w:wheel(dy) then return true end
  if self.onwheel then return self.onwheel(self, dy) end
  return false
end
function Window:paste(s)
  if self.focus and self.focus:paste(s) then return true end
  if self.onpaste then return self.onpaste(self, s) end
  return false
end

--- A small modal question: win.ask(title, message, {"Yes", "No"}, function(answer) end)
function win.ask(title, message, buttons, cb)
  local sw, sh = gfx.size()
  local w = math.min(sw - 8, math.max(120, win.textw(message) + 16))
  local h = win.TITLE_H() + T.fh * 3 + 16
  local dlg = Window.new{ title = title, x = math.floor((sw - w) / 2), y = math.floor((sh - h) / 2), w = w, h = h, modal = true, resizable = false }
  dlg:add(Label{ x = 4, y = 3, w = w - 10, text = message })
  local bw = math.floor((w - 12 - (#buttons - 1) * 4) / #buttons)
  for i, b in ipairs(buttons) do
    dlg:add(Button{ x = 4 + (i - 1) * (bw + 4), y = T.fh + 8, w = bw, h = T.fh + 6, text = b, onclick = function()
      kernel.close(dlg)
      if cb then cb(b, i) end
    end })
  end
  kernel.show(dlg)
  return dlg
end

--- Several lines and an OK button: win.info(title, {"line", "line"}). win.ask's message is a single Label and
--- a Label is one line, so anything with a newline in it came out as control characters -- which is what a
--- Properties box is made of.
function win.info(title, lines, cb)
  local sw, sh = gfx.size()
  local widest = 0
  for _, l in ipairs(lines) do widest = math.max(widest, win.textw(l)) end
  local w = math.min(sw - 8, math.max(120, widest + 14))
  local h = win.TITLE_H() + #lines * (T.fh + 2) + T.fh + 16
  local dlg = Window.new{ title = title, modal = true, resizable = false,
    x = math.floor((sw - w) / 2), y = math.max(0, math.floor((sh - h) / 2)) }
  dlg.w, dlg.h, dlg.needsFit = w, h, nil
  for i, l in ipairs(lines) do dlg:add(Label{ x = 4, y = 3 + (i - 1) * (T.fh + 2), w = w - 10, text = l }) end
  dlg:add(Button{ x = math.floor((w - T.fw * 8) / 2), y = h - win.TITLE_H() - T.fh - 8, w = T.fw * 8, h = T.fh + 6,
    text = "OK", onclick = function() kernel.close(dlg) if cb then cb() end end })
  kernel.show(dlg)
  return dlg
end

--- A one-line question: win.prompt(title, message, default, cb) -> cb(text) with nil on Cancel.
function win.prompt(title, message, default, cb)
  local sw, sh = gfx.size()
  local w = math.min(sw - 8, math.max(150, win.textw(message) + 16))
  local h = win.TITLE_H() + T.fh * 3 + 26
  local dlg = Window.new{ title = title, x = math.floor((sw - w) / 2), y = math.floor((sh - h) / 2), w = w, h = h, modal = true, resizable = false }
  dlg:add(Label{ x = 4, y = 3, w = w - 10, text = message })
  local field = dlg:add(TextField{ x = 4, y = T.fh + 6, w = w - 10, h = T.fh + 6, text = default or "" })
  field.cursor = #field.text
  local function done(ok) kernel.close(dlg) if cb then cb(ok and field.text or nil) end end
  field.onenter = function() done(true) end
  local bw = math.floor((w - 16) / 2)
  dlg:add(Button{ x = 4, y = T.fh * 2 + 16, w = bw, h = T.fh + 6, text = "OK", onclick = function() done(true) end })
  dlg:add(Button{ x = 8 + bw, y = T.fh * 2 + 16, w = bw, h = T.fh + 6, text = "Cancel", onclick = function() done(false) end })
  kernel.show(dlg)
  dlg:setfocus(field)
  return dlg
end

-- Menu ---------------------------------------------------------------------------------------------
-- A popup menu (ROADMAP §9 U6): the start menu, and the right-click menu on the desktop, an icon, a title bar
-- and a taskbar button -- all the same thing. It lives in a **borderless window marked `popup`**, which is how
-- the kernel knows to dismiss it on the next click elsewhere, keep it out of the taskbar and never save it with
-- the desktop. It sits down here rather than with the other widgets because it needs Window, which is below them.
--
-- An item is `{ text = "Open", onclick = f }`, a rule (`{ sep = true }`), or a submenu
-- (`{ text = "Programs", submenu = items }`, or a function returning items, so a long list is only built when
-- it is opened). `disabled = true` greys one out; `check = true` marks it.
local Menu = subclass("Menu")

function Menu:rowh(item) return (item and item.sep) and 4 or (T.fh + 4) end

function Menu:itemAt(ly)
  local y = 0
  for i, it in ipairs(self.items) do
    local h = self:rowh(it)
    if ly >= y and ly < y + h then return i, it end
    y = y + h
  end
end

function Menu:itemY(i)
  local y = 0
  for j = 1, i - 1 do y = y + self:rowh(self.items[j]) end
  return y
end

function Menu:draw(ox, oy)
  local x, y = ox + self.x, oy + self.y
  for i, it in ipairs(self.items) do
    local h = self:rowh(it)
    if it.sep then
      gfx.line(x + 3, y + 1, x + self.w - 4, y + 1, T.frameDark)
      gfx.line(x + 3, y + 2, x + self.w - 4, y + 2, T.frameLight)
    else
      local hot = i == self.selected and not it.disabled
      if hot then gfx.fill(x, y, self.w, h, T.sel) end
      local fg = it.disabled and T.disabled or (hot and T.selText or T.text)
      text(x + 4, y + 2, it.check and "*" or " ", fg)
      text(x + 4 + T.fw, y + 2, fit(it.text or "", self.w - 10 - T.fw - (it.submenu and T.fw or 0)), fg)
      if it.submenu then text(x + self.w - T.fw - 3, y + 2, ">", fg) end
    end
    y = y + h
  end
end

--- Highlight what the pointer is over, and open a submenu as soon as it is reached -- with no timer, because a
--- menu that waits for a dwell needs a clock the kernel would have to keep waking for.
function Menu:hover(lx, ly)
  local i, it = self:itemAt(ly)
  if i == self.selected then return end
  self.selected = i
  if self.window and self.window.child then win.closemenu(self.window.child) self.window.child = nil end
  if it and it.submenu and not it.disabled then self:opensub(i, it) end
  self:invalidate()
end

function Menu:opensub(i, it)
  local wd = self.window
  if not wd then return end
  local items = type(it.submenu) == "function" and it.submenu() or it.submenu
  if type(items) ~= "table" or #items == 0 then
    items = { { text = "(none)", disabled = true } }
  end
  wd.child = win.menu(wd.x + wd.w - 2, wd.y + self:itemY(i) - 1, items, { parent = wd })
end

function Menu:press(lx, ly, button)
  self.selected = self:itemAt(ly)
  self:invalidate()
end

--- Choosing an item closes the *whole* chain first and calls the handler afterwards, so a handler is free to
--- open a window, a dialog or another menu without the one it came from being torn down under it.
function Menu:release(lx, ly, button)
  local _, it = self:itemAt(ly)
  if not it or it.sep or it.disabled then return end
  if it.submenu then return end -- opened by the hover; a click on the parent row just leaves it open
  win.closemenu(win.rootmenu(self.window))
  if it.onclick then it.onclick(it) end
end

function Menu:key(code, down, mods)
  if not down then return false end
  local n = #self.items
  local function step(d)
    local i = (self.selected or (d > 0 and 0 or n + 1))
    for _ = 1, n do
      i = i + d
      if i < 1 then i = n elseif i > n then i = 1 end
      local it = self.items[i]
      if it and not it.sep and not it.disabled then self.selected = i return end
    end
  end
  if code == KEY.up then step(-1)
  elseif code == KEY.down then step(1)
  elseif code == KEY.esc or code == KEY.left then win.closemenu(self.window)
  elseif code == KEY.enter or code == KEY.kpenter or code == KEY.right then
    local it = self.items[self.selected or 0]
    if not it then return true end
    if it.submenu then
      if self.window and self.window.child then win.closemenu(self.window.child) self.window.child = nil end
      self:opensub(self.selected, it)
    else
      win.closemenu(win.rootmenu(self.window))
      if it.onclick then it.onclick(it) end
    end
    return true
  else return false end
  self:invalidate()
  return true
end

--- The menu at the top of a chain of submenus.
function win.rootmenu(wd)
  while wd and wd.parentMenu do wd = wd.parentMenu end
  return wd
end

--- Close a menu and everything it opened.
function win.closemenu(wd)
  while wd and not wd.closed do
    local child = wd.child
    wd.child = nil
    kernel.close(wd)
    wd = child
  end
end

--- win.menu(x, y, items [, opts]) -> the menu's window, or nil if there is nothing to show.
--- (x, y) is where it would *like* its top-left corner; it flips and clamps to stay on the desktop, so a start
--- menu asked to open at the taskbar's top edge flips **up**, which is exactly what the taskbar wants.
function win.menu(x, y, items, opts)
  opts = opts or {}
  local list = {}
  for _, it in ipairs(items or {}) do if it then list[#list + 1] = it end end
  if #list == 0 then return nil end
  local body = Menu{ items = list }
  local w = 0
  local h = 2
  for _, it in ipairs(list) do
    if not it.sep then
      w = math.max(w, win.textw(it.text or "") + 12 + T.fw + (it.submenu and T.fw or 0))
    end
    h = h + body:rowh(it)
  end
  local sw, sh = gfx.size()
  local deskH = sh - (kernel and kernel.taskbarH or 0) -- a menu never covers the taskbar it came from
  w = math.min(math.max(w, T.fw * 8), sw)
  h = math.min(h, math.max(T.fh + 6, deskH))
  local wd = Window.new{ title = "menu", borderless = true, resizable = false, x = 0, y = 0 }
  -- past Window.new's minimum size on purpose: a two-item menu is smaller than any draggable window, and the
  -- `needsFit` that clamp sets would have the next layout blow it up to a default-sized window.
  wd.w, wd.h, wd.needsFit = w, h, nil
  wd.popup, wd.parentMenu = true, opts.parent
  wd.x = math.max(0, math.min(x, sw - w))
  wd.y = y
  if wd.y + h > deskH then wd.y = math.max(0, (opts.parent and deskH - h or y - h)) end
  wd.y = math.max(0, wd.y)
  body.x, body.y, body.w, body.h = 0, 1, w, h - 2
  -- a borderless window draws no frame of its own, and a menu without one is a floating list of words
  wd.ondraw = function(self, cx, cy, cw, ch) bevel(cx, cy, cw, ch, T.frame, T.frameLight, T.frameDark) end
  wd:add(body)
  wd.menu = body
  kernel.show(wd)
  wd:setfocus(body)
  return wd
end

return win
