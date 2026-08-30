-- Keypad: a PIN pad that drives a redstone output. Everyone's first program on a computer that is standing in a
-- world, and the reason is that it is the shortest line between "I wrote software" and "my door opens". The pad is
-- pointer-first because the desktop is, but a player at a monitor with a keyboard types the digits instead.
--
-- The PIN lives on the machine's own disk, not in this file: a program on a CD is read-only and shared, so the
-- secret has to belong to the computer that runs it. No PIN saved yet means the first thing it asks for is a new
-- one, which is also the only moment the pad will show you what you are typing.
local w, h = gfx.size()
local KEY = win.KEY
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local STORE = "/disk/keypad.json"
local SIDES = { "front", "back", "left", "right", "top", "bottom" }

-- The three greys the machine's 16 base colours do not carry (see TESTING): a key face, its lit edge, its shadow.
local FACE, LIT, SHADE, PANEL = 145, 188, 102, 59
local GREEN, RED, AMBER = 11, 8, 10

local HEAD = 30                                  -- the readout strip: state, then the masked entry
local FOOT = 11                                  -- the hint line along the bottom
local COLS, ROWS = 3, 4
local GAP = 2
local KW = math.floor((w - GAP * (COLS + 1)) / COLS)
local KH = math.floor((h - HEAD - FOOT - GAP * (ROWS + 1)) / ROWS)
local OX = math.floor((w - (KW * COLS + GAP * (COLS - 1))) / 2)
local OY = HEAD + GAP

