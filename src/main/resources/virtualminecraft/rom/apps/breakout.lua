-- Breakout: the second game in ROM. The paddle follows the pointer (or arrows/A-D); space serves; Q quits.
-- Bricks are drawn once and erased when hit; the ball and paddle are erased and redrawn each frame.
local app = { id = "breakout", name = "Breakout", icon = "B" }

function app.open(args)
  local wd = win.Window.new{ title = "Breakout", fullscreen = true }
  kernel.show(wd)
  local input = { px = nil, left = false, right = false, serve = false, quit = false }
  local p = kernel.spawn("breakout", function()
    local w, h = gfx.size()
    local top = 12
    local scale = math.max(1, math.floor(h / 128))
    local padW, padH = 24 * scale, 3 * scale
    local ballS = 3 * scale
    local cols = math.floor(w / (12 * scale))
    local brickW, brickH = math.floor(w / cols), 5 * scale
    local rows = 6
    local score, lives = 0, 3
    local function header(msg)
      gfx.fill(0, 0, w, top, 0)
      gfx.text(2, 2, "Breakout  " .. score .. "  lives " .. lives .. (msg and ("  " .. msg) or ""), 7, nil, 1)
    end
    local bricks, left
    local function newLevel()
      gfx.clear(0)
      bricks, left = {}, 0
      for r = 1, rows do
        bricks[r] = {}
        for c = 1, cols do
          bricks[r][c] = true
          left = left + 1
          gfx.fill((c - 1) * brickW, top + 8 + (r - 1) * (brickH + 1), brickW - 1, brickH, 8 + r)
        end
      end
    end
    newLevel()
    local padX = math.floor((w - padW) / 2)
    local padY = h - padH - 2
    local ball = { x = 0, y = 0, dx = 0, dy = 0, held = true }
    local speed = 1.6 * scale
    local lastPad, lastBall = nil, nil
    local function drawBall()
      if lastBall then gfx.fill(lastBall[1], lastBall[2], ballS, ballS, 0) end
      gfx.fill(math.floor(ball.x), math.floor(ball.y), ballS, ballS, 7)
      lastBall = { math.floor(ball.x), math.floor(ball.y) }
    end
    local function drawPad()
      if lastPad and lastPad ~= padX then gfx.fill(lastPad, padY, padW, padH, 0) end
      gfx.fill(padX, padY, padW, padH, 12)
      lastPad = padX
    end
    header("space serves")
    while lives > 0 do
      if input.quit then return end
      -- paddle
      if input.px then padX = math.floor(input.px - padW / 2) input.px = nil end
      if input.left then padX = padX - 3 * scale end
      if input.right then padX = padX + 3 * scale end
      padX = math.max(0, math.min(w - padW, padX))
      if ball.held then
        ball.x, ball.y = padX + padW / 2 - ballS / 2, padY - ballS - 1
        if input.serve then
          input.serve = false
          ball.held = false
          ball.dx, ball.dy = (math.random() < 0.5 and -1 or 1) * speed * 0.7, -speed
          header()
        end
      else
        ball.x, ball.y = ball.x + ball.dx, ball.y + ball.dy
        if ball.x < 0 then ball.x = 0 ball.dx = -ball.dx snd.beep(300, 0.03) end
        if ball.x > w - ballS then ball.x = w - ballS ball.dx = -ball.dx snd.beep(300, 0.03) end
        if ball.y < top then ball.y = top ball.dy = -ball.dy snd.beep(300, 0.03) end
        -- paddle
        if ball.dy > 0 and ball.y + ballS >= padY and ball.y <= padY + padH and ball.x + ballS >= padX and ball.x <= padX + padW then
          local rel = ((ball.x + ballS / 2) - (padX + padW / 2)) / (padW / 2)
          ball.dx = rel * speed * 1.2
          ball.dy = -math.abs(ball.dy)
          ball.y = padY - ballS
          snd.beep(440, 0.04)
        end
        -- bricks
        local r = math.floor((ball.y - top - 8) / (brickH + 1)) + 1
        local c = math.floor(ball.x / brickW) + 1
        if r >= 1 and r <= rows and c >= 1 and c <= cols and bricks[r][c] then
          bricks[r][c] = false
          left = left - 1
          score = score + 10
          gfx.fill((c - 1) * brickW, top + 8 + (r - 1) * (brickH + 1), brickW - 1, brickH, 0)
          ball.dy = -ball.dy
          snd.beep(600 + r * 60, 0.05, 1)
          header()
          if left == 0 then
            newLevel()
            speed = speed * 1.15
            ball.held = true
            lastBall, lastPad = nil, nil
            header("level up - space")
          end
        end
        if ball.y > h then
          lives = lives - 1
          ball.held = true
          lastBall = nil
          snd.channel(1, snd.NOISE, 120, 0.5, 0.01, 0.3, 0, 0.1)
          header(lives > 0 and "space serves" or "game over - any key")
        end
      end
      drawPad()
      drawBall()
      gfx.present()
    end
    input.serve = false
    while not input.serve and not input.quit do gfx.present() end
  end, wd)
  p.key = function(code, down, mods)
    local KEY = win.KEY
    if code == KEY.left or code == KEY.a then input.left = down
    elseif code == KEY.right or code == KEY.d then input.right = down
    elseif down and (code == KEY.space or code == KEY.up or code == KEY.enter) then input.serve = true
    elseif down and (code == KEY.q or code == KEY.esc) then input.quit = true
    elseif down then input.serve = true end
  end
  p.pointer = function(px, py, buttons, pressed, released)
    input.px = px
    if pressed then input.serve = true end
  end
  return nil
end

return app
