-- Starter: a program you are meant to change. It is on a floppy, so unlike a CD you can edit it and keep it.
--
-- The screen is a grid of pixels; every colour is a number from 0 to 255 (0 black, 7 white, 8 red, 11 green,
-- 12 blue). gfx.present() shows what you drew and waits for the next frame. Q leaves.
-- PROGRAMMING.md (outside the game) and `man` in the Terminal have the rest.
local w, h = gfx.size()
local KEY = win.KEY
local me = kernel.top() and kernel.top().program

local quit = false
local x, y, dx, dy = 20, 20, 1.5, 1
local colour = 11
if me then
  me.key = function(code, down)
    if down and (code == KEY.q or code == KEY.esc) then quit = true end
    if down and code == KEY.space then colour = 8 + (colour - 8 + 1) % 8 end   -- space: next colour
  end
end

while not quit do
  gfx.clear(0)
  gfx.text(4, 4, "hello from a floppy", 7, nil, 1)
  gfx.text(4, h - 10, "space changes the colour, Q quits", 6, nil, 1)
  gfx.disc(math.floor(x), math.floor(y), 6, colour)
  x, y = x + dx, y + dy
  if x < 6 or x > w - 6 then dx = -dx end          -- bounce off the edges
  if y < 16 or y > h - 18 then dy = -dy end
  gfx.present()
end
return "starter: bye"
