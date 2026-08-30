-- Lights Out: press a light and it toggles, along with its four neighbours. Turn them all off. A 1995 handheld
-- puzzle whose rules are four lines and whose difficulty is entirely in the player's head — the perfect shape for
-- a machine like this one, and it is genuinely all linear algebra over GF(2) underneath.
--
-- The honest reason it is worth shipping: it is the cheapest program in the catalogue that a player will lose an
-- evening to, and unlike a game of chance it can always be solved, so it never feels unfair.
local w, h = gfx.size()
local KEY = win.KEY
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local HEAD = 14
local FOOT = 11
local N = 5                                       -- 5x5 is the original; the maths below does not care
local GAP = 3
local CELL = math.max(10, math.min(48, math.floor(math.min(w - 12, h - HEAD - FOOT - 12) / N) - GAP))
local BOARD = N * CELL + (N - 1) * GAP
local OX = math.floor((w - BOARD) / 2)
local OY = HEAD + math.floor((h - HEAD - FOOT - BOARD) / 2)

-- On is a lamp, off is a dead bulb in a dark socket: the two states have to read at a glance from across a room,
-- because that is where a monitor wall is. 10 is the palette's yellow; the greys come out of the 6x6x6 cube.
local ON, ONLIT, OFF, OFFLIT, BACK = 10, 14, 59, 102, 16
local cells, moves, won, level = {}, 0, false, 1
local input, hovered = {}, nil
local quit = false
local cur = { x = 1, y = 1 }

local function idx(x, y) return (y - 1) * N + x end
local function inside(x, y) return x >= 1 and y >= 1 and x <= N and y <= N end

-- Toggling is the whole game: the cell and its orthogonal neighbours, nothing diagonal.
local function toggle(x, y)
  if not inside(x, y) then return end
  cells[idx(x, y)] = not cells[idx(x, y)]
  if inside(x - 1, y) then cells[idx(x - 1, y)] = not cells[idx(x - 1, y)] end
  if inside(x + 1, y) then cells[idx(x + 1, y)] = not cells[idx(x + 1, y)] end
  if inside(x, y - 1) then cells[idx(x, y - 1)] = not cells[idx(x, y - 1)] end
  if inside(x, y + 1) then cells[idx(x, y + 1)] = not cells[idx(x, y + 1)] end
end

local function solved()
  for i = 1, N * N do if cells[i] then return false end end
  return true
end

-- The board is scrambled by *playing* it backwards from solved, never by randomising the lights: only about one
-- in four random 5x5 boards is solvable at all, and handing a player an impossible puzzle is the one bug this
-- game can have. Pressing the same square twice cancels, so an odd number of distinct presses is what scrambles.
local function scramble(n)
  for i = 1, N * N do cells[i] = false end
  local pressed = 0
  local guard = 0
  while pressed < n and guard < 500 do
    guard = guard + 1
    toggle(math.random(N), math.random(N))
    pressed = pressed + 1
  end
  if solved() then toggle(math.random(N), math.random(N)) end   -- never open on an already-won board
  moves, won = 0, false
end

local function press(x, y)
  if won or not inside(x, y) then return end
  toggle(x, y)
  moves = moves + 1
  snd.beep(cells[idx(x, y)] and 720 or 480, 0.02, 0)
  if solved() then
    won = true
    for k = 1, 3 do snd.channel(k, snd.SQUARE, 330 * k, 0.4, 0.01, 0.4, 0, 0.2) end
  end
end

local function cellAt(px, py)
  local x = math.floor((px - OX) / (CELL + GAP)) + 1
  local y = math.floor((py - OY) / (CELL + GAP)) + 1
  -- the click has to land on the lamp, not in the gap after it
  local lx = OX + (x - 1) * (CELL + GAP)
  local ly = OY + (y - 1) * (CELL + GAP)
  if inside(x, y) and px < lx + CELL and py < ly + CELL then return x, y end
end

local function count()
  local n = 0
  for i = 1, N * N do if cells[i] then n = n + 1 end end
  return n
end

local function draw()
  gfx.clear(BACK)
  gfx.fill(0, 0, w, HEAD, 0)
  gfx.text(2, 3, "lights out  level " .. level, 7, nil, 1)
  local status = won and ("solved in " .. moves .. " - N for the next") or (count() .. " lit   " .. moves .. " moves")
  gfx.text(w - #status * 6 - 2, 3, status, won and 11 or 6, nil, 1)

  for y = 1, N do
    for x = 1, N do
      local on = cells[idx(x, y)]
      local px, py = OX + (x - 1) * (CELL + GAP), OY + (y - 1) * (CELL + GAP)
      gfx.fill(px, py, CELL, CELL, on and ON or OFF)
      gfx.line(px, py, px + CELL - 1, py, on and ONLIT or OFFLIT)
      gfx.line(px, py, px, py + CELL - 1, on and ONLIT or OFFLIT)
      gfx.line(px, py + CELL - 1, px + CELL - 1, py + CELL - 1, 0)
      gfx.line(px + CELL - 1, py, px + CELL - 1, py + CELL - 1, 0)
      -- a filament, so an unlit lamp still looks like a lamp rather than an empty square
      local r = math.max(2, math.floor(CELL / 5))
      gfx.disc(px + math.floor(CELL / 2), py + math.floor(CELL / 2), r, on and ONLIT or OFFLIT)
    end
  end

  if not won then
    local px, py = OX + (cur.x - 1) * (CELL + GAP), OY + (cur.y - 1) * (CELL + GAP)
    gfx.rect(px - 2, py - 2, CELL + 3, CELL + 3, 15)
  end
  gfx.text(2, h - FOOT + 2, win.fit("arrows + space, or click   N new   R restart   Q quit", w - 4), 6, nil, 1)
end

if me then
  me.window.cursor = true
  kernel.showCursor(true)
  me.pointer = function(px, py, b, pressed)
    local x, y = cellAt(px, py)
    local hv = x and idx(x, y) or nil
    if hv ~= hovered then hovered = hv input.redraw = true end
    if x then
      cur.x, cur.y = x, y
      if pressed then input.press = { x, y } end
    end
  end
  me.key = function(code, down)
    if not down then return end
    if code == KEY.up then cur.y = math.max(1, cur.y - 1)
    elseif code == KEY.down then cur.y = math.min(N, cur.y + 1)
    elseif code == KEY.left then cur.x = math.max(1, cur.x - 1)
    elseif code == KEY.right then cur.x = math.min(N, cur.x + 1)
    elseif code == KEY.space or code == KEY.enter then input.press = { cur.x, cur.y }
    elseif code == KEY.n then input.next = true
    elseif code == KEY.r then input.restart = true
    elseif code == KEY.q or code == KEY.esc then input.quit = true end
    input.redraw = true
  end
end

-- The difficulty ramp is the number of scrambling presses, which is very nearly the length of the shortest
-- solution: level 1 is three presses and obvious, level 10 is a real sit-down.
local function start(n)
  scramble(math.min(N * N, 2 + n))
  input.redraw = true
end

start(level)
draw()
gfx.present()
while not quit do
  if input.quit then input.quit = nil quit = true end
  if input.press then local p = input.press input.press = nil press(p[1], p[2]) input.redraw = true end
  if input.next then
    input.next = nil
    if won then level = level + 1 end
    start(level)
  end
  if input.restart then input.restart = nil start(level) end
  if input.redraw then input.redraw = nil draw() end
  gfx.present()
end
return "Lights Out: level " .. level .. (won and " solved in " .. moves .. " moves" or ", " .. count() .. " still lit")
