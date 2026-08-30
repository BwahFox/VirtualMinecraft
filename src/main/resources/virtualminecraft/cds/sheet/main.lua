-- Sheet (ROADMAP §9 U7): a spreadsheet — a grid, formulas and a chart. ROADMAP calls this "the single most
-- useful thing a player can be handed", and the reason is that it is the one program whose *point* is that you
-- write the program: a column of numbers and =SUM(A1:A9) is a tool nobody had to anticipate.
--
-- **Three panes stacked, not a toolbar**: the reference and the formula bar on top, the grid in the middle, a
-- status line and four buttons underneath. On a 1x1 monitor a window is 28 characters, which is a row header and
-- three columns — cramped, but a spreadsheet that only works on a monitor wall is a spreadsheet most players
-- never see.
--
-- **Editing happens in the formula bar, never in the cell.** An in-cell editor is a second text widget with its
-- own cursor, scrolling and clipping, and it buys nothing here: the bar is always visible, always shows the
-- *source* rather than the value, and typing a character on the grid jumps to it with that character already in
-- — which is what a spreadsheet feels like anyway.
--
-- **Values are computed lazily and memoised per edit**, with a visiting set for cycles. That is the whole recalc
-- engine: there is no dependency graph, because a sheet that fits on this machine is small enough that "work it
-- out when someone asks" is both simpler and fast enough.
local T = win.theme
local KEY = win.KEY
local DIR = "/disk"
local EXT = ".sheet"
local ROWS, COLS = 200, 52        -- A..AZ; the caps exist so a runaway formula cannot walk off the end
local CELLW = 7                   -- characters per column
local HDRW = 3                    -- characters in the row-number gutter

-- kernel.top() is our own window at launch, which is the only reliable way to find it: scanning
-- kernel.programs for "main.lua" picks the wrong one when a second CD is also running a main.lua.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

---------------------------------------------------------------------------------------------------- names
local function colName(c)
  local s = ""
  while c > 0 do
    local d = (c - 1) % 26
    s = string.char(65 + d) .. s
    c = math.floor((c - 1 - d) / 26)
  end
  return s
end

local function colIndex(s)
  local n = 0
  for i = 1, #s do n = n * 26 + (s:byte(i) - 64) end
  return n
end

local function ref(r, c) return colName(c) .. r end

---------------------------------------------------------------------------------------------------- the sheet
local cells = {}                  -- cells[r][c] = source string
local path = nil                  -- the file this sheet came from, or nil for an unsaved one
local dirty = false

local function src(r, c)
  local row = cells[r]
  return row and row[c] or nil
end

local function setSrc(r, c, s)
  if s == nil or s == "" then
    if cells[r] then cells[r][c] = nil end
  else
    cells[r] = cells[r] or {}
    cells[r][c] = s
  end
  dirty = true
end

---------------------------------------------------------------------------------------------------- values
-- An error is a table so it can never be confused with the string a cell might legitimately hold.
local function err(kind) return { err = kind } end
local function isErr(v) return type(v) == "table" and v.err ~= nil end

-- A table, not a string: a sentinel that can never collide with something a cell might legitimately hold.
local BLANK = {}
local vals, visiting = {}, {}
local function clearValues() vals, visiting = {}, {} end

local valueOf -- forward: the parser calls it for cell references

