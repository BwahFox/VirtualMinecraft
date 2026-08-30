-- Sprite (ROADMAP §9 U7, the catalogue's "tools that make more software"): a sprite *sheet* editor.
--
-- **Why this is not Paint.** Paint edits one picture and saves one `.spr`. A game does not want a picture, it
-- wants sixteen of them addressed by number — a player facing four ways, an explosion in five frames, a tileset.
-- So the document here is a grid of tiles held in **one image**, and the payoff is Export: a Lua file a game
-- loads in one line, with `sheet.draw(x, y, n)` already written.
--
-- **The sheet is a plain wide `.spr`, on purpose.** `gfx.blit` has always taken a `stride` — the source row
-- width — which means tile *n* of a sheet is just a blit that starts part-way into the buffer and steps by the
-- sheet's width. No engine change, no new file format for the *image*, and Paint can still open an exported
-- sheet and scribble on it. Export writes the `.lua` and the `.spr` together for exactly that reason.
--
-- **Three panes**: the whole sheet at 1:1 on the left (so you can see the thing you are making), one tile
-- magnified in the middle (so you can hit a pixel), the palette down the right. Everything else is on the
-- right-click menu, because four buttons is what the bottom of a 1x1 monitor's window holds.
--
-- **Pixels live in a table of integers, not in a string.** A string would make every pen stroke an O(sheet)
-- copy and a flood fill O(sheet²); the string is built only when something needs to blit, and cached until the
-- next edit.
local T = win.theme
local KEY = win.KEY
local DIR = "/disk/sprites"
local EXT = ".sht"

-- kernel.top() is our own window at launch; scanning kernel.programs for "main.lua" picks the wrong one when a
-- second CD is also running a main.lua (Sheet learned this in session 20).
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

---------------------------------------------------------------------------------------------------- the sheet
local tw, th, cols, rows = 16, 16, 4, 4
local W, H = tw * cols, th * rows
local px = {}                     -- px[y * W + x + 1] = palette index, 0..255
local path, docName = nil, nil    -- the file this sheet came from
local dirty = false
local keyCol = 0                  -- the index Export marks transparent
local colour = 9
local page = 0                    -- palette page (16 colours each)
local tool = "pen"                -- "pen" | "fill" | "pick"
local tile = 0                    -- the tile being edited, 0-based
local cx, cy = 0, 0               -- the pixel cursor inside that tile
local clipTile = nil              -- the copied tile, as a flat table
local undo = {}                   -- { tile = n, px = {...} }, newest last
local UNDO_MAX = 32
local strCache = nil              -- the sheet as a byte string, for gfx.blit

local function blank(n) local t2 = {} for i = 1, n do t2[i] = 0 end return t2 end

local function newSheet(a, b, c, d)
  tw, th, cols, rows = a, b, c, d
  W, H = tw * cols, th * rows
  px = blank(W * H)
  tile, cx, cy = 0, 0, 0
  undo = {}
  strCache = nil
  dirty = true
end

local function getp(x, y) return px[y * W + x + 1] or 0 end
local function setp(x, y, c)
  local i = y * W + x + 1
  if px[i] ~= c then px[i] = c strCache = nil dirty = true end
end

local function tileOrigin(n) return (n % cols) * tw, math.floor(n / cols) * th end

--- The sheet as one byte string, rebuilt only after an edit. gfx.blit wants a string; everything else wants the
--- table, and rebuilding 4096 characters on every frame would be the whole frame.
local function sheetString()
  if not strCache then
    local parts = {}
    for i = 1, W * H do parts[i] = string.char(px[i] or 0) end
    strCache = table.concat(parts)
  end
  return strCache
end

---------------------------------------------------------------------------------------------------- undo
--- One entry per *stroke*, not per pixel: a drag that paints forty pixels is one thing you meant to do, so it is
--- one thing Undo takes back. The snapshot is a tile, not the sheet — 256 numbers rather than 4096.
local function pushUndo()
  local ox, oy = tileOrigin(tile)
  local snap = {}
  local k = 0
  for y = 0, th - 1 do
    for x = 0, tw - 1 do k = k + 1 snap[k] = getp(ox + x, oy + y) end
  end
  undo[#undo + 1] = { tile = tile, px = snap }
  if #undo > UNDO_MAX then table.remove(undo, 1) end
end

local function doUndo()
  local e = table.remove(undo)
  if not e then kernel.notify("Nothing to undo", 2) return false end
  tile = e.tile
  local ox, oy = tileOrigin(tile)
  local k = 0
  for y = 0, th - 1 do
    for x = 0, tw - 1 do k = k + 1 setp(ox + x, oy + y, e.px[k]) end
  end
  return true
end

---------------------------------------------------------------------------------------------------- window
local wd = me.window
wd.fullscreen = false
wd.title = "Sprite"
wd.w = math.min(math.max(kernel.w - kernel.iconW - 8, 170), 340)
wd.h = math.min(math.max(kernel.h - kernel.taskbarH - 20, 120), 260)
wd.x, wd.y = kernel.iconW + 4, 12
wd.minW, wd.minH = T.fw * 22, T.fh * 11
wd:relayout()

local sheetv = wd:add(win.Label{})    -- Labels used as bare canvases; their methods are replaced below
local canvas = wd:add(win.Label{})
local palette = wd:add(win.Label{})
local status = wd:add(win.Label{ text = "" })
local bh = T.fh + 6
local b1 = wd:add(win.Button{ text = "Open", h = bh })
local b2 = wd:add(win.Button{ text = "Save", h = bh })
local b3 = wd:add(win.Button{ text = "Export", h = bh })
local b4 = wd:add(win.Button{ text = "Close", h = bh })
local buttons = { b1, b2, b3, b4 }

local swatch = T.fh + 4
local palW = swatch * 2 + 2
local cell = 4                        -- pixels per sheet pixel in the zoom canvas; set by canvas.layout
local sx, sy = 0, 0                   -- the sheet view's scroll, in pixels

local function bodyH(ch) return ch - bh - T.fh - 6 end

sheetv.layout = function(self, cw, ch)
  local room = math.max(tw, math.floor((cw - palW - 8) / 2))
  self.x, self.y = 0, 0
  self.w, self.h = math.min(W, room), math.min(H, bodyH(ch))
end
canvas.layout = function(self, cw, ch)
  self.x, self.y = sheetv.w + 4, 0
  self.w, self.h = cw - palW - self.x - 4, bodyH(ch)
  cell = math.max(1, math.floor(math.min(self.w / tw, self.h / th)))
end
palette.layout = function(self, cw, ch)
  self.x, self.y, self.w, self.h = cw - palW, 0, palW, math.min(swatch * 8 + 2, bodyH(ch))
end
status.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 2, ch - bh - T.fh - 2, cw - 4, T.fh end
for i, b in ipairs(buttons) do
  b.layout = function(self, cw, ch)
    local bw = math.floor((cw - 2 * (#buttons - 1)) / #buttons)
    self.x, self.y, self.w = (i - 1) * (bw + 2), ch - bh, bw
  end
end
wd:relayout()

---------------------------------------------------------------------------------------------------- status
local function setStatus()
  status.text = string.format("%dx%d  t%d/%d  %s  c%d  key%d%s", tw, th, tile + 1, cols * rows, tool, colour,
    keyCol, docName and ("  " .. docName) or "")
  wd:invalidate()
end

--- Keeps the current tile inside the sheet view, which is what makes a sheet bigger than the pane usable at all.
local function scrollIntoView()
  local ox, oy = tileOrigin(tile)
  if ox < sx then sx = ox end
  if oy < sy then sy = oy end
  if ox + tw > sx + sheetv.w then sx = ox + tw - sheetv.w end
  if oy + th > sy + sheetv.h then sy = oy + th - sheetv.h end
  sx = math.max(0, math.min(sx, math.max(0, W - sheetv.w)))
  sy = math.max(0, math.min(sy, math.max(0, H - sheetv.h)))
end

---------------------------------------------------------------------------------------------------- drawing
sheetv.draw = function(self, ox, oy)
  local x0, y0 = ox + self.x, oy + self.y
  local vw, vh = math.min(self.w, W - sx), math.min(self.h, H - sy)
  gfx.fill(x0, y0, self.w, self.h, T.frameDark)
  -- One blit for the whole visible sheet: `stride = W` steps a source row, and the offset picks the corner.
  gfx.blit(x0, y0, vw, vh, sheetString():sub(sy * W + sx + 1), nil, W)
  local tx, ty = tileOrigin(tile)
  gfx.rect(x0 + tx - sx - 1, y0 + ty - sy - 1, tw + 2, th + 2, T.accent)
  gfx.rect(x0 - 1, y0 - 1, self.w + 2, self.h + 2, T.frameDark)
end

canvas.draw = function(self, ox, oy)
  local x0, y0 = ox + self.x, oy + self.y
  local tx, ty = tileOrigin(tile)
  for y = 0, th - 1 do
    for x = 0, tw - 1 do
      gfx.fill(x0 + x * cell, y0 + y * cell, cell, cell, getp(tx + x, ty + y))
    end
  end
  -- The pixel cursor is drawn in two colours so it is visible whatever is under it.
  if cell >= 3 then
    gfx.rect(x0 + cx * cell, y0 + cy * cell, cell, cell, 0)
    gfx.rect(x0 + cx * cell - 1, y0 + cy * cell - 1, cell + 2, cell + 2, 7)
  end
  gfx.rect(x0 - 1, y0 - 1, tw * cell + 2, th * cell + 2, T.frameDark)
end

palette.draw = function(self, ox, oy)
  local x0, y0 = ox + self.x, oy + self.y
  for i = 0, 15 do
    local c = page * 16 + i
    local x, y = x0 + (i % 2) * swatch + 1, y0 + math.floor(i / 2) * swatch + 1
    gfx.fill(x, y, swatch - 1, swatch - 1, c)
    if c == keyCol then
      -- the transparent index wears a slash, so "which colour vanishes in my game" is answerable at a glance
      gfx.line(x, y + swatch - 2, x + swatch - 2, y, 7)
    end
    if c == colour then
      gfx.rect(x, y, swatch - 1, swatch - 1, 7)
      gfx.rect(x + 1, y + 1, swatch - 3, swatch - 3, 0)
    end
  end
end

---------------------------------------------------------------------------------------------------- editing
local function flood(x0, y0, from, to)
  if from == to then return end
  local ox, oy = tileOrigin(tile)
  local stack = { { x0, y0 } }
  local n = 0
  while #stack > 0 and n < 8192 do
    local p = table.remove(stack)
    local x, y = p[1], p[2]
    if x >= 0 and y >= 0 and x < tw and y < th and getp(ox + x, oy + y) == from then
      setp(ox + x, oy + y, to)
      n = n + 1
      stack[#stack + 1] = { x + 1, y }
      stack[#stack + 1] = { x - 1, y }
      stack[#stack + 1] = { x, y + 1 }
      stack[#stack + 1] = { x, y - 1 }
    end
  end
end

--- Applies the current tool at a tile-local pixel. `fresh` is true on a press and false while dragging, which is
--- what keeps one stroke to one undo entry.
local function apply(x, y, fresh)
  if x < 0 or y < 0 or x >= tw or y >= th then return end
  local ox, oy = tileOrigin(tile)
  if tool == "pick" then
    colour = getp(ox + x, oy + y)
    setStatus()
    return
  end
  if fresh then pushUndo() end
  if tool == "fill" then
    flood(x, y, getp(ox + x, oy + y), colour)
  else
    setp(ox + x, oy + y, colour)
  end
  cx, cy = x, y
  wd:invalidate()
end

-- The right button never arrives here: Window:press is the left button, and the right one goes to
-- Window:rightpress, which is where the tile menu lives. The eyedropper is the Pick tool (`p` cycles to it).
canvas.press = function(self, lx, ly)
  apply(math.floor((lx - self.x) / cell), math.floor((ly - self.y) / cell), true)
end
canvas.drag = function(self, lx, ly)
  if tool ~= "pen" then return end
  apply(math.floor((lx - self.x) / cell), math.floor((ly - self.y) / cell), false)
end

sheetv.press = function(self, lx, ly)
  local x, y = lx - self.x + sx, ly - self.y + sy
  local n = math.floor(y / th) * cols + math.floor(x / tw)
  if n >= 0 and n < cols * rows then tile = n scrollIntoView() setStatus() end
end

palette.press = function(self, lx, ly, button)
  local i = math.floor((lx - self.x - 1) / swatch) + math.floor((ly - self.y - 1) / swatch) * 2
  if i < 0 or i > 15 then return end
  if button == 2 then keyCol = page * 16 + i else colour = page * 16 + i end
  setStatus()
end
palette.rightpress = function(self, lx, ly) palette.press(self, lx, ly, 2) return true end

---------------------------------------------------------------------------------------------------- tile ops
local function eachTilePixel(fn)
  pushUndo()
  local ox, oy = tileOrigin(tile)
  local old = {}
  local k = 0
  for y = 0, th - 1 do
    for x = 0, tw - 1 do k = k + 1 old[k] = getp(ox + x, oy + y) end
  end
  for y = 0, th - 1 do
    for x = 0, tw - 1 do
      local nx, ny = fn(x, y)
      setp(ox + x, oy + y, old[ny * tw + nx + 1])
    end
  end
  wd:invalidate()
end

local function flipH() eachTilePixel(function(x, y) return tw - 1 - x, y end) end
local function flipV() eachTilePixel(function(x, y) return x, th - 1 - y end) end
local function shift(dx, dy)
  eachTilePixel(function(x, y) return (x - dx) % tw, (y - dy) % th end)
end

local function copyTile()
  local ox, oy = tileOrigin(tile)
  clipTile = { w = tw, h = th, px = {} }
  local k = 0
  for y = 0, th - 1 do
    for x = 0, tw - 1 do k = k + 1 clipTile.px[k] = getp(ox + x, oy + y) end
  end
  kernel.notify("Tile " .. (tile + 1) .. " copied", 2)
end

local function pasteTile()
  if not clipTile then kernel.notify("Copy a tile first", 2) return end
  if clipTile.w ~= tw or clipTile.h ~= th then kernel.notify("That tile is a different size", 3) return end
  pushUndo()
  local ox, oy = tileOrigin(tile)
  local k = 0
  for y = 0, th - 1 do
    for x = 0, tw - 1 do k = k + 1 setp(ox + x, oy + y, clipTile.px[k]) end
  end
  wd:invalidate()
end

local function clearTile()
  pushUndo()
  local ox, oy = tileOrigin(tile)
  for y = 0, th - 1 do
    for x = 0, tw - 1 do setp(ox + x, oy + y, keyCol) end
  end
  wd:invalidate()
end

---------------------------------------------------------------------------------------------------- files
local function hex()
  local parts = {}
  for i = 1, W * H do parts[i] = string.format("%02x", px[i] or 0) end
  return table.concat(parts)
end

local function toTable()
  return { tw = tw, th = th, cols = cols, rows = rows, key = keyCol, data = hex() }
end

local function fromTable(t2)
  assert(type(t2) == "table" and type(t2.data) == "string", "not a sheet")
  tw, th, cols, rows = t2.tw or 16, t2.th or 16, t2.cols or 4, t2.rows or 4
  W, H = tw * cols, th * rows
  keyCol = t2.key or 0
  px = {}
  for i = 1, W * H do px[i] = tonumber(t2.data:sub(i * 2 - 1, i * 2), 16) or 0 end
  tile, cx, cy, sx, sy = 0, 0, 0, 0, 0
  undo = {}
  strCache = nil
end

local function ensureDir() if not fs.exists(DIR) then fs.mkdir(DIR) end end

--- The Lua a game loads. It carries the pixels as hex and decodes them once at load, then `draw` is a single
--- `gfx.blit` with the sheet's width as the stride — the same trick the editor's own sheet view uses.
local function exportLua(base)
  local out = {}
  local function w(s) out[#out + 1] = s end
  w("-- " .. base .. ": a sprite sheet exported by Sprite (" .. cols .. "x" .. rows .. " tiles of " .. tw .. "x" .. th .. ").")
  w("-- Usage:  local s = fs.run(\"" .. DIR .. "/" .. base .. ".lua\")")
  w("--         s.draw(20, 20, 1)          -- tile 1 (they run left to right, top to bottom)")
  w("--         s.draw(20, 20, 1, s.key)   -- ... with the transparent colour left out")
  w("local sheet = { w = " .. W .. ", h = " .. H .. ", tw = " .. tw .. ", th = " .. th ..
    ", cols = " .. cols .. ", rows = " .. rows .. ", n = " .. (cols * rows) .. ", key = " .. keyCol .. " }")
  w("local hex = \"" .. hex() .. "\"")
  w("local b = {}")
  w("for i = 1, sheet.w * sheet.h do b[i] = string.char(tonumber(hex:sub(i * 2 - 1, i * 2), 16)) end")
  w("sheet.data = table.concat(b)")
  w("--- draw(x, y, n [, key]): tile n, 1-based. key is the colour to treat as transparent.")
  w("function sheet.draw(x, y, n, key)")
  w("  n = ((n or 1) - 1) % sheet.n")
  w("  local col, row = n % sheet.cols, math.floor(n / sheet.cols)")
  w("  local off = row * sheet.th * sheet.w + col * sheet.tw")
  w("  gfx.blit(x, y, sheet.tw, sheet.th, sheet.data:sub(off + 1), key, sheet.w)")
  w("end")
  w("--- tile(n) -> a { w, h, data } the way gfx.loadsprite returns one, for gfx.sprite.")
  w("function sheet.tile(n)")
  w("  n = ((n or 1) - 1) % sheet.n")
  w("  local col, row = n % sheet.cols, math.floor(n / sheet.cols)")
  w("  local rowsOut = {}")
  w("  for y = 0, sheet.th - 1 do")
  w("    local at = (row * sheet.th + y) * sheet.w + col * sheet.tw + 1")
  w("    rowsOut[#rowsOut + 1] = sheet.data:sub(at, at + sheet.tw - 1)")
  w("  end")
  w("  return { w = sheet.tw, h = sheet.th, data = table.concat(rowsOut) }")
  w("end")
  w("return sheet")
  fs.write(DIR .. "/" .. base .. ".lua", table.concat(out, "\n") .. "\n")
end

local function doSave(saveAs)
  local function write(base)
    ensureDir()
    path = DIR .. "/" .. base .. EXT
    docName = base
    fs.write(path, json.encode(toTable()))
    dirty = false
    kernel.notify("Saved " .. fs.basename(path), 2)
    setStatus()
  end
  if path and not saveAs then
    local ok, err = pcall(function() write(docName) end)
    if not ok then kernel.notify(tostring(err), 4) end
    return
  end
  win.prompt("Save sheet", "Name", docName or "sheet", function(name)
    if not name or name == "" then return end
    if not fs.validname(name) then win.info("Sprite", { "Not a usable name.", fs.NAME_HELP }) return end
    local ok, err = pcall(function() write(name) end)
    if not ok then kernel.notify(tostring(err), 4) end
  end)
end

local function doExport()
  local base = docName
  local function go(name)
    local ok, err = pcall(function()
      ensureDir()
      exportLua(name)
      gfx.savesprite(DIR .. "/" .. name .. ".spr", W, H, sheetString())
    end)
    kernel.notify(ok and ("Exported " .. name .. ".lua and .spr") or tostring(err), 4)
  end
  if base then go(base) return end
  win.prompt("Export", "Name", "sheet", function(name)
    if not name or name == "" then return end
    if not fs.validname(name) then win.info("Sprite", { "Not a usable name.", fs.NAME_HELP }) return end
    go(name)
  end)
end

local function doOpen()
  ensureDir()
  local names = {}
  for _, f in ipairs(fs.list(DIR) or {}) do
    local n = type(f) == "table" and f.name or f
    if type(n) == "string" and n:sub(-#EXT) == EXT then names[#names + 1] = n end
  end
  if #names == 0 then kernel.notify("No sheets in " .. DIR .. " yet", 3) return end
  table.sort(names)
  local dlg = win.Window.new{ title = "Open sheet", x = kernel.iconW + 10, y = 20,
    w = T.fw * 24, h = T.fh * 9, modal = true, resizable = false }
  local list = dlg:add(win.List{ items = names, selected = 1 })
  local ok = dlg:add(win.Button{ text = "Open", h = bh })
  local no = dlg:add(win.Button{ text = "Cancel", h = bh })
  list.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 2, 2, cw - 4, ch - bh - 6 end
  ok.layout = function(self, cw, ch) self.x, self.y, self.w = 2, ch - bh - 2, math.floor(cw / 2) - 4 end
  no.layout = function(self, cw, ch) self.x, self.y, self.w = math.floor(cw / 2) + 2, ch - bh - 2, math.floor(cw / 2) - 4 end
  dlg:relayout()
  local function load()
    local n = names[list.selected]
    kernel.close(dlg)
    if not n then return end
    local okr, err = pcall(function()
      fromTable(json.decode(fs.read(DIR .. "/" .. n)))
      path, docName = DIR .. "/" .. n, n:sub(1, #n - #EXT)
      dirty = false
    end)
    if not okr then kernel.notify(tostring(err), 4) return end
    wd:relayout()
    setStatus()
  end
  list.onactivate = load
  ok.onclick = load
  no.onclick = function() kernel.close(dlg) end
  dlg:setfocus(list)
  kernel.show(dlg)
end

local function doNew()
  win.prompt("New sheet", "tile w h, then grid cols rows", tw .. " " .. th .. " " .. cols .. " " .. rows, function(s)
    if not s then return end
    local a, b, c, d = s:match("(%d+)%D+(%d+)%D+(%d+)%D+(%d+)")
    if not a then kernel.notify("Four numbers, e.g. 16 16 4 4", 4) return end
    a, b, c, d = tonumber(a), tonumber(b), tonumber(c), tonumber(d)
    -- The cap is memory, not principle: the pixels live in a Lua table, one number each.
    if a < 4 or b < 4 or a > 64 or b > 64 or c < 1 or d < 1 or a * c * b * d > 16384 then
      kernel.notify("Tiles 4-64, and at most 16384 pixels in the sheet", 5)
      return
    end
    newSheet(a, b, c, d)
    path, docName = nil, nil
    sx, sy = 0, 0
    wd:relayout()
    setStatus()
  end)
end

---------------------------------------------------------------------------------------------------- menus
wd.onrightpress = function(_, lx, ly, mx, my)
  if canvas:contains(lx, ly) then
    win.menu(mx, my, {
      { text = "Pen", onclick = function() tool = "pen" setStatus() end },
      { text = "Fill", onclick = function() tool = "fill" setStatus() end },
      { text = "Pick", onclick = function() tool = "pick" setStatus() end },
      { sep = true },
      { text = "Undo", onclick = function() if doUndo() then wd:invalidate() end end },
      { text = "Copy tile", onclick = copyTile },
      { text = "Paste tile", disabled = clipTile == nil, onclick = pasteTile },
      { text = "Clear tile", onclick = clearTile },
      { sep = true },
      { text = "Flip across", onclick = flipH },
      { text = "Flip down", onclick = flipV },
      { text = "Shift right", onclick = function() shift(1, 0) end },
      { text = "Shift down", onclick = function() shift(0, 1) end },
    })
    return true
  end
  win.menu(mx, my, {
    { text = "New sheet...", onclick = doNew },
    { text = "Open...", onclick = doOpen },
    { text = "Save as...", onclick = function() doSave(true) end },
    { text = "Export", onclick = doExport },
    { sep = true },
    { text = "Colour " .. colour .. " is transparent", onclick = function() keyCol = colour setStatus() end },
  })
  return true
end

---------------------------------------------------------------------------------------------------- keys
--- Everything here is reachable from the keyboard on purpose: it is how the emulator harness drives the program
--- (TESTING.md), and it is how anyone draws a straight line of pixels without a steady hand.
canvas.key = function(self, code, down, mods)
  if not down then return false end
  if code == KEY.left then cx = math.max(0, cx - 1)
  elseif code == KEY.right then cx = math.min(tw - 1, cx + 1)
  elseif code == KEY.up then cy = math.max(0, cy - 1)
  elseif code == KEY.down then cy = math.min(th - 1, cy + 1)
  elseif code == KEY.space then apply(cx, cy, true) return true
  elseif code == KEY.delete then
    pushUndo()
    local ox, oy = tileOrigin(tile)
    setp(ox + cx, oy + cy, keyCol)
  elseif code == KEY.tab then
    local n = cols * rows
    tile = (tile + (mods.shift and -1 or 1)) % n
    scrollIntoView()
  elseif code == KEY.pgup then colour = (colour - 1) % 256
  elseif code == KEY.pgdn then colour = (colour + 1) % 256
  elseif code == KEY.home then page = (page - 1) % 16
  elseif code == KEY["end"] then page = (page + 1) % 16
  elseif code == KEY.p then tool = (tool == "pen" and "fill") or (tool == "fill" and "pick") or "pen"
  elseif code == KEY.z then doUndo()
  elseif code == KEY.c then copyTile()
  elseif code == KEY.v then pasteTile()
  elseif code == KEY.x then clearTile()
  else return false end
  setStatus()
  return true
end

wd.onkey = function(_, code, down, mods)
  if not down then return false end
  if mods.ctrl and code == KEY.s then doSave(false) return true end
  if mods.ctrl and code == KEY.o then doOpen() return true end
  if mods.ctrl and code == KEY.n then doNew() return true end
  if mods.ctrl and code == KEY.z then if doUndo() then wd:invalidate() end return true end
  if code == KEY.esc then wd:setfocus(canvas) return true end
  return false
end

-- The harness (TESTING.md): the emulator's `exec` reaches these, so a script can save and export without
-- driving a prompt through the keyboard.
wd.canvas, wd.sheetv, wd.palette, wd.buttons = canvas, sheetv, palette, buttons
wd.harness = {
  save = doSave, open = doOpen, export = doExport, new = doNew, undo = doUndo,
  paint = function(x, y, c) if c then colour = c end apply(x, y, true) end,
  pick = function(n) tile = n scrollIntoView() setStatus() end,
  state = function() return { tw = tw, th = th, cols = cols, rows = rows, tile = tile, colour = colour, key = keyCol, name = docName } end,
}

b1.onclick = doOpen
b2.onclick = function() doSave(false) end
b3.onclick = doExport
b4.onclick = function() kernel.close(wd) end

---------------------------------------------------------------------------------------------------- go
--- A sheet that was never saved goes to /disk/sprites/untitled.sht rather than nowhere. onclose cannot put up a
--- dialog and cannot refuse to close, so the choice is between quietly keeping the work and quietly losing it —
--- and losing an evening's pixels to a misclicked X is not a thing this should do. (Sheet, session 20.)
local function saveOnExit()
  if not dirty then return end
  local p = path or (DIR .. "/untitled" .. EXT)
  local ok = pcall(function()
    ensureDir()
    fs.write(p, json.encode(toTable()))
  end)
  if ok then
    dirty = false
    if not path then kernel.notify("Unsaved sheet kept as " .. fs.basename(p), 4) end
  end
end
wd.onclose = saveOnExit

-- A first sheet, so an empty machine shows what the thing is for rather than sixteen black squares: a four-frame
-- blob that walks, which is the smallest thing that explains why a *sheet* and not a picture.
newSheet(16, 16, 4, 4)
do
  local art = {
    "....7777........", "...766667.......", "..76666667......", "..76677667......",
    "..76677667......", "..76666667......", "...766667.......", "....7777........",
    "...966669.......", "..96666669......", "..96666669......", "...966669.......",
    "....9..9........", "....9..9........", "...99..99.......", "................",
  }
  for y = 0, 15 do
    local row = art[y + 1]
    for x = 0, 15 do
      local ch = row:sub(x + 1, x + 1)
      if ch ~= "." then setp(x, y, tonumber(ch)) end
    end
  end
  -- frames 2..4: the same blob shifted, so the strip reads as an animation at a glance
  for n = 1, 3 do
    local ox = (n % 4) * 16
    local oy = math.floor(n / 4) * 16
    for y = 0, 15 do
      for x = 0, 15 do
        local sxp = (x - n) % 16
        setp(ox + x, oy + y, getp(sxp, y))
      end
    end
  end
end
dirty = true

setStatus()
wd:setfocus(canvas)
wd:invalidate()

while not wd.closed do os.sleep(120) end
saveOnExit()
return string.format("Sprite: %dx%d, %d tiles", tw, th, cols * rows)
