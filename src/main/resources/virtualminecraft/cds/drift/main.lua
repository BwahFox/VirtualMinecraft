-- Drift: rocks, momentum and no brakes. Turn, thrust, shoot; a big rock breaks into two middling ones, those
-- break again, and everything — you included — wraps around the edges of the screen.
--
-- The deliberate opposite of Barrage, which is on the shelf next to it: that one is pointer-native and this one
-- is keyboard-native, so between them the machine has one good game for each way of sitting at it. It is also
-- the cheapest good-looking game on a machine like this, because it is drawn entirely in lines — no sprites, no
-- art budget, and it looks exactly as intended at 256x256 and at 1024x768.
--
-- Ours, from the rules up: a 1979 idea, an original name, original artwork, every line this machine's own.
local w, h = gfx.size()
local KEY = win.KEY
local KEY_LSHIFT = 0x2a
-- The program object for THIS program. kernel.top() is our own window at launch, which is the only reliable
-- way to find it: scanning kernel.programs for "main.lua" picks the wrong one when a second CD is also running
-- a main.lua, and both CD slots can be full.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local HEAD = 12
local SHIP, ROCK, SHOT, FLAME, DEAD = 15, 7, 11, 10, 8
local TAU = math.pi * 2

local ship, rocks, shots, bits = nil, {}, {}, {}
local score, lives, wave, state, respawn = 0, 3, 1, "playing", 0
local keys = {}
local input = {}
local quit = false

local function wrap(o)
  if o.x < 0 then o.x = o.x + w elseif o.x >= w then o.x = o.x - w end
  if o.y < HEAD then o.y = o.y + (h - HEAD) elseif o.y >= h then o.y = o.y - (h - HEAD) end
end

-- Distance with wrapping taken into account: a rock one pixel off the left edge is next to a ship one pixel off
-- the right edge, and a game that forgets this kills you from across the screen.
local function near(ax, ay, bx, by)
  local dx = math.abs(ax - bx)
  local dy = math.abs(ay - by)
  if dx > w / 2 then dx = w - dx end
  local span = h - HEAD
  if dy > span / 2 then dy = span - dy end
  return math.sqrt(dx * dx + dy * dy)
end

---------------------------------------------------------------------------------------------------- the shapes
-- A rock is a ring of points at jittered radii, generated once and then only rotated. Regenerating the jitter
-- every frame is the classic mistake: the rock boils instead of tumbling.
local function makeRock(x, y, size)
  local pts = {}
  local n = 8 + math.random(3)
  for i = 1, n do
    pts[i] = 0.65 + math.random() * 0.5
  end
  local speed = (16 + math.random(20)) * (4 - size) * 0.5
  local dir = math.random() * TAU
  return {
    x = x, y = y, size = size,
    r = ({ 6, 11, 18 })[size],
    pts = pts,
    a = math.random() * TAU,
    spin = (math.random() - 0.5) * 1.6,
    vx = math.cos(dir) * speed, vy = math.sin(dir) * speed,
  }
end

local function polygon(cx, cy, radius, pts, angle, col)
  local n = #pts
  local px, py
  for i = 1, n + 1 do
    local k = ((i - 1) % n) + 1
    local t = angle + (k - 1) / n * TAU
    local x = cx + math.cos(t) * radius * pts[k]
    local y = cy + math.sin(t) * radius * pts[k]
    if px then gfx.line(math.floor(px), math.floor(py), math.floor(x), math.floor(y), col) end
    px, py = x, y
  end
end

local function newShip()
  return { x = w / 2, y = (h + HEAD) / 2, vx = 0, vy = 0, a = -math.pi / 2, thrusting = false, cool = 0 }
end

