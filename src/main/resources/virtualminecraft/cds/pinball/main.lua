-- Pinball: one ball, two flippers, and a table that is mostly things to bounce off. Three pop bumpers up the
-- middle, a slingshot over each flipper, three rollover lanes under the arch, a bank of drop targets on the
-- right, a plunger you hold to charge, and a nudge that tilts the machine if you lean on it.
--
-- The table is laid out in its own units (200 wide, 300 tall) and scaled to whatever screen it finds, so it is the
-- same game on a 1x1 monitor and a 4x3 wall; the score panel takes whatever width is left beside it. Physics is a
-- ball against circles and line segments, stepped 240 times a second so a fast ball cannot pass through a wall.
-- Flippers are capsules that swing, and the ball is struck with the flipper's surface speed at the contact point,
-- which is what makes a flip feel like a hit rather than a bounce.
--
-- Ours, from the rules up: an original table, original artwork, every line this machine's own.
local w, h = gfx.size()
local KEY = win.KEY
local KEY_SLASH = 0x35
-- The program object for THIS program: kernel.top() is our own window at launch. Scanning kernel.programs for
-- "main.lua" picks the wrong one when the other CD slot is also running a main.lua.
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

local TW, TH = 200, 300                   -- the table, in table units
local LANE = 176                          -- x of the wall between the playfield and the plunger lane
local GRAVITY = 380
local MAXSPEED = 560
local STEP = 1 / 240
local BALLS = 3
local EXTRA_AT = 20000
local SAVE = "/disk/pinball.json"

-- palette: PICO-style base colours
local FELT, WALL, WHITE, RED, ORANGE, YELLOW, GREEN, BLUE, PINK, PEACH, GREY, DARK = 1, 6, 7, 8, 9, 10, 11, 12, 14, 15, 5, 0

---------------------------------------------------------------------------------------------------- layout
-- The table fills the height; the score panel is whatever is left on the right, and both are centred together.
local S = math.min((w - 64) / TW, h / TH)
local tw, th = math.floor(TW * S), math.floor(TH * S)
local pw = math.min(w - tw - 8, 170)
local ox = math.floor((w - tw - pw) / 2)
local oy = math.floor((h - th) / 2)
local px0 = ox + tw + 8
local pfont = pw >= 112 and 2 or 1
local pfw = gfx.fontw(pfont)
local pfh = gfx.fonth(pfont)

local function X(x) return math.floor(ox + x * S + 0.5) end
local function Y(y) return math.floor(oy + y * S + 0.5) end

---------------------------------------------------------------------------------------------------- the table
local walls, bumpers, lanes, targets, flippers = {}, {}, {}, {}, {}

