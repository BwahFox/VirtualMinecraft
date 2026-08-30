-- Blocks on the VirtualMinecraft Computer: seven shapes, a ten-wide well, full rows go. The rules of a falling-block
-- game are nobody's property; the code, the look and the name are this machine's. A CD program (main.lua +
-- program.txt), keyboard-driven, with the held keys repeating on their own timer so moving feels like moving.
local w, h = gfx.size()
local KEY = win.KEY
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local COLS, ROWS = 10, 20
local CELL = math.max(5, math.min(14, math.floor(math.min(w * 0.55 / COLS, (h - 6) / ROWS))))
local WX = math.floor((w - CELL * COLS) / 2 - (w > 200 and CELL * 2 or 0))
local WY = math.floor((h - CELL * ROWS) / 2)
local PANEL = WX + CELL * COLS + 8

-- Each shape as the cells of its first turn inside a 4×4 box; the other three turns are worked out at load, so
-- there is one description of a shape and no table of magic numbers to get wrong.
local SHAPES = {
  { colour = 12, box = 4, cells = { { 0, 1 }, { 1, 1 }, { 2, 1 }, { 3, 1 } } }, -- the long one
  { colour = 10, box = 2, cells = { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 1 } } }, -- the square
  { colour = 11, box = 3, cells = { { 1, 0 }, { 2, 0 }, { 0, 1 }, { 1, 1 } } },
  { colour = 8,  box = 3, cells = { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 2, 1 } } },
  { colour = 13, box = 3, cells = { { 1, 0 }, { 0, 1 }, { 1, 1 }, { 2, 1 } } },
  { colour = 1,  box = 3, cells = { { 0, 0 }, { 0, 1 }, { 1, 1 }, { 2, 1 } } },
  { colour = 9,  box = 3, cells = { { 2, 0 }, { 0, 1 }, { 1, 1 }, { 2, 1 } } },
}
for _, s in ipairs(SHAPES) do
  s.turns = { s.cells }
  for t = 2, 4 do
    local prev, next = s.turns[t - 1], {}
    for i, c in ipairs(prev) do next[i] = { s.box - 1 - c[2], c[1] } end -- a quarter turn inside the box
    s.turns[t] = next
  end
end

local well, piece, nextShape, score, lines, level, over, paused
local input, held = {}, {}

local function newPiece(shape)
  local s = SHAPES[shape]
  return { shape = shape, turn = 1, x = math.floor((COLS - s.box) / 2), y = 0 }
end

local function cellsOf(p)
  local s = SHAPES[p.shape]
  local out = {}
  for i, c in ipairs(s.turns[p.turn]) do out[i] = { p.x + c[1], p.y + c[2] } end
  return out
end

local function fits(p)
  for _, c in ipairs(cellsOf(p)) do
    local x, y = c[1], c[2]
    if x < 0 or x >= COLS or y >= ROWS then return false end
    if y >= 0 and well[y * COLS + x] then return false end
  end
  return true
end

