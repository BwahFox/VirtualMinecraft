-- Manual (ROADMAP §9 U3c): the book about this machine, on this machine. It is in the ROM rather than on a CD
-- on purpose -- it is the thing you reach for when nothing else is working, and a manual that needs a disk in a
-- drive is no use on the evening you cannot work out why the computer will not switch on.
--
-- One pane, two modes, the lesson the Reader already paid for: a window on a 1x1 monitor is 28 characters wide,
-- so a contents list beside the text turns prose into a column of two-word lines. The contents *replaces* the
-- page while you are choosing and gets out of the way once you have chosen.
--
-- The pages are plain text in /rom/manual/, named so they sort, with the title on the first line. Adding one is
-- adding a file; nothing here has a list of them in it.
local app = { id = "manual", name = "Manual", icon = "?" }
local DIR = "/rom/manual"

function app.open(args)
  local T = win.theme
  local KEY = win.KEY
  local wd = win.Window.new{ title = "Manual", x = kernel.iconW + 6, y = 14,
    w = math.min(math.max(kernel.w - kernel.iconW - 10, 170), 330),
    h = math.min(math.max(kernel.h - kernel.taskbarH - 20, 120), 240) }
  wd.minW, wd.minH = T.fw * 22, T.fh * 9

  ---------------------------------------------------------------------------------------------- the pages
  -- { path, title }, in filename order. The number in the name is the order and is not shown.
  local pages = {}
  local ok, entries = pcall(fs.list, DIR)
  if ok and type(entries) == "table" then
    table.sort(entries, function(a, b) return a.name < b.name end)
    for _, e in ipairs(entries) do
      if not e.dir and e.name:match("%.txt$") then
        local rok, body = pcall(fs.read, DIR .. "/" .. e.name)
        local title = rok and tostring(body):match("^([^\n]*)") or e.name
        pages[#pages + 1] = { path = DIR .. "/" .. e.name, title = title ~= "" and title or e.name }
      end
    end
  end

  ---------------------------------------------------------------------------------------------- widgets
  local list = wd:add(win.List{ items = {} })
  local area = wd:add(win.TextArea{ wrap = true, readonly = true })
  local status = wd:add(win.Label{ text = "" })
  local bh = T.fh + 6
  -- Relabelled rather than swapped: a row of buttons that moves under the pointer between one screen and the
  -- next is its own small cruelty, and the Reader settled that argument first.
  local b1 = wd:add(win.Button{ text = "Read", h = bh })
  local b2 = wd:add(win.Button{ text = "", h = bh })
  local b3 = wd:add(win.Button{ text = "", h = bh })
  local b4 = wd:add(win.Button{ text = "Close", h = bh })
  local buttons = { b1, b2, b3, b4 }
  wd.buttons, wd.list, wd.area, wd.status = buttons, list, area, status -- the harness

  local function bodyH(ch) return ch - bh - T.fh - 4 end
  list.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, cw, bodyH(ch) end
  area.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, cw, bodyH(ch) end
  status.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 2, ch - bh - T.fh - 2, cw - 4, T.fh end
  for i, b in ipairs(buttons) do
    b.layout = function(self, cw, ch)
      local bw = math.floor((cw - 2 * (#buttons - 1)) / #buttons)
      self.x, self.y, self.w = (i - 1) * (bw + 2), ch - bh, bw
    end
  end
  wd:relayout()

  ---------------------------------------------------------------------------------------------- state
  local mode = "contents"   -- "contents" | "reading"
  local page = 0            -- which one is open, 0 = none

  local function maxScroll() return math.max(0, #area:visual() - area:rows()) end

  local function setStatus()
    if mode == "contents" then
      status.text = #pages == 0 and "The manual is missing from /rom" or (#pages .. " pages  -  Enter to read")
      return
    end
    -- how far down this page you are, not how far through the book: the page number already says the second
    local m = maxScroll()
    local pct = m <= 0 and 100 or math.floor((area.scroll or 0) * 100 / m)
    status.text = win.fit(string.format("Page %d of %d   %d%%", page, #pages, pct), wd.w - 8)
  end

  local function showContents()
    mode = "contents"
    area.hidden, list.hidden = true, false
    wd.title = "Manual"
    list.items = {}
    for i, p in ipairs(pages) do list.items[i] = i .. ". " .. p.title end
    list.selected = page > 0 and page or (list.selected or 1)
    b1.text, b2.text, b3.text, b4.text = "Read", "", "", "Close"
    b2.disabled, b3.disabled = true, true
    wd:setfocus(list)
    setStatus()
    wd:invalidate()
  end

  local function open(i)
    if not pages[i] then return end
    page = i
    mode = "reading"
    local rok, body = pcall(fs.read, pages[i].path)
    -- The title is the first line of the file and the window's title bar, so it is not printed twice.
    local text = rok and tostring(body):gsub("^[^\n]*\n\n?", "") or ("Could not read this page: " .. tostring(body))
    area:settext(text)
    area.scroll = 0
    area.hidden, list.hidden = false, true
    wd.title = pages[i].title
    b1.text, b2.text, b3.text, b4.text = "Contents", "Back", "Next", "Close"
    b2.disabled, b3.disabled = i <= 1, i >= #pages
    wd:setfocus(area)
    setStatus()
    wd:invalidate()
  end

  ---------------------------------------------------------------------------------------------- wiring
  list.onactivate = function(i) open(i) end
  b1.onclick = function() if mode == "contents" then open(list.selected or 1) else showContents() end end
  b2.onclick = function() if mode == "reading" and page > 1 then open(page - 1) end end
  b3.onclick = function() if mode == "reading" and page < #pages then open(page + 1) end end
  b4.onclick = function() kernel.close(wd) end

  -- Scrolling is the TextArea's; everything else is the page turner. Space and Backspace page the way a book
  -- does, because the arrow keys are already the fine adjustment.
  local areakey = area.key
  function area:key(code, down, mods)
    if not down then return false end
    if code == KEY.esc then showContents() return true end
    if code == KEY.space then
      if (area.scroll or 0) >= maxScroll() and page < #pages then open(page + 1)
      else area.scroll = math.min(maxScroll(), (area.scroll or 0) + area:rows() - 1) setStatus() wd:invalidate() end
      return true
    end
    if code == KEY.backspace then
      if (area.scroll or 0) <= 0 and page > 1 then open(page - 1) area.scroll = maxScroll()
      else area.scroll = math.max(0, (area.scroll or 0) - area:rows() + 1) end
      setStatus()
      wd:invalidate()
      return true
    end
    local handled = areakey(self, code, down, mods)
    setStatus()
    return handled
  end

  local basewheel = area.wheel
  function area:wheel(dy)
    local r = basewheel(self, dy)
    setStatus()
    return r
  end

  wd.save = function() return { page = page > 0 and page or nil, scroll = area.scroll or 0 } end

  local restore = type(args) == "table" and args.restore or nil
  if type(restore) == "table" and tonumber(restore.page) and pages[math.floor(restore.page)] then
    open(math.floor(restore.page))
    area.scroll = math.max(0, math.min(tonumber(restore.scroll) or 0, maxScroll()))
    setStatus()
  else
    showContents()
  end
  return wd
end

function app.save(wd) return wd.save and wd.save() or nil end

return app
