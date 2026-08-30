-- Life: Conway's cellular automaton. Four rules, no player, no score — you build a starting position and then
-- watch what it does, and the whole appeal is that almost nothing about the result is guessable from the rules.
--
-- Public domain in the only sense that matters: it is a *rule*, published in 1970, not a work anyone owns.
-- Nothing here is ported from anywhere; the patterns below are facts about the rule, the way a prime number is
-- a fact about arithmetic.
--
-- Built for building rather than for watching: paint by dragging, stamp a pattern from the library, pause and
-- edit mid-run, and save what you made to the machine's disk. The glider gun is in the library because placing
-- one by hand is thirty-six cells of careful counting and nobody enjoys that twice.
local w, h = gfx.size()
local KEY = win.KEY
local KEY_C, KEY_G, KEY_H, KEY_L, KEY_B = 0x2e, 0x22, 0x23, 0x26, 0x30   -- win.KEY names none of these
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local HEAD = 12
local FOOT = 10
local CELL = math.max(2, math.min(6, math.floor(math.min(w, h - HEAD - FOOT) / 64)))
local COLS = math.floor(w / CELL)
local ROWS = math.floor((h - HEAD - FOOT) / CELL)
local OX = math.floor((w - COLS * CELL) / 2)
local OY = HEAD + math.floor((h - HEAD - FOOT - ROWS * CELL) / 2)

local ALIVE, DYING, GRID, BACK, UI = 11, 22, 59, 16, 7
local STORE = "/disk/life.json"

-- Two flat arrays swapped each generation. A table of tables would be COLS*ROWS tables to garbage collect
-- every step, and this machine has 4 MB.
local cur, nxt = {}, {}
local running, gen, speed, pop = false, 0, 8, 0
local showGrid = true
local help = true
local input, painting, paintTo = {}, false, true
local quit = false
local message, messageUntil = nil, 0

local function idx(x, y) return y * COLS + x + 1 end
local function get(x, y) return cur[idx(x, y)] end

local function say(s, secs) message, messageUntil = s, os.clock() + (secs or 2) end

local function clear()
  for i = 1, COLS * ROWS do cur[i] = false nxt[i] = false end
  gen, pop = 0, 0
  input.redraw = true
end

---------------------------------------------------------------------------------------------------- patterns
-- Each is a list of {dx, dy} live cells. These are the classics, written out rather than loaded: a glider is
-- five cells and a gun is thirty-six, and typing them is cheaper than inventing a file format for them.
local PATTERNS = {
  { name = "glider", cells = { {1,0},{2,1},{0,2},{1,2},{2,2} } },
  { name = "blinker", cells = { {0,0},{1,0},{2,0} } },
  { name = "toad", cells = { {1,0},{2,0},{3,0},{0,1},{1,1},{2,1} } },
  { name = "beacon", cells = { {0,0},{1,0},{0,1},{1,1},{2,2},{3,2},{2,3},{3,3} } },
  { name = "pulsar", cells = {
      {2,0},{3,0},{4,0},{8,0},{9,0},{10,0},
      {0,2},{5,2},{7,2},{12,2},{0,3},{5,3},{7,3},{12,3},{0,4},{5,4},{7,4},{12,4},
      {2,5},{3,5},{4,5},{8,5},{9,5},{10,5},
      {2,7},{3,7},{4,7},{8,7},{9,7},{10,7},
      {0,8},{5,8},{7,8},{12,8},{0,9},{5,9},{7,9},{12,9},{0,10},{5,10},{7,10},{12,10},
      {2,12},{3,12},{4,12},{8,12},{9,12},{10,12} } },
  { name = "lwss", cells = { {0,0},{3,0},{4,1},{0,2},{4,2},{1,3},{2,3},{3,3},{4,3} } },
  { name = "r-pentomino", cells = { {1,0},{2,0},{0,1},{1,1},{1,2} } },
  { name = "acorn", cells = { {1,0},{3,1},{0,2},{1,2},{4,2},{5,2},{6,2} } },
  -- Gosper's glider gun: the first pattern anyone found that grows without limit, and the reason "machines"
  -- are a thing people build in Life at all. It fires a glider every 30 generations.
  { name = "glider gun", cells = {
      {24,0},{22,1},{24,1},{12,2},{13,2},{20,2},{21,2},{34,2},{35,2},
      {11,3},{15,3},{20,3},{21,3},{34,3},{35,3},
      {0,4},{1,4},{10,4},{16,4},{20,4},{21,4},
      {0,5},{1,5},{10,5},{14,5},{16,5},{17,5},{22,5},{24,5},
      {10,6},{16,6},{24,6},{11,7},{15,7},{12,8},{13,8} } },
}
local pattern = 1

