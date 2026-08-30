-- Drift 3D: Drift from the cockpit. The same game -- momentum and no brakes, rocks that split, a world that wraps --
-- but seen first-person, and drawn by raycasting. One ray per screen column is intersected with every rock as a
-- circle; the nearest hit wins the column (a z-buffer, one number per column), its distance sets the slab height,
-- the rock's own jagged outline (the same jitter ring Drift draws) bends that height so the silhouette is rugged,
-- and the surface normal at the hit shades the slab so a rock reads as round rather than as a wall. Shots and
-- debris are projected points tested against the z-buffer, the stars pan with your heading, and a heading-up
-- radar in the corner is how you find the rocks you cannot see -- which in first person is most of them.
--
-- Ours, from the rules up: a 1979 idea, seen from inside, every line this machine's own.
local w, h = gfx.size()
local KEY = win.KEY
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local TAU = math.pi * 2
local floor, sqrt, cos, sin, atan2 = math.floor, math.sqrt, math.cos, math.sin, math.atan2
local WW, WH = 320, 320                -- the world: the view is not the world any more, so it has its own size
local FAR = 200                        -- fog: black at this distance, which also hides the wrap seam at 160
local NEAR = 40                        -- ...and full brightness inside this
local HEAD = 12
local halfH = floor(h / 2)
local STEP = math.max(1, math.ceil(w / 320))
local SHOT, FLAME, DEAD, HUD, DIM = 11, 10, 8, 7, 5

---------------------------------------------------------------------------------------------------- colours
local function cube(r, g, b) return 16 + 36 * r + 6 * g + b end
-- The rock's shades: the palette's 24 greys (232..255), black first. The colour cube only has six steps per
-- channel and rounding each channel separately makes neighbouring levels change hue, which put red and olive
-- stripes down every rock; the grey ramp is neutral and twice as fine.
local SHADE = { [0] = 0 }
for i = 1, 24 do SHADE[i] = 231 + i end
local SHADES = 24
local STAR = { cube(2, 2, 2), cube(3, 3, 4), 7 }

---------------------------------------------------------------------------------------------------- state
local ship, rocks, shots, bits = nil, {}, {}, {}
local score, lives, wave, state, respawn = 0, 3, 1, "playing", 0
local keys, input = {}, {}
local watched, quit = true, false
local fps, frames, fpsAt = 0, 0, 0
local zbuf = {}
local stars = {}
for i = 1, 90 do stars[i] = { a = math.random() * TAU, y = 0.08 + math.random() * 0.84, c = STAR[math.random(3)] } end

local function wrap(o)
  if o.x < 0 then o.x = o.x + WW elseif o.x >= WW then o.x = o.x - WW end
  if o.y < 0 then o.y = o.y + WH elseif o.y >= WH then o.y = o.y - WH end
end

-- b relative to a, the short way round the torus
local function rel(ax, ay, bx, by)
  local dx, dy = bx - ax, by - ay
  if dx > WW / 2 then dx = dx - WW elseif dx < -WW / 2 then dx = dx + WW end
  if dy > WH / 2 then dy = dy - WH elseif dy < -WH / 2 then dy = dy + WH end
  return dx, dy
end

local function near(ax, ay, bx, by)
  local dx, dy = rel(ax, ay, bx, by)
  return sqrt(dx * dx + dy * dy)
end

---------------------------------------------------------------------------------------------------- the things
local function makeRock(x, y, size)
  local pts = {}
  local n = 8 + math.random(3)
  for i = 1, n do pts[i] = 0.65 + math.random() * 0.5 end
  local speed = (16 + math.random(20)) * (4 - size) * 0.5
  local dir = math.random() * TAU
  return {
    x = x, y = y, size = size, r = ({ 6, 11, 18 })[size], pts = pts,
    a = math.random() * TAU, spin = (math.random() - 0.5) * 1.6,
    vx = cos(dir) * speed, vy = sin(dir) * speed,
  }
end

local function newShip()
  return { x = WW / 2, y = WH / 2, vx = 0, vy = 0, a = -math.pi / 2, thrusting = false, cool = 0 }
end

