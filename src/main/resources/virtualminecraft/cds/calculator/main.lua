-- Calculator: the most boring program on any machine and the one whose absence is noticed first. A 90s box had
-- one, so this box has one. Pointer-first like the desktop, keyboard for anyone standing at a monitor.
--
-- It is a four-function calculator with a memory and a tape, not an expression evaluator: you press 2 + 3 = and
-- it says 5, the way a desk calculator does, because that is the thing people expect when they see a keypad. The
-- tape down the side is the part a desk calculator has and a phone does not, and it is why this is worth writing.
local w, h = gfx.size()
local KEY = win.KEY
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local FACE, LIT, SHADE, PANEL = 145, 188, 102, 59
local OPFACE, EQFACE = 40, 22
local READOUT, READTEXT, AMBER = 22, 15, 10

-- The tape takes the right third on a wide screen and is dropped entirely on a narrow one: at 256x256 with a
-- readable font there is no room for both, and the keys matter more than the history.
local TAPEW = w >= 300 and math.floor(w / 3) or 0
local PADW = w - TAPEW
local HEAD = 34
local FOOT = 11
local COLS, ROWS = 4, 5
local GAP = 2
local KW = math.floor((PADW - GAP * (COLS + 1)) / COLS)
local KH = math.floor((h - HEAD - FOOT - GAP * (ROWS + 1)) / ROWS)
local OX = math.floor((PADW - (KW * COLS + GAP * (COLS - 1))) / 2)
local OY = HEAD + GAP

-- Row order is the desk-calculator one: clear across the top, operators down the right, zero double-wide.
local PAD = {
  "C", "CE", "%", "/",
  "7", "8", "9", "*",
  "4", "5", "6", "-",
  "1", "2", "3", "+",
  "0", ".", "+/-", "=",
}

