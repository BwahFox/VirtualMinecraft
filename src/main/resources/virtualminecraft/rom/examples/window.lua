-- How to make a window application instead of a fullscreen one. Every program starts owning the whole screen,
-- so the trick is not to *create* a window -- you already have one -- it is to stop yours being fullscreen and
-- give it a size. After that the desktop treats it like any other app: a title bar, a close box, dragging, the
-- resize grip and a taskbar entry, all for free, with the desktop still running behind it.
-- `examples window` runs it; copy it to change it:  cp /rom/examples/window.lua /disk/window.lua
-- The whole API is written up out-of-game in PROGRAMMING.md.
local me
for _, p in ipairs(kernel.programs) do if p.name == "main.lua" or p.name == "window.lua" then me = p end end
if not me then return "window.lua: no program object -- run me from the shell" end

local wd = me.window
wd.fullscreen = false                                  -- this is the line that does it
wd.title = "Window example"
wd.w = math.min(kernel.w - 20, 200)
wd.h = math.min(kernel.h - kernel.taskbarH - 20, 116)
wd.x, wd.y = math.floor((kernel.w - wd.w) / 2), 14
wd:relayout()

local clicks = 0
-- wd:add returns the widget, so keep the ones you are going to change later.
local label = wd:add(win.Label{ x = 6, y = 4, w = wd.w - 14, text = "the button has not been pressed" })
local field = wd:add(win.TextField{ x = 6, y = 40, w = wd.w - 16, h = 14, text = "type in me" })

wd:add(win.Button{ x = 6, y = 18, w = 74, h = 16, text = "Press me", onclick = function()
  clicks = clicks + 1
  label.text = "pressed " .. clicks .. (clicks == 1 and " time" or " times")
  snd.beep(660, 0.03, 0)
  wd:invalidate()                                      -- widgets do not repaint themselves; ask for a redraw
end })

wd:add(win.Button{ x = 86, y = 18, w = 74, h = 16, text = "Read it", onclick = function()
  label.text = "you typed: " .. (field.text or "")
  wd:invalidate()
end })

wd:add(win.Button{ x = 6, y = 60, w = 74, h = 16, text = "Ask me", onclick = function()
  win.ask("Example", "Is this a window?", function(yes)
    label.text = yes and "it is a window" or "it is a window anyway"
    wd:invalidate()
  end)
end })

wd:add(win.Button{ x = 86, y = 60, w = 74, h = 16, text = "Close", onclick = function()
  kernel.close(wd)
end })

wd:invalidate()
-- The window is the program's lifetime now: sleep until somebody closes it. os.sleep yields, so this costs
-- the machine nothing while it waits.
while not wd.closed do os.sleep(100) end
return "window example: " .. clicks .. " clicks"
