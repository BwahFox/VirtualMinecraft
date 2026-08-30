-- Settings: the machine's name, what it has, the desktop's look, sound, and power.
-- Three pages since U6 (ROADMAP §9 U6). One flat panel had already outgrown a 1×1 monitor before the
-- screensaver and the volume were added to it; the tab row costs one line and gives each page room to breathe.
local app = { id = "settings", name = "Settings", icon = "S" }

local PAGES = { "Desktop", "Sound", "System" }

function app.open(args)
  local T = win.theme
  -- Tall enough for the Desktop page's rows in whichever font the player chose: 160 was sized for 6x8 and clips
  -- the last two rows -- one of which is the button that puts the small font back -- at 8x16.
  local wd = win.Window.new{ title = "Settings", x = kernel.iconW + 12, y = T.fh > 8 and 4 or 24, w = math.min(kernel.w - kernel.iconW - 16, 260), h = math.min(kernel.h - kernel.taskbarH - 8, 160 + (T.fh - 8) * 7) }
  local lh = T.fh + 8
  local page = tonumber(args.restore and args.restore.page) or 1
  local setVol -- the Sound page's buttons close over it before it is written

  -- The tab row. Widgets carry a `page` number; everything else is on every page.
  local tabs = {}
  local top = 2
  local function showPage(n)
    page = n
    for _, w in ipairs(wd.widgets) do if w.page then w.hidden = w.page ~= n end end
    for i, b in ipairs(tabs) do b.active = i == n end
    wd:setfocus(nil)
    wd:invalidate()
  end
  local _, _, cw = wd:client()
  local tw = math.floor((cw - 4 - (#PAGES - 1) * 2) / #PAGES)
  for i, name in ipairs(PAGES) do
    tabs[i] = wd:add(win.Button{ x = 2 + (i - 1) * (tw + 2), y = top, w = tw, h = T.fh + 6, text = name,
      onclick = function() showPage(i) end })
  end
  local y0 = top + lh

  -- Rows of controls that **wrap**. The old flat panel placed every button at a fixed x computed against a
  -- 260-wide window, so on a 1x1 monitor -- where the window is 176 wide -- the last button of each row ran off
  -- the edge. Everything below asks for a width in characters and is put on the next row if it will not fit.
  local flow = { x = 2, y = y0 }
  local function newrow() if flow.x > 2 then flow.x, flow.y = 2, flow.y + lh end end
  local function startpage() flow.x, flow.y = 2, y0 end
  local function put(n, o)
    local ww = math.min(T.fw * n, cw - 4)
    if flow.x > 2 and flow.x + ww > cw - 2 then newrow() end
    o.x, o.y, o.w, o.h = flow.x, flow.y, ww, o.h or (T.fh + 6)
    flow.x = flow.x + ww + 2
    return wd:add(o)
  end
  local function label(pageNo, n, text2, fg)
    newrow()
    local l = put(n, win.Label{ page = pageNo, text = text2, fg = fg, h = T.fh })
    flow.y = flow.y + T.fh + 4
    flow.x = 2
    return l
  end

  ---------------------------------------------------------------------------------------------- Desktop
  -- The patterns are drawn by the kernel from primitives, so there is nothing to install; "Painted" walks
  -- whatever is in /disk/sprites, which is exactly where Paint's Save button puts things.
  startpage()
  -- Text size (2026-08-28, [name]: the letters on a monitor are hard to read in the world). Auto is the old rule
  -- (small under 512 px, large from there); large and small force one. The whole desktop is laid out from the
  -- font, so this window re-opens itself on the same page at the new size rather than sit there half-clipped. It is the FIRST row
  -- because in the large font on a 1x1 monitor the page does not fit, and the way back must never be what is cut off.
  local SIZES = { "auto", "large", "small" }
  put(12, win.Button{ page = 1, text = "Text: " .. (kernel.textSize or "auto"), onclick = function()
    local at = 1
    for i, k in ipairs(SIZES) do if k == kernel.textSize then at = i end end
    kernel.textSize = SIZES[at % #SIZES + 1]
    kernel.savePrefs()
    kernel.close(wd)
    kernel.layout()
    kernel.open("settings", { restore = { page = 1 } })
  end })
  newrow()
  local wp = kernel.wallpaper or { kind = "grid" }
  kernel.wallpaper = wp
  local COLOURS = { 1, 4, 5, 8, 12, 22, 59, 102, 16 }
  -- Accents worth having: the bright end of the base sixteen, then three greys out of the cube for something
  -- quieter than a primary colour.
  local ACCENTS = { 5, 7, 11, 12, 10, 14, 8, 15, 255, 188, 145, 102 }
  local function wallLabel()
    local what = wp.kind == "sprite" and ("painted " .. tostring(wp.sprite)) or tostring(wp.kind or "plain")
    return "Wallpaper: " .. what
  end
  local wallBtn
  local function refresh()
    kernel.wallpaper = wp
    if wallBtn then wallBtn.text = wallLabel() end
    wd:invalidate()
    -- Straight to the machine's disk, and now rather than at shutdown. NOT kernel.save(): that writes the
    -- session blob (state/kernel.dat), which a reboot deletes on purpose -- which is why the wallpaper kept
    -- vanishing on reboot even after it was being saved.
    kernel.savePrefs()
  end
  wallBtn = put(26, win.Button{ page = 1, text = wallLabel(), onclick = function()
    local at = 1
    for i, k in ipairs(kernel.wallpapers) do if k == wp.kind then at = i end end
    wp.kind = kernel.wallpapers[at % #kernel.wallpapers + 1]
    wp.sprite = nil
    refresh()
  end })
  -- The accent is the one that actually changes how a pattern looks -- grey stars and gold stars are different
  -- wallpapers -- so it sits next to the background colour rather than with the window theme.
  newrow()
  put(10, win.Button{ page = 1, text = "Background", onclick = function()
    local at = 0
    for i, c in ipairs(COLOURS) do if c == wp.colour then at = i end end
    wp.colour = COLOURS[at % #COLOURS + 1]
    refresh()
  end })
  put(8, win.Button{ page = 1, text = "Accent", onclick = function()
    local at = 0
    for i, c in ipairs(ACCENTS) do if c == wp.accent then at = i end end
    wp.accent = ACCENTS[at % #ACCENTS + 1]
    refresh()
  end })
  local accentBtn
  accentBtn = put(13, win.Button{ page = 1, text = "Windows: " .. kernel.accents[kernel.accent].name,
    onclick = function()
      kernel.setAccent(kernel.accent % #kernel.accents + 1)
      accentBtn.text = "Windows: " .. kernel.accents[kernel.accent].name
      wd:invalidate()
      kernel.savePrefs()
    end })
  newrow()
  put(9, win.Button{ page = 1, text = "Painted", onclick = function()
    local names = {}
    if fs.exists("/disk/sprites") then
      for _, e in ipairs(fs.list("/disk/sprites")) do
        if not e.dir and e.name:match("%.spr$") then names[#names + 1] = (e.name:gsub("%.spr$", "")) end
      end
    end
    if #names == 0 then
      kernel.notify("Draw one in Paint and press Save first", 4)
      return
    end
    local at = 0
    for i, n in ipairs(names) do if n == wp.sprite then at = i end end
    wp.kind, wp.sprite = "sprite", names[at % #names + 1]
    refresh()
  end })
  put(11, win.Button{ page = 1, text = "Palette",
    onclick = function() gfx.palette() kernel.notify("Palette reset", 2) end })
  -- The screensaver (U6). "After" includes never, because a computer wired into a redstone contraption and
  -- watched all day is one whose owner may want the screen to just stay put.
  local TIMEOUTS = { 0, 60, 120, 300, 600, 1800 }
  local function afterLabel()
    local t = kernel.saver.timeout or 0
    if t <= 0 then return "After: never" end
    if t < 120 then return "After: " .. t .. " s" end
    return "After: " .. math.floor(t / 60) .. " min"
  end
  newrow()
  local saverBtn, afterBtn
  saverBtn = put(15, win.Button{ page = 1, text = "Saver: " .. kernel.saver.kind, onclick = function()
    local at = 1
    for i, k in ipairs(kernel.savers) do if k == kernel.saver.kind then at = i end end
    kernel.saver.kind = kernel.savers[at % #kernel.savers + 1]
    saverBtn.text = "Saver: " .. kernel.saver.kind
    wd:invalidate()
    kernel.savePrefs()
  end })
  afterBtn = put(12, win.Button{ page = 1, text = afterLabel(), onclick = function()
    local at = 0
    for i, t in ipairs(TIMEOUTS) do if t == kernel.saver.timeout then at = i end end
    kernel.saver.timeout = TIMEOUTS[at % #TIMEOUTS + 1]
    afterBtn.text = afterLabel()
    wd:invalidate()
    kernel.savePrefs()
  end })
  newrow()
  put(9, win.Button{ page = 1, text = "Preview", onclick = function() kernel.startSaver() end })

  ---------------------------------------------------------------------------------------------- Sound
  startpage()
  local volLabel = label(2, 16, "")
  local volBar
  put(4, win.Button{ page = 2, text = "-", onclick = function() setVol(kernel.volume - 0.1) end })
  put(4, win.Button{ page = 2, text = "+", onclick = function() setVol(kernel.volume + 0.1) end })
  put(6, win.Button{ page = 2, text = "Test", onclick = function() snd.beep(880, 0.15) end })
  newrow()
  volBar = label(2, 24, "")
  put(8, win.Button{ page = 2, text = "Mute", onclick = function() setVol(0) end })
  put(8, win.Button{ page = 2, text = "Full", onclick = function() setVol(1) end })
  put(10, win.Button{ page = 2, text = "Silence",
    onclick = function() snd.stop() kernel.notify("All channels stopped", 2) end })
  newrow()
  local case0 = os.info()
  label(2, 34, string.format("%d synth voices, %d sample channels", case0.synth or 4, case0.samples or 2))
  label(2, 34, "The chassis speaks through the block.", T.disabled)
  -- defined here so the buttons above can call it, and called once at the end to fill in the label and the bar
  setVol = function(v)
    kernel.setVolume(v)
    volLabel.text = "Volume  " .. math.floor(kernel.volume * 100 + 0.5) .. "%"
    -- a bar out of characters: the toolkit has no slider, and a slider is a drag target this does not need
    local n = math.floor(kernel.volume * 20 + 0.5)
    volBar.text = "[" .. string.rep("#", n) .. string.rep("-", 20 - n) .. "]"
    wd:invalidate()
    kernel.savePrefs()
  end

  ---------------------------------------------------------------------------------------------- System
  startpage()
  local nameLabel = put(5, win.Label{ page = 3, text = "Name" })
  nameLabel.y = nameLabel.y + 3
  local name = put(20, win.TextField{ page = 3 })
  name.text = os.label()
  local apply = put(7, win.Button{ page = 3, text = "Apply", onclick = function()
    os.label(name.text)
    kernel.notify("Named " .. name.text, 2)
  end })
  name.onenter = function() apply.onclick() end
  newrow()
  local _, cap = vmc.mem()
  local sw, sh = gfx.size()
  local case = os.info()
  label(3, 40, string.format("%s  CPU %d%%  %d colours  disk %d KB", case.tierName or "Computer", case.cpu or 25, case.colours or 256, case.disk or 0))
  local info = label(3, 40, "")
  local modes = { [0] = "Boot: auto", [1] = "Boot: desktop", [2] = "Boot: shell" }
  local mode = case.desktopMode or 0
  put(14, win.Button{ page = 3, text = modes[mode], onclick = function(b)
    mode = (mode + 1) % 3
    os.desktop(({ [0] = "auto", [1] = "desktop", [2] = "shell" })[mode])
    b.text = modes[mode]
    b:invalidate()
    kernel.notify("Takes effect at the next boot", 3)
  end })
  put(8, win.Button{ page = 3, text = "About", onclick = function() kernel.about() end })
  newrow()
  put(9, win.Button{ page = 3, text = "Reboot", onclick = function()
    win.ask("Reboot", "Reboot now?", { "Reboot", "Cancel" }, function(b) if b == "Reboot" then kernel.save() os.reboot() end end)
  end })
  put(11, win.Button{ page = 3, text = "Shut down", onclick = function()
    win.ask("Power", "Shut down?", { "Shut down", "Cancel" }, function(b) if b == "Shut down" then kernel.save() os.shutdown() end end)
  end })

  wd.ondraw = function()
    local u = vmc.mem()
    local mounts = ""
    for _, m in ipairs(fs.mounts()) do mounts = mounts .. "/" .. m.name .. " " end
    info.text = string.format("Mem %d/%d KB  %dx%d  %s", math.floor(u / 1024), math.floor(cap / 1024), sw, sh, mounts)
  end
  wd.save = function() return { page = page } end
  setVol(kernel.volume)
  showPage(page)
  return wd
end

function app.save(wd) return wd.save and wd.save() or nil end

return app