local acc, pending, entry, fresh = 0, nil, "0", true
local memory, tape, err = 0, {}, nil
local input, hovered = {}, nil
local quit = false
-- Typed characters arrive in bursts (one frame can carry a whole "12+34"), so presses queue rather than
-- occupying a single slot that each new character would overwrite.
local queue = {}
local function enqueue(v) queue[#queue + 1] = v end

local function note(line)
  table.insert(tape, line)
  while #tape > 40 do table.remove(tape, 1) end
end

-- Numbers are shown the way a calculator shows them: integers without a trailing .0, everything else trimmed of
-- the float noise Lua's %g leaves behind. 12 digits is what fits and what a desk calculator had.
local function show(v)
  if v ~= v then return "nan" end
  if v == math.huge or v == -math.huge then return "inf" end
  if v == math.floor(v) and math.abs(v) < 1e12 then return string.format("%d", v) end
  local s = string.format("%.10g", v)
  return s
end

local function current()
  return tonumber(entry) or 0
end

local function apply(op, a, b)
  if op == "+" then return a + b end
  if op == "-" then return a - b end
  if op == "*" then return a * b end
  if op == "/" then
    if b == 0 then err = "divide by zero" return 0 end
    return a / b
  end
  return b
end

local function beep(f) snd.beep(f, 0.02, 0) end

local function digit(d)
  if fresh then entry = (d == ".") and "0." or d fresh = false
  elseif d == "." then
    if not entry:find("%.") then entry = entry .. "." end
  elseif entry == "0" then entry = d
  elseif #entry < 12 then entry = entry .. d end
  beep(520)
end

local function operator(op)
  if pending and not fresh then
    local v = apply(pending, acc, current())
    note(show(acc) .. " " .. pending .. " " .. show(current()) .. " = " .. show(v))
    acc = v
    entry = show(v)
  else
    acc = current()
  end
  pending, fresh = op, true
  beep(660)
end

local function equals()
  if not pending then note("= " .. show(current())) fresh = true return end
  local b = current()
  local v = apply(pending, acc, b)
  note(show(acc) .. " " .. pending .. " " .. show(b) .. " = " .. show(v))
  entry = show(v)
  acc, pending, fresh = v, nil, true
  snd.beep(880, 0.03, 0)
end

local function press(label)
  err = nil
  if label >= "0" and label <= "9" then digit(label)
  elseif label == "." then digit(".")
  elseif label == "+" or label == "-" or label == "*" or label == "/" then operator(label)
  elseif label == "=" then equals()
  elseif label == "C" then acc, pending, entry, fresh, memory = 0, nil, "0", true, memory tape = {} beep(300)
  elseif label == "CE" then entry, fresh = "0", true beep(300)
  elseif label == "+/-" then
    if entry:sub(1, 1) == "-" then entry = entry:sub(2) else if entry ~= "0" then entry = "-" .. entry end end
    beep(440)
  elseif label == "%" then
    -- The percent key on a desk calculator is "percent of the accumulator", not "divide by a hundred".
    local v = pending and (acc * current() / 100) or (current() / 100)
    entry, fresh = show(v), true
    beep(440)
  end
  input.redraw = true
end

local function keyAt(px, py)
  for i = 1, #PAD do
    local col, row = (i - 1) % COLS, math.floor((i - 1) / COLS)
    local kx, ky = OX + col * (KW + GAP), OY + row * (KH + GAP)
    if px >= kx and py >= ky and px < kx + KW and py < ky + KH then return i end
  end
end

local function draw()
  gfx.clear(PANEL)

  gfx.fill(0, 0, PADW, HEAD, READOUT)
  gfx.line(0, HEAD - 1, PADW - 1, HEAD - 1, SHADE)
  local shown = err or entry
  gfx.text(PADW - #shown * 6 - 4, 12, shown, err and 8 or READTEXT, nil, 1)
  local state = (pending and (show(acc) .. " " .. pending) or "") .. (memory ~= 0 and "   M" or "")
  gfx.text(4, 3, win.fit(state, PADW - 8), 6, nil, 1)

  for i = 1, #PAD do
    local label = PAD[i]
    local col, row = (i - 1) % COLS, math.floor((i - 1) / COLS)
    local kx, ky = OX + col * (KW + GAP), OY + row * (KH + GAP)
    local face = FACE
    if label == "=" then face = EQFACE
    elseif label == "+" or label == "-" or label == "*" or label == "/" or label == "%" then face = OPFACE
    elseif label == "C" or label == "CE" then face = 66 end
    if hovered == i then face = face + 1 end
    gfx.fill(kx, ky, KW, KH, face)
    gfx.line(kx, ky, kx + KW - 1, ky, LIT)
    gfx.line(kx, ky, kx, ky + KH - 1, LIT)
    gfx.line(kx, ky + KH - 1, kx + KW - 1, ky + KH - 1, SHADE)
    gfx.line(kx + KW - 1, ky, kx + KW - 1, ky + KH - 1, SHADE)
    gfx.text(kx + math.floor((KW - #label * 6) / 2), ky + math.floor((KH - 8) / 2), label, 0, nil, 1)
  end

  if TAPEW > 0 then
    gfx.fill(PADW, 0, TAPEW, h - FOOT, 0)
    gfx.line(PADW, 0, PADW, h - FOOT, SHADE)
    gfx.text(PADW + 3, 3, "tape", 6, nil, 1)
    local rows = math.floor((h - FOOT - 14) / 9)
    local first = math.max(1, #tape - rows + 1)
    local y = 14
    for i = first, #tape do
      gfx.text(PADW + 3, y, win.fit(tape[i], TAPEW - 6), 7, nil, 1)
      y = y + 9
    end
  end

  gfx.text(2, h - FOOT + 2, win.fit("M+ M- MR store  C clears the tape  Q quit", w - 4), 6, nil, 1)
end

if me then
  me.window.cursor = true
  kernel.showCursor(true)
  me.pointer = function(px, py, b, pressed)
    local i = keyAt(px, py)
    if i ~= hovered then hovered = i input.redraw = true end
    if pressed and i then enqueue(PAD[i]) end
  end
  -- Characters, not scancodes: a calculator wants every digit and every operator symbol, and those live on
  -- shifted keys the scancode table does not name. kernel dispatches char to the top program.
  me.char = function(cp)
    local c = string.char(cp)
    if (c >= "0" and c <= "9") or c == "." or c == "+" or c == "-" or c == "*" or c == "/" or c == "%" then
      enqueue(c)
    elseif c == "=" then enqueue("=")
    end
  end
  me.key = function(code, down)
    if not down then return end
    if code == KEY.enter or code == KEY.kpenter then enqueue("=")
    elseif code == KEY.backspace then enqueue("CE")
    elseif code == KEY.delete then enqueue("C")
    elseif code == KEY.q or code == KEY.esc then input.quit = true
    -- M+ M- MR on the three keys win.KEY happens to carry that a calculator can spare
    elseif code == KEY.a then memory = memory + (tonumber(entry) or 0) input.redraw = true snd.beep(700, 0.03, 0)
    elseif code == KEY.z then memory = memory - (tonumber(entry) or 0) input.redraw = true snd.beep(500, 0.03, 0)
    elseif code == KEY.r then entry, fresh = show(memory), true input.redraw = true snd.beep(880, 0.03, 0)
    end
    input.redraw = true
  end
end

draw()
gfx.present()
while not quit do
  if input.quit then input.quit = nil quit = true end
  while #queue > 0 do press(table.remove(queue, 1)) end
  if input.redraw then input.redraw = nil draw() end
  gfx.present()
end
return "Calculator: " .. entry
