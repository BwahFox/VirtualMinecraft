-- 2048 on the VirtualMinecraft Computer: a program on a CD (a directory with main.lua + program.txt; the desktop's
-- Apps list shows it the moment the CD is in). Runs full-screen: gfx.* for drawing, the kernel hands keys to
-- kernel.programs' `key` hook, gfx.present() ends a frame. PROGRAM_DIR is the CD directory.
local w, h = gfx.size()
local KEY = win.KEY
local board, score, moved, over
local input = {}
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local function reset()
  board, score, over = {}, 0, false
  for i = 1, 16 do board[i] = 0 end
  for _ = 1, 2 do
    local empty = {}
    for i = 1, 16 do if board[i] == 0 then empty[#empty + 1] = i end end
    board[empty[math.random(#empty)]] = math.random() < 0.9 and 2 or 4
  end
end

local function slide(line)
  local out, changed = {}, false
  for _, v in ipairs(line) do if v ~= 0 then out[#out + 1] = v end end
  local i = 1
  while i < #out do
    if out[i] == out[i + 1] then out[i] = out[i] * 2 score = score + out[i] table.remove(out, i + 1) end
    i = i + 1
  end
  while #out < 4 do out[#out + 1] = 0 end
  for k = 1, 4 do if out[k] ~= line[k] then changed = true end end
  return out, changed
end

local function move(dx, dy)
  local any = false
  for k = 1, 4 do
    local idx = {}
    for j = 1, 4 do
      local x, y
      if dy == 0 then x, y = (dx > 0 and 5 - j or j), k else x, y = k, (dy > 0 and 5 - j or j) end
      idx[j] = (y - 1) * 4 + x
    end
    local line = {}
    for j = 1, 4 do line[j] = board[idx[j]] end
    local out, changed = slide(line)
    if changed then any = true for j = 1, 4 do board[idx[j]] = out[j] end end
  end
  if any then
    local empty = {}
    for i = 1, 16 do if board[i] == 0 then empty[#empty + 1] = i end end
    if #empty > 0 then board[empty[math.random(#empty)]] = math.random() < 0.9 and 2 or 4 end
    snd.beep(440, 0.04, 3)
  end
  -- game over: no empty cell and no equal neighbours
  over = true
  for i = 1, 16 do
    local x, y = (i - 1) % 4 + 1, math.floor((i - 1) / 4) + 1
    if board[i] == 0 then over = false end
    if x < 4 and board[i] == board[i + 1] then over = false end
    if y < 4 and board[i] == board[i + 4] then over = false end
  end
end

local COLOURS = { [0] = 5, [2] = 7, [4] = 6, [8] = 9, [16] = 14, [32] = 8, [64] = 13, [128] = 11, [256] = 10, [512] = 12, [1024] = 15, [2048] = 4 }
local function draw()
  gfx.clear(0)
  local top = 12
  local cell = math.floor(math.min(w, h - top) / 4)
  local ox, oy = math.floor((w - cell * 4) / 2), top
  gfx.text(2, 2, "2048  score " .. score .. (over and "  game over - R restarts" or "  arrows/WASD  R  Q"), 7, nil, 1)
  for i = 1, 16 do
    local x, y = (i - 1) % 4, math.floor((i - 1) / 4)
    local v = board[i]
    gfx.fill(ox + x * cell + 1, oy + y * cell + 1, cell - 2, cell - 2, COLOURS[v] or 4)
    if v > 0 then
      local s = tostring(v)
      local font = cell >= 40 and 0 or 1
      local fw = gfx.fontw(font)
      gfx.text(ox + x * cell + math.floor((cell - #s * fw) / 2), oy + y * cell + math.floor((cell - gfx.fonth(font)) / 2), s, v >= 8 and 0 or 0, nil, font)
    end
  end
end

if me then
  me.key = function(code, down)
    if not down then return end
    if code == KEY.up or code == KEY.w then input.move = { 0, -1 }
    elseif code == KEY.down or code == KEY.s then input.move = { 0, 1 }
    elseif code == KEY.left or code == KEY.a then input.move = { -1, 0 }
    elseif code == KEY.right or code == KEY.d then input.move = { 1, 0 }
    elseif code == KEY.r then input.reset = true
    elseif code == KEY.q or code == KEY.esc then input.quit = true end
  end
end

-- Across a freeze (ROADMAP §9 U12). A board and a score is all this game is, so it comes back exactly where it
-- was: `program.save` is called when the machine freezes and `program.restore()` hands it back on the way in.
-- The version is ours -- bump it if the shape of this table ever changes and old saves are refused instead of
-- being unpacked into fields that have moved.
program.version = 1
program.save = function() return { board = board, score = score, over = over } end

local kept = program.restore()
if kept and type(kept.board) == "table" and #kept.board == 16 then
  board, score, over = kept.board, tonumber(kept.score) or 0, kept.over == true
else
  reset()
end
draw()
gfx.present()
while not input.quit do
  if input.reset then input.reset = nil reset() draw() end
  if input.move then
    local m = input.move
    input.move = nil
    if not over then move(m[1], m[2]) draw() end
  end
  gfx.present()
end
return "2048: " .. score
