-- Edit: a text editor. Ctrl+S saves, F5 saves and runs, paste works, unsaved text survives a freeze.
-- Word wrap is **on by default** ([name], session 18: "we also need text wrap or text scrolling in Edit"). The
-- horizontal scroll was always there, but on a 1x1 monitor the window is 28 characters wide and a line that
-- runs off the edge with nothing to say so reads as lost text. Wrap is a button, so code can have it back.
local app = { id = "edit", name = "Edit", icon = "E" }

function app.open(args)
  local T = win.theme
  local r = args.restore or {}
  local path = args.path or r.path or "/disk/untitled.lua"
  local wd = win.Window.new{ title = "Edit", x = kernel.iconW + 8, y = 18, w = math.min(kernel.w - kernel.iconW - 12, 320), h = math.min(kernel.h - kernel.taskbarH - 24, 200) }
  local area = wd:add(win.TextArea{})
  local status = wd:add(win.Label{ text = "" })
  local bh = T.fh + 6
  local save = wd:add(win.Button{ text = "Save", h = bh })
  local run = wd:add(win.Button{ text = "Run", h = bh })
  local wrap = wd:add(win.Button{ text = "Wrap", h = bh })
  local close = wd:add(win.Button{ text = "Close", h = bh })
  area.wrap = r.wrap ~= false
  wrap.active = area.wrap
  wrap.onclick = function()
    area.wrap = not area.wrap
    wrap.active = area.wrap
    area.hscroll = 0
    area:scrollToCursor()
    wd:invalidate()
  end
  area.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, cw, ch - bh - T.fh - 4 end
  status.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 2, ch - bh - T.fh - 2, cw - 4, T.fh end
  local bw = T.fw * 7
  save.layout = function(self, cw, ch) self.x, self.y, self.w = 0, ch - bh, bw end
  run.layout = function(self, cw, ch) self.x, self.y, self.w = bw + 2, ch - bh, bw end
  wrap.layout = function(self, cw, ch) self.x, self.y, self.w = (bw + 2) * 2, ch - bh, bw end
  close.layout = function(self, cw, ch) self.x, self.y, self.w = cw - bw, ch - bh, bw end
  wd:relayout()

  local dirty = false
  local function setTitle()
    wd.title = "Edit " .. fs.basename(path) .. (dirty and " *" or "")
    status.text = path .. "  " .. #area:gettext() .. " bytes  Ctrl+S save  F5 run"
    wd:invalidate()
  end
  local text = args.text or r.text
  if text == nil then
    local ok, content = pcall(fs.read, path)
    text = ok and content or ""
    if not ok and not args.path then path = "/disk/untitled.lua" end
  end
  area:settext(text)
  dirty = r.dirty or (args.text ~= nil)
  area.onchange = function() dirty = true setTitle() end
  setTitle()

  local function doSave()
    local ok, err = pcall(fs.write, path, area:gettext())
    if ok then dirty = false kernel.addRecent(path) kernel.notify("Saved " .. path, 2) else kernel.notify(tostring(err), 5) end
    setTitle()
  end
  local function doRun()
    if dirty then doSave() end
    if not dirty then kernel.runfile(path) end
  end
  save.onclick = doSave
  run.onclick = doRun
  close.onclick = function()
    if dirty then
      win.ask("Close", "Save changes to " .. fs.basename(path) .. "?", { "Save", "Discard", "Cancel" }, function(b)
        if b == "Save" then doSave() kernel.close(wd) elseif b == "Discard" then kernel.close(wd) end
      end)
    else kernel.close(wd) end
  end
  wd.onkey = function(_, code, down, mods)
    if not down then return false end
    if mods.ctrl and code == win.KEY.s then doSave() return true end
    if code == win.KEY.f5 then doRun() return true end
    return false
  end
  wd.save = function() return { path = path, text = dirty and area:gettext() or nil, dirty = dirty, wrap = area.wrap } end
  wd:setfocus(area)
  return wd
end

function app.save(wd) return wd.save and wd.save() or nil end

return app
