-- Paint: sprites and palettes for games (ROADMAP §7h §7). A 16×16 or 32×32 canvas, a palette page, pen and fill,
-- saved as /disk/sprites/<name>.spr (gfx.loadsprite / gfx.sprite draw them).
local app = { id = "paint", name = "Paint", icon = "P" }

function app.open(args)
  local T = win.theme
  local r = args.restore or {}
  local size = r.size or 16
  local pixels = r.pixels or string.rep("\0", size * size)
  local colour = r.colour or 9
  local page = r.page or 0
  local fillMode = false
  local wd = win.Window.new{ title = "Paint", x = kernel.iconW + 4, y = 6, w = math.min(kernel.w - kernel.iconW - 8, 280), h = math.min(kernel.h - kernel.taskbarH - 10, 220) }
  wd.minW, wd.minH = wd.w, wd.h -- fixed layout: no smaller than it opens
  local bh = T.fh + 6
  local names = { "Pen", "Fill", "Pg", "Size", "Save", "Load", "Clear" }
  local buttons = {}
  for i, n in ipairs(names) do buttons[i] = wd:add(win.Button{ text = n, h = bh }) end
  local canvas = wd:add(win.Label{})
  local palette = wd:add(win.Label{})
  local status = wd:add(win.Label{ text = "" })
  local swatch = T.fh + 4
  local cell
  canvas.layout = function(self, cw, ch)
    cell = math.max(2, math.floor(math.min(cw - swatch * 2 - 6, ch - bh - T.fh - 6) / size))
    self.x, self.y, self.w, self.h = 0, 0, cell * size + 1, cell * size + 1
  end
  palette.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = cw - swatch * 2 - 2, 0, swatch * 2 + 2, swatch * 8 + 2 end
  status.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, ch - bh - T.fh - 3, cw, T.fh end
  for i, b in ipairs(buttons) do
    b.layout = function(self, cw, ch)
      local bw = math.floor((cw - 2 * (#buttons - 1)) / #buttons)
      self.x, self.y, self.w, self.h = (i - 1) * (bw + 2), ch - bh, bw, bh
    end
  end
  wd:relayout()

  local function get(x, y) return pixels:byte(y * size + x + 1) end
  local function set(x, y, c)
    local i = y * size + x + 1
    pixels = pixels:sub(1, i - 1) .. string.char(c) .. pixels:sub(i + 1)
  end
  local function setStatus()
    status.text = string.format("%dx%d  colour %d  %s%s", size, size, colour, fillMode and "fill" or "pen", r.name and ("  " .. r.name) or "")
    wd:invalidate()
  end
  canvas.draw = function(self, ox, oy)
    local x0, y0 = ox + self.x, oy + self.y
    for y = 0, size - 1 do
      for x = 0, size - 1 do
        gfx.fill(x0 + x * cell, y0 + y * cell, cell, cell, get(x, y))
      end
    end
    gfx.rect(x0 - 1, y0 - 1, self.w + 1, self.h + 1, T.frameDark)
  end
  local function flood(x, y, from, to)
    if from == to then return end
    local stack = { { x, y } }
    local n = 0
    while #stack > 0 and n < 4096 do
      local p = table.remove(stack)
      local px, py = p[1], p[2]
      if px >= 0 and py >= 0 and px < size and py < size and get(px, py) == from then
        set(px, py, to)
        n = n + 1
        stack[#stack + 1] = { px + 1, py }
        stack[#stack + 1] = { px - 1, py }
        stack[#stack + 1] = { px, py + 1 }
        stack[#stack + 1] = { px, py - 1 }
      end
    end
  end
  local function paintAt(lx, ly)
    local x, y = math.floor((lx - canvas.x) / cell), math.floor((ly - canvas.y) / cell)
    if x < 0 or y < 0 or x >= size or y >= size then return end
    if fillMode then flood(x, y, get(x, y), colour) else set(x, y, colour) end
    wd:invalidate()
  end
  canvas.press = function(self, lx, ly) paintAt(lx, ly) end
  canvas.drag = function(self, lx, ly) if not fillMode then paintAt(lx, ly) end end
  palette.draw = function(self, ox, oy)
    local x0, y0 = ox + self.x, oy + self.y
    for i = 0, 15 do
      local c = page * 16 + i
      local x, y = x0 + (i % 2) * swatch + 1, y0 + math.floor(i / 2) * swatch + 1
      gfx.fill(x, y, swatch - 1, swatch - 1, c)
      if c == colour then gfx.rect(x, y, swatch - 1, swatch - 1, 7) gfx.rect(x + 1, y + 1, swatch - 3, swatch - 3, 0) end
    end
  end
  palette.press = function(self, lx, ly)
    local i = math.floor((lx - self.x - 1) / swatch) + math.floor((ly - self.y - 1) / swatch) * 2
    if i >= 0 and i < 16 then colour = page * 16 + i setStatus() end
  end
  buttons[1].onclick = function() fillMode = false setStatus() end
  buttons[2].onclick = function() fillMode = true setStatus() end
  buttons[3].onclick = function() page = (page + 1) % 16 wd:invalidate() end
  buttons[4].onclick = function()
    local newSize = size == 16 and 32 or 16
    local out = {}
    for y = 0, newSize - 1 do
      for x = 0, newSize - 1 do out[#out + 1] = string.char((x < size and y < size) and get(x, y) or 0) end
    end
    size, pixels = newSize, table.concat(out)
    wd:relayout()
    setStatus()
  end
  buttons[5].onclick = function()
    win.prompt("Save sprite", "Name", r.name or "sprite", function(name)
      if not name or name == "" then return end
      local ok, err = pcall(function()
        if not fs.exists("/disk/sprites") then fs.mkdir("/disk/sprites") end
        gfx.savesprite("/disk/sprites/" .. name .. ".spr", size, size, pixels)
      end)
      kernel.notify(ok and ("Saved /disk/sprites/" .. name .. ".spr") or tostring(err), 3)
      r.name = name
      setStatus()
    end)
  end
  buttons[6].onclick = function()
    win.prompt("Load sprite", "Name", r.name or "sprite", function(name)
      if not name or name == "" then return end
      local ok, err = pcall(function()
        local spr = gfx.loadsprite("/disk/sprites/" .. name .. ".spr")
        assert(spr.w == spr.h and (spr.w == 16 or spr.w == 32), "Paint edits 16x16 or 32x32 sprites")
        size, pixels = spr.w, spr.data
      end)
      kernel.notify(ok and "Loaded" or tostring(err), 3)
      r.name = name
      wd:relayout()
      setStatus()
    end)
  end
  buttons[7].onclick = function() pixels = string.rep("\0", size * size) wd:invalidate() end
  wd.paintAt, wd.pixels = paintAt, function() return pixels end -- the harness
  wd.save = function() return { size = size, pixels = pixels, colour = colour, page = page, name = r.name } end
  setStatus()
  return wd
end

function app.save(wd) return wd.save and wd.save() or nil end

return app
