-- Sentry: the program no other computer can run, because no other computer is standing in a world. It asks the
-- world sensor who is near, draws them on a sweep, keeps a log of arrivals and departures against the in-game
-- clock, and — armed — drives a redstone output while anyone is inside the ring. A door that opens for people,
-- or an alarm that does not.
local w, h = gfx.size()
local KEY = win.KEY
local KEY_A, KEY_PLUS, KEY_MINUS = 0x1e, 0x0d, 0x0c   -- A arms, + and - widen the ring
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local CX, CY = math.floor(w / 2), math.floor(h * 0.42)
local R = math.min(CX, CY) - 6
local range, armed, side = 16, false, "right"
local seen, log, sweep, alarmed = {}, {}, 0, false
local watched = true   -- the viewers event: a sweep nobody can see is a machine burning its share for nothing
local input = {}

local function clock()
  local t = bus.call("world", "getTime")
  if type(t) ~= "table" then return "" end
  local mins = math.floor((tonumber(t.time) or 0) / 1000 * 60 + 6 * 60) % 1440
  return string.format("day %d %02d:%02d", tonumber(t.day) or 0, math.floor(mins / 60), mins % 60)
end

local function note(line)
  table.insert(log, 1, clock() .. "  " .. line)
  while #log > 6 do table.remove(log) end
end

local function output(level)
  pcall(bus.call, "redstone", "setOutput", side, level)
end

-- One look around: who is inside the ring, who has just arrived, who has gone.
local function scan()
  local ok, players = pcall(bus.call, "world", "getPlayers", range)
  if not ok or type(players) ~= "table" then return nil, "no world sensor on the bus" end
  local now, near = {}, false
  for _, p in ipairs(players) do
    local d = tonumber(p.distance) or 0
    now[p.name] = p
    if d <= range then near = true end
    if not seen[p.name] then note(p.name .. " arrived, " .. string.format("%.0f", d) .. " m") snd.beep(880, 0.05, 0) end
  end
  for name in pairs(seen) do
    if not now[name] then note(name .. " left") snd.beep(330, 0.05, 0) end
  end
  seen = now
  if armed and near ~= alarmed then
    alarmed = near
    output(near and 15 or 0)
    note(near and ("output " .. side .. " on") or ("output " .. side .. " off"))
    if near then for k = 1, 2 do snd.channel(k, snd.SQUARE, 440 + k * 220, 0.5, 0.01, 0.2, 0, 0.1) end end
  end
  return players
end

local function draw(players, err)
  gfx.clear(0)
  gfx.text(2, 2, "sentry  " .. range .. " m", 6, nil, 1)
  local state = armed and (alarmed and "ARMED - " .. side .. " on" or "armed - " .. side) or "not armed  (A)"
  gfx.text(w - #state * 6 - 2, 2, state, armed and (alarmed and 8 or 11) or 5, nil, 1)
  for _, frac in ipairs({ 0.33, 0.66, 1 }) do
    gfx.circle(CX, CY, math.floor(R * frac), 3)
  end
  gfx.line(CX - R, CY, CX + R, CY, 3)
  gfx.line(CX, CY - R, CX, CY + R, 3)
  -- the sweep hand, so an idle radar still looks alive
  local a = sweep * math.pi / 12
  gfx.line(CX, CY, CX + math.floor(math.cos(a) * R), CY + math.floor(math.sin(a) * R), 11)
  gfx.disc(CX, CY, 2, 7)
  if err then
    gfx.text(CX - #err * 3, CY + R + 6, err, 8, nil, 1)
  else
    for _, p in ipairs(players or {}) do
      local d = tonumber(p.distance) or 0
      -- the sensor answers in blocks relative to us; north is up, exactly as the world is
      local px = CX + math.floor((tonumber(p.x) or 0) / range * R)
      local py = CY + math.floor((tonumber(p.z) or 0) / range * R)
      local inside = d <= range
      gfx.disc(px, py, 3, inside and 8 or 5)
      gfx.text(px + 5, py - 4, tostring(p.name) .. " " .. string.format("%.0f", d), inside and 7 or 5, nil, 1)
    end
  end
  local ly = CY + R + 14
  gfx.text(2, ly, "log", 5, nil, 1)
  for i, line in ipairs(log) do
    if ly + i * 9 < h - 9 then gfx.text(2, ly + i * 9, line, i == 1 and 6 or 5, nil, 1) end
  end
  gfx.text(2, h - 9, "A arms   + -  range   Q quits", 5, nil, 1)
end

if me then
  -- Session 14 taught the desktop to stop repainting its clock when nobody is watching; a radar owes the same
  -- courtesy. Unwatched, this drops to one look around a second and only redraws when something moved, which is
  -- what lets the host park the framebuffer behind us.
  me.onbus = function(ev)
    if ev.name == "viewers" then
      local now = (tonumber(ev.n) or 0) > 0
      if now ~= watched then watched = now input.redraw = true end
    end
  end
  me.key = function(code, down)
    if not down then return end
    if code == KEY_A then
      armed = not armed
      if not armed and alarmed then alarmed = false output(0) end
      note(armed and "armed" or "disarmed")
    elseif code == KEY_PLUS then range = math.min(64, range + 8)
    elseif code == KEY_MINUS then range = math.max(8, range - 8)
    elseif code == KEY.q or code == KEY.esc then input.quit = true end
    input.redraw = true
  end
end

local players, err = scan()
draw(players, err)
gfx.present()
local lastScan = os.clock()
while not input.quit do
  local now = os.clock()
  if now - lastScan > 1 then
    lastScan = now
    local before = #log
    players, err = scan()
    if #log ~= before or watched then input.redraw = true end
  end
  if watched then
    sweep = (sweep + 1) % 24
    draw(players, err)
  elseif input.redraw then
    input.redraw = nil
    draw(players, err)
  end
  gfx.present()
end
if alarmed then output(0) end
return "Sentry: " .. #log .. " entries in the log"
