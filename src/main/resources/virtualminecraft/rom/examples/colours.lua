-- How many colours this case lets a program choose. Q leaves.
-- Every palette entry from 1 up is set to a grey, sixteen black-to-white ramps, one per row of the grid below.
-- A case only accepts as many palette writes as it has colours (`os.info().colours`), so on a bare Basic
-- Computer the top row goes grey and the other 240 swatches stay the stock colour cube -- the line is the cap,
-- drawn where it falls. Put a graphics card in that case and the whole grid goes grey.
-- `examples colours` runs it; copy it to change it:  cp /rom/examples/colours.lua /disk/colours.lua
local KEY = win.KEY
local info = os.info()

-- entry 0 is left alone so this page keeps a black background whatever the case does with the rest
local function ramp(i) local g = 16 + ((i - 1) % 16) * 15 return g * 65536 + g * 256 + g end

local before = {}
for i = 1, 255 do before[i] = gfx.palette(i) gfx.palette(i, ramp(i)) end
-- the cap is the first entry that refused the write; entries are settable from 0 up, so that is the count
local cap = 1
while cap < 256 and gfx.palette(cap) == ramp(cap) do cap = cap + 1 end

local w, h = gfx.size()
local top = 20
local cell = math.max(4, math.min(math.floor((w - 8) / 16), math.floor((h - top - 8) / 16)))
local x0, y0 = math.floor((w - cell * 16) / 2), top + math.floor((h - top - cell * 16) / 2)

-- 0 and 255 are black and white in both palettes, so the writing reads whichever one is in force
gfx.clear(0)
gfx.text(4, 2, (info.tierName or "computer") .. ": " .. cap .. " settable colours", 255, 0, 1)
gfx.text(4, 11, "grey above the line, the case's own below", 255, 0, 1)
for i = 0, 255 do
  gfx.fill(x0 + (i % 16) * cell, y0 + math.floor(i / 16) * cell, cell - 1, cell - 1, i)
end
if cap < 256 then
  local y = y0 + math.floor(cap / 16) * cell - 1
  gfx.line(x0, y, x0 + cell * 16 - 1, y, 255)
end
gfx.present()

local me = kernel.top() and kernel.top().program
local quit = false
if me then me.key = function(code, down) if down and (code == KEY.q or code == KEY.esc) then quit = true end end end
while not quit do gfx.present() end
for i = 1, 255 do gfx.palette(i, before[i]) end   -- leave the desktop's colours as we found them
kernel.invalidate()
return cap .. " of 256 colours are settable on a " .. (info.tierName or "computer")