local function startWave()
  rocks, shots = {}, {}
  local n = 3 + wave
  local sx = ship and ship.x or WW / 2
  local sy = ship and ship.y or WH / 2
  for _ = 1, n do
    local x, y
    local tries = 0
    repeat
      x, y = math.random(WW), math.random(WH)
      tries = tries + 1
    until near(x, y, sx, sy) > 80 or tries > 200
    rocks[#rocks + 1] = makeRock(x, y, 3)
  end
end

local function newGame()
  ship = newShip()
  score, lives, wave, state, respawn = 0, 3, 1, "playing", 0
  bits = {}
  startWave()
end

local function shatter(x, y, n, col)
  for _ = 1, n do
    local d = math.random() * TAU
    local s = 20 + math.random(50)
    bits[#bits + 1] = { x = x, y = y, z = (math.random() - 0.5) * 8, vx = cos(d) * s, vy = sin(d) * s,
      life = 0.5 + math.random() * 0.5, col = col }
  end
end

local function splitRock(i)
  local r = rocks[i]
  score = score + ({ 100, 50, 20 })[r.size]
  shatter(r.x, r.y, 6, SHADE[16])
  snd.channel(1, snd.NOISE, 80 + r.size * 40, 0.45, 0, 0.18, 0, 0.06)
  table.remove(rocks, i)
  if r.size > 1 then
    for _ = 1, 2 do rocks[#rocks + 1] = makeRock(r.x, r.y, r.size - 1) end
  end
end

local function killShip()
  lives = lives - 1
  shatter(ship.x, ship.y, 12, DEAD)
  snd.channel(2, snd.NOISE, 45, 0.6, 0, 0.5, 0, 0.2)
  if lives <= 0 then state = "over" else respawn = 1.5 ship = nil end
end

---------------------------------------------------------------------------------------------------- the step
local function update(dt)
  if state == "over" then
    for _, b in ipairs(bits) do b.x = b.x + b.vx * dt b.y = b.y + b.vy * dt b.life = b.life - dt end
    for i = #bits, 1, -1 do if bits[i].life <= 0 then table.remove(bits, i) end end
    return
  end
  if not ship then
    respawn = respawn - dt
    if respawn <= 0 then
      local clear = true
      for _, r in ipairs(rocks) do
        if near(WW / 2, WH / 2, r.x, r.y) < r.r + 50 then clear = false break end
      end
      if clear then ship = newShip() end
    end
  else
    if keys.left then ship.a = ship.a - 2.6 * dt end
    if keys.right then ship.a = ship.a + 2.6 * dt end
    ship.thrusting = keys.up == true
    if ship.thrusting then
      ship.vx = ship.vx + cos(ship.a) * 150 * dt
      ship.vy = ship.vy + sin(ship.a) * 150 * dt
    end
    local drag = math.max(0, 1 - 0.35 * dt)
    ship.vx, ship.vy = ship.vx * drag, ship.vy * drag
    ship.x = ship.x + ship.vx * dt
    ship.y = ship.y + ship.vy * dt
    wrap(ship)
    ship.cool = math.max(0, ship.cool - dt)
    if keys.fire and ship.cool <= 0 and #shots < 5 then
      ship.cool = 0.18
      shots[#shots + 1] = {
        x = ship.x + cos(ship.a) * 6, y = ship.y + sin(ship.a) * 6,
        vx = ship.vx + cos(ship.a) * 190, vy = ship.vy + sin(ship.a) * 190, life = 1.1,
      }
      snd.beep(900, 0.02, 0)
    end
  end

  for _, s in ipairs(shots) do
    s.x = s.x + s.vx * dt s.y = s.y + s.vy * dt s.life = s.life - dt
    wrap(s)
  end
  for i = #shots, 1, -1 do if shots[i].life <= 0 then table.remove(shots, i) end end
  for _, r in ipairs(rocks) do
    r.x = r.x + r.vx * dt r.y = r.y + r.vy * dt r.a = r.a + r.spin * dt
    wrap(r)
  end
  for _, b in ipairs(bits) do
    b.x = b.x + b.vx * dt b.y = b.y + b.vy * dt b.life = b.life - dt
    wrap(b)
  end
  for i = #bits, 1, -1 do if bits[i].life <= 0 then table.remove(bits, i) end end

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
      if near(ship.x, ship.y, rocks[i].x, rocks[i].y) < rocks[i].r + 3 then
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
end

---------------------------------------------------------------------------------------------------- the view
local camx, camy, cama = WW / 2, WH / 2, -math.pi / 2   -- the camera stays where the ship died, until it is back
local dirx, diry, planex, planey

-- Cast the columns. Every rock is a circle to the rays; its outline ring bends the slab height afterwards.
local function castRocks()
  local vis = {}
  for _, r in ipairs(rocks) do
    local rx, ry = rel(camx, camy, r.x, r.y)
    local d2 = rx * rx + ry * ry
    if d2 < (FAR + r.r) * (FAR + r.r) then
      vis[#vis + 1] = { rock = r, rx = rx, ry = ry, c2 = d2, r2 = r.r * r.r }
    end
  end
  local nvis = #vis
  local lx, ly = cos(0.8), sin(0.8)          -- a fixed light, so turning changes the shading and rocks look solid
  for x = 0, w - 1, STEP do
    local cx = 2 * x / w - 1
    local rdx, rdy = dirx + planex * cx, diry + planey * cx
    local aa = rdx * rdx + rdy * rdy
    local best, bestV = 1e30, nil
    for i = 1, nvis do
      local v = vis[i]
      local b = rdx * v.rx + rdy * v.ry
      if b > 0 then
        local disc = b * b - aa * (v.c2 - v.r2)
        if disc > 0 then
          local t = (b - sqrt(disc)) / aa
          if t > 0.05 and t < best then best, bestV = t, v end
        end
      end
    end
    if bestV then
      local r = bestV.rock
      local hx, hy = rdx * best - bestV.rx, rdy * best - bestV.ry   -- the hit, relative to the rock's centre
      local nx, ny = hx / r.r, hy / r.r
      -- the outline ring at this angle: which of the rock's points, and how far between it and the next
      local pts = r.pts
      local n = #pts
      local ang = (atan2(hy, hx) - r.a) / TAU * n
      ang = ang - floor(ang / n) * n
      local k = floor(ang)
      local f = ang - k
      local rug = pts[k + 1] * (1 - f) + pts[(k + 1) % n + 1] * f
      local lh = floor(1.15 * h * r.r * rug / best)
      if lh > h * 3 then lh = h * 3 end
      local y0 = halfH - floor(lh / 2)
      local y1 = y0 + lh
      if y0 < HEAD then y0 = HEAD end
      if y1 > h then y1 = h end
      local rl = 1 / sqrt(aa)
      local facing = -(nx * rdx + ny * rdy) * rl
      if facing < 0 then facing = 0 end
      local lit = 0.5 + 0.5 * (nx * lx + ny * ly)
      local fog = (FAR - best) / (FAR - NEAR)
      if fog < 0 then fog = 0 elseif fog > 1 then fog = 1 end
      local inten = fog * (0.2 + 0.5 * facing + 0.3 * lit)
      local col = SHADE[floor(inten * SHADES + 0.5)]
      if STEP == 1 then gfx.line(x, y0, x, y1 - 1, col) else gfx.fill(x, y0, STEP, y1 - y0, col) end
      zbuf[x] = best
    else
      zbuf[x] = 1e30
    end
  end
end

-- A point in the world -> screen x and depth (camera space), or nil when behind the camera.
local invDet
local function project(dx, dy)
  local tx = invDet * (diry * dx - dirx * dy)
  local ty = invDet * (-planey * dx + planex * dy)
  if ty <= 0.5 then return nil end
  return floor((w / 2) * (1 + tx / ty)), ty
end

local function drawPoint(px, py, z, size, col)
  local dx, dy = rel(camx, camy, px, py)
  local sx, depth = project(dx, dy)
  if not sx or sx < 0 or sx >= w then return end
  local zx = sx - (sx % STEP)
  if depth >= (zbuf[zx] or 1e30) then return end
  local sy = halfH + floor(h * z / depth)
  if sy < HEAD or sy >= h then return end
  local rad = floor(h * size / depth)
  if rad < 1 then gfx.pixel(sx, sy, col) else gfx.disc(sx, sy, rad, col) end
end

local function drawStars()
  local half = math.atan(0.66)
  for _, s in ipairs(stars) do
    local ra = s.a - cama
    ra = ra - floor((ra + math.pi) / TAU) * TAU
    if ra > -half and ra < half then
      local sx = floor((w / 2) * (1 + math.tan(ra) / 0.66))
      local sy = HEAD + floor(s.y * (h - HEAD))
      if sx >= 0 and sx < w then gfx.pixel(sx, sy, s.c) end
    end
  end
end

local function drawRadar()
  local R = floor(math.min(w, h) * 0.12)
  local cx, cy = R + 6, h - R - 6
  gfx.disc(cx, cy, R, 0)
  gfx.circle(cx, cy, R, DIM)
  gfx.line(cx, cy - R, cx, cy - R + 3, 6)
  local s = R / (WW / 2)
  for _, r in ipairs(rocks) do
    local dx, dy = rel(camx, camy, r.x, r.y)
    local fwd = dx * dirx + dy * diry
    local side = -dx * diry + dy * dirx
    local px, py = cx + floor(side * s), cy - floor(fwd * s)
    if (px - cx) * (px - cx) + (py - cy) * (py - cy) < R * R then
      if r.size == 3 then gfx.fill(px - 1, py - 1, 3, 3, 6) else gfx.pixel(px, py, r.size == 2 and 6 or 5) end
    end
  end
  for _, sh in ipairs(shots) do
    local dx, dy = rel(camx, camy, sh.x, sh.y)
    local fwd = dx * dirx + dy * diry
    local side = -dx * diry + dy * dirx
    gfx.pixel(cx + floor(side * s), cy - floor(fwd * s), SHOT)
  end
  if ship then
    -- where you are actually going, which in Drift is rarely where you are looking
    local vf = ship.vx * dirx + ship.vy * diry
    local vs = -ship.vx * diry + ship.vy * dirx
    local k = R / 260
    gfx.line(cx, cy, cx + floor(vs * k), cy - floor(vf * k), FLAME)
    gfx.fill(cx - 1, cy - 1, 3, 3, 7)
  end
end

local function draw()
  if ship then camx, camy, cama = ship.x, ship.y, ship.a end
  dirx, diry = cos(cama), sin(cama)
  planex, planey = -diry * 0.66, dirx * 0.66
  invDet = 1 / (planex * diry - dirx * planey)
  gfx.clear(0)
  drawStars()
  gfx.line(0, halfH, w - 1, halfH, cube(0, 0, 1))
  castRocks()
  for _, s in ipairs(shots) do drawPoint(s.x, s.y, 0, 0.08, SHOT) end
  for _, b in ipairs(bits) do drawPoint(b.x, b.y, b.z * 0.04, 0.05, b.col) end
  -- the cockpit: a reticle, and the flame when thrusting
  gfx.line(floor(w / 2) - 4, halfH, floor(w / 2) - 2, halfH, 6)
  gfx.line(floor(w / 2) + 2, halfH, floor(w / 2) + 4, halfH, 6)
  gfx.line(floor(w / 2), halfH - 4, floor(w / 2), halfH - 2, 6)
  gfx.line(floor(w / 2), halfH + 2, floor(w / 2), halfH + 4, 6)
  if ship and ship.thrusting and math.random() > 0.3 then
    local fx = floor(w / 2)
    gfx.line(fx - 3, h - 1, fx, h - 6 - math.random(4), FLAME)
    gfx.line(fx + 3, h - 1, fx, h - 6 - math.random(4), FLAME)
  end
  drawRadar()
  gfx.fill(0, 0, w, HEAD, 0)
  gfx.text(2, 2, "drift 3d  " .. score, HUD, nil, 1)
  local right = state == "over" and "out of ships - N again" or ("wave " .. wave .. "   ships " .. lives .. "  " .. fps .. " fps")
  gfx.text(w - #right * 6 - 2, 2, right, state == "over" and DEAD or 6, nil, 1)
  if not ship and state ~= "over" then
    local msg = "ship lost"
    gfx.text(floor((w - #msg * 6) / 2), halfH + 10, msg, DEAD, nil, 1)
  elseif state == "playing" and wave == 1 and score == 0 then
    local hint = "arrows turn and thrust, space fires"
    gfx.text(floor((w - #hint * 6) / 2), h - 10, hint, DIM, nil, 1)
  end
end

---------------------------------------------------------------------------------------------------- input
if me then
  me.key = function(code, down)
    if code == KEY.left then keys.left = down
    elseif code == KEY.right then keys.right = down
    elseif code == KEY.up then keys.up = down
    elseif code == KEY.space then keys.fire = down
    elseif down and (code == KEY.q or code == KEY.esc) then input.quit = true
    elseif down and code == KEY.n then input.new = true end
  end
  me.onbus = function(ev)
    if ev.name == "viewers" then watched = (tonumber(ev.n) or 0) > 0 end
  end
end

newGame()
draw()
gfx.present()
local last = os.clock()
fpsAt = last
while not quit do
  if input.quit then input.quit = nil quit = true end
  if input.new then input.new = nil newGame() end
  local now = os.clock()
  local dt = now - last
  last = now
  if dt > 0.2 then dt = 0.2 end
  if watched then
    update(dt)
    draw()
    frames = frames + 1
    if now - fpsAt >= 1 then fps = frames frames = 0 fpsAt = now end
  end
  gfx.present()
end
return "Drift 3D: wave " .. wave .. ", " .. score .. " points"