---------------------------------------------------------------------------------------------------- lexer
local function lex(s)
  local out, i, n = {}, 1, #s
  while i <= n do
    local c = s:sub(i, i)
    if c:match("%s") then
      i = i + 1
    elseif c:match("%d") or (c == "." and s:sub(i + 1, i + 1):match("%d")) then
      local num = s:match("^%d*%.?%d*", i)
      out[#out + 1] = { t = "num", v = tonumber(num) }
      i = i + #num
    elseif c == '"' then
      local j = i + 1
      local buf = {}
      while j <= n and s:sub(j, j) ~= '"' do buf[#buf + 1] = s:sub(j, j) j = j + 1 end
      if j > n then return nil, "#ERR" end
      out[#out + 1] = { t = "str", v = table.concat(buf) }
      i = j + 1
    elseif c:match("%a") then
      local word = s:match("^%a[%w_]*", i)
      i = i + #word
      -- A1 is a reference; SUM is a name. The difference is only whether digits follow the letters.
      local letters, digits = word:match("^(%a+)(%d+)$")
      if letters and digits and #letters <= 2 then
        out[#out + 1] = { t = "ref", r = tonumber(digits), c = colIndex(letters:upper()) }
      else
        out[#out + 1] = { t = "name", v = word:upper() }
      end
    else
      local two = s:sub(i, i + 1)
      if two == "<>" or two == "<=" or two == ">=" then
        out[#out + 1] = { t = "op", v = two }
        i = i + 2
      elseif ("+-*/^()&,:=<>%"):find(c, 1, true) then
        out[#out + 1] = { t = "op", v = c }
        i = i + 1
      else
        return nil, "#ERR"
      end
    end
  end
  return out
end

---------------------------------------------------------------------------------------------------- parser
-- Recursive descent, one function per precedence level. It throws a plain error() carrying the error table, and
-- the caller pcalls it — which is what keeps every failure path down to one line at each site.
local function parse(tokens)
  local pos = 1
  local function peek() return tokens[pos] end
  local function take() local t = tokens[pos] pos = pos + 1 return t end
  local function isOp(v)
    local t = tokens[pos]
    return t and t.t == "op" and t.v == v
  end
  local function expect(v)
    if not isOp(v) then error(err("#ERR"), 0) end
    pos = pos + 1
  end

  local expr

  --- A number out of a value, or an error thrown. Blank is 0, which is what makes SUM over a gappy column work.
  local function num(v)
    if isErr(v) then error(v, 0) end
    if v == nil then return 0 end
    if type(v) == "boolean" then return v and 1 or 0 end
    if type(v) == "number" then return v end
    local x = tonumber(v)
    if x == nil then error(err("#VALUE"), 0) end
    return x
  end

  --- Every value in a rectangle, in reading order. Blanks are skipped rather than counted as zero: COUNT and AVG
  --- would both be wrong otherwise, and "the average of the numbers I typed" is what anyone means.
  local function flatten(v, out)
    out = out or {}
    if type(v) == "table" and v.range then
      for r = v.r1, v.r2 do
        for c = v.c1, v.c2 do
          local cv = valueOf(r, c)
          if cv ~= nil then out[#out + 1] = cv end
        end
      end
    else
      out[#out + 1] = v
    end
    return out
  end

  local function callFn(name, args)
    local function numbers()
      local out = {}
      for _, a in ipairs(args) do
        for _, v in ipairs(flatten(a)) do
          if isErr(v) then error(v, 0) end
          -- Text inside a range is ignored rather than fatal: a column of numbers under a heading is the normal
          -- shape of a spreadsheet, and SUM(A1:A9) must not break because A1 says "Cost".
          if type(v) == "number" then out[#out + 1] = v
          elseif type(v) == "boolean" then out[#out + 1] = v and 1 or 0
          elseif type(v) == "string" and tonumber(v) then out[#out + 1] = tonumber(v) end
        end
      end
      return out
    end
    local function one() return num(flatten(args[1])[1]) end
    if name == "SUM" then
      local t, s = numbers(), 0
      for _, v in ipairs(t) do s = s + v end
      return s
    elseif name == "AVG" or name == "AVERAGE" then
      local t, s = numbers(), 0
      if #t == 0 then return err("#DIV/0") end
      for _, v in ipairs(t) do s = s + v end
      return s / #t
    elseif name == "MIN" or name == "MAX" then
      local t = numbers()
      if #t == 0 then return 0 end
      local best = t[1]
      for _, v in ipairs(t) do
        if (name == "MIN" and v < best) or (name == "MAX" and v > best) then best = v end
      end
      return best
    elseif name == "COUNT" then
      return #numbers()
    elseif name == "ABS" then return math.abs(one())
    elseif name == "INT" then return math.floor(one())
    elseif name == "SQRT" then
      local x = one()
      if x < 0 then return err("#VALUE") end
      return math.sqrt(x)
    elseif name == "ROUND" then
      local x = one()
      local d = args[2] and num(flatten(args[2])[1]) or 0
      local m = 10 ^ math.floor(d)
      return math.floor(x * m + 0.5) / m
    elseif name == "IF" then
      local cond = flatten(args[1])[1]
      if isErr(cond) then error(cond, 0) end
      local yes = cond ~= nil and cond ~= false and cond ~= 0 and cond ~= ""
      local pick = yes and args[2] or args[3]
      if pick == nil then return yes end
      return flatten(pick)[1]
    elseif name == "LEN" then
      local v = flatten(args[1])[1]
      return #tostring(v == nil and "" or v)
    elseif name == "TRUE" then return true
    elseif name == "FALSE" then return false
    elseif name == "PI" then return math.pi
    end
    return err("#NAME")
  end

  local function atom()
    local t = take()
    if t == nil then error(err("#ERR"), 0) end
    if t.t == "num" then return t.v end
    if t.t == "str" then return t.v end
    if t.t == "ref" then
      if isOp(":") then
        pos = pos + 1
        local b = take()
        if not b or b.t ~= "ref" then error(err("#REF"), 0) end
        return { range = true,
          r1 = math.min(t.r, b.r), r2 = math.max(t.r, b.r),
          c1 = math.min(t.c, b.c), c2 = math.max(t.c, b.c) }
      end
      if t.r < 1 or t.r > ROWS or t.c < 1 or t.c > COLS then error(err("#REF"), 0) end
      local v = valueOf(t.r, t.c)
      if isErr(v) then error(v, 0) end
      return v
    end
    if t.t == "name" then
      if not isOp("(") then
        -- A bare word is a function called with no arguments (PI, TRUE) or a typo. Both answer here.
        return callFn(t.v, {})
      end
      pos = pos + 1
      local args = {}
      if not isOp(")") then
        args[1] = expr()
        while isOp(",") do
          pos = pos + 1
          args[#args + 1] = expr()
        end
      end
      expect(")")
      local v = callFn(t.v, args)
      if isErr(v) then error(v, 0) end
      return v
    end
    if t.t == "op" and t.v == "(" then
      local v = expr()
      expect(")")
      return v
    end
    if t.t == "op" and (t.v == "-" or t.v == "+") then
      local v = num(atom())
      return t.v == "-" and -v or v
    end
    error(err("#ERR"), 0)
  end

  local function power()
    local base = atom()
    if isOp("^") then
      pos = pos + 1
      return num(base) ^ num(power())
    end
    return base
  end

  local function term()
    local v = power()
    while isOp("*") or isOp("/") or isOp("%") do
      local o = take().v
      local rhs = num(power())
      if o == "*" then v = num(v) * rhs
      else
        if rhs == 0 then error(err("#DIV/0"), 0) end
        v = o == "/" and num(v) / rhs or (num(v) % rhs)
      end
    end
    return v
  end

  local function sum()
    local v = term()
    while isOp("+") or isOp("-") do
      local o = take().v
      local rhs = num(term())
      v = o == "+" and num(v) + rhs or num(v) - rhs
    end
    return v
  end

  --- `&` joins text, which is the only way to build a label out of a number without a function for it.
  local function concat()
    local v = sum()
    while isOp("&") do
      pos = pos + 1
      local rhs = sum()
      local function s(x)
        if x == nil then return "" end
        if type(x) == "number" then return (math.floor(x) == x) and tostring(math.floor(x)) or tostring(x) end
        return tostring(x)
      end
      v = s(v) .. s(rhs)
    end
    return v
  end

  expr = function()
    local v = concat()
    local t = peek()
    if t and t.t == "op" and (t.v == "=" or t.v == "<>" or t.v == "<" or t.v == ">" or t.v == "<=" or t.v == ">=") then
      pos = pos + 1
      local rhs = concat()
      -- Numbers compare as numbers and anything else as text, so "abc" < "abd" works and 9 < 10 is not "1" < "9".
      local a, b = v, rhs
      if type(a) ~= type(b) or type(a) == "boolean" then a, b = tostring(a), tostring(b) end
      if t.v == "=" then return a == b
      elseif t.v == "<>" then return a ~= b
      elseif t.v == "<" then return a < b
      elseif t.v == ">" then return a > b
      elseif t.v == "<=" then return a <= b
      else return a >= b end
    end
    return v
  end

  local v = expr()
  if pos <= #tokens then error(err("#ERR"), 0) end
  if type(v) == "table" and v.range then error(err("#REF"), 0) end
  return v
end

--- The value of one cell: a number, a string, a boolean, an error table, or nil for blank.
valueOf = function(r, c)
  local key = r * 1000 + c
  if vals[key] ~= nil then
    local v = vals[key]
    -- NOT `v == BLANK and nil or v`: `and nil` collapses and the `or` branch always wins, so the memo handed the
    -- sentinel straight back and every empty cell in the grid drew the word "nil". Third time this idiom has bitten
    -- this project (HANDOFF, session 18) — the pattern cannot carry a nil, ever.
    if v == BLANK then return nil end
    return v
  end
  local s = src(r, c)
  local out
  if s == nil then
    out = nil
  elseif s:sub(1, 1) == "=" then
    if visiting[key] then return err("#CYCLE") end
    visiting[key] = true
    local tokens, lexErr = lex(s:sub(2))
    if not tokens then
      out = err(lexErr)
    else
      local ok, v = pcall(parse, tokens)
      if ok then out = v
      elseif isErr(v) then out = v
      else out = err("#ERR") end
    end
    visiting[key] = nil
  else
    out = tonumber(s) or s
  end
  vals[key] = out == nil and BLANK or out
  return out
end

--- What goes in the cell: numbers right, everything else left. A number is given as many decimals as will fit,
--- most first — 118.6667 in six characters is **118.67**, which is the number a person wanted, where the obvious
--- `%.2g` gives 1.2e+02, which is the number a computer wanted. Only when nothing fits does it fall back to an
--- exponent, and then to a row of hashes: cutting "123456789" to "123456~" would read as a value, and a wrong
--- number is worse than no number.
local function display(v, width)
  if v == nil then return "", "left" end
  if isErr(v) then return v.err, "left" end
  if type(v) == "boolean" then return v and "TRUE" or "FALSE", "left" end
  if type(v) == "number" then
    if v ~= v then return "#NAN", "right" end
    -- `%d` throws on a float with no integer representation, so it is only ever asked about small whole numbers.
    if math.floor(v) == v and math.abs(v) < 1e15 then
      local s = string.format("%d", v)
      if #s <= width then return s, "right" end
    end
    for d = 4, 0, -1 do
      local s = string.format("%." .. d .. "f", v)
      -- Trailing zeros only ever cost width and say nothing: ROUND(22/7,3) is 3.143, not 3.1430. Guarded on the
      -- point being there at all, or "120" would lose its nought.
      if s:find("%.") then s = s:gsub("0+$", ""):gsub("%.$", "") end
      if #s <= width then return s, "right" end
    end
    local e = string.format("%.0e", v)
    if #e <= width then return e, "right" end
    return string.rep("#", width), "right"
  end
  return tostring(v), "left"
end

---------------------------------------------------------------------------------------------------- window
local wd = me.window
wd.fullscreen = false
wd.title = "Sheet"
-- Bigger caps than the other window apps get, because every extra row and column here is another cell you can
-- see, and a spreadsheet you have to scroll to read is most of what makes one annoying.
wd.w = math.min(math.max(kernel.w - kernel.iconW - 8, 170), 380)
wd.h = math.min(math.max(kernel.h - kernel.taskbarH - 20, 120), 300)
wd.x, wd.y = kernel.iconW + 4, 14
wd.minW, wd.minH = T.fw * 20, T.fh * 10
wd:relayout()

local where = wd:add(win.Label{ text = "A1" })
local bar = wd:add(win.TextField{ text = "", placeholder = "value, text, or =formula" })
local grid = wd:add(win.Label{})            -- a Label instance used as a bare canvas; its methods are replaced below
local status = wd:add(win.Label{ text = "" })
local bh = T.fh + 6
local b1 = wd:add(win.Button{ text = "Open", h = bh })
local b2 = wd:add(win.Button{ text = "Save", h = bh })
local b3 = wd:add(win.Button{ text = "Chart", h = bh })
local b4 = wd:add(win.Button{ text = "Close", h = bh })
local buttons = { b1, b2, b3, b4 }
wd.buttons, wd.grid, wd.bar, wd.status, wd.where = buttons, grid, bar, status, where -- the harness

local refW = T.fw * 5
where.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 3, refW, T.fh end
bar.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = refW, 0, cw - refW, T.fh + 6 end
grid.layout = function(self, cw, ch)
  self.x, self.y, self.w, self.h = 0, T.fh + 8, cw, ch - (T.fh + 8) - bh - T.fh - 4
end
status.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 2, ch - bh - T.fh - 2, cw - 4, T.fh end
for i, b in ipairs(buttons) do
  b.layout = function(self, cw, ch)
    local bw = math.floor((cw - 2 * (#buttons - 1)) / #buttons)
    self.x, self.y, self.w = (i - 1) * (bw + 2), ch - bh, bw
  end
end
wd:relayout()

---------------------------------------------------------------------------------------------------- state
local cr, cc = 1, 1               -- the cursor cell
local ar, ac = 1, 1               -- the selection anchor; equal to the cursor when nothing is dragged out
local top, left = 1, 1            -- the first visible row and column
local mode = "grid"               -- "grid" | "chart"
local chartRange = nil

local function selection()
  return math.min(ar, cr), math.max(ar, cr), math.min(ac, cc), math.max(ac, cc)
end

local cellPx = T.fw * CELLW
local gutterPx = T.fw * HDRW + 2
--- Characters a cell body can really show. Not CELLW: the box loses a pixel to its separator, and formatting a
--- number to seven characters and then cutting it to six is how "118.67" became "1.2e+~".
local function cellChars() return math.max(1, math.floor((cellPx - 1) / T.fw)) end
local function rowH() return T.fh + 2 end
local function visCols() return math.max(1, math.floor((grid.w - gutterPx - 2) / cellPx)) end
local function visRows() return math.max(1, math.floor((grid.h - rowH() - 2) / rowH())) end

local function scrollIntoView()
  if cr < top then top = cr end
  if cr > top + visRows() - 1 then top = cr - visRows() + 1 end
  if cc < left then left = cc end
  if cc > left + visCols() - 1 then left = cc - visCols() + 1 end
  top = math.max(1, top)
  left = math.max(1, left)
end

local function setStatus()
  local r1, r2, c1, c2 = selection()
  if r1 ~= r2 or c1 ~= c2 then
    -- The one number everybody wants from a selection, and it is free once ranges exist.
    local n, s, mn, mx = 0, 0, nil, nil
    for r = r1, r2 do
      for c = c1, c2 do
        local v = valueOf(r, c)
        if type(v) == "number" then
          n = n + 1
          s = s + v
          mn = (mn == nil or v < mn) and v or mn
          mx = (mx == nil or v > mx) and v or mx
        end
      end
    end
    local avg = n > 0 and (math.floor(s / n * 100 + 0.5) / 100) or 0
    status.text = win.fit(ref(r1, c1) .. ":" .. ref(r2, c2) .. "  n=" .. n .. "  sum=" .. s .. "  avg=" .. avg, wd.w - 8)
  else
    local v = valueOf(cr, cc)
    local shown = display(v, 40)
    status.text = win.fit((path and fs.basename(path) or "untitled") .. (dirty and " *" or "")
      .. "   " .. ref(cr, cc) .. " = " .. (shown == "" and "(blank)" or shown), wd.w - 8)
  end
end

local function syncBar()
  where.text = ref(cr, cc)
  bar.text = src(cr, cc) or ""
  bar.cursor = #bar.text
  setStatus()
  wd:invalidate()
end

---------------------------------------------------------------------------------------------------- drawing
function grid:draw(ox, oy)
  local x, y = ox + self.x, oy + self.y
  gfx.fill(x, y, self.w, self.h, T.field)
  gfx.rect(x, y, self.w, self.h, T.frameDark)
  if mode == "chart" then return end
  local rh, nc, nr = rowH(), visCols(), visRows()
  local r1, r2, c1, c2 = selection()
  -- column headers
  gfx.fill(x + 1, y + 1, self.w - 2, rh, T.frame)
  for i = 0, nc - 1 do
    local c = left + i
    if c <= COLS then
      local cx = x + 1 + gutterPx + i * cellPx
      local name = colName(c)
      local hot = c >= c1 and c <= c2
      if hot then gfx.fill(cx, y + 1, cellPx - 1, rh, T.frameDark) end
      win.text(cx + math.floor((cellPx - #name * T.fw) / 2), y + 2, name, hot and T.selText or T.text)
    end
  end
  for j = 0, nr - 1 do
    local r = top + j
    if r > ROWS then break end
    local ry = y + 1 + rh + j * rh
    -- row header
    local hotRow = r >= r1 and r <= r2
    gfx.fill(x + 1, ry, gutterPx - 1, rh, hotRow and T.frameDark or T.frame)
    win.text(x + 2, ry + 1, win.fit(tostring(r), gutterPx - 2), hotRow and T.selText or T.text)
    for i = 0, nc - 1 do
      local c = left + i
      if c > COLS then break end
      local cx = x + 1 + gutterPx + i * cellPx
      local selected = r >= r1 and r <= r2 and c >= c1 and c <= c2
      if selected then gfx.fill(cx, ry, cellPx - 1, rh, T.sel) end
      local s, align = display(valueOf(r, c), cellChars())
      if s ~= "" then
        local tx = align == "right" and (cx + cellPx - 1 - #s * T.fw) or (cx + 1)
        win.text(tx, ry + 1, win.fit(s, cellPx - 1), selected and T.selText or T.text)
      end
      -- The cursor cell wears a box on top of the selection fill, so "where I am" survives inside "what I picked".
      if r == cr and c == cc then gfx.rect(cx - 1, ry - 1, cellPx + 1, rh + 1, T.title) end
      gfx.line(cx + cellPx - 1, ry, cx + cellPx - 1, ry + rh - 1, T.frame)
    end
    gfx.line(x + 1, ry + rh - 1, x + self.w - 2, ry + rh - 1, T.frame)
  end
  gfx.line(x + gutterPx - 1, y + 1, x + gutterPx - 1, y + self.h - 2, T.frameDark)
end

--- The chart: bars for every number in the selection, tallest to the top of the pane. Deliberately one chart
--- type — bars answer "which of these is biggest", which is the question a sheet in a game world actually asks,
--- and a chart menu would be more program than the thing it draws.
local function drawChart(ox, oy)
  local x, y, w, h = ox + grid.x, oy + grid.y, grid.w, grid.h
  local r1, r2, c1, c2 = chartRange[1], chartRange[2], chartRange[3], chartRange[4]
  local vs, labels = {}, {}
  for r = r1, r2 do
    for c = c1, c2 do
      local v = valueOf(r, c)
      if type(v) == "number" then
        vs[#vs + 1] = v
        labels[#labels + 1] = (c1 == c2) and tostring(r) or colName(c)
      end
    end
  end
  local title = ref(r1, c1) .. ":" .. ref(r2, c2)
  if #vs == 0 then
    win.text(x + 4, y + 4, win.fit("Nothing to chart in " .. title, w - 8), T.text)
    return
  end
  local lo, hi = vs[1], vs[1]
  for _, v in ipairs(vs) do
    if v < lo then lo = v end
    if v > hi then hi = v end
  end
  -- The baseline is zero unless every bar is above it; otherwise bars of similar size all look the same height.
  if lo > 0 then lo = 0 end
  if hi < 0 then hi = 0 end
  if hi == lo then hi = lo + 1 end
  local padTop, padBot = T.fh + 3, T.fh + 2
  local plotY, plotH = y + padTop, h - padTop - padBot
  local zero = plotY + plotH - math.floor((0 - lo) / (hi - lo) * plotH)
  local bw = math.max(2, math.floor((w - 6) / #vs))
  win.text(x + 3, y + 2, win.fit(title .. "   " .. lo .. " to " .. hi, w - 6), T.text)
  for i, v in ipairs(vs) do
    local bx = x + 3 + (i - 1) * bw
    local vy = plotY + plotH - math.floor((v - lo) / (hi - lo) * plotH)
    local bh2 = math.abs(vy - zero)
    gfx.fill(bx, math.min(vy, zero), math.max(1, bw - 1), math.max(1, bh2), v < 0 and T.warn or T.title)
    if bw >= T.fw + 1 then
      win.text(bx, y + h - T.fh - 1, win.fit(labels[i], bw), T.text)
    end
  end
  gfx.line(x + 2, zero, x + w - 3, zero, T.frameDark)
end

local baseDraw = grid.draw
function grid:draw(ox, oy)
  baseDraw(self, ox, oy)
  if mode == "chart" then drawChart(ox, oy) end
end

---------------------------------------------------------------------------------------------------- files
local function sheetToTable()
  local out = {}
  for r, row in pairs(cells) do
    for c, s in pairs(row) do out[ref(r, c)] = s end
  end
  return { cells = out, cursor = ref(cr, cc) }
end

local function tableToSheet(t)
  cells = {}
  if type(t) ~= "table" or type(t.cells) ~= "table" then return end
  for k, v in pairs(t.cells) do
    local letters, digits = tostring(k):match("^(%a+)(%d+)$")
    if letters and type(v) == "string" then
      local r, c = tonumber(digits), colIndex(letters:upper())
      if r >= 1 and r <= ROWS and c >= 1 and c <= COLS then
        cells[r] = cells[r] or {}
        cells[r][c] = v
      end
    end
  end
end

local function doSave(as)
  local function write(p)
    if not p:find("%" .. EXT .. "$") then p = p .. EXT end
    local ok, e = pcall(function() fs.write(p, json.encode(sheetToTable())) end)
    if not ok then
      win.info("Sheet", { "Could not save", p, tostring(e) })
      return
    end
    path = p
    dirty = false
    wd.title = "Sheet - " .. fs.basename(p)
    kernel.notify("Saved " .. fs.basename(p), 2)
    setStatus()
    wd:invalidate()
  end
  if path and not as then write(path) return end
  win.prompt("Save", "File:", path or (DIR .. "/sheet" .. EXT), function(p)
    if not p or p == "" then return end
    if not fs.validname(fs.basename(p)) then
      win.info("Sheet", { "Not a usable name.", fs.NAME_HELP })
      return
    end
    write(p)
  end)
end

local function doOpen()
  local found = {}
  local ok, entries = pcall(function() return fs.list(DIR) end)
  if ok and type(entries) == "table" then
    for _, e in ipairs(entries) do
      if not e.dir and e.name:find("%" .. EXT .. "$") then found[#found + 1] = DIR .. "/" .. e.name end
    end
  end
  table.sort(found)
  -- No file picker in the toolkit, so the prompt is seeded with the first sheet on the disk and says how many
  -- there are. On a machine with one sheet — which is most of them — that is a single Enter.
  local msg = #found == 0 and "File (none saved yet):" or ("File (" .. #found .. " on /disk):")
  win.prompt("Open", msg, found[1] or (DIR .. "/sheet" .. EXT), function(p)
    if not p or p == "" then return end
    if not p:find("%" .. EXT .. "$") then p = p .. EXT end
    if not fs.exists(p) then kernel.notify("No such sheet: " .. p, 4) return end
    local ok2, body = pcall(function() return fs.read(p) end)
    if not ok2 then win.info("Sheet", { "Could not read", p }) return end
    local ok3, t = pcall(function() return json.decode(body) end)
    if not ok3 then win.info("Sheet", { "Not a sheet file:", p }) return end
    tableToSheet(t)
    path = p
    dirty = false
    clearValues()
    cr, cc, ar, ac, top, left = 1, 1, 1, 1, 1, 1
    local letters, digits = tostring(t.cursor or "A1"):match("^(%a+)(%d+)$")
    if letters then cr, cc = tonumber(digits), colIndex(letters:upper()) ar, ac = cr, cc end
    scrollIntoView()
    wd.title = "Sheet - " .. fs.basename(p)
    syncBar()
  end)
end

---------------------------------------------------------------------------------------------------- editing
local function commit(text, move)
  setSrc(cr, cc, text)
  clearValues()
  if move == "down" then cr = math.min(ROWS, cr + 1)
  elseif move == "right" then cc = math.min(COLS, cc + 1) end
  ar, ac = cr, cc
  scrollIntoView()
  syncBar()
  wd:setfocus(grid)
end

local function clearSelection()
  local r1, r2, c1, c2 = selection()
  for r = r1, r2 do
    for c = c1, c2 do setSrc(r, c, nil) end
  end
  clearValues()
  syncBar()
end

--- Copy the top row of the selection down over the rest of it, translating references by the row offset — the
--- one piece of spreadsheet magic that is genuinely load-bearing, because without it a formula is a thing you
--- retype forty times.
local function fillDown()
  local r1, r2, c1, c2 = selection()
  if r1 == r2 then kernel.notify("Select more than one row first", 3) return end
  for c = c1, c2 do
    local s = src(r1, c)
    for r = r1 + 1, r2 do
      if s == nil then
        setSrc(r, c, nil)
      elseif s:sub(1, 1) ~= "=" then
        setSrc(r, c, s)
      else
        local d = r - r1
        setSrc(r, c, (s:gsub("(%a+)(%d+)", function(letters, digits)
          if #letters > 2 then return letters .. digits end -- a function name that happens to end in digits
          return letters .. tostring(tonumber(digits) + d)
        end)))
      end
    end
  end
  clearValues()
  syncBar()
end

---------------------------------------------------------------------------------------------------- input
function grid:press(lx, ly, button)
  if mode == "chart" then return end
  local rh = rowH()
  local c = left + math.floor((lx - self.x - 1 - gutterPx) / cellPx)
  local r = top + math.floor((ly - self.y - 1 - rh) / rh)
  if lx - self.x - 1 < gutterPx then c = left end   -- the row gutter selects from the first visible column
  if ly - self.y - 1 < rh then r = top end          -- the header row likewise
  cr = math.max(1, math.min(ROWS, r))
  cc = math.max(1, math.min(COLS, c))
  ar, ac = cr, cc
  syncBar()
end

function grid:drag(lx, ly)
  if mode == "chart" then return end
  local rh = rowH()
  local c = left + math.floor((lx - self.x - 1 - gutterPx) / cellPx)
  local r = top + math.floor((ly - self.y - 1 - rh) / rh)
  cr = math.max(1, math.min(ROWS, r))
  cc = math.max(1, math.min(COLS, c))
  scrollIntoView()
  setStatus()
  wd:invalidate()
end

function grid:wheel(dy)
  if mode == "chart" then return true end
  top = math.max(1, math.min(ROWS, top - dy * 3))
  wd:invalidate()
  return true
end

local function move(dr, dc, extend)
  cr = math.max(1, math.min(ROWS, cr + dr))
  cc = math.max(1, math.min(COLS, cc + dc))
  if not extend then ar, ac = cr, cc end
  scrollIntoView()
  syncBar()
end

function grid:key(code, down, mods)
  if not down then return false end
  if mode == "chart" then
    if code == KEY.esc or code == KEY.enter then mode = "grid" wd:invalidate() return true end
    return false
  end
  local shift = mods.shift
  if code == KEY.up then move(-1, 0, shift)
  elseif code == KEY.down then move(1, 0, shift)
  elseif code == KEY.left then move(0, -1, shift)
  elseif code == KEY.right then move(0, 1, shift)
  elseif code == KEY.pgup then move(-visRows(), 0, shift)
  elseif code == KEY.pgdn then move(visRows(), 0, shift)
  elseif code == KEY.home then cc = 1 if not shift then ar, ac = cr, cc end scrollIntoView() syncBar()
  elseif code == KEY["end"] then cr = 1 cc = 1 ar, ac = 1, 1 top, left = 1, 1 syncBar()
  elseif code == KEY.enter or code == KEY.kpenter then
    -- Enter on the grid means "edit this cell", which is the other half of typing straight into it.
    wd:setfocus(bar)
  elseif code == KEY.tab then move(0, 1, false)
  elseif code == KEY.delete or code == KEY.backspace then clearSelection()
  else return false end
  return true
end

--- Typing anywhere on the grid starts an edit with that character already in the bar, the way a spreadsheet does.
function grid:char(cp)
  if mode == "chart" then return false end
  local ch = win.utf8char(cp)
  if ch == "" then return false end
  bar.text = ch
  bar.cursor = #bar.text
  wd:setfocus(bar)
  wd:invalidate()
  return true
end

bar.onenter = function() commit(bar.text, "down") end

---------------------------------------------------------------------------------------------------- chart
local function doChart()
  if mode == "chart" then mode = "grid" wd:invalidate() return end
  local r1, r2, c1, c2 = selection()
  if r1 == r2 and c1 == c2 then
    kernel.notify("Select the numbers first (drag, or Shift and the arrows)", 4)
    return
  end
  chartRange = { r1, r2, c1, c2 }
  mode = "chart"
  wd:setfocus(grid)
  wd:invalidate()
end

---------------------------------------------------------------------------------------------------- buttons
b1.onclick = function() doOpen() end
b2.onclick = function() doSave(false) end
b3.onclick = doChart
b4.onclick = function() kernel.close(wd) end

wd.onrightpress = function(_, lx, ly, px, py)
  if not grid:contains(lx, ly) then return false end
  if mode == "chart" then
    win.menu(px, py, { { text = "Back to the grid", onclick = function() mode = "grid" wd:invalidate() end } })
    return true
  end
  grid:press(lx, ly, 2)
  local r1, r2, c1, c2 = selection()
  local many = r1 ~= r2 or c1 ~= c2
  win.menu(px, py, {
    { text = "Clear", onclick = clearSelection },
    { text = "Fill down", disabled = r1 == r2, onclick = fillDown },
    { sep = true },
    { text = "Sum below", disabled = not many, onclick = function()
      -- The commonest thing anyone does to a column, one click instead of typing the range out.
      local r1b, r2b, c1b, c2b = selection()
      for c = c1b, c2b do
        if r2b < ROWS then setSrc(r2b + 1, c, "=SUM(" .. ref(r1b, c) .. ":" .. ref(r2b, c) .. ")") end
      end
      clearValues()
      cr, ar = math.min(ROWS, r2b + 1), math.min(ROWS, r2b + 1)
      scrollIntoView()
      syncBar()
    end },
    { text = "Chart", disabled = not many, onclick = doChart },
    { sep = true },
    { text = "Save as...", onclick = function() doSave(true) end },
  })
  return true
end

wd.onkey = function(_, code, down, mods)
  if not down then return false end
  if mods.ctrl and code == KEY.s then doSave(false) return true end
  if mods.ctrl and code == KEY.o then doOpen() return true end
  if mods.ctrl and code == KEY.d then fillDown() return true end
  if code == KEY.esc then
    -- Esc in the bar is "forget this edit"; Esc on the grid leaves the chart.
    if mode == "chart" then mode = "grid" wd:invalidate() return true end
    syncBar()
    wd:setfocus(grid)
    return true
  end
  return false
end

--- Closing is how this program ends, so it is the last chance to write the file. A sheet that was never saved
--- goes to /disk/untitled.sheet rather than nowhere: onclose cannot put up a dialog and cannot refuse to close,
--- so the choice is between quietly keeping the work and quietly losing it, and losing an afternoon's numbers
--- because you clicked the wrong X is not a thing this should do. Open lists it like any other sheet.
local function saveOnExit()
  if not dirty then return end
  local p = path or (DIR .. "/untitled" .. EXT)
  if pcall(function() fs.write(p, json.encode(sheetToTable())) end) then
    dirty = false
    if not path then kernel.notify("Unsaved sheet kept as " .. fs.basename(p), 4) end
  end
end

wd.onclose = saveOnExit

---------------------------------------------------------------------------------------------------- go
-- A first sheet, so an empty machine shows what the thing does rather than an empty grid. It is a shopping list
-- with a total, which is both the smallest useful spreadsheet and the one that explains itself.
if not fs.exists(DIR .. "/sheet" .. EXT) then
  setSrc(1, 1, "Item") setSrc(1, 2, "Each") setSrc(1, 3, "Qty") setSrc(1, 4, "Cost")
  setSrc(2, 1, "Iron")   setSrc(2, 2, "12") setSrc(2, 3, "9")  setSrc(2, 4, "=B2*C2")
  setSrc(3, 1, "Gold")   setSrc(3, 2, "40") setSrc(3, 3, "3")  setSrc(3, 4, "=B3*C3")
  setSrc(4, 1, "Redst")  setSrc(4, 2, "2")  setSrc(4, 3, "64") setSrc(4, 4, "=B4*C4")
  setSrc(5, 1, "Total")  setSrc(5, 4, "=SUM(D2:D4)")
  setSrc(7, 1, "Type =SUM(D2:D4) in a cell, or select numbers and press Chart.")
  dirty = true
end

clearValues()
syncBar()
wd:setfocus(grid)
wd:invalidate()

while not wd.closed do os.sleep(120) end
saveOnExit()
local n = 0
for _, row in pairs(cells) do for _ in pairs(row) do n = n + 1 end end
return "Sheet: " .. n .. " cells"