local function reset()
  well, score, lines, level, over, paused = {}, 0, 0, 1, false, false
  nextShape = math.random(#SHAPES)
  piece = newPiece(math.random(#SHAPES))
end

local function clearRows()
  local got = 0
  local y = ROWS - 1
  while y >= 0 do
    local full = true
    for x = 0, COLS - 1 do if not well[y * COLS + x] then full = false break end end
    if full then
      got = got + 1
      for yy = y, 1, -1 do
        for x = 0, COLS - 1 do well[yy * COLS + x] = well[(yy - 1) * COLS + x] end
      end
      for x = 0, COLS - 1 do well[x] = nil end
    else
      y = y - 1
    end
  end
  if got > 0 then
    lines = lines + got
    score = score + ({ 100, 300, 500, 800 })[got] * level
    level = 1 + math.floor(lines / 10)
    for k = 1, math.min(4, got) do snd.channel(k, snd.SQUARE, 330 * (k + got), 0.4, 0.005, 0.12, 0, 0.05) end
  end
end

local function land()
  for _, c in ipairs(cellsOf(piece)) do
    if c[2] >= 0 then well[c[2] * COLS + c[1]] = SHAPES[piece.shape].colour end
  end
  clearRows()
  piece = newPiece(nextShape)
  nextShape = math.random(#SHAPES)
  if not fits(piece) then
    over = true
    snd.channel(1, snd.NOISE, 70, 0.7, 0, 0.6, 0, 0.2)
  end
end

local function move(dx, dy)
  local try = { shape = piece.shape, turn = piece.turn, x = piece.x + dx, y = piece.y + dy }
  if fits(try) then piece = try return true end
  return false
end

local function turn()
  local try = { shape = piece.shape, turn = piece.turn % 4 + 1, x = piece.x, y = piece.y }
  -- A turn against the wall nudges off it rather than being refused; the alternative feels broken.
  for _, kick in ipairs({ 0, -1, 1, -2, 2 }) do
    try.x = piece.x + kick
    if fits(try) then piece = try snd.beep(520, 0.02, 0) return end
  end
end

local function drop()
  local n = 0
  while move(0, 1) do n = n + 1 end
  score = score + n
  land()
end

local function block(px, py, colour)
  gfx.fill(px, py, CELL - 1, CELL - 1, colour)
  gfx.line(px, py, px + CELL - 2, py, 7)
  gfx.line(px, py, px, py + CELL - 2, 7)
end

local function draw()
  gfx.clear(0)
  gfx.rect(WX - 2, WY - 2, CELL * COLS + 3, CELL * ROWS + 3, 6)
  for y = 0, ROWS - 1 do
    for x = 0, COLS - 1 do
      local c = well[y * COLS + x]
      if c then block(WX + x * CELL, WY + y * CELL, c) end
    end
  end
  if not over then
    for _, c in ipairs(cellsOf(piece)) do
      if c[2] >= 0 then block(WX + c[1] * CELL, WY + c[2] * CELL, SHAPES[piece.shape].colour) end
    end
  end
  local tx = PANEL <= w - 40 and PANEL or 2
  local ty = PANEL <= w - 40 and WY or 2
  gfx.text(tx, ty, "score", 6, nil, 1)
  gfx.text(tx, ty + 9, tostring(score), 7, nil, 1)
  gfx.text(tx, ty + 24, "lines " .. lines, 6, nil, 1)
  gfx.text(tx, ty + 33, "level " .. level, 6, nil, 1)
  if PANEL <= w - 40 then
    gfx.text(tx, ty + 50, "next", 6, nil, 1)
    local s = SHAPES[nextShape]
    for _, c in ipairs(s.turns[1]) do
      block(tx + c[1] * CELL, ty + 60 + c[2] * CELL, s.colour)
    end
    gfx.text(tx, ty + 60 + CELL * 3, "arrows", 5, nil, 1)
    gfx.text(tx, ty + 69 + CELL * 3, "space", 5, nil, 1)
    gfx.text(tx, ty + 78 + CELL * 3, "P R Q", 5, nil, 1)
  end
  if over then
    gfx.fill(WX, WY + CELL * 8, CELL * COLS, 20, 0)
    gfx.text(WX + 4, WY + CELL * 8 + 3, "game over", 8, nil, 1)
    gfx.text(WX + 4, WY + CELL * 8 + 12, "R starts again", 6, nil, 1)
  elseif paused then
    gfx.fill(WX, WY + CELL * 8, CELL * COLS, 12, 0)
    gfx.text(WX + 4, WY + CELL * 8 + 3, "paused", 10, nil, 1)
  end
end

if me then
  me.key = function(code, down)
    held[code] = down or nil
    if not down then return end
    if code == KEY.left then input.move = -1
    elseif code == KEY.right then input.move = 1
    elseif code == KEY.up then input.turn = true
    elseif code == KEY.space then input.drop = true
    elseif code == KEY.p then input.pause = true
    elseif code == KEY.r then input.reset = true
    elseif code == KEY.q or code == KEY.esc then input.quit = true end
  end
end

math.randomseed(math.floor(os.clock() * 1000) % 100000)
reset()
draw()
gfx.present()

local lastFall, lastRepeat = os.clock(), os.clock()
while not input.quit do
  local now = os.clock()
  local dirty = false
  if input.reset then input.reset = nil reset() dirty = true end
  if input.pause then input.pause = nil paused = not paused dirty = true end
  if not over and not paused then
    if input.move then if move(input.move, 0) then dirty = true end input.move = nil end
    if input.turn then input.turn = nil turn() dirty = true end
    if input.drop then input.drop = nil drop() dirty = true end
    -- held keys repeat on their own clock: one press moves once, holding slides
    if now - lastRepeat > 0.09 then
      lastRepeat = now
      if held[KEY.left] and move(-1, 0) then dirty = true end
      if held[KEY.right] and move(1, 0) then dirty = true end
      if held[KEY.down] then if move(0, 1) then score = score + 1 lastFall = now dirty = true else land() dirty = true end end
    end
    local fall = math.max(0.07, 0.62 - (level - 1) * 0.055)
    if now - lastFall > fall then
      lastFall = now
      if not move(0, 1) then land() end
      dirty = true
    end
  end
  if dirty then draw() end
  gfx.present()
end
return "Blocks: " .. score .. " points, " .. lines .. " lines"
