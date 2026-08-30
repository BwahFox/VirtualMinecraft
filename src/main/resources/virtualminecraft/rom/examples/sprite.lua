-- Show a sprite you drew in Paint, and move it with the arrow keys. Q leaves.
-- `examples` lists these; `examples sprite [name]` runs this one; copy it to your own disk to change it:
--   cp /rom/examples/sprite.lua /disk/mysprite.lua     then     start /disk/mysprite.lua
local name = ...                                   -- `examples sprite star` uses /disk/sprites/star.spr
local DIR = "/disk/sprites"
local KEY = win.KEY

local function firstSprite()
  if not fs.exists(DIR) then return nil end
  for _, e in ipairs(fs.list(DIR)) do
    if not e.dir and e.name:match("%.spr$") then return (e.name:gsub("%.spr$", "")) end
  end
end

name = name or firstSprite()
if not name then return "No sprites yet: draw one in Paint, press Save, then run this again." end
local spr = gfx.loadsprite(DIR .. "/" .. name .. ".spr")   -- { w = 16, h = 16, data = "..." }: what Paint wrote

local w, h = gfx.size()
local me = kernel.top() and kernel.top().program
local held, quit = {}, false
if me then
  me.key = function(code, down)
    if down and (code == KEY.q or code == KEY.esc) then quit = true end
    held[code] = down or nil
  end
end

local x, y = math.floor(w / 2 - spr.w / 2), math.floor(h / 2 - spr.h / 2)
local px, py = -1, -1
gfx.clear(1)
gfx.text(4, 4, "sprite " .. name .. "   arrows move, Q leaves", 15, 1, 1)
while not quit do
  if held[KEY.left] then x = x - 2 end
  if held[KEY.right] then x = x + 2 end
  if held[KEY.up] then y = y - 2 end
  if held[KEY.down] then y = y + 2 end
  x = math.max(0, math.min(w - spr.w, x))
  y = math.max(0, math.min(h - spr.h, y))
  if x ~= px or y ~= py then
    if px >= 0 then gfx.fill(px, py, spr.w, spr.h, 1) end   -- rub out the old one: only what changed is redrawn
    gfx.sprite(x, y, spr, 0)                                -- colour 0 is the transparent key
    px, py = x, y
  end
  gfx.present()
end
return "sprite " .. name .. " " .. spr.w .. "x" .. spr.h
