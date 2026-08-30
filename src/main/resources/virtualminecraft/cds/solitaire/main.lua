-- Solitaire (Klondike): the program that sold more office hours than any other piece of software ever written,
-- and the one everybody already knows how to play. Pointer-first, which suits it — this is a game about picking
-- a card up and putting it down, and the desktop already has a cursor.
--
-- Draw one, unlimited redeals. That is the forgiving variant rather than the tournament one, deliberately: a
-- player who finds this on a floppy in a village chest should win sometimes.
--
-- The suits are drawn, not typed. The 6x8 font has no pip characters, and four letters (H D C S) on the corner
-- of every card reads like a spreadsheet, so hearts/diamonds/clubs/spades are built out of discs and triangles.
local w, h = gfx.size()
local KEY = win.KEY
local KEY_H = 0x23        -- win.KEY does not name H; raw scancode, per PROGRAMMING.md gotcha 3
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local GAP = 2
local CARDW = math.max(18, math.floor((w - GAP * 8) / 7))
local CARDH = math.max(16, math.min(34, math.floor(CARDW * 0.78)))
local OX = math.floor((w - (CARDW * 7 + GAP * 6)) / 2)
local TOPY = 3
local TABY = TOPY + CARDH + 5
local FOOT = 10
-- How far each card in a pile peeks out from under the one on top of it. Face-down cards show less because
-- there is nothing on them worth seeing; face-up cards must show a rank and a pip.
local DOWNFAN = math.max(3, math.floor(CARDH * 0.16))
local UPFAN = math.max(7, math.floor(CARDH * 0.38))

local FELT, FACE, EDGE, BACK1, BACK2 = 22, 255, 0, 12, 4
local RED, BLACK, SLOT, HILITE = 8, 0, 59, 11
local RANKS = { "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K" }
-- 1 spades, 2 hearts, 3 diamonds, 4 clubs — hearts and diamonds are the red ones.
local function isRed(suit) return suit == 2 or suit == 3 end