local function seg(x1, y1, x2, y2, opt)
  local s = opt or {}
  s.x1, s.y1, s.x2, s.y2 = x1, y1, x2, y2
  s.e = s.e or 0.55
  walls[#walls + 1] = s
  return s
end

-- The arch across the top: half an ellipse, as sixteen straight pieces.
do
  local N = 16
  local lx, ly
  for i = 0, N do
    local t = math.pi - i * math.pi / N
    local x = 100 + 92 * math.cos(t)
    local y = 62 - 54 * math.sin(t)
    if lx then seg(lx, ly, x, y) end
    lx, ly = x, y
  end
end
seg(8, 62, 8, 218)                        -- left wall
seg(8, 218, 54, 262)                      -- left slope, down to the left flipper
seg(192, 62, 192, 300)                    -- right outer wall (the plunger lane's far side)
seg(LANE, 300, 192, 300)                  -- the lane floor
seg(LANE, 90, LANE, 300)                  -- the lane wall
seg(LANE, 90, 192, 90, { oneway = true, e = 0.2 })   -- a one-way gate: the ball leaves the lane, never falls back in
seg(LANE, 218, 130, 262)                  -- right slope, down to the right flipper

-- Slingshots: a triangle over each flipper whose long face kicks the ball away. Filled when drawn, so keep the
-- three corners together for the fill.
local function sling(ax, ay, bx, by, cx, cy)
  local s = { ax = ax, ay = ay, bx = bx, by = by, cx = cx, cy = cy, lit = 0 }
  seg(ax, ay, bx, by, { e = 0.5 })
  seg(bx, by, cx, cy, { e = 0.5 })
  seg(cx, cy, ax, ay, { e = 0.6, kick = 200, sling = s })
  return s
end
local slings = { sling(28, 190, 28, 222, 48, 238), sling(156, 190, 156, 222, 136, 238) }

-- Rollover lanes under the arch: four dividers, three lanes, a light in each.
for _, x in ipairs({ 59, 81, 103, 125 }) do seg(x, 24, x, 48, { e = 0.4 }) end
for _, x in ipairs({ 70, 92, 114 }) do lanes[#lanes + 1] = { x = x, y = 36, lit = false, inside = false } end

-- Drop targets: a bank of three on the right, hit from the left; all three down is worth a lot and they stand up again.
for _, y in ipairs({ 130, 148, 166 }) do
  targets[#targets + 1] = seg(170, y, 170, y + 12, { e = 0.4, target = true, up = true })
end

for _, b in ipairs({ { 62, 105 }, { 122, 105 }, { 92, 145 } }) do
  bumpers[#bumpers + 1] = { x = b[1], y = b[2], r = 11, kick = 240, lit = 0 }
end

-- Flippers: a pivot, a length, and the two angles they swing between. Angles are y-down, so a positive angle
-- points below the horizontal.
flippers[1] = { px = 54, py = 262, len = 34, r = 3.5, rest = 0.52, active = -0.5, a = 0.52, w = 0, side = "left" }
flippers[2] = { px = 130, py = 262, len = 34, r = 3.5, rest = math.pi - 0.52, active = math.pi + 0.5, a = math.pi - 0.52, w = 0, side = "right" }
local FLIP_SPEED = 24

---------------------------------------------------------------------------------------------------- state
local ball = nil
local score, high, mult, ballsLeft, ballNo, state = 0, 0, 1, BALLS, 0, "over"
local extraGiven = false
local power, charging = 0, false
local spawnIn, bankIn = 0, 0
local tiltHeat, tilted = 0, false
local msg, msgT = "", 0
local keys, input = {}, {}
local watched = true
local quit = false

local ok, saved = pcall(function() return json.decode(fs.read(SAVE)) end)
if ok and type(saved) == "table" and tonumber(saved.high) then high = tonumber(saved.high) end

local function saveHigh()
  if score > high then
    high = score
    pcall(fs.write, SAVE, json.encode({ high = high }))
  end
end

local function flash(s, t) msg, msgT = s, t or 1.2 end

local function add(n)
  score = score + n * mult
  if not extraGiven and score >= EXTRA_AT then
    extraGiven = true
    ballsLeft = ballsLeft + 1
    flash("EXTRA BALL", 2)
    for k = 1, 3 do snd.channel(k, snd.SQUARE, 400 * k, 0.35, 0.01, 0.35, 0, 0.2) end
  end
end

local function resetBank()
  for _, t in ipairs(targets) do t.up = true t.active = true end
end

local function newBall()
  ball = { x = 184, y = 290, vx = 0, vy = 0, r = 5, held = true, still = 0 }
  power, charging = 0, false
  tilted, tiltHeat = false, 0
  for _, l in ipairs(lanes) do l.inside = false end
  ballNo = ballNo + 1
end

local function newGame()
  score, mult, ballsLeft, ballNo, state = 0, 1, BALLS, 0, "play"
  extraGiven = false
  spawnIn, bankIn = 0, 0
  for _, l in ipairs(lanes) do l.lit = false end
  resetBank()
  newBall()
  flash("hold SPACE to launch", 3)
end

---------------------------------------------------------------------------------------------------- physics
local function closest(px, py, x1, y1, x2, y2)
  local dx, dy = x2 - x1, y2 - y1
  local l2 = dx * dx + dy * dy
  local t = 0
  if l2 > 0 then
    t = ((px - x1) * dx + (py - y1) * dy) / l2
    if t < 0 then t = 0 elseif t > 1 then t = 1 end
  end
  return x1 + dx * t, y1 + dy * t
end

local function hitSeg(b, s)
  if s.active == false then return false end
  if s.oneway and b.vy <= 0 then return false end
  local cx, cy = closest(b.x, b.y, s.x1, s.y1, s.x2, s.y2)
  local dx, dy = b.x - cx, b.y - cy
  local d2 = dx * dx + dy * dy
  if d2 >= b.r * b.r then return false end
  local d = math.sqrt(d2)
  local nx, ny
  if d < 1e-6 then
    local sx, sy = s.x2 - s.x1, s.y2 - s.y1
    local l = math.sqrt(sx * sx + sy * sy)
    nx, ny = -sy / l, sx / l
    if nx * b.vx + ny * b.vy > 0 then nx, ny = -nx, -ny end
  else
    nx, ny = dx / d, dy / d
  end
  b.x, b.y = cx + nx * b.r, cy + ny * b.r
  local vn = b.vx * nx + b.vy * ny
  if vn < 0 then
    b.vx = b.vx - (1 + s.e) * vn * nx
    b.vy = b.vy - (1 + s.e) * vn * ny
    if s.kick then
      b.vx = b.vx + nx * s.kick
      b.vy = b.vy + ny * s.kick
    end
    return true, vn
  end
  return false
end

local function hitBumper(b, p)
  local dx, dy = b.x - p.x, b.y - p.y
  local rr = b.r + p.r
  local d2 = dx * dx + dy * dy
  if d2 >= rr * rr then return false end
  local d = math.sqrt(d2)
  if d < 1e-6 then dx, dy, d = 0, -1, 1 end
  local nx, ny = dx / d, dy / d
  b.x, b.y = p.x + nx * rr, p.y + ny * rr
  local vn = b.vx * nx + b.vy * ny
  if vn < 0 then
    b.vx = b.vx - 1.5 * vn * nx
    b.vy = b.vy - 1.5 * vn * ny
  end
  b.vx = b.vx + nx * p.kick
  b.vy = b.vy + ny * p.kick
  return true
end

local function hitFlipper(b, f)
  local tx, ty = f.px + math.cos(f.a) * f.len, f.py + math.sin(f.a) * f.len
  local cx, cy = closest(b.x, b.y, f.px, f.py, tx, ty)
  local dx, dy = b.x - cx, b.y - cy
  local rr = b.r + f.r
  local d2 = dx * dx + dy * dy
  if d2 >= rr * rr then return false end
  local d = math.sqrt(d2)
  local nx, ny
  if d < 1e-6 then
    nx, ny = -math.sin(f.a), math.cos(f.a)
    if ny > 0 then nx, ny = -nx, -ny end
  else
    nx, ny = dx / d, dy / d
  end
  b.x, b.y = cx + nx * rr, cy + ny * rr
  -- The surface of a swinging flipper is moving; strike the ball relative to that, then put the motion back.
  local rx, ry = cx - f.px, cy - f.py
  local sx, sy = -ry * f.w, rx * f.w
  local vx, vy = b.vx - sx, b.vy - sy
  local vn = vx * nx + vy * ny
  if vn < 0 then
    vx = vx - 1.35 * vn * nx
    vy = vy - 1.35 * vn * ny
    b.vx, b.vy = vx + sx, vy + sy
    return true
  end
  return false
end

local function drain()
  snd.channel(4, snd.NOISE, 60, 0.5, 0, 0.6, 0, 0.3)
  ball = nil
  ballsLeft = ballsLeft - 1
  tilted = false
  if ballsLeft <= 0 then
    state = "over"
    saveHigh()
    flash("GAME OVER - N plays again", 6)
  else
    spawnIn = 1.2
    flash("ball lost", 1.2)
  end
end

local function step(sdt)
  -- flippers first, so the ball sees where they are now
  for _, f in ipairs(flippers) do
    local pressed = (f.side == "left" and keys.left) or (f.side == "right" and keys.right)
    local target = (pressed and not tilted) and f.active or f.rest
    local old = f.a
    local da = FLIP_SPEED * sdt
    if math.abs(target - f.a) <= da then f.a = target
    elseif target > f.a then f.a = f.a + da
    else f.a = f.a - da end
    f.w = (f.a - old) / sdt
  end
  local b = ball
  if not b or b.held then return end

  b.vy = b.vy + GRAVITY * sdt
  b.x = b.x + b.vx * sdt
  b.y = b.y + b.vy * sdt

  for _, s in ipairs(walls) do
    local hit, vn = hitSeg(b, s)
    if hit then
      if s.sling then
        if s.sling.lit <= 0 then
          add(10)
          snd.channel(2, snd.SQUARE, 220, 0.3, 0, 0.06, 0, 0.03)
        end
        s.sling.lit = 0.1
      elseif s.target then
        s.up = false
        s.active = false
        add(200)
        flash("target 200")
        snd.channel(3, snd.SQUARE, 660, 0.35, 0, 0.1, 0, 0.05)
        local down = true
        for _, t in ipairs(targets) do if t.up then down = false end end
        if down then
          add(2000)
          flash("BANK 2000", 2)
          bankIn = 1.0
          for k = 1, 3 do snd.channel(k, snd.SQUARE, 500 + 150 * k, 0.3, 0.01, 0.25, 0, 0.1) end
        end
      elseif vn < -90 then
        snd.channel(3, snd.NOISE, 200, 0.12, 0, 0.03, 0, 0.02)
      end
    end
  end
  for _, p in ipairs(bumpers) do
    if hitBumper(b, p) then
      if p.lit <= 0 then
        add(100)
        snd.channel(1, snd.SQUARE, 440 + math.random(60), 0.4, 0, 0.08, 0, 0.05)
      end
      p.lit = 0.12
    end
  end
  for _, f in ipairs(flippers) do
    if hitFlipper(b, f) and math.abs(f.w) > 1 then
      snd.channel(4, snd.SQUARE, 160, 0.25, 0, 0.04, 0, 0.02)
    end
  end

  local sp = math.sqrt(b.vx * b.vx + b.vy * b.vy)
  if sp > MAXSPEED then
    b.vx, b.vy = b.vx * MAXSPEED / sp, b.vy * MAXSPEED / sp
  end

  -- rollover lanes: light on entering, once per pass
  for _, l in ipairs(lanes) do
    local inside = math.abs(b.x - l.x) < 8 and b.y > 26 and b.y < 46
    if inside and not l.inside then
      if not l.lit then
        l.lit = true
        add(50)
        snd.channel(3, snd.SQUARE, 880, 0.3, 0, 0.08, 0, 0.04)
        local all = true
        for _, o in ipairs(lanes) do if not o.lit then all = false end end
        if all then
          add(1000)
          if mult < 5 then mult = mult + 1 end
          flash("LANES 1000  bonus x" .. mult, 2)
          for _, o in ipairs(lanes) do o.lit = false end
          for k = 1, 3 do snd.channel(k, snd.SQUARE, 300 * k, 0.35, 0.01, 0.3, 0, 0.15) end
        end
      end
    end
    l.inside = inside
  end

  -- back at rest in the lane: load the plunger again
  if b.x > LANE and b.y > 286 and sp < 8 then
    b.held, b.x, b.y, b.vx, b.vy = true, 184, 290, 0, 0
    power = 0
  end

  if b.y > TH + 12 then drain() end
end

local function update(dt)
  if msgT > 0 then msgT = msgT - dt end
  for _, p in ipairs(bumpers) do if p.lit > 0 then p.lit = p.lit - dt end end
  for _, s in ipairs(slings) do if s.lit > 0 then s.lit = s.lit - dt end end
  if bankIn > 0 then
    bankIn = bankIn - dt
    if bankIn <= 0 then resetBank() end
  end
  if spawnIn > 0 then
    spawnIn = spawnIn - dt
    if spawnIn <= 0 then newBall() end
  end
  if tiltHeat > 0 then tiltHeat = math.max(0, tiltHeat - 0.8 * dt) end

  if ball and ball.held then
    if charging then power = math.min(1, power + dt / 1.1) end
    if input.launch then
      input.launch = nil
      ball.held = false
      ball.vy = -(180 + 340 * math.max(power, 0.15))
      power, charging = 0, false
      snd.channel(4, snd.NOISE, 300, 0.4, 0, 0.15, 0, 0.1)
    end
  else
    input.launch = nil
  end

  if input.nudge then
    input.nudge = nil
    if ball and not ball.held and not tilted then
      ball.vy = ball.vy - 70
      ball.vx = ball.vx + (math.random() - 0.5) * 80
      tiltHeat = tiltHeat + 1
      snd.channel(3, snd.NOISE, 120, 0.3, 0, 0.05, 0, 0.03)
      if tiltHeat > 2.5 then
        tilted = true
        flash("TILT", 3)
        snd.channel(4, snd.NOISE, 40, 0.6, 0, 0.5, 0, 0.3)
      end
    end
  end

  local n = math.max(1, math.ceil(dt / STEP))
  local sdt = dt / n
  for _ = 1, n do step(sdt) end

  -- a ball that has stopped somewhere it should not is given a shove
  if ball and not ball.held then
    local sp = math.sqrt(ball.vx * ball.vx + ball.vy * ball.vy)
    if sp < 4 then ball.still = ball.still + dt else ball.still = 0 end
    if ball.still > 2 then
      ball.still = 0
      ball.vx = ball.vx + (math.random() - 0.5) * 80
      ball.vy = ball.vy - 60
    end
  end
end

---------------------------------------------------------------------------------------------------- drawing
local function L(x1, y1, x2, y2, c) gfx.line(X(x1), Y(y1), X(x2), Y(y2), c) end

-- A filled triangle in screen pixels: scanlines between the two edges.
local function tri(x1, y1, x2, y2, x3, y3, c)
  if y2 < y1 then x1, y1, x2, y2 = x2, y2, x1, y1 end
  if y3 < y1 then x1, y1, x3, y3 = x3, y3, x1, y1 end
  if y3 < y2 then x2, y2, x3, y3 = x3, y3, x2, y2 end
  local function at(ax, ay, bx, by, y)
    if by == ay then return ax end
    return ax + (bx - ax) * (y - ay) / (by - ay)
  end
  for y = y1, y3 do
    local xa = at(x1, y1, x3, y3, y)
    local xb
    if y < y2 then xb = at(x1, y1, x2, y2, y) else xb = at(x2, y2, x3, y3, y) end
    gfx.line(math.floor(xa), y, math.floor(xb), y, c)
  end
end

local function capsule(x1, y1, x2, y2, r, c)
  gfx.disc(x1, y1, r, c)
  gfx.disc(x2, y2, r, c)
  local dx, dy = x2 - x1, y2 - y1
  local l = math.sqrt(dx * dx + dy * dy)
  if l < 1 then return end
  local nx, ny = -dy / l, dx / l
  for k = -r, r do
    gfx.line(math.floor(x1 + nx * k), math.floor(y1 + ny * k), math.floor(x2 + nx * k), math.floor(y2 + ny * k), c)
  end
end

local function ptext(row, s, c, font)
  font = font or pfont
  gfx.text(px0, oy + 4 + row, s, c, nil, font)
end

local function draw()
  gfx.clear(DARK)
  gfx.fill(ox, oy, tw, th, FELT)
  -- the drain: a darker strip between the flippers, so the gap reads as one
  gfx.fill(X(54), Y(262), X(130) - X(54), Y(TH) - Y(262), DARK)
  gfx.fill(X(LANE), Y(90), X(192) - X(LANE), Y(300) - Y(90), DARK)

  for _, s in ipairs(walls) do
    if not s.sling and not s.target and not s.oneway then L(s.x1, s.y1, s.x2, s.y2, WALL) end
  end
  L(LANE, 90, 192, 90, GREY)

  for _, s in ipairs(slings) do
    local c = s.lit > 0 and YELLOW or ORANGE
    tri(X(s.ax), Y(s.ay), X(s.bx), Y(s.by), X(s.cx), Y(s.cy), c)
    L(s.cx, s.cy, s.ax, s.ay, s.lit > 0 and WHITE or PEACH)
  end

  for _, l in ipairs(lanes) do
    gfx.disc(X(l.x), Y(l.y), math.max(1, math.floor(3 * S)), l.lit and GREEN or GREY)
  end

  for _, t in ipairs(targets) do
    local hh = Y(t.y2) - Y(t.y1)
    if t.up then gfx.fill(X(t.x1) - 1, Y(t.y1), math.max(2, math.floor(3 * S)), hh, PINK)
    else gfx.fill(X(t.x1), Y(t.y1), 1, hh, GREY) end
  end

  for _, p in ipairs(bumpers) do
    local r = math.max(2, math.floor(p.r * S))
    gfx.disc(X(p.x), Y(p.y), r, p.lit > 0 and YELLOW or RED)
    gfx.circle(X(p.x), Y(p.y), r, p.lit > 0 and WHITE or PEACH)
    gfx.disc(X(p.x), Y(p.y), math.max(1, math.floor(r / 3)), WHITE)
  end

  for _, f in ipairs(flippers) do
    local tx, ty = f.px + math.cos(f.a) * f.len, f.py + math.sin(f.a) * f.len
    capsule(X(f.px), Y(f.py), X(tx), Y(ty), math.max(1, math.floor(f.r * S)), tilted and GREY or YELLOW)
    gfx.disc(X(f.px), Y(f.py), math.max(1, math.floor(1.5 * S)), ORANGE)
  end

  if ball then
    local r = math.max(1, math.floor(ball.r * S))
    gfx.disc(X(ball.x), Y(ball.y), r, WHITE)
    gfx.pixel(X(ball.x) - math.floor(r / 3), Y(ball.y) - math.floor(r / 3), PEACH)
    if ball.held then
      -- the plunger, drawn as a bar that fills as you hold
      local top, bot = Y(296), Y(300)
      local full = X(192) - X(LANE) - 2
      gfx.fill(X(LANE) + 1, top, math.floor(full * power), bot - top, power > 0.85 and RED or GREEN)
    end
  end

  -- the panel
  local row = 0
  ptext(row, "PINBALL", YELLOW) row = row + pfh + 4
  ptext(row, "SCORE", GREY) row = row + pfh
  ptext(row, tostring(score), WHITE) row = row + pfh + 4
  ptext(row, "HIGH", GREY) row = row + pfh
  ptext(row, tostring(high), WHITE) row = row + pfh + 4
  if state == "play" then
    ptext(row, "BALL " .. ballNo, GREY) row = row + pfh
    ptext(row, "left " .. math.max(0, ballsLeft - 1), GREY) row = row + pfh + 4
  else
    ptext(row, "GAME OVER", RED) row = row + pfh * 2 + 4
  end
  ptext(row, "BONUS x" .. mult, mult > 1 and GREEN or GREY) row = row + pfh + 4
  if tilted then ptext(row, "TILT", RED) end
  row = row + pfh + 4

  local lines = { "Z / X or", "arrows flip", "SPACE launch", "UP nudges", "N new Q quit" }
  local y = oy + th - #lines * 9 - 2
  for _, s in ipairs(lines) do
    gfx.text(px0, y, s, GREY, nil, 1)
    y = y + 9
  end

  if msgT > 0 then
    local fw = gfx.fontw(1)
    local x = math.floor(ox + (tw - #msg * fw) / 2)
    gfx.text(x, Y(TH) - 14, msg, YELLOW, DARK, 1)
  end
end

---------------------------------------------------------------------------------------------------- input
if me then
  me.key = function(code, down)
    if code == KEY.z or code == KEY.lshift or code == KEY.left then keys.left = down
    elseif code == KEY.x or code == KEY.rshift or code == KEY.right or code == KEY_SLASH then keys.right = down
    elseif code == KEY.space or code == KEY.down then
      if down and not charging then charging = true
      elseif not down and charging then charging = false input.launch = true end
      if state == "over" and down then input.new = true end
    elseif down and code == KEY.up then input.nudge = true
    elseif down and (code == KEY.q or code == KEY.esc) then input.quit = true
    elseif down and code == KEY.n then input.new = true end
  end
  -- Stop simulating when nobody is looking: a pinball game nobody can see is a machine burning its share.
  me.onbus = function(ev)
    if ev.name == "viewers" then watched = (tonumber(ev.n) or 0) > 0 end
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
  if dt > 0.1 then dt = 0.1 end
  if watched then
    update(dt)
    draw()
  end
  gfx.present()
end
saveHigh()
return "Pinball: " .. score .. " points"
