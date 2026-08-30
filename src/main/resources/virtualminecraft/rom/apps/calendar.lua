-- Calendar: what day it is in *this world* (ROADMAP §9 U10(b), [name]: "day 0, time 0 would be equal to January
-- 1st, 1970 at 6:00AM minecraft time"). World tick 0 is 1970-01-01 06:00 and a Minecraft day is a real day, so a
-- world that has been played for a few hundred days has a genuine date, and this is where you look it up. The
-- month grid is read-only on purpose -- nothing in the world is scheduled, so a "click a day" would do nothing.
local app = { id = "calendar", name = "Calendar", icon = "C" }

function app.open(args)
  local T = win.theme
  local lh = T.fh + 2
  -- two header lines, the weekday row, six week rows and the button bar. Sizing the window from the theme
  -- rather than a magic number is the whole reason this fits at 16 px text as well as at 8: the large font is
  -- twice the height, and a 190 px window that looked right on a 1x1 monitor hid the last week of the month.
  local gridTop = 2 + lh * 2 + 2
  local wantH = gridTop + lh + 6 * (T.fh + 1) + T.fh + 12 + win.TITLE_H()
  local wd = win.Window.new{ title = "Calendar", x = kernel.iconW + 10, y = 18,
    w = math.min(kernel.w - kernel.iconW - 14, math.max(T.fw * 30, 216)),
    h = math.min(math.max(kernel.h - kernel.taskbarH - 24, 60), wantH) }
  local today = os.datetable()
  local view = { year = today.year, month = today.month }
  local head = wd:add(win.Label{ x = 2, y = 2, w = 600, text = "" })
  local sub = wd:add(win.Label{ x = 2, y = 2 + lh, w = 600, text = "" })

  local function step(months)
    local m = view.month + months
    view.year = view.year + math.floor((m - 1) / 12)
    view.month = (m - 1) % 12 + 1
    wd:invalidate()
  end

  local bar = {}
  local labels = { "<<", "<", "Today", ">", ">>" }
  local acts = { function() step(-12) end, function() step(-1) end,
    function() view.year, view.month = today.year, today.month wd:invalidate() end,
    function() step(1) end, function() step(12) end }
  for i, text in ipairs(labels) do
    bar[i] = wd:add(win.Button{ text = text, h = T.fh + 6, onclick = acts[i] })
  end
  -- the four arrows keep their width and Today takes what is left, laid out left to right so the row never
  -- overlaps itself or runs off the edge -- the window is resizable and this bar is the first thing to break
  local function layoutBar(_, cw, ch)
    local gap = 2
    local arrow = T.fw * 2 + 6
    local y = ch - T.fh - 6
    local wide = math.max(T.fw * 5 + 6, cw - 4 - arrow * 4 - gap * 4)
    local widths = { arrow, arrow, wide, arrow, arrow }
    local x = 2
    for i, b in ipairs(bar) do
      b.x, b.y, b.w = x, y, widths[i]
      x = x + widths[i] + gap
    end
  end
  for _, b in ipairs(bar) do b.layout = layoutBar end
  wd:relayout()

  local function refresh()
    today = os.datetable()
    head.text = os.date("%A %d %B %Y")
    sub.text = string.format("%s   Minecraft day %d", os.date("%H:%M"), today.worldday)
    wd.title = os.MONTHS[view.month] .. " " .. view.year -- the month being looked at is what the window IS
    wd:invalidate()
  end

  wd.ondraw = function(self, cx, cy, cw, ch)
    -- the 1st of the month sits under its own weekday, and the grid runs from there
    local first = os.daysfromdate(view.year, view.month, 1)
    local lead = (first + 4) % 7 -- 0 = Sunday, matching os.DAYS
    local days = os.monthdays(view.year, view.month)
    -- size the cells to the rows this month actually needs: February over five rows should not leave a sixth
    -- row's worth of blank, and a 31-day month starting on a Saturday must not run under the button bar
    local rows = math.max(1, math.ceil((lead + days) / 7))
    local cellW = math.max(T.fw + 2, math.floor((cw - 4) / 7))
    local gridH = ch - gridTop - lh - T.fh - 10 -- what is left between the weekday row and the button bar
    local cellH = math.max(T.fh + 1, math.floor(gridH / rows))
    local x0, y0 = cx + 2, cy + gridTop
    for i = 1, 7 do
      local d = os.DAYS[i]:sub(1, 2)
      gfx.text(x0 + (i - 1) * cellW + math.floor((cellW - win.textw(d)) / 2), y0, d, T.disabled, nil, T.font)
    end
    y0 = y0 + lh
    for d = 1, days do
      local cell = lead + d - 1
      local col, row = cell % 7, math.floor(cell / 7)
      local x, y = x0 + col * cellW, y0 + row * cellH
      if (row + 1) * cellH > gridH then break end -- shrunk too far to hold every week: stop, never overlap the bar
      local isToday = view.year == today.year and view.month == today.month and d == today.day
      if isToday then gfx.fill(x, y - 1, cellW - 1, cellH - 1, T.sel) end
      local s = tostring(d)
      gfx.text(x + math.floor((cellW - win.textw(s)) / 2), y, s, isToday and T.selText or T.text, nil, T.font)
    end
  end

  refresh()
  wd.refresh = refresh
  wd.save = function() return { year = view.year, month = view.month } end
  if type(args) == "table" and tonumber(args.year) and tonumber(args.month) then
    view.year, view.month = math.floor(args.year), math.floor(args.month)
  end
  -- a minute of world time is 60 real seconds / 72, so once a second is the cheapest beat that never looks stale
  kernel.spawn("calendar", function() while not wd.closed do os.sleep(1000) if not wd.closed then refresh() end end end, wd)
  return wd
end

function app.save(wd) return wd.save and wd.save() or nil end

return app
