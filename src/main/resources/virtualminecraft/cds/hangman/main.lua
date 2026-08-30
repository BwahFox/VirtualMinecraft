-- Hangman: guess the word before the drawing finishes. The oldest paper-and-pencil game there is, which is
-- exactly why it belongs on a machine that wants a library — it costs an evening and everyone already knows how
-- to play, so it needs no manual.
--
-- The word list is a file, not a table in this program: `/disk/words.txt` if the machine has one, otherwise the
-- `/cd0/words.txt` that ships on the CD. A player who wants their own words edits a text file, which is the
-- kind of thing that makes a library feel bigger than the number of programs in it.
local w, h = gfx.size()
local KEY = win.KEY
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local MISSES = 6                                    -- head, body, two arms, two legs
local HEAD = 14
local FOOT = 11
local BACK, PAPER, INK, GOOD, BAD, DIM = 16, 59, 15, 11, 8, 102

local ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
local KEYCOLS = 13
local KEYROWS = 2
local KW = math.max(9, math.floor((w - 8) / KEYCOLS))
local KH = math.max(9, math.min(18, math.floor((h - HEAD - FOOT) * 0.22 / KEYROWS)))
local KOX = math.floor((w - KW * KEYCOLS) / 2)
local KOY = h - FOOT - KH * KEYROWS - 3

