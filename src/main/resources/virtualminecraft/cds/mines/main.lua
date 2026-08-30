-- Mines on the VirtualMinecraft Computer: a program on a CD (a directory with main.lua + program.txt). The
-- desktop is pointer-first, so this is too — left button opens, right flags — but a monitor you are standing at
-- with a keyboard works the same way with the arrows and Space. Ours, from the rules up: the mechanic is old, the
-- code and the look are this machine's.
local w, h = gfx.size()
local KEY = win.KEY
local KEY_F = 0x21                          -- win.KEY carries the letters the apps needed; F for "flag" is ours
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local HEAD = 14                             -- the status strip along the top
local CELL = math.max(8, math.min(16, math.floor((h - HEAD) / 18)))
local COLS = math.max(8, math.floor(w / CELL))
local ROWS = math.max(8, math.floor((h - HEAD) / CELL))
local MINES = math.max(5, math.floor(COLS * ROWS * 0.15))
local HALF = math.floor(CELL / 2)          -- Cobalt has no // operator; these are the ones the drawing wants
local THIRD = math.floor(CELL / 3)
local OX = math.floor((w - COLS * CELL) / 2)
local OY = HEAD + math.floor((h - HEAD - ROWS * CELL) / 2)

-- 1 blue, 2 green, 3 red, then the dark end of the palette: the classic reading, in this machine's 16 colours.
local NUMBER = { 12, 11, 8, 1, 4, 2, 5, 0 }
-- Three greys out of the palette's 6×6×6 cube (index 16 + 43k is r=g=b=k): a raised square, its lit edge and its
-- shadow. The 16 base colours only carry two greys, and a field of squares needs three to look like buttons.
local FACE, LIT, SHADE, FLAT = 145, 188, 102, 6
local cells, opened, flags, seeded, dead, won, started, stopped
local cur = { x = 1, y = 1 }
local input, buttons = {}, 0

local function at(x, y) return (x >= 1 and y >= 1 and x <= COLS and y <= ROWS) and cells[(y - 1) * COLS + x] or nil end

local function reset()
  cells, opened, flags, seeded, dead, won, started, stopped = {}, 0, 0, false, false, false, nil, nil
  for i = 1, COLS * ROWS do cells[i] = { mine = false, open = false, flag = false, n = 0 } end
end

-- Mines are laid *after* the first square is opened, so the first click can never lose. Everyone expects this
-- and nobody says it out loud.
local function seed(sx, sy)
  local placed = 0
  while placed < MINES do
    local x, y = math.random(COLS), math.random(ROWS)
    local c = at(x, y)
    if not c.mine and not (math.abs(x - sx) <= 1 and math.abs(y - sy) <= 1) then c.mine = true placed = placed + 1 end
  end
  for y = 1, ROWS do
    for x = 1, COLS do
      local n = 0
      for dy = -1, 1 do for dx = -1, 1 do
        local o = at(x + dx, y + dy)
        if o and o.mine and not (dx == 0 and dy == 0) then n = n + 1 end
      end end
      at(x, y).n = n
    end
  end
  seeded = true
  started = os.clock()
end