local function startWave()
  rocks, shots = {}, {}
  local n = 3 + wave
  -- Keep clear of where the ship IS, or -- if it is dead right now -- of where it is about to come back.
  -- Ramming the last rock of a wave kills you and clears the board in the same frame, so this runs with no
  -- ship in existence and must not reach for one. (That crashed the game: "attempt to index a nil value".)
  local sx = ship and ship.x or w / 2
  local sy = ship and ship.y or (h + HEAD) / 2
  for _ = 1, n do
    local x, y
    local tries = 0
    repeat
      x, y = math.random(w), HEAD + math.random(h - HEAD)
      tries = tries + 1
    until near(x, y, sx, sy) > 60 or tries > 200   -- a tiny screen may have nowhere far enough; do not spin
    rocks[#rocks + 1] = makeRock(x, y, 3)
  end
end

local function newGame()
  ship = newShip()
  score, lives, wave, state, respawn = 0, 3, 1, "playing", 0
  bits = {}
  startWave()
  input.redraw = true
end

---------------------------------------------------------------------------------------------------- debris
local function shatter(x, y, n, col)
  for _ = 1, n do
    local d = math.random() * TAU
    local s = 20 + math.random(50)
    bits[#bits + 1] = { x = x, y = y, vx = math.cos(d) * s, vy = math.sin(d) * s, life = 0.5 + math.random() * 0.4, col = col }
  end
end

local function splitRock(i)
  local r = rocks[i]
  score = score + ({ 100, 50, 20 })[r.size]
  shatter(r.x, r.y, 6, ROCK)
  snd.channel(1, snd.NOISE, 80 + r.size * 40, 0.45, 0, 0.18, 0, 0.06)
  table.remove(rocks, i)
  if r.size > 1 then
    for _ = 1, 2 do
      local nr = makeRock(r.x, r.y, r.size - 1)
      rocks[#rocks + 1] = nr
    end
  end
end

local function killShip()
  lives = lives - 1
  shatter(ship.x, ship.y, 12, DEAD)
  snd.channel(1, snd.NOISE, 45, 0.6, 0, 0.5, 0, 0.2)
  if lives <= 0 then
    state = "over"
  else
    respawn = 1.5
    ship = nil
  end
end

---------------------------------------------------------------------------------------------------- the step
local function update(dt)
  if state == "over" then
    for _, b in ipairs(bits) do b.x = b.x + b.vx * dt b.y = b.y + b.vy * dt b.life = b.life - dt end
    for i = #bits, 1, -1 do if bits[i].life <= 0 then table.remove(bits, i) end end
    input.redraw = true
    return
  end

  if not ship then
    respawn = respawn - dt
    if respawn <= 0 then
      -- Only come back when the middle is clear, or the new ship dies the instant it appears.
      local clear = true
      for _, r in ipairs(rocks) do
        if near(w / 2, (h + HEAD) / 2, r.x, r.y) < r.r + 40 then clear = false break end
      end
      if clear then ship = newShip() end
    end
  else
    if keys.left then ship.a = ship.a - 3.4 * dt end
    if keys.right then ship.a = ship.a + 3.4 * dt end
    ship.thrusting = keys.up == true
    if ship.thrusting then
      ship.vx = ship.vx + math.cos(ship.a) * 150 * dt
      ship.vy = ship.vy + math.sin(ship.a) * 150 * dt
    end
    -- A whisper of drag. Real space has none, but a ship you can never slow down is a ship nobody enjoys.
    local drag = math.max(0, 1 - 0.35 * dt)
    ship.vx, ship.vy = ship.vx * drag, ship.vy * drag
    ship.x = ship.x + ship.vx * dt
    ship.y = ship.y + ship.vy * dt
    wrap(ship)

    ship.cool = math.max(0, ship.cool - dt)
    if keys.fire and ship.cool <= 0 and #shots < 5 then
      ship.cool = 0.18
      shots[#shots + 1] = {
        x = ship.x + math.cos(ship.a) * 8, y = ship.y + math.sin(ship.a) * 8,
        vx = ship.vx + math.cos(ship.a) * 190, vy = ship.vy + math.sin(ship.a) * 190,
        life = 1.1,
      }
      snd.beep(900, 0.02, 0)
    end
  end

  for _, s in ipairs(shots) do
    s.x = s.x + s.vx * dt
    s.y = s.y + s.vy * dt
    s.life = s.life - dt
    wrap(s)
  end
  for i = #shots, 1, -1 do if shots[i].life <= 0 then table.remove(shots, i) end end

  for _, r in ipairs(rocks) do
    r.x = r.x + r.vx * dt
    r.y = r.y + r.vy * dt
    r.a = r.a + r.spin * dt
    wrap(r)
  end

  for _, b in ipairs(bits) do
    b.x = b.x + b.vx * dt
    b.y = b.y + b.vy * dt
    b.life = b.life - dt
  end
  for i = #bits, 1, -1 do if bits[i].life <= 0 then table.remove(bits, i) end end

  -- Shots against rocks, walking backwards so removing one does not skip the next.
  for i = #rocks, 1, -1 do
    local r = rocks[i]
    for j = #shots, 1, -1 do
      if near(shots[j].x, shots[j].y, r.x, r.y) < r.r then
        table.remove(shots, j)
        splitRock(i)
        break
      end
    end
  end

  if ship then
    for i = #rocks, 1, -1 do
      if near(ship.x, ship.y, rocks[i].x, rocks[i].y) < rocks[i].r + 4 then
        splitRock(i)
        killShip()
        break
      end
    end
  end

  if #rocks == 0 and state == "playing" then
    wave = wave + 1
    score = score + 200
    startWave()
    for k = 1, 3 do snd.channel(k, snd.SQUARE, 300 * k, 0.35, 0.01, 0.3, 0, 0.15) end
  end
  input.redraw = true
end

---------------------------------------------------------------------------------------------------- drawing
local function drawShip()
  if not ship then return end
  local a = ship.a
  local function pt(dist, off)
    local t = a + off
    return ship.x + math.cos(t) * dist, ship.y + math.sin(t) * dist
  end
  local nx, ny = pt(9, 0)
  local lx, ly = pt(7, 2.5)
  local rx, ry = pt(7, -2.5)
  local bx, by = pt(3, math.pi)
  gfx.line(math.floor(nx), math.floor(ny), math.floor(lx), math.floor(ly), SHIP)
  gfx.line(math.floor(nx), math.floor(ny), math.floor(rx), math.floor(ry), SHIP)
  gfx.line(math.floor(lx), math.floor(ly), math.floor(bx), math.floor(by), SHIP)
  gfx.line(math.floor(rx), math.floor(ry), math.floor(bx), math.floor(by), SHIP)
  -- The flame only when thrusting, and only every other frame, so it flickers like one.
  if ship.thrusting and math.random() > 0.35 then
    local fx, fy = pt(9 + math.random(4), math.pi)
    gfx.line(math.floor(bx), math.floor(by), math.floor(fx), math.floor(fy), FLAME)
  end
end

local function draw()
  gfx.clear(0)
  for _, r in ipairs(rocks) do polygon(r.x, r.y, r.r, r.pts, r.a, ROCK) end
  for _, s in ipairs(shots) do gfx.pixel(math.floor(s.x), math.floor(s.y), SHOT) end
  for _, b in ipairs(bits) do gfx.pixel(math.floor(b.x), math.floor(b.y), b.col) end
  drawShip()

  gfx.fill(0, 0, w, HEAD, 0)
  gfx.text(2, 2, "drift  " .. score, 7, nil, 1)
  local right = state == "over" and "out of ships - N again" or ("wave " .. wave .. "   ships " .. lives)
  gfx.text(w - #right * 6 - 2, 2, right, state == "over" and DEAD or 6, nil, 1)
  if state == "playing" and wave == 1 and score == 0 then
    local hint = "arrows turn and thrust, space fires"
    gfx.text(math.floor((w - #hint * 6) / 2), h - 10, hint, 102, nil, 1)
  end
end

if me then
  me.key = function(code, down)
    if code == KEY.left then keys.left = down
    elseif code == KEY.right then keys.right = down
    elseif code == KEY.up then keys.up = down
    elseif code == KEY.space then keys.fire = down
    elseif down and (code == KEY.q or code == KEY.esc) then input.quit = true
    elseif down and code == KEY.n then input.new = true end
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
  local now = os.clock()
  local dt = now - last
  last = now
  if dt > 0.2 then dt = 0.2 end
  update(dt)
  if input.redraw then input.redraw = nil draw() end
  gfx.present()
end
return "Drift: wave " .. wave .. ", " .. score .. " points"