local word, guessed, misses, state = "", {}, 0, "playing"
local wins, losses, played = 0, 0, 0
local words = {}
local input, hovered = {}, nil
local quit = false
-- Guesses queue: typed letters arrive several to a frame, and a single slot would drop all but the last.
local queue = {}
local function enqueue(v) queue[#queue + 1] = v end

-- Reading the list. Anything that is not a run of letters is dropped rather than repaired: a word with a space
-- or a digit in it cannot be drawn on the letter keyboard, so it would be an unwinnable round.
local function loadwords(path)
  if not fs.exists(path) then return false end
  local ok, text = pcall(function() return fs.read(path) end)
  if not ok or type(text) ~= "string" then return false end
  local found = 0
  for line in (text .. "\n"):gmatch("([^\n]*)\n") do
    local s = line:match("^%s*(.-)%s*$"):upper()
    if s ~= "" and s:sub(1, 1) ~= "#" and s:match("^%u+$") and #s >= 3 then
      words[#words + 1] = s
      found = found + 1
    end
  end
  return found > 0
end

local function newround()
  word = words[math.random(#words)] or "COMPUTER"
  guessed, misses, state = {}, 0, "playing"
  input.redraw = true
end

local function shown()
  local out = {}
  for i = 1, #word do
    local c = word:sub(i, i)
    -- A lost round reveals the whole word in place: the player wants to see what it was where they were
    -- looking, not read it out of the status line.
    out[i] = (state == "lost" or guessed[c]) and c or "_"
  end
  return table.concat(out, " ")
end

local function complete()
  for i = 1, #word do if not guessed[word:sub(i, i)] then return false end end
  return true
end

local function guess(c)
  if state ~= "playing" or guessed[c] then return end
  guessed[c] = true
  if word:find(c, 1, true) then
    snd.beep(700, 0.03, 0)
    if complete() then
      state, wins, played = "won", wins + 1, played + 1
      for k = 1, 3 do snd.channel(k, snd.SQUARE, 440 * k, 0.4, 0.01, 0.4, 0, 0.2) end
    end
  else
    misses = misses + 1
    snd.beep(240, 0.05, 0)
    if misses >= MISSES then
      state, losses, played = "lost", losses + 1, played + 1
      snd.channel(1, snd.NOISE, 60, 0.6, 0, 0.4, 0, 0.15)
    end
  end
  input.redraw = true
end

local function keyAt(px, py)
  if py < KOY or py >= KOY + KH * KEYROWS then return nil end
  local col = math.floor((px - KOX) / KW)
  local row = math.floor((py - KOY) / KH)
  if col < 0 or col >= KEYCOLS or row < 0 or row >= KEYROWS then return nil end
  local i = row * KEYCOLS + col + 1
  if i > #ALPHA then return nil end
  return i
end

-- The gallows. Drawn from the parts that are "used up", so the picture is the score: six misses and it is done.
local function gallows(x, y, size)
  local base = y + size
  gfx.line(x, base, x + math.floor(size * 0.6), base, DIM)                       -- ground
  gfx.line(x + math.floor(size * 0.2), base, x + math.floor(size * 0.2), y, DIM) -- post
  gfx.line(x + math.floor(size * 0.2), y, x + math.floor(size * 0.6), y, DIM)    -- beam
  local rx = x + math.floor(size * 0.6)
  gfx.line(rx, y, rx, y + math.floor(size * 0.12), DIM)                          -- rope
  local hy = y + math.floor(size * 0.12)
  local r = math.max(3, math.floor(size * 0.09))
  local body = math.floor(size * 0.32)
  if misses >= 1 then gfx.circle(rx, hy + r, r, INK) end
  if misses >= 2 then gfx.line(rx, hy + r * 2, rx, hy + r * 2 + body, INK) end
  if misses >= 3 then gfx.line(rx, hy + r * 2 + math.floor(body * 0.25), rx - math.floor(size * 0.14), hy + r * 2 + math.floor(body * 0.6), INK) end
  if misses >= 4 then gfx.line(rx, hy + r * 2 + math.floor(body * 0.25), rx + math.floor(size * 0.14), hy + r * 2 + math.floor(body * 0.6), INK) end
  if misses >= 5 then gfx.line(rx, hy + r * 2 + body, rx - math.floor(size * 0.14), hy + r * 2 + body + math.floor(size * 0.18), INK) end
  if misses >= 6 then gfx.line(rx, hy + r * 2 + body, rx + math.floor(size * 0.14), hy + r * 2 + body + math.floor(size * 0.18), INK) end
end

local function draw()
  gfx.clear(BACK)
  gfx.fill(0, 0, w, HEAD, 0)
  local status = state == "won" and "got it - N for another"
              or state == "lost" and ("it was " .. word)
              or ((MISSES - misses) .. " wrong left")
  local title = "hangman  " .. wins .. "/" .. played
  local sx = w - #status * 6 - 2
  -- A long reveal ("it was NETHERRACK") is wide enough to reach back into the title on a 256-wide screen, and
  -- two strings drawn over each other is worse than one. The score is the one that can be spared.
  if sx > #title * 6 + 6 then gfx.text(2, 3, title, 7, nil, 1) end
  gfx.text(math.max(2, sx), 3, status, state == "won" and GOOD or state == "lost" and BAD or 6, nil, 1)

  local size = math.min(math.floor((KOY - HEAD) * 0.72), math.floor(w * 0.4))
  gallows(math.floor(w * 0.08), HEAD + 8, size)

  -- The word, centred in whatever room is left beside the gallows, and never so wide it runs off the screen.
  local s = shown()
  local scale = 1
  local tw = #s * 6
  local wx = math.floor(w * 0.5) + math.floor((math.floor(w * 0.5) - tw) / 2)
  if tw > math.floor(w * 0.5) then wx = math.max(2, math.floor((w - tw) / 2)) end
  gfx.text(wx, HEAD + math.floor(size * 0.45), s, state == "lost" and BAD or INK, nil, scale)

  -- Letters already spent, so a player is not guessing the same wrong letter twice.
  local wrong = {}
  for i = 1, #ALPHA do
    local c = ALPHA:sub(i, i)
    if guessed[c] and not word:find(c, 1, true) then wrong[#wrong + 1] = c end
  end
  if #wrong > 0 then
    gfx.text(math.floor(w * 0.5), HEAD + math.floor(size * 0.45) + 14, win.fit(table.concat(wrong, " "), math.floor(w * 0.5) - 4), BAD, nil, 1)
  end

  for i = 1, #ALPHA do
    local c = ALPHA:sub(i, i)
    local col, row = (i - 1) % KEYCOLS, math.floor((i - 1) / KEYCOLS)
    local kx, ky = KOX + col * KW, KOY + row * KH
    local hit = guessed[c] and word:find(c, 1, true)
    local face = PAPER
    if guessed[c] then face = hit and 22 or 40 end
    if hovered == i and not guessed[c] then face = face + 2 end
    gfx.fill(kx, ky, KW - 1, KH - 1, face)
    gfx.text(kx + math.floor((KW - 6) / 2), ky + math.floor((KH - 8) / 2), c, guessed[c] and (hit and GOOD or DIM) or INK, nil, 1)
  end
  -- Kept short enough to fit a 256-wide screen, where win.fit would otherwise cut it off mid-word.
  local foot = state == "playing" and "type or click a letter   Enter deals   Q quits"
                                  or "any letter deals again   Q quits"
  gfx.text(2, h - FOOT + 2, win.fit(foot, w - 4), 6, nil, 1)
end

if me then
  me.window.cursor = true
  kernel.showCursor(true)
  me.pointer = function(px, py, b, pressed)
    local i = keyAt(px, py)
    if i ~= hovered then hovered = i input.redraw = true end
    if pressed and i then enqueue(ALPHA:sub(i, i)) end
  end
  -- Letters as characters, not scancodes: win.KEY only names the handful the ROM's apps needed, and this game
  -- needs all twenty-six. kernel dispatches char to the top program, which is what makes this a one-liner.
  me.char = function(cp)
    local c = string.char(cp):upper()
    if c >= "A" and c <= "Z" then enqueue(c) end
  end
  -- New word is on ENTER, not on a letter. me.key and me.char BOTH fire for one keypress, so binding it to N
  -- meant pressing N dealt a fresh word and then immediately guessed N against it. No letter can carry a
  -- command in a game where every letter is a move.
  me.key = function(code, down)
    if not down then return end
    if code == KEY.q or code == KEY.esc then input.quit = true
    elseif code == KEY.enter or code == KEY.kpenter then input.new = true end
  end
end

if not loadwords("/disk/words.txt") then loadwords("/cd0/words.txt") end
if #words == 0 then words = { "COMPUTER", "REDSTONE", "CREEPER", "MONITOR", "OBSIDIAN" } end
newround()
draw()
gfx.present()
while not quit do
  if input.quit then input.quit = nil quit = true end
  if input.new then input.new = nil newround() end
  while #queue > 0 do
    local c = table.remove(queue, 1)
    -- N is a letter and also "next word"; while a round is over, the keyboard means next word.
    if state ~= "playing" then newround() else guess(c) end
  end
  if input.redraw then input.redraw = nil draw() end
  gfx.present()
end
return "Hangman: " .. wins .. " of " .. played .. " guessed"