local function stamp(px, py, cells)
  -- Placed centred on the pointer, so what you clicked is where the pattern lands.
  local minx, miny, maxx, maxy = 1e9, 1e9, -1e9, -1e9
  for _, c in ipairs(cells) do
    minx, miny = math.min(minx, c[1]), math.min(miny, c[2])
    maxx, maxy = math.max(maxx, c[1]), math.max(maxy, c[2])
  end
  local ox = px - math.floor((maxx - minx) / 2) - minx
  local oy = py - math.floor((maxy - miny) / 2) - miny
  for _, c in ipairs(cells) do
    local x, y = (ox + c[1]) % COLS, (oy + c[2]) % ROWS
    cur[idx(x, y)] = true
  end
  input.redraw = true
end

---------------------------------------------------------------------------------------------------- the rule
-- Conway's four rules, and the only interesting line in the program: a live cell with 2 or 3 live neighbours
-- lives, a dead cell with exactly 3 is born, everything else dies. The grid is a torus — it wraps — so a
-- glider sails off one edge and comes back on the other instead of hitting a wall.
local function step()
  local n = 0
  for y = 0, ROWS - 1 do
    local up = (y - 1) % ROWS
    local down = (y + 1) % ROWS
    for x = 0, COLS - 1 do
      local left = (x - 1) % COLS
      local right = (x + 1) % COLS
      local c = 0
      if cur[idx(left, up)] then c = c + 1 end
      if cur[idx(x, up)] then c = c + 1 end
      if cur[idx(right, up)] then c = c + 1 end
      if cur[idx(left, y)] then c = c + 1 end
      if cur[idx(right, y)] then c = c + 1 end
      if cur[idx(left, down)] then c = c + 1 end
      if cur[idx(x, down)] then c = c + 1 end
      if cur[idx(right, down)] then c = c + 1 end
      local live = cur[idx(x, y)]
      local born = live and (c == 2 or c == 3) or (not live and c == 3)
      nxt[idx(x, y)] = born
      if born then n = n + 1 end
    end
  end
  cur, nxt = nxt, cur
  gen, pop = gen + 1, n
  input.redraw = true
end

local function randomise()
  for i = 1, COLS * ROWS do cur[i] = math.random() < 0.28 end
  gen = 0
  input.redraw = true
end

