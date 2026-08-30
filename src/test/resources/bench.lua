-- The S0 speed benchmark (ROADMAP §7h §10, S0 point 6). The same file runs under lua.wasm (MachineBench) and
-- under Cobalt (CobaltRef); only wall time is compared. Written in the 5.1 subset both understand (no //, no bit ops). Four parts, roughly what a game on the machine does:
-- call-heavy code, table churn, string work, and a sprite-style pixel loop over a 1024x768/8 buffer.
local function fib(n) if n < 2 then return n end return fib(n - 1) + fib(n - 2) end

local function part_fib()
  return fib(27)
end

local function part_tables()
  local acc = 0
  for i = 1, 300000 do
    local t = { x = i, y = i * 2, name = "p" }
    acc = acc + t.x + t.y + #t.name
  end
  local arr = {}
  for i = 1, 200000 do arr[i] = i end
  for i = 1, 200000 do acc = acc + arr[i] end
  return acc
end

local function part_strings()
  local n = 0
  for i = 1, 20000 do
    local s = "item" .. i .. ":" .. (i * 3)
    local a, b = s:match("(%a+)(%d+)")
    n = n + #a + #b
    local u = s:upper():gsub("%d", "#")
    n = n + #u
  end
  local parts = {}
  for i = 1, 20000 do parts[i] = tostring(i) end
  return n + #table.concat(parts, ",")
end

local function part_pixels()
  local W, H = 1024, 768
  local buf = {}
  local stride = math.floor(W / 8)
  for i = 1, stride * H do buf[i] = 0 end
  local sum = 0
  for frame = 1, 3 do
    for y = 0, H - 1 do
      local row = y * stride
      for x = 1, stride do
        local v = (x + y + frame) % 256
        buf[row + x] = v
        sum = sum + v
      end
    end
  end
  return sum
end

local total = 0
total = total + part_fib()
total = total + part_tables()
total = total + part_strings()
total = total + part_pixels()
local say = (vmc and function(s) vmc.log(1, s) end) or print or function() end
say("bench total " .. total)
return total