local stock, waste, found, tab = {}, {}, {}, {}
local sel, message, messageUntil, moves, won = nil, nil, 0, 0, false
local help = true   -- shown on the first deal: a game nobody can play is a game nobody plays
local input, hovered = {}, nil
local quit = false
local queue = {}
local function enqueue(v) queue[#queue + 1] = v end

local function say(s, secs) message, messageUntil = s, os.clock() + (secs or 2) end

---------------------------------------------------------------------------------------------- the suit pips
-- No polygon fill in the graphics API, so a triangle is a stack of horizontal lines that get shorter.
local function tri(cx, y, halfw, height, col, up)
  for i = 0, height - 1 do
    local t = i / math.max(1, height - 1)
    local hw = up and math.floor(halfw * t) or math.floor(halfw * (1 - t))
    gfx.line(cx - hw, y + i, cx + hw, y + i, col)
  end
end

local function pip(suit, cx, cy, size, col)
  local r = math.max(2, math.floor(size / 3))
  if suit == 2 then                                   -- heart: two lobes and a point
    gfx.disc(cx - r + 1, cy - r + 1, r, col)
    gfx.disc(cx + r - 1, cy - r + 1, r, col)
    tri(cx, cy - r + 1, r * 2 - 1, r * 2, col, false)
  elseif suit == 3 then                               -- diamond: two triangles back to back
    tri(cx, cy - size + 1, size - 1, size, col, true)
    tri(cx, cy, size - 1, size, col, false)
  elseif suit == 1 then                               -- spade: a point, two lobes, a stem
    tri(cx, cy - size + 1, size - 1, size, col, true)
    gfx.disc(cx - r + 1, cy - 1, r, col)
    gfx.disc(cx + r - 1, cy - 1, r, col)
    gfx.fill(cx - 1, cy, 2, math.max(2, math.floor(size / 2)), col)
  else                                                -- club: three lobes and a stem
    gfx.disc(cx, cy - size + r, r, col)
    gfx.disc(cx - r, cy, r, col)
    gfx.disc(cx + r, cy, r, col)
    gfx.fill(cx - 1, cy, 2, math.max(2, math.floor(size / 2)), col)
  end
end

---------------------------------------------------------------------------------------------------- the deal
local function shuffled()
  local d = {}
  for s = 1, 4 do for r = 1, 13 do d[#d + 1] = { rank = r, suit = s, up = false } end end
  for i = #d, 2, -1 do
    local j = math.random(i)
    d[i], d[j] = d[j], d[i]
  end
  return d
end

local function deal()
  local d = shuffled()
  stock, waste, found, tab = {}, {}, { {}, {}, {}, {} }, {}
  for c = 1, 7 do
    tab[c] = {}
    for i = 1, c do
      local card = table.remove(d)
      card.up = (i == c)                              -- only the last card of each column starts face up
      tab[c][i] = card
    end
  end
  while #d > 0 do stock[#stock + 1] = table.remove(d) end
  sel, moves, won = nil, 0, false
  input.redraw = true
end

---------------------------------------------------------------------------------------------------- the rules
local function canFound(card, f)
  if not card then return false end
  local pile = found[f]
  if #pile == 0 then return card.rank == 1 and card.suit == f end
  return card.suit == f and card.rank == #pile + 1
end

local function canTab(card, c)
  if not card then return false end
  local pile = tab[c]
  if #pile == 0 then return card.rank == 13 end       -- only a King goes on an empty column
  local top = pile[#pile]
  if not top.up then return false end
  return top.rank == card.rank + 1 and isRed(top.suit) ~= isRed(card.suit)
end

local function checkWin()
  for f = 1, 4 do if #found[f] ~= 13 then return end end
  won = true
  for k = 1, 4 do snd.channel(k, snd.SQUARE, 220 * k, 0.4, 0.01, 0.5, 0, 0.25) end
end

-- Turning over the card a move exposed. This is the only place a face-down card ever flips, which is why it is
-- worth its own function: forgetting it is the classic solitaire bug.
local function expose(c)
  local pile = tab[c]
  local top = pile[#pile]
  if top and not top.up then
    top.up = true
    snd.beep(600, 0.03, 0)
  end
end

local function toFoundation(card, fromPile, fromCol)
  for f = 1, 4 do
    if canFound(card, f) then
      found[f][#found[f] + 1] = card
      if fromPile == "waste" then table.remove(waste)
      else table.remove(tab[fromCol]) expose(fromCol) end
      moves = moves + 1
      snd.beep(880, 0.03, 0)
      checkWin()
      return true
    end
  end
  return false
end

---------------------------------------------------------------------------------------------------- drawing
local function drawCard(x, y, card, selected)
  if not card then return end
  if card.up then
    gfx.fill(x, y, CARDW, CARDH, FACE)
    gfx.rect(x, y, CARDW, CARDH, selected and HILITE or EDGE)
    if selected then gfx.rect(x + 1, y + 1, CARDW - 2, CARDH - 2, HILITE) end
    local col = isRed(card.suit) and RED or BLACK
    gfx.text(x + 2, y + 2, RANKS[card.rank], col, nil, 1)
    local size = math.max(3, math.floor(CARDH / 4))
    pip(card.suit, x + CARDW - size - 3, y + CARDH - size - 3, size, col)
  else
    gfx.fill(x, y, CARDW, CARDH, BACK1)
    gfx.rect(x, y, CARDW, CARDH, EDGE)
    for i = 3, CARDH - 4, 3 do gfx.line(x + 2, y + i, x + CARDW - 3, y + i, BACK2) end
  end
end

local function slot(x, y, label)
  gfx.rect(x, y, CARDW, CARDH, SLOT)
  if label then gfx.text(x + math.floor((CARDW - #label * 6) / 2), y + math.floor((CARDH - 8) / 2), label, SLOT, nil, 1) end
end

local function colX(c) return OX + (c - 1) * (CARDW + GAP) end

-- Where card i of column c sits. Walking the pile is the only honest way: the offset depends on how many of
-- the cards under it are face down.
local function cardY(c, i)
  local y = TABY
  for k = 1, i - 1 do y = y + (tab[c][k].up and UPFAN or DOWNFAN) end
  return y
end

local function draw()
  gfx.clear(FELT)

  if #stock > 0 then drawCard(colX(1), TOPY, { up = false }) else slot(colX(1), TOPY, "O") end
  if #waste > 0 then
    drawCard(colX(2), TOPY, waste[#waste], sel and sel.pile == "waste")
  else slot(colX(2), TOPY) end
  for f = 1, 4 do
    local x = colX(3 + f)
    if #found[f] > 0 then drawCard(x, TOPY, found[f][#found[f]])
    else
      slot(x, TOPY)
      local size = math.max(3, math.floor(CARDH / 4))
      pip(f, x + math.floor(CARDW / 2), TOPY + math.floor(CARDH / 2) + math.floor(size / 2), size, SLOT)
    end
  end

  for c = 1, 7 do
    local pile = tab[c]
    if #pile == 0 then slot(colX(c), TABY) end
    for i = 1, #pile do
      local selected = sel and sel.pile == "tab" and sel.col == c and i >= sel.idx
      drawCard(colX(c), cardY(c, i), pile[i], selected)
    end
  end

  if help then
    -- Klondike's rules are four lines and everybody assumes everybody already knows them. [name] did not, and
    -- said so — which is the whole argument for this box, because the point of this machine is that you can
    -- use it without going and reading something first.
    local lines = {
      "SOLITAIRE",
      "",
      "Build the four slots at the top",
      "up from Ace to King, one suit",
      "each.",
      "",
      "Stack the columns DOWN in",
      "alternating colours: a red 7 on",
      "a black 8. Only a King goes on",
      "an empty column.",
      "",
      "Click a card, then click where",
      "it goes. RIGHT-CLICK sends one",
      "straight home. Click the deck",
      "to turn cards over.",
      "",
      "A sends home all it can.",
      "H shows this again.",
      "",
      "any key or click to start",
    }
    local bw = math.min(w - 6, 33 * 6 + 12)
    local bh = math.min(h - 6, #lines * 9 + 10)
    local bx, by = math.floor((w - bw) / 2), math.floor((h - bh) / 2)
    gfx.fill(bx, by, bw, bh, 0)
    gfx.rect(bx, by, bw, bh, HILITE)
    for i, line in ipairs(lines) do
      local col = (i == 1 or i == #lines) and HILITE or 15
      gfx.text(bx + 6, by + 5 + (i - 1) * 9, win.fit(line, bw - 12), col, nil, 1)
    end
    return
  end

  gfx.fill(0, h - FOOT, w, FOOT, 0)
  -- The hint has to fit a 256-wide screen, where win.fit would otherwise cut it off mid-word.
  local hint = won and "you win!  N deals again"
            or ("moves " .. moves .. "   right-click sends home   A auto  H help  N new  Q quit")
  gfx.text(2, h - FOOT + 1, win.fit(hint, w - 4), won and HILITE or 6, nil, 1)
  if message and os.clock() < messageUntil then
    gfx.fill(0, h - FOOT, w, FOOT, 0)
    gfx.text(2, h - FOOT + 1, win.fit(message, w - 4), 10, nil, 1)
  end
end

---------------------------------------------------------------------------------------------------- the input
-- What is under the pointer: the stock, the waste, a foundation, or a particular card in a tableau column.
-- Tableau columns are walked from the top card down, because that is the one you would actually be touching.
local function hit(px, py)
  if py >= TOPY and py < TOPY + CARDH then
    for c = 1, 7 do
      if px >= colX(c) and px < colX(c) + CARDW then
        if c == 1 then return { pile = "stock" } end
        if c == 2 then return { pile = "waste" } end
        if c >= 4 then return { pile = "found", f = c - 3 } end
        return nil
      end
    end
    return nil
  end
  for c = 1, 7 do
    if px >= colX(c) and px < colX(c) + CARDW then
      local pile = tab[c]
      if #pile == 0 then
        if py >= TABY and py < TABY + CARDH then return { pile = "tab", col = c, idx = 1, empty = true } end
        return nil
      end
      for i = #pile, 1, -1 do
        local y = cardY(c, i)
        local bottom = (i == #pile) and (y + CARDH) or cardY(c, i + 1)
        if py >= y and py < bottom then return { pile = "tab", col = c, idx = i } end
      end
      return nil
    end
  end
  return nil
end

local function dealFromStock()
  if #stock > 0 then
    local card = table.remove(stock)
    card.up = true
    waste[#waste + 1] = card
    snd.beep(440, 0.02, 0)
  elseif #waste > 0 then
    -- Recycling the waste: it goes back under the stock in the same order, face down again.
    while #waste > 0 do
      local card = table.remove(waste)
      card.up = false
      stock[#stock + 1] = card
    end
    snd.beep(300, 0.05, 0)
  end
  sel = nil
  input.redraw = true
end

-- Moving a run of face-up cards from one column to another. A single card is just a run of length one, so
-- there is only one path through this.
local function moveRun(fromCol, idx, toCol)
  local run = {}
  for i = idx, #tab[fromCol] do run[#run + 1] = tab[fromCol][i] end
  if not canTab(run[1], toCol) then return false end
  for _ = idx, #tab[fromCol] do table.remove(tab[fromCol]) end
  for _, card in ipairs(run) do tab[toCol][#tab[toCol] + 1] = card end
  expose(fromCol)
  moves = moves + 1
  snd.beep(520, 0.02, 0)
  return true
end

local function selectedCard()
  if not sel then return nil end
  if sel.pile == "waste" then return waste[#waste] end
  return tab[sel.col] and tab[sel.col][sel.idx]
end

local function click(px, py, right)
  if help then help = false input.redraw = true return end
  if won then return end
  local t = hit(px, py)
  if not t then sel = nil input.redraw = true return end

  if t.pile == "stock" then dealFromStock() return end

  -- Right button is the shortcut everyone wants: send this card home if it can go.
  if right then
    if t.pile == "waste" and #waste > 0 then
      if not toFoundation(waste[#waste], "waste") then say("nowhere to put it") end
    elseif t.pile == "tab" and not t.empty then
      local pile = tab[t.col]
      if t.idx == #pile and pile[t.idx].up then
        if not toFoundation(pile[t.idx], "tab", t.col) then say("nowhere to put it") end
      end
    end
    sel = nil
    input.redraw = true
    return
  end

  if not sel then
    -- Picking up. Only a face-up card can be taken, and from the waste only the top one.
    if t.pile == "waste" then
      if #waste > 0 then sel = { pile = "waste" } end
    elseif t.pile == "tab" and not t.empty then
      local card = tab[t.col][t.idx]
      if card and card.up then sel = { pile = "tab", col = t.col, idx = t.idx } end
    end
    input.redraw = true
    return
  end

  -- Putting down. Clicking the card you are holding puts it back.
  if t.pile == "waste" and sel.pile == "waste" then sel = nil input.redraw = true return end
  if t.pile == "tab" and sel.pile == "tab" and t.col == sel.col then sel = nil input.redraw = true return end

  local card = selectedCard()
  if t.pile == "found" then
    -- Only a single card goes to a foundation, never a run.
    local single = (sel.pile == "waste") or (sel.idx == #tab[sel.col])
    if single and canFound(card, t.f) then
      found[t.f][#found[t.f] + 1] = card
      if sel.pile == "waste" then table.remove(waste) else table.remove(tab[sel.col]) expose(sel.col) end
      moves = moves + 1
      snd.beep(880, 0.03, 0)
      checkWin()
    else say("that does not go there") end
  elseif t.pile == "tab" then
    if sel.pile == "waste" then
      if canTab(card, t.col) then
        tab[t.col][#tab[t.col] + 1] = card
        table.remove(waste)
        moves = moves + 1
        snd.beep(520, 0.02, 0)
      else say("that does not go there") end
    else
      if not moveRun(sel.col, sel.idx, t.col) then say("that does not go there") end
    end
  end
  sel = nil
  input.redraw = true
end

-- Autoplay: send everything that can go home, over and over until nothing moves. The end of a won game is
-- forty clicks of pure bookkeeping and nobody enjoys them.
local function autoplay()
  local moved = true
  local n = 0
  while moved and n < 60 do
    moved = false
    n = n + 1
    if #waste > 0 and toFoundation(waste[#waste], "waste") then moved = true end
    for c = 1, 7 do
      local pile = tab[c]
      if #pile > 0 and pile[#pile].up and toFoundation(pile[#pile], "tab", c) then moved = true end
    end
  end
  if n <= 1 then say("nothing to send up") end
  input.redraw = true
end

if me then
  me.window.cursor = true
  kernel.showCursor(true)
  local buttons = 0
  me.pointer = function(px, py, b, pressed)
    if pressed then enqueue({ px, py, false } ) end
    if b >= 2 and buttons < 2 then enqueue({ px, py, true }) end
    buttons = b
  end
  me.key = function(code, down)
    if not down then return end
    if help then                                   -- any key dismisses it; H is how you get it back
      help = false
      input.redraw = true
      if code ~= KEY.q and code ~= KEY.esc then return end
    end
    if code == KEY.q or code == KEY.esc then input.quit = true
    elseif code == KEY.n then input.new = true
    elseif code == KEY.a then input.auto = true
    elseif code == KEY_H then input.help = true
    elseif code == KEY.space then input.deal = true end
    input.redraw = true
  end
end

deal()
draw()
gfx.present()
while not quit do
  if input.quit then input.quit = nil quit = true end
  if input.new then input.new = nil deal() end
  if input.deal then input.deal = nil dealFromStock() end
  if input.auto then input.auto = nil autoplay() end
  if input.help then input.help = nil help = true input.redraw = true end
  while #queue > 0 do
    local c = table.remove(queue, 1)
    click(c[1], c[2], c[3])
  end
  if input.redraw then input.redraw = nil draw() end
  gfx.present()
end
local total = 0
for f = 1, 4 do total = total + #found[f] end
return "Solitaire: " .. (won and ("won in " .. moves .. " moves") or (total .. " of 52 home, " .. moves .. " moves"))