-- Opening a blank square opens its neighbours, and theirs: a stack, not recursion, because a 32×20 field of
-- zeroes would be 600 frames deep and this machine's stack is not free.
local function open(x, y)
  local c = at(x, y)
  if not c or c.open or c.flag then return end
  if not seeded then seed(x, y) end
  if c.mine then
    dead = true
    stopped = os.clock()
    for i = 1, COLS * ROWS do if cells[i].mine then cells[i].open = true end end
    snd.channel(1, snd.NOISE, 90, 0.7, 0, 0.35, 0, 0.1)
    return
  end
  local stack = { { x, y } }
  while #stack > 0 do
    local p = table.remove(stack)
    local q = at(p[1], p[2])
    if q and not q.open and not q.flag then
      q.open = true
      opened = opened + 1
      if q.n == 0 then
        for dy = -1, 1 do for dx = -1, 1 do
          if at(p[1] + dx, p[2] + dy) then stack[#stack + 1] = { p[1] + dx, p[2] + dy } end
        end end
      end
    end
  end
  snd.beep(660, 0.03, 0)
  if opened >= COLS * ROWS - MINES then
    won = true
    stopped = os.clock()
    for i = 1, COLS * ROWS do if cells[i].mine then cells[i].flag = true end end
    for k = 1, 3 do snd.channel(k, snd.SQUARE, 440 * k, 0.4, 0.01, 0.5, 0, 0.2) end
  end
end

local function flag(x, y)
  local c = at(x, y)
  if not c or c.open then return end
  c.flag = not c.flag
  flags = flags + (c.flag and 1 or -1)
  snd.beep(c.flag and 880 or 440, 0.02, 0)
end

local function draw()
  gfx.clear(SHADE)
  local left = MINES - flags
  local secs = started and math.floor((stopped or os.clock()) - started) or 0
  local status = dead and "boom - R starts again" or won and "clear! - R starts again" or (COLS .. "x" .. ROWS .. "  " .. MINES .. " mines")
  gfx.fill(0, 0, w, HEAD, 0)
  gfx.text(2, 3, "mines " .. left .. "   " .. secs .. "s", 7, nil, 1)
  gfx.text(w - #status * 6 - 2, 3, status, dead and 8 or won and 11 or 6, nil, 1)
  for y = 1, ROWS do
    for x = 1, COLS do
      local c = at(x, y)
      local px, py = OX + (x - 1) * CELL, OY + (y - 1) * CELL
      if c.open then
        gfx.fill(px, py, CELL - 1, CELL - 1, c.mine and 8 or FLAT)
        if c.mine then
          gfx.disc(px + HALF, py + HALF, math.max(2, math.floor(CELL / 4)), 0)
        elseif c.n > 0 then
          gfx.text(px + math.floor((CELL - 6) / 2), py + math.floor((CELL - 8) / 2), tostring(c.n), NUMBER[c.n], nil, 1)
        end
      else
        gfx.fill(px, py, CELL - 1, CELL - 1, FACE)
        gfx.line(px, py, px + CELL - 2, py, LIT)        -- lit along the top and left, shadowed along the bottom
        gfx.line(px, py, px, py + CELL - 2, LIT)        -- and right: the square still stands up
        gfx.line(px, py + CELL - 2, px + CELL - 2, py + CELL - 2, SHADE)
        gfx.line(px + CELL - 2, py, px + CELL - 2, py + CELL - 2, SHADE)
        if c.flag then
          gfx.fill(px + THIRD, py + 2, 2, CELL - 5, 0)
          gfx.fill(px + THIRD, py + 2, THIRD, math.max(2, math.floor(CELL / 4)), 8)
        end
      end
    end
  end
  if not (dead or won) then                              -- where the keyboard is, for a player who is typing
    local px, py = OX + (cur.x - 1) * CELL, OY + (cur.y - 1) * CELL
    gfx.rect(px - 1, py - 1, CELL, CELL, 10)
  end
end

if me then
  me.window.cursor = true
  kernel.showCursor(true)
  me.pointer = function(px, py, b, pressed)
    local x = math.floor((px - OX) / CELL) + 1
    local y = math.floor((py - OY) / CELL) + 1
    if at(x, y) then
      cur.x, cur.y = x, y
      if pressed and not (dead or won) then input.open = { x, y } end
      if b >= 2 and buttons < 2 and not (dead or won) then input.flag = { x, y } end
    end
    buttons = b
  end
  me.key = function(code, down)
    if not down then return end
    if code == KEY.up then cur.y = math.max(1, cur.y - 1)
    elseif code == KEY.down then cur.y = math.min(ROWS, cur.y + 1)
    elseif code == KEY.left then cur.x = math.max(1, cur.x - 1)
    elseif code == KEY.right then cur.x = math.min(COLS, cur.x + 1)
    elseif code == KEY.space then input.open = { cur.x, cur.y }
    elseif code == KEY_F then input.flag = { cur.x, cur.y }
    elseif code == KEY.r then input.reset = true
    elseif code == KEY.q or code == KEY.esc then input.quit = true end
    input.redraw = true
  end
end

reset()
draw()
gfx.present()
local lastSec = -1
while not input.quit do
  if input.reset then input.reset = nil reset() input.redraw = true end
  if input.open and not (dead or won) then local p = input.open input.open = nil open(p[1], p[2]) input.redraw = true end
  if input.flag and not (dead or won) then local p = input.flag input.flag = nil flag(p[1], p[2]) input.redraw = true end
  local secs = started and math.floor(os.clock() - started) or 0
  if input.redraw or (secs ~= lastSec and not (dead or won)) then
    input.redraw = nil
    lastSec = secs
    draw()
  end
  gfx.present()
end
return "Mines: " .. (won and "cleared" or dead and "boom" or (opened .. " squares"))