local function save()
  -- Only the live cells, so a mostly-empty board is a small file.
  local live = {}
  for y = 0, ROWS - 1 do
    for x = 0, COLS - 1 do
      if cur[idx(x, y)] then live[#live + 1] = { x, y } end
    end
  end
  local ok = pcall(function() fs.write(STORE, json.encode({ cols = COLS, rows = ROWS, cells = live })) end)
  say(ok and ("saved " .. #live .. " cells") or "could not save")
end

local function load()
  if not fs.exists(STORE) then say("nothing saved yet") return end
  local ok, t = pcall(function() return json.decode(fs.read(STORE)) end)
  if not ok or type(t) ~= "table" or type(t.cells) ~= "table" then say("could not read it") return end
  clear()
  for _, c in ipairs(t.cells) do
    local x, y = tonumber(c[1]), tonumber(c[2])
    if x and y and x < COLS and y < ROWS then cur[idx(x, y)] = true end
  end
  say("loaded " .. #t.cells .. " cells")
  input.redraw = true
end

---------------------------------------------------------------------------------------------------- drawing
local function draw()
  if help then
    gfx.clear(0)
    local lines = {
      "LIFE",
      "",
      "Every cell looks at its eight",
      "neighbours, all at once:",
      "",
      "  2 or 3 neighbours - it lives",
      "  exactly 3 - a dead cell is born",
      "  anything else - it dies",
      "",
      "That is the whole rule. Nothing",
      "else is going on.",
      "",
      "DRAG to draw, drag on live cells",
      "to rub out. SPACE runs and pauses,",
      "S steps once, +/- speed.",
      "",
      "B stamps a pattern (RIGHT-CLICK",
      "places it), N cycles which one.",
      "C clears, R randomises,",
      "G grid, L saves, K loads.",
      "",
      "The edges wrap around.",
      "",
      "any key or click to start",
    }
    local bw = math.min(w - 4, 36 * 6 + 10)
    local bh = math.min(h - 4, #lines * 9 + 8)
    local bx, by = math.floor((w - bw) / 2), math.floor((h - bh) / 2)
    gfx.fill(bx, by, bw, bh, 0)
    gfx.rect(bx, by, bw, bh, ALIVE)
    for i, line in ipairs(lines) do
      local col = (i == 1 or i == #lines) and ALIVE or 15
      gfx.text(bx + 5, by + 4 + (i - 1) * 9, win.fit(line, bw - 10), col, nil, 1)
    end
    return
  end

  gfx.clear(BACK)
  if showGrid and CELL >= 4 then
    for x = 0, COLS do gfx.line(OX + x * CELL, OY, OX + x * CELL, OY + ROWS * CELL, GRID) end
    for y = 0, ROWS do gfx.line(OX, OY + y * CELL, OX + COLS * CELL, OY + y * CELL, GRID) end
  end
  for y = 0, ROWS - 1 do
    for x = 0, COLS - 1 do
      if cur[idx(x, y)] then
        gfx.fill(OX + x * CELL, OY + y * CELL, CELL - (showGrid and CELL >= 4 and 1 or 0), CELL - (showGrid and CELL >= 4 and 1 or 0), ALIVE)
      end
    end
  end

  gfx.fill(0, 0, w, HEAD, 0)
  gfx.text(2, 2, "life  gen " .. gen .. "  pop " .. pop, UI, nil, 1)
  local right = (running and "running" or "paused") .. "  " .. PATTERNS[pattern].name
  gfx.text(w - #right * 6 - 2, 2, right, running and ALIVE or 10, nil, 1)

  gfx.fill(0, h - FOOT, w, FOOT, 0)
  local foot = "space runs  S step  B stamp  N pattern  H help  Q quit"
  if message and os.clock() < messageUntil then foot = message end
  gfx.text(2, h - FOOT + 1, win.fit(foot, w - 4), 6, nil, 1)
end

---------------------------------------------------------------------------------------------------- input
local function cellAt(px, py)
  local x = math.floor((px - OX) / CELL)
  local y = math.floor((py - OY) / CELL)
  if x >= 0 and y >= 0 and x < COLS and y < ROWS then return x, y end
end

if me then
  me.window.cursor = true
  kernel.showCursor(true)
  local buttons = 0
  me.pointer = function(px, py, b, pressed, released)
    if help then
      if pressed then help = false input.redraw = true end
      buttons = b
      return
    end
    local x, y = cellAt(px, py)
    -- Right button stamps the current pattern where you point.
    if b >= 2 and buttons < 2 and x then
      stamp(x, y, PATTERNS[pattern].cells)
      say("stamped " .. PATTERNS[pattern].name)
    end
    -- Dragging paints. Which way it paints is decided by the cell you started on, so a drag that begins on a
    -- live cell rubs out instead of scribbling over what is already there.
    if pressed and x then
      painting = true
      paintTo = not get(x, y)
      cur[idx(x, y)] = paintTo
      input.redraw = true
    elseif painting and (b % 2) == 1 and x then
      cur[idx(x, y)] = paintTo
      input.redraw = true
    end
    if (b % 2) == 0 then painting = false end
    buttons = b
  end
  me.key = function(code, down)
    if not down then return end
    if help then
      help = false
      input.redraw = true
      if code ~= KEY.q and code ~= KEY.esc then return end
    end
    if code == KEY.q or code == KEY.esc then input.quit = true
    elseif code == KEY.space then running = not running
    elseif code == KEY.s then input.step = true
    elseif code == KEY.r then randomise()
    elseif code == KEY.n then pattern = pattern % #PATTERNS + 1 say(PATTERNS[pattern].name)
    elseif code == KEY_B then input.stampCentre = true
    elseif code == KEY_C then clear()
    elseif code == KEY_G then showGrid = not showGrid
    elseif code == KEY_H then help = true
    elseif code == KEY_L then save()
    elseif code == 0x25 then load()                      -- K, which win.KEY does not name either
    elseif code == 0x0d then speed = math.min(30, speed + 2) say("speed " .. speed)   -- +
    elseif code == 0x0c then speed = math.max(1, speed - 2) say("speed " .. speed)    -- -
    end
    input.redraw = true
  end
end

clear()
-- Open on the gun, paused, with the gun also SELECTED so the header names what is actually on the board.
pattern = #PATTERNS
stamp(math.floor(COLS / 2), math.floor(ROWS / 2), PATTERNS[pattern].cells)
draw()
gfx.present()
local last = os.clock()
while not quit do
  if input.quit then input.quit = nil quit = true end
  if input.step then input.step = nil step() end
  if input.stampCentre then
    input.stampCentre = nil
    stamp(math.floor(COLS / 2), math.floor(ROWS / 2), PATTERNS[pattern].cells)
    say("stamped " .. PATTERNS[pattern].name)
  end
  if running then
    local now = os.clock()
    if now - last >= 1 / speed then
      last = now
      step()
    end
  end
  if input.redraw then input.redraw = nil draw() end
  gfx.present()
end
return "Life: generation " .. gen .. ", population " .. pop