-- The pad reads the way a phone does, not the way a numpad does: 1 at the top left, 0 at the bottom.
local PAD = { "1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "E" }

local cfg = { pin = nil, side = "right", hold = 5 }
local entry, message, messageUntil, state = "", nil, 0, "locked"
local openedAt, setting, confirm, attempts = nil, false, nil, 0
local lockedOut = 0
local input, buttons, hovered = {}, 0, nil
local quit = false
-- Typed characters arrive in bursts -- `type 1234` is four char events inside one frame -- so a single
-- "next press" slot loses all but the last of them. Everything the player presses goes on a queue and the
-- main loop drains it. (Found the hard way: a four-digit PIN arrived as one digit.)
local queue = {}
local function enqueue(v) queue[#queue + 1] = v end

local function save()
  pcall(function() fs.write(STORE, json.encode(cfg)) end)
end

local function load()
  if not fs.exists(STORE) then return end
  local ok, t = pcall(function() return json.decode(fs.read(STORE)) end)
  if ok and type(t) == "table" then
    cfg.pin = type(t.pin) == "string" and t.pin or nil
    cfg.side = type(t.side) == "string" and t.side or "right"
    cfg.hold = tonumber(t.hold) or 5
  end
end

-- Every write to the world goes through here, and every one of them is a pcall: a machine with no redstone
-- component on the bus is a normal thing (the emulator is one), and the pad should still be usable and still say so.
local haveRedstone = true
local function output(level)
  local ok = pcall(bus.call, "redstone", "setOutput", cfg.side, level)
  haveRedstone = ok
  return ok
end

local function say(s, secs)
  message, messageUntil = s, os.clock() + (secs or 2)
end

local function beepGood() snd.beep(880, 0.04, 0) end
local function beepBad() snd.channel(1, snd.NOISE, 70, 0.5, 0, 0.18, 0, 0.05) end

local function lock()
  state = "locked"
  openedAt = nil
  output(0)
end

local function unlock()
  state = "open"
  openedAt = os.clock()
  output(15)
  for k = 1, 3 do snd.channel(k, snd.SQUARE, 440 * k, 0.35, 0.01, 0.35, 0, 0.15) end
end

-- Entering the PIN. Three wrong tries buys a ten-second lockout — not security (anyone who can break the block
-- owns the machine), just the sound and the feel of a real pad, which is the whole point of the program.
local function submit()
  if setting then
    if #entry < 4 then say("at least 4 digits") beepBad() entry = "" return end
    if not confirm then confirm = entry entry = "" say("type it again", 30) return end
    if confirm ~= entry then confirm = nil entry = "" say("did not match") beepBad() return end
    cfg.pin = entry
    confirm, setting, entry = nil, false, ""
    save()
    say("PIN saved")
    beepGood()
    return
  end
  if entry == cfg.pin then
    attempts, entry = 0, ""
    unlock()
    say("open for " .. cfg.hold .. "s", cfg.hold)
  else
    attempts = attempts + 1
    entry = ""
    beepBad()
    if attempts >= 3 then
      lockedOut = os.clock() + 10
      attempts = 0
      say("locked out 10s", 10)
    else
      say("wrong")
    end
  end
end

local function digit(d)
  if os.clock() < lockedOut then beepBad() return end
  if #entry >= 12 then return end
  entry = entry .. d
  snd.beep(500 + #entry * 40, 0.02, 0)
end

local function press(label)
  if label == "C" then
    if entry == "" and state == "open" then lock() say("locked") else entry = "" snd.beep(330, 0.03, 0) end
  elseif label == "E" then
    submit()
  else
    digit(label)
  end
  input.redraw = true
end

local function keyAt(px, py)
  for i = 1, #PAD do
    local col, row = (i - 1) % COLS, math.floor((i - 1) / COLS)
    local kx, ky = OX + col * (KW + GAP), OY + row * (KH + GAP)
    if px >= kx and py >= ky and px < kx + KW and py < ky + KH then return i, kx, ky end
  end
end

local function draw()
  gfx.clear(PANEL)

  -- The readout. A pad that shows the digits you typed is not a pad, so it shows dots — except while setting a
  -- new PIN, where hiding it from the person choosing it would only cause typos.
  local barCol = state == "open" and GREEN or (os.clock() < lockedOut and RED or 0)
  gfx.fill(0, 0, w, HEAD, barCol)
  local title = setting and (confirm and "confirm PIN" or "new PIN") or (state == "open" and "OPEN" or "LOCKED")
  gfx.text(3, 3, title, state == "open" and 0 or 7, nil, 1)
  local shown = setting and entry or string.rep("*", #entry)
  gfx.text(3, 14, shown == "" and "____" or shown, state == "open" and 0 or 15, nil, 1)
  if state == "open" and openedAt then
    local left = math.max(0, cfg.hold - (os.clock() - openedAt))
    local s = string.format("%.0fs", left)
    gfx.text(w - #s * 6 - 3, 14, s, 0, nil, 1)
  end
  if message and os.clock() < messageUntil then
    gfx.text(w - #message * 6 - 3, 3, message, state == "open" and 0 or AMBER, nil, 1)
  end

  for i = 1, #PAD do
    local label = PAD[i]
    local col, row = (i - 1) % COLS, math.floor((i - 1) / COLS)
    local kx, ky = OX + col * (KW + GAP), OY + row * (KH + GAP)
    local face = FACE
    if label == "E" then face = 22 elseif label == "C" then face = 40 end
    if hovered == i then face = face + 1 end
    gfx.fill(kx, ky, KW, KH, face)
    gfx.line(kx, ky, kx + KW - 1, ky, LIT)
    gfx.line(kx, ky, kx, ky + KH - 1, LIT)
    gfx.line(kx, ky + KH - 1, kx + KW - 1, ky + KH - 1, SHADE)
    gfx.line(kx + KW - 1, ky, kx + KW - 1, ky + KH - 1, SHADE)
    local text = label == "E" and "OK" or label == "C" and "CL" or label
    gfx.text(kx + math.floor((KW - #text * 6) / 2), ky + math.floor((KH - 8) / 2), text, 0, nil, 1)
  end

  local hint = haveRedstone and ("out " .. cfg.side .. "  S side  P new PIN  Q quit")
                            or "no redstone on the bus  S side  P new PIN  Q quit"
  gfx.text(2, h - FOOT + 2, win.fit(hint, w - 4), haveRedstone and 6 or AMBER, nil, 1)
end

if me then
  me.window.cursor = true
  kernel.showCursor(true)
  me.pointer = function(px, py, b, pressed)
    local i = keyAt(px, py)
    if i ~= hovered then hovered = i input.redraw = true end
    if pressed and i then enqueue(PAD[i]) end
    buttons = b
  end
  -- Digits arrive as characters, not scancodes: win.KEY only carries the letters the ROM's apps needed, and a
  -- pad wants every digit on both the number row and the numpad. kernel dispatches char to the top program.
  me.char = function(cp)
    local c = string.char(cp)
    if c >= "0" and c <= "9" then enqueue(c) end
  end
  me.key = function(code, down)
    if not down then return end
    if code == KEY.enter or code == KEY.kpenter then enqueue("E")
    elseif code == KEY.backspace or code == KEY.delete then enqueue("C")
    elseif code == KEY.q or code == KEY.esc then input.quit = true
    elseif code == KEY.p then input.newpin = true
    elseif code == KEY.s then input.side = true end
    input.redraw = true
  end
end

load()
if not cfg.pin then setting = true say("set a PIN, then OK", 30) end
lock()
draw()
gfx.present()

local lastTick = -1
while not quit do
  if input.quit then input.quit = nil quit = true end
  while #queue > 0 do press(table.remove(queue, 1)) end
  if input.newpin then
    input.newpin = nil
    setting, confirm, entry = true, nil, ""
    say("new PIN, then OK", 30)
    input.redraw = true
  end
  if input.side then
    input.side = nil
    local at = 1
    for i, s in ipairs(SIDES) do if s == cfg.side then at = i end end
    cfg.side = SIDES[at % #SIDES + 1]
    save()
    if state == "open" then output(15) end
    say("out " .. cfg.side)
    input.redraw = true
  end
  -- The hold: an output that stays on forever is a door propped open, so it relocks itself.
  if state == "open" and openedAt and os.clock() - openedAt >= cfg.hold then
    lock()
    say("locked")
    input.redraw = true
  end
  local tick = math.floor(os.clock() * 4)
  if input.redraw or tick ~= lastTick then
    input.redraw = nil
    lastTick = tick
    draw()
  end
  gfx.present()
end

output(0)
return "Keypad: " .. (cfg.pin and ("PIN set, output " .. cfg.side) or "no PIN set")
