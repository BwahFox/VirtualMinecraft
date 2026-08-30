-- Maze: a first-person maze, drawn the 1992 way. One ray per screen column walks the grid until it hits a wall
-- (a DDA, so a ray costs a dozen steps however far it goes), the wall's distance becomes a column height, and the
-- column is one gfx.line. Walls are flat-shaded by which face you see and how far it is -- the 6x6x6 colour cube
-- gives six depths of every colour, which is all the fog a maze needs. No textures: that would be a pixel per
-- pixel, and a line per column is what makes this run at a real frame rate on a quarter of a core.
--
-- The maze is generated (a recursive backtracker), so every level is new; find the green door. Levels grow.
--
-- Ours, from the rules up: an original maze, an original renderer, every line this machine's own.
local w, h = gfx.size()
local KEY = win.KEY
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end

---------------------------------------------------------------------------------------------------- colours
-- cube(r, g, b) with r, g, b in 0..5 -> a palette index in the 6x6x6 cube.
local function cube(r, g, b) return 16 + 36 * r + 6 * g + b end
-- Wall kinds -> six shades, far to near, and a darker set for the north/south faces so corners read.
local function ramp(fr, fg, fb)
  local t = { {}, {} }
  for d = 1, 6 do
    local k = d / 6
    t[1][d] = cube(math.floor(fr * k + 0.5), math.floor(fg * k + 0.5), math.floor(fb * k + 0.5))
    local q = k * 0.65
    t[2][d] = cube(math.floor(fr * q + 0.5), math.floor(fg * q + 0.5), math.floor(fb * q + 0.5))
  end
  return t
end
local WALLS = {
  [1] = ramp(4, 4, 4),   -- stone
  [2] = ramp(5, 2, 1),   -- brick
  [3] = ramp(2, 3, 5),   -- blue
  [9] = ramp(1, 5, 1),   -- the exit
}
local CEIL, FLOOR = cube(1, 1, 2), cube(2, 2, 1)
local HUD = 7

---------------------------------------------------------------------------------------------------- the maze
local level = 1
local map, mw, mh = nil, 0, 0
local px, py, dirx, diry, planex, planey
local exitx, exity
local t0, timeTaken = 0, 0
local state = "play"       -- play | won
local showMap = false
local keys, input = {}, {}
local fps, frames, fpsAt = 0, 0, 0
local watched = true
local quit = false
local best = {}

