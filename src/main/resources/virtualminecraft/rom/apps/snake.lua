-- Snake: the first game in ROM (ROADMAP §7h §7). Arrows or WASD, space pauses, Q quits. A full-screen program that
-- draws only what changed each move, so it costs the same on a 1×1 monitor and a 4×3 wall.
local app = { id = "snake", name = "Snake", icon = "S" }

function app.open(args)
  local wd = win.Window.new{ title = "Snake", fullscreen = true }
  kernel.show(wd)
  local input = { dir = nil, pause = false, quit = false }
  local p = kernel.spawn("snake", function()
    local KEY = win.KEY
    local w, h = gfx.size()
    local cell = math.max(4, math.floor(math.min(w, h) / 32))
    local top = 12
    local cols, rows = math.floor(w / cell), math.floor((h - top) / cell)
    local ox, oy = math.floor((w - cols * cell) / 2), top + math.floor((h - top - rows * cell) / 2)
    local function drawCell(x, y, c) gfx.fill(ox + (x - 1) * cell, oy + (y - 1) * cell, cell - 1, cell - 1, c) end
    local function header(score, msg)
      gfx.fill(0, 0, w, top, 0)
      gfx.text(2, 2, "Snake  " .. score .. (msg and ("  " .. msg) or ""), 7, nil, 1)
    end
    while true do
      gfx.clear(0)
      gfx.rect(ox - 1, oy - 1, cols * cell + 1, rows * cell + 1, 5)
      local snake = { { x = math.floor(cols / 2), y = math.floor(rows / 2) } }
      local occupied = {}
      local function key(x, y) return y * 1024 + x end
      occupied[key(snake[1].x, snake[1].y)] = true
      local dir = { x = 1, y = 0 }
      local score, alive = 0, true
      local food
      local function placeFood()
        for _ = 1, 1000 do
          local fx, fy = math.random(cols), math.random(rows)
          if not occupied[key(fx, fy)] then food = { x = fx, y = fy } drawCell(fx, fy, 9) return end
        end
      end
      placeFood()
      drawCell(snake[1].x, snake[1].y, 11)
      header(score)
      gfx.present()
      local every, frame = 3, 0
      while alive do
        if input.quit then return end
        if input.dir then
          local d = input.dir
          input.dir = nil
          if not (d.x == -dir.x and d.y == -dir.y) or #snake == 1 then dir = d end
        end
        frame = frame + 1
        if input.pause then
          header(score, "paused")
        elseif frame % every == 0 then
          local head = snake[#snake]
          local nx, ny = head.x + dir.x, head.y + dir.y
          if nx < 1 or ny < 1 or nx > cols or ny > rows or occupied[key(nx, ny)] then
            alive = false
            snd.channel(1, snd.NOISE, 200, 0.6, 0.01, 0.4, 0, 0.1)
            header(score, "game over - any key")
          else
            snake[#snake + 1] = { x = nx, y = ny }
            occupied[key(nx, ny)] = true
            drawCell(head.x, head.y, 10)
            drawCell(nx, ny, 11)
            if food and nx == food.x and ny == food.y then
              score = score + 1
              snd.beep(660 + score * 20, 0.08)
              placeFood()
              if score % 5 == 0 and every > 1 then every = every - 1 end
              header(score)
            else
              local tail = table.remove(snake, 1)
              occupied[key(tail.x, tail.y)] = nil
              drawCell(tail.x, tail.y, 0)
            end
          end
        end
        gfx.present()
      end
      -- dead: wait for a key, then a new round
      input.dir, input.any = nil, false
      while not input.dir and not input.any and not input.quit do gfx.present() end
      if input.quit then return end
    end
  end, wd)
  p.key = function(code, down, mods)
    if not down then return end
    local KEY = win.KEY
    if code == KEY.up or code == KEY.w then input.dir = { x = 0, y = -1 }
    elseif code == KEY.down or code == KEY.s then input.dir = { x = 0, y = 1 }
    elseif code == KEY.left or code == KEY.a then input.dir = { x = -1, y = 0 }
    elseif code == KEY.right or code == KEY.d then input.dir = { x = 1, y = 0 }
    elseif code == KEY.space then input.pause = not input.pause
    elseif code == KEY.q or code == KEY.esc then input.quit = true end
    input.any = true
  end
  return nil
end

return app
