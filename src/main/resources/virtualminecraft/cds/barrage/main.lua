-- Barrage: warheads fall on six towns and you have three batteries to stop them. Click where you want the sky
-- to explode; the shell takes time to fly there, so you are aiming at where a warhead *will be*, not where it
-- is. That one idea is the whole game and it is why this shape of game has outlived its cabinet.
--
-- Ours, from the rules up: the mechanic is a 1980 arcade idea and mechanics are not copyrightable, but the
-- name, the artwork and every line of this are this machine's own. It is here because the desktop is
-- pointer-first and this is the genre that wants a pointer more than any other — no other control scheme plays
-- it as well, which makes it a better fit for this computer than the games it sits next to.
local w, h = gfx.size()
local KEY = win.KEY
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local HEAD = 12
local GROUND = h - 14                              -- the skyline sits on this
local SKY, DIRT, TOWN, GONE = 0, 102, 12, 40
local ENEMY, MINE, BLAST, HOT = 8, 11, 10, 15

local cities, batteries = {}, {}
local incoming, shots, blasts = {}, {}, {}
local score, wave, state, waveTimer = 0, 1, "playing", 0
local toSpawn, spawnGap, spawned = 0, 0, 0
local input, aim = {}, { x = math.floor(w / 2), y = math.floor(h / 2) }
local quit = false
local queue = {}
local function enqueue(v) queue[#queue + 1] = v end

local function dist(ax, ay, bx, by)
  local dx, dy = ax - bx, ay - by
  return math.sqrt(dx * dx + dy * dy)
end

---------------------------------------------------------------------------------------------------- the board
local function setup()
  cities, batteries = {}, {}
  -- Six towns and three batteries, interleaved the way the cabinet had them: battery, two towns, battery,
  -- two towns, battery. The outer batteries are the ones you run dry first, which is the point.
  local slots = 9
  local step = w / (slots + 1)
  local order = { "b", "c", "c", "b", "c", "c", "b", "c", "c" }
  for i = 1, slots do
    local x = math.floor(step * i)
    if order[i] == "b" then
      batteries[#batteries + 1] = { x = x, ammo = 10, alive = true }
    else
      cities[#cities + 1] = { x = x, alive = true }
    end
  end
end

local function livingCities()
  local n = 0
  for _, c in ipairs(cities) do if c.alive then n = n + 1 end end
  return n
end

local function startWave()
  toSpawn = 6 + wave * 2
  spawned = 0
  spawnGap = math.max(0.25, 1.2 - wave * 0.06)
  waveTimer = 0
  for _, b in ipairs(batteries) do
    if b.alive then b.ammo = 10 end
  end
end

local function newGame()
  setup()
  incoming, shots, blasts = {}, {}, {}
  score, wave, state = 0, 1, "playing"
  startWave()
  input.redraw = true
end

---------------------------------------------------------------------------------------------------- spawning
local function spawnWarhead()
  -- Aim at something still standing; if every town is gone the wave still finishes, it just has nothing to hit.
  local targets = {}
  for _, c in ipairs(cities) do if c.alive then targets[#targets + 1] = c.x end end
  for _, b in ipairs(batteries) do if b.alive then targets[#targets + 1] = b.x end end
  if #targets == 0 then targets = { math.random(w) } end
  local tx = targets[math.random(#targets)]
  local x0 = math.random(w)
  -- Pixels per second, scaled to the screen rather than to a number that happened to look right at 256 high:
  -- a warhead should take about eight seconds to fall on wave 1 and about three by wave 10, whatever size
  -- monitor wall this is running on.
  local fall = GROUND
  local speed = (fall / 8) * (1 + (wave - 1) * 0.16)
  local dx, dy = tx - x0, GROUND - 0
  local len = math.sqrt(dx * dx + dy * dy)
  incoming[#incoming + 1] = {
    x0 = x0, y0 = 0, x = x0, y = 0,
    vx = dx / len * speed, vy = dy / len * speed,
    alive = true,
  }
end

local function fire(tx, ty)
  if state ~= "playing" then return end
  if ty >= GROUND - 2 then return end            -- no firing into your own dirt
  -- The nearest battery with ammo, because that is what a player means by clicking.
  local best, bestd
  for _, b in ipairs(batteries) do
    if b.alive and b.ammo > 0 then
      local d = math.abs(b.x - tx)
      if not bestd or d < bestd then best, bestd = b, d end
    end
  end
  if not best then snd.beep(140, 0.05, 0) return end
  best.ammo = best.ammo - 1
  local dx, dy = tx - best.x, ty - GROUND
  local len = math.max(1, math.sqrt(dx * dx + dy * dy))
  local speed = math.max(120, GROUND * 0.75)   -- a shell must comfortably outrun a warhead
  shots[#shots + 1] = {
    x0 = best.x, y0 = GROUND, x = best.x, y = GROUND,
    vx = dx / len * speed, vy = dy / len * speed,
    tx = tx, ty = ty,
  }
  snd.beep(300 + math.random(80), 0.02, 0)
end

local function boom(x, y, big)
  blasts[#blasts + 1] = { x = x, y = y, r = 1, max = big and 22 or 15, grow = true }
  snd.channel(1, snd.NOISE, big and 70 or 110, 0.5, 0, 0.22, 0, 0.08)
end

---------------------------------------------------------------------------------------------------- the step
local function update(dt)
  if state ~= "playing" then return end
  waveTimer = waveTimer + dt

  if spawned < toSpawn and waveTimer >= spawnGap then
    waveTimer = 0
    spawned = spawned + 1
    spawnWarhead()
  end

  for _, s in ipairs(shots) do
    s.x = s.x + s.vx * dt
    s.y = s.y + s.vy * dt
    -- A shell explodes where you clicked, not where it ran out of sky.
    if (s.vy < 0 and s.y <= s.ty) or (s.vy >= 0 and s.y >= s.ty) then
      s.done = true
      boom(s.tx, s.ty, false)
    end
  end
  for i = #shots, 1, -1 do if shots[i].done then table.remove(shots, i) end end

  for _, m in ipairs(incoming) do
    if m.alive then
      m.x = m.x + m.vx * dt
      m.y = m.y + m.vy * dt
      if m.y >= GROUND then
        m.alive = false
        boom(m.x, GROUND, true)
        -- Whatever was standing there is not any more.
        for _, c in ipairs(cities) do
          if c.alive and math.abs(c.x - m.x) < 10 then c.alive = false end
        end
        for _, b in ipairs(batteries) do
          if b.alive and math.abs(b.x - m.x) < 10 then b.alive = false end
        end
      end
    end
  end

  for _, bl in ipairs(blasts) do
    if bl.grow then
      bl.r = bl.r + 34 * dt
      if bl.r >= bl.max then bl.grow = false end
    else
      bl.r = bl.r - 24 * dt
    end
    -- A fireball kills anything that flies into it, and that is how chains happen: one shell, six warheads.
    for _, m in ipairs(incoming) do
      if m.alive and dist(m.x, m.y, bl.x, bl.y) <= bl.r then
        m.alive = false
        score = score + 25
        boom(m.x, m.y, false)
      end
    end
  end
  for i = #blasts, 1, -1 do if blasts[i].r <= 0 then table.remove(blasts, i) end end
  for i = #incoming, 1, -1 do if not incoming[i].alive then table.remove(incoming, i) end end

  if spawned >= toSpawn and #incoming == 0 and #shots == 0 and #blasts == 0 then
    if livingCities() == 0 then
      state = "over"
      snd.channel(1, snd.NOISE, 50, 0.6, 0, 0.6, 0, 0.2)
    else
      -- Towns still standing are worth something, and so is every shell you did not need.
      score = score + livingCities() * 50
      for _, b in ipairs(batteries) do if b.alive then score = score + b.ammo * 5 end end
      wave = wave + 1
      startWave()
      for k = 1, 3 do snd.channel(k, snd.SQUARE, 330 * k, 0.35, 0.01, 0.3, 0, 0.15) end
    end
  end
  input.redraw = true
end

---------------------------------------------------------------------------------------------------- drawing
local function draw()
  gfx.clear(SKY)
  gfx.fill(0, 0, w, HEAD, 0)
  gfx.text(2, 2, "barrage  " .. score, 7, nil, 1)
  local right = state == "over" and "all towns lost - N again" or ("wave " .. wave .. "   towns " .. livingCities())
  gfx.text(w - #right * 6 - 2, 2, right, state == "over" and ENEMY or 6, nil, 1)

  gfx.fill(0, GROUND, w, h - GROUND, DIRT)
  for _, c in ipairs(cities) do
    if c.alive then
      gfx.fill(c.x - 5, GROUND - 6, 10, 6, TOWN)
      gfx.fill(c.x - 3, GROUND - 9, 2, 3, TOWN)
      gfx.fill(c.x + 1, GROUND - 8, 2, 2, TOWN)
    else
      gfx.fill(c.x - 5, GROUND - 2, 10, 2, GONE)
    end
  end
  for _, b in ipairs(batteries) do
    if b.alive then
      gfx.fill(b.x - 4, GROUND - 4, 8, 4, MINE)
      gfx.line(b.x, GROUND - 5, b.x, GROUND - 8, MINE)
      gfx.text(b.x - 5, GROUND - 16, string.format("%2d", b.ammo), b.ammo > 3 and MINE or ENEMY, nil, 1)
    else
      gfx.fill(b.x - 4, GROUND - 1, 8, 1, GONE)
    end
  end

  -- Trails: a warhead is a line from where it entered the sky to where it is now. That trail is the game's
  -- only real information — it is how you work out where to aim ahead of it.
  for _, m in ipairs(incoming) do
    gfx.line(math.floor(m.x0), math.floor(m.y0), math.floor(m.x), math.floor(m.y), ENEMY)
    gfx.pixel(math.floor(m.x), math.floor(m.y), HOT)
  end
  for _, s in ipairs(shots) do
    gfx.line(math.floor(s.x0), math.floor(s.y0), math.floor(s.x), math.floor(s.y), MINE)
  end
  for _, bl in ipairs(blasts) do
    local r = math.floor(bl.r)
    if r > 0 then
      gfx.disc(math.floor(bl.x), math.floor(bl.y), r, BLAST)
      gfx.circle(math.floor(bl.x), math.floor(bl.y), r, HOT)
    end
  end

  -- The crosshair, so the keyboard is playable too and so a pointer has something to sit behind.
  gfx.line(aim.x - 4, aim.y, aim.x + 4, aim.y, HOT)
  gfx.line(aim.x, aim.y - 4, aim.x, aim.y + 4, HOT)
end

if me then
  me.window.cursor = true
  kernel.showCursor(true)
  me.pointer = function(px, py, b, pressed)
    aim.x, aim.y = px, py
    if pressed then enqueue({ px, py }) end
    input.redraw = true
  end
  me.key = function(code, down)
    if not down then return end
    if code == KEY.q or code == KEY.esc then input.quit = true
    elseif code == KEY.n then input.new = true
    elseif code == KEY.left then aim.x = math.max(0, aim.x - 6)
    elseif code == KEY.right then aim.x = math.min(w - 1, aim.x + 6)
    elseif code == KEY.up then aim.y = math.max(0, aim.y - 6)
    elseif code == KEY.down then aim.y = math.min(h - 1, aim.y + 6)
    elseif code == KEY.space then enqueue({ aim.x, aim.y }) end
    input.redraw = true
  end
end

newGame()
draw()
gfx.present()
local last = os.clock()
while not quit do
  if input.quit then input.quit = nil quit = true end
  if input.new then input.new = nil newGame() end
  while #queue > 0 do
    local c = table.remove(queue, 1)
    if state == "over" then newGame() else fire(c[1], c[2]) end
  end
  local now = os.clock()
  local dt = now - last
  last = now
  if dt > 0.25 then dt = 0.25 end               -- a long stall must not teleport every warhead into the dirt
  update(dt)
  if input.redraw then input.redraw = nil draw() end
  gfx.present()
end
return "Barrage: wave " .. wave .. ", " .. score .. " points"