-- Cells are walls (kind >= 1) or open (0). The maze is odd-sized: rooms at odd coordinates, walls between.
local function generate(cells)
  mw, mh = cells * 2 + 1, cells * 2 + 1
  map = {}
  for y = 1, mh do
    map[y] = {}
    for x = 1, mw do map[y][x] = 1 + (((x * 7 + y * 13) % 5 == 0) and 1 or 0) end
  end
  -- carve with a stack: every odd cell is a room, knock through the wall to an unvisited neighbour
  local stack = { { 2, 2 } }
  map[2][2] = 0
  while #stack > 0 do
    local c = stack[#stack]
    local cx, cy = c[1], c[2]
    local dirs = {}
    if cx > 2 and map[cy][cx - 2] ~= 0 then dirs[#dirs + 1] = { -2, 0 } end
    if cx < mw - 1 and map[cy][cx + 2] ~= 0 then dirs[#dirs + 1] = { 2, 0 } end
    if cy > 2 and map[cy - 2][cx] ~= 0 then dirs[#dirs + 1] = { 0, -2 } end
    if cy < mh - 1 and map[cy + 2][cx] ~= 0 then dirs[#dirs + 1] = { 0, 2 } end
    if #dirs == 0 then
      table.remove(stack)
    else
      local d = dirs[math.random(#dirs)]
      map[cy + d[2] / 2][cx + d[1] / 2] = 0
      map[cy + d[2]][cx + d[1]] = 0
      stack[#stack + 1] = { cx + d[1], cy + d[2] }
    end
  end
  -- a few walls in blue so the maze has landmarks
  for y = 1, mh do for x = 1, mw do
    if map[y][x] ~= 0 and (x * 31 + y * 17) % 11 == 0 then map[y][x] = 3 end
  end end
  -- the exit: the far corner's outer wall becomes a door
  exitx, exity = mw, mh - 1
  map[exity][exitx] = 9
  px, py = 2.5, 2.5
  -- face along whichever corridor leaves the first room, so the first frame is a hallway and not a wall
  if map[2][3] == 0 then dirx, diry = 1, 0 else dirx, diry = 0, 1 end
  planex, planey = -diry * 0.66, dirx * 0.66
end

local function newLevel()
  generate(math.min(3 + level, 14))
  state = "play"
  showMap = false
  t0 = os.clock()
end

---------------------------------------------------------------------------------------------------- moving
local function blocked(x, y)
  local cx, cy = math.floor(x), math.floor(y)
  local c = map[cy] and map[cy][cx]
  return c == nil or (c ~= 0 and c ~= 9)
end

local function tryMove(dx, dy)
  local R = 0.2
  local nx = px + dx
  if not blocked(nx + (dx > 0 and R or -R), py - R) and not blocked(nx + (dx > 0 and R or -R), py + R) then px = nx end
  local ny = py + dy
  if not blocked(px - R, ny + (dy > 0 and R or -R)) and not blocked(px + R, ny + (dy > 0 and R or -R)) then py = ny end
end

local function rotate(a)
  local c, s = math.cos(a), math.sin(a)
  dirx, diry = dirx * c - diry * s, dirx * s + diry * c
  planex, planey = planex * c - planey * s, planex * s + planey * c
end

local function update(dt)
  if state ~= "play" then return end
  local ms, ts = 2.6 * dt, 2.2 * dt
  if keys.left then rotate(-ts) end
  if keys.right then rotate(ts) end
  if keys.up then tryMove(dirx * ms, diry * ms) end
  if keys.down then tryMove(-dirx * ms, -diry * ms) end
  if keys.sl then tryMove(planex * -ms, planey * -ms) end
  if keys.sr then tryMove(planex * ms, planey * ms) end
  -- step onto the door and the level is done
  local c = map[math.floor(py)] and map[math.floor(py)][math.floor(px)]
  if c == 9 then
    state = "won"
    timeTaken = os.clock() - t0
    if not best[level] or timeTaken < best[level] then best[level] = timeTaken end
    for k = 1, 3 do snd.channel(k, snd.SQUARE, 330 * k, 0.35, 0.01, 0.4, 0, 0.2) end
  end
end

---------------------------------------------------------------------------------------------------- the view
local STEP = math.max(1, math.ceil(w / 320))   -- ray every STEP columns on a big wall; the line is STEP wide
local floor = math.floor
local halfH = floor(h / 2)

local function render()
  gfx.fill(0, 0, w, halfH, CEIL)
  gfx.fill(0, halfH, w, h - halfH, FLOOR)
  local mapT = map
  for x = 0, w - 1, STEP do
    local camx = 2 * x / w - 1
    local rdx, rdy = dirx + planex * camx, diry + planey * camx
    local mx, my = floor(px), floor(py)
    local ddx = rdx == 0 and 1e30 or math.abs(1 / rdx)
    local ddy = rdy == 0 and 1e30 or math.abs(1 / rdy)
    local sx, sy, sdx, sdy
    if rdx < 0 then sx, sdx = -1, (px - mx) * ddx else sx, sdx = 1, (mx + 1 - px) * ddx end
    if rdy < 0 then sy, sdy = -1, (py - my) * ddy else sy, sdy = 1, (my + 1 - py) * ddy end
    local side, kind = 0, 1
    for _ = 1, 64 do
      if sdx < sdy then
        sdx = sdx + ddx mx = mx + sx side = 0
      else
        sdy = sdy + ddy my = my + sy side = 1
      end
      local row = mapT[my]
      local c = row and row[mx]
      if c == nil then kind = 1 break end
      if c ~= 0 then kind = c break end
    end
    local dist
    if side == 0 then dist = sdx - ddx else dist = sdy - ddy end
    if dist < 0.01 then dist = 0.01 end
    local lh = floor(h / dist)
    local y0 = halfH - floor(lh / 2)
    local y1 = y0 + lh
    if y0 < 0 then y0 = 0 end
    if y1 > h then y1 = h end
    local shade = 6 - floor(dist / 1.6)
    if shade < 1 then shade = 1 elseif shade > 6 then shade = 6 end
    local col = (WALLS[kind] or WALLS[1])[side + 1][shade]
    if STEP == 1 then
      gfx.line(x, y0, x, y1 - 1, col)
    else
      gfx.fill(x, y0, STEP, y1 - y0, col)
    end
  end
end

local function drawMap()
  local cell = math.max(2, floor(math.min(w, h) * 0.6 / math.max(mw, mh)))
  local ox, oy = w - mw * cell - 4, 4
  gfx.fill(ox - 2, oy - 2, mw * cell + 4, mh * cell + 4, 0)
  for y = 1, mh do
    local row = map[y]
    for x = 1, mw do
      local c = row[x]
      if c ~= 0 then
        gfx.fill(ox + (x - 1) * cell, oy + (y - 1) * cell, cell, cell, c == 9 and 11 or (c == 3 and 12 or 6))
      end
    end
  end
  local mx, my = ox + floor((px - 1) * cell), oy + floor((py - 1) * cell)
  gfx.fill(mx - 1, my - 1, 3, 3, 8)
  gfx.line(mx, my, mx + floor(dirx * cell * 1.5), my + floor(diry * cell * 1.5), 10)
end

local function draw()
  render()
  if showMap then drawMap() end
  local secs = state == "won" and timeTaken or (os.clock() - t0)
  gfx.text(2, 2, string.format("level %d   %d:%02d   %d fps", level, floor(secs / 60), floor(secs % 60), fps), HUD, 0, 1)
  if state == "won" then
    local msg = string.format("found it in %d:%02d  -  Enter for level %d", floor(timeTaken / 60), floor(timeTaken % 60), level + 1)
    gfx.text(floor((w - #msg * 6) / 2), halfH - 4, msg, 10, 0, 1)
  elseif level == 1 and os.clock() - t0 < 6 then
    local hint = "arrows move  A/D strafe  M map  Q quit"
    gfx.text(floor((w - #hint * 6) / 2), h - 10, hint, 6, 0, 1)
  end
end

---------------------------------------------------------------------------------------------------- input
if me then
  me.key = function(code, down)
    if code == KEY.up or code == KEY.w then keys.up = down
    elseif code == KEY.down or code == KEY.s then keys.down = down
    elseif code == KEY.left then keys.left = down
    elseif code == KEY.right then keys.right = down
    elseif code == KEY.a then keys.sl = down
    elseif code == KEY.d then keys.sr = down
    elseif down and (code == KEY.enter or code == KEY.space) then input.next = true
    elseif down and code == KEY.n then input.new = true
    elseif down and (code == KEY.q or code == KEY.esc) then input.quit = true end
  end
  me.char = function(cp)
    if cp == 109 or cp == 77 then showMap = not showMap end   -- m / M
  end
  me.onbus = function(ev)
    if ev.name == "viewers" then watched = (tonumber(ev.n) or 0) > 0 end
  end
end

newLevel()
draw()
gfx.present()
local last = os.clock()
fpsAt = last
while not quit do
  if input.quit then input.quit = nil quit = true end
  if input.new then input.new = nil level = 1 newLevel() end
  if input.next then
    input.next = nil
    if state == "won" then level = level + 1 newLevel() end
  end
  local now = os.clock()
  local dt = now - last
  last = now
  if dt > 0.1 then dt = 0.1 end
  if watched then
    update(dt)
    draw()
    frames = frames + 1
    if now - fpsAt >= 1 then fps = frames frames = 0 fpsAt = now end
  end
  gfx.present()
end
return string.format("Maze: reached level %d", level)
