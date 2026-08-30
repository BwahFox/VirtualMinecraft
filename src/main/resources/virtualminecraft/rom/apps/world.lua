-- World: where the machine is, the time as a day strip, weather, biome, light, who is near, and a line of chat.
local app = { id = "world", name = "World", icon = "W" }

local function clock(t)
  local hours = math.floor((t / 1000 + 6) % 24)
  local minutes = math.floor((t % 1000) / 1000 * 60)
  return string.format("%02d:%02d", hours, minutes)
end

function app.open(args)
  local T = win.theme
  local wd = win.Window.new{ title = "World", x = kernel.iconW + 6, y = 14, w = math.min(kernel.w - kernel.iconW - 10, 300), h = math.min(kernel.h - kernel.taskbarH - 20, 200) }
  local lh = T.fh + 2
  local lines = {}
  for i = 1, 5 do lines[i] = wd:add(win.Label{ x = 2, y = 2 + (i - 1) * lh, w = 800, text = "" }) end
  local stripY = 2 + 5 * lh
  local players = wd:add(win.List{ items = {} })
  local chat = wd:add(win.TextField{ text = "" })
  local say = wd:add(win.Button{ text = "Say", h = T.fh + 6 })
  local sayW = T.fw * 5
  players.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, stripY + 12, cw, ch - stripY - 12 - (T.fh + 8) end
  chat.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, ch - T.fh - 6, cw - sayW - 2, T.fh + 6 end
  say.layout = function(self, cw, ch) self.x, self.y, self.w = cw - sayW, ch - T.fh - 6, sayW end
  wd:relayout()

  local info = { time = 0 }
  local function refresh()
    local ok, err = pcall(function()
      local t = bus.call("world", "getTime") or {}
      local w = bus.call("world", "getWeather") or {}
      local p = bus.call("world", "getPosition") or {}
      local biome = bus.call("world", "getBiome")
      local light = bus.call("world", "getLight", 0, 1, 0) -- the block above: inside our own block it is always 0
      info.time = tonumber(t.time) or 0
      lines[1].text = string.format("%s, %s, %s  %s", tostring(p.x), tostring(p.y), tostring(p.z), tostring(p.dimension or ""):gsub("^minecraft:", ""))
      lines[2].text = "Day " .. tostring(t.day) .. "  " .. clock(info.time) .. (t.daylight and "  daylight" or "  night")
      lines[3].text = "Weather: " .. tostring(w.weather) .. (w.rainingHere and "  (raining here)" or "")
      lines[4].text = "Biome: " .. tostring(biome):gsub("^minecraft:", "")
      lines[5].text = "Light: " .. tostring(light)
      local ps = bus.call("world", "getPlayers") or {}
      local labels = {}
      for i, pl in ipairs(ps) do labels[i] = string.format("%s  %.0f m", tostring(pl.name), tonumber(pl.distance) or 0) end
      if #labels == 0 then labels[1] = "(nobody near)" end
      players.items = labels
    end)
    if not ok then lines[1].text = tostring(err) end
    wd:invalidate()
  end
  wd.ondraw = function(self, cx, cy, cw, ch)
    -- the day strip: day in the sky colour, night dark, a marker at the current time (06:00 at the left edge)
    local x, y, w = cx + 2, cy + stripY + 1, cw - 4
    gfx.fill(x, y, w, 8, 0)
    gfx.fill(x, y, math.floor(w * 12000 / 24000), 8, T.title)
    local mx = x + math.floor(w * (info.time % 24000) / 24000)
    gfx.fill(mx - 1, y - 1, 3, 10, T.accent)
  end
  say.onclick = function()
    if chat.text == nil or chat.text == "" then return end
    local ok, err = pcall(bus.call, "chat", "say", chat.text)
    if not ok then kernel.notify(tostring(err), 4) end
    chat.text = ""
    chat.cursor = 0
    wd:invalidate()
  end
  chat.onenter = function() say.onclick() end
  wd.onbus = function(_, ev)
    if ev.name == "chat" then kernel.notify("<" .. tostring(ev.player) .. "> " .. tostring(ev.message), 4) end
  end
  wd.refresh = refresh
  refresh()
  wd:setfocus(chat)
  kernel.spawn("world", function() while not wd.closed do os.sleep(3000) if not wd.closed then refresh() end end end, wd)
  return wd
end

return app
